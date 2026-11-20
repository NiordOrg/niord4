# Code, data and configuration: the repository only adds

**What this document is:** the rules and the present state of the rule "the repository only adds", adopted by
the owner on 2026-10-21: the decisions (§2, §13), the model (§3), texts (§4), settings (§5), templates and
scripts (§6), the bootstrap (§7), the sample (§8), the country repositories (§9), what is deleted (§10) and the
risks (§12). It is not a record of how the rule was built. Its section numbers are the ones the code and the
other documents cite, so they do not change, and the numbers 11 and 14 are not used.
The rulings in §2 were taken by the owner on 2026-10-19 and 2026-10-20, after an inventory of every path by
which repository content reaches a deployment's database. **The one-rule model (R-CDC9, 2026-10-20):** every
template, script and report is data, the 11 templates the code names included; the code never reads one from
the jar.
Where a table describes the state before the rule, its file and line references are to the code as it was at
the ruling. **[I]** marks an inference not confirmed by code or a run.
**Scope:** every path from a repository into a running deployment's database, and what replaces it: the
backend's texts, settings, templates and scripts, start-up writers, the seed, the bootstrap of a fresh
deployment and the sample. It owns the backend half of the country split (`docs/fe-country-split-plan.md`
§4.5 packs' backend half, §4.9–§4.11, the sample in §3.4), which points here.
**Repositories:** the base product's (niord-base, niord-app, the admin app's Dictionaries, Script resources and
Settings pages), `SIFA-DK/niord-dk`, `SIFA-DK/niord-gl`.
**Not in scope:** the frontend packs, seams and builds (the country-split plan); production, which is touched
only at the owner's request.

---

## 1. Summary

**Terms.** *Base product*: the country-unaware Niord that every country builds on. *Country*: a
country repository (`niord-dk`, `niord-gl`, …) and the deployments built from it.

**The rule.** A repository change never changes or deletes anything a running deployment has in its
database. It may only add what a deployment has never had, and only inert things: a text key or a setting
key. Everything a deployment runs that an operator can edit is data. Data arrives only by a deliberate act:
the bootstrap of a fresh deployment, or a sysadmin's import. Existing deployments keep their data as it is.

**One rule decides what is data** (R-CDC9): if an operator can see and edit it in admin or sysadmin, it is
data; if not, it is code. Texts and setting keys are the one deliberate exception: they are inert **keys** the
code refers to, so a missing key is added. Templates, scripts and reports are **content**, and content never
arrives without an operator.

| | Before the rule | Under the rule |
|---|---|---|
| Backend texts (`*_<lang>.properties`) | merged into the database at every start; a deleted key comes back; a sysadmin Reload writes every file value over the database; a country file hides the base product file whole | base product English, the language pack and the country's texts, merged **per key**; missing keys added at start and never changed; a deleted key stays deleted; no Reload |
| Settings (`niord.json`, `@Setting`) | missing keys inserted at start and on every read; a country file replaces the base product file | the same "add when missing", only at start; a settings catalog generated from the code; the country file still replaces the base product's, now checked; the Settings page shows the same settings as before |
| All 80 templates and scripts (11 of them named by the code) | the 11 copied into the database on first render, the rest on first use, by an anonymous PDF view or an admin GET; a sysadmin Reload overwrites edits | data: in the database only by bootstrap or import; never read from the jar; the code refers to them by path |
| A fresh deployment | the Danish dev seed when a Helm switch (default `true`) says so; otherwise copies on use | a bootstrap archive outside the jar, applied once through the existing import jobs |
| Start-up | re-creates deleted built-in reports, parameter types and the master domain | the built-in reports and the master domain come with the bootstrap; the standard parameter types are held in memory (code) |

---

## 2. Decisions (owner)

| | Decision | Rejected, and why |
|---|---|---|
| **R-CDC1** (2026-10-19/20) | **Add-only.** A repository change never changes or deletes anything a running deployment has in its database. It may add only what a deployment has never had, and only inert things (text keys, setting keys). Existing deployments are not touched: no transition, no migration of their data, no comparison tool. Their data is correct as it is. | Read-only defaults with database overrides and a one-time transition (the draft of 2026-10-19), because a tool would decide, row by row in production, whether a value is an edit or a stale copy. That is a judgement on production data the owner does not want made. Reload-style resynchronisation in any form. |
| **R-CDC2** (2026-10-19/20) | **Six kinds** (§3): code, base product defaults (inert), configuration, data, bootstrap, sample. Templates, scripts and reports are data (R-CDC9). Base product defaults (backend texts and setting keys) are added when missing and never changed. They live in a clearly named place with a README saying a running deployment may differ. Settings stay editable on the Settings page as today (owner, 2026-10-20). A repository settings file holds no real secret. | Every template as code: operators edit message templates, mail bodies and report layouts every day. A split into code templates and data templates (the draft of 2026-10-20): one page with two rules (R-CDC9). |
| **R-CDC3** (2026-10-19/20) | **Executable data is never added by a release.** Templates and scripts reach a running deployment only by a sysadmin's import, recorded with the user. A release that offers a new or changed template ships it as an import file named in its release notes. A Java change that a template depends on stays compatible with the templates deployments hold, or ships the updated template that way (§6.2). New features put behaviour in Java or TypeScript, and add a template only when operators should edit it. The hardening applies whatever the source: the FreeMarker class resolver, limits on what scripts may call, and imports that are SYSADMIN-only with history. | Copy-on-use, or a start-up import of new data templates: executable content would enter production with no human act. The country-split plan's security review proposed updating rows equal to an earlier shipped revision at start-up: it changes production data. |
| **R-CDC4** (2026-10-19/20) | **Deletions.** Both "Reload from system" functions are deleted, frontend and backend, with their specs, i18n keys and docs. There is no "reset to default" anywhere. Copy-on-use is removed: `FmTemplateLoader`'s persistence, `JsResourceService`'s class-path load, `ScriptResourceService.findOrLoadFromClassPath` and the `?path=` preload of `GET /rest/script-resources/all`. An anonymous PDF view or an admin GET never writes a row. | Keeping Reload behind a stronger confirmation: one click would still overwrite every edit. A "reset to default" action: a reload by another name. |
| **R-CDC5** (2026-10-19/20) | **Deleted stays deleted.** The system remembers what it added from defaults (a ledger), so a text key or translation an operator deleted is never added again, in any language, and neither is a setting whose row was removed (the Settings page has no delete, §5.6; a row goes only by hand SQL: `V005`'s retirement of three settings is applied history, and D2 allows no new retirement changeset, §7.4). | Today's merge, which treats a deleted key as missing and adds it back at the next start. |
| **R-CDC6** (2026-10-19/20) | **Texts are layered key by key**: base product `en`, then the language pack, then the country's texts, merged per key, never as a whole-file shadow. The result's missing keys are added to the database and never changed. | Whole-file shadows: DK's and GL's `mail_en.properties` hid the base product key `mail.safetynet.cancel`. |
| **R-CDC7** (2026-10-20) | **Settings.** A country's settings file replaces the base product's whole file, because settings are almost never equal between base product and country. Keys are added when missing and never changed. A settings catalog generated from the code (key, type, default, scope, web, editable, introduced-in), in which every key of the base product's settings file has a code declaration, is published with each base product release. Each country generates its own, with its own keys in its own namespace (`dk.*`, `gl.*`). The country build fails on a key of the base product's settings file missing from its file (unless listed as "use the base product default" with a reason), on a key nobody declares, or on a wrong type. New base product keys appear in the upgrade report. Insert-on-read is replaced by the start-up add-missing step. | A merged, values-only country file (ruled 2026-10-19, reversed 2026-10-20). Insert-on-read (SETTINGS-1, SETTINGS-2, RENDER2). |
| **R-CDC8** (2026-10-19/20) | **Inert values are checked at change time**, in base product and country CI. Texts: the markup rule (country-split plan §4.6), length limits and control-character limits. Settings: type validation, a warning for a non-`https` URL or an unexpected host, and no real secrets (a password setting is empty in a repository file). The backend repeats the type validation on insert. | Runtime validation only: a bad value would first be found by a mariner. SQL-injection checks: values go through parameterised JPA, so injection is not the risk. |
| **R-CDC9** (2026-10-20) | **One rule: what an operator can see and edit in admin or sysadmin is data; anything else is code.** All 80 templates and scripts are data, including the 11 the code names; the code refers to them by path and never reads one from the jar. All reports are data, including the built-ins `standard`, `draft` and `public`; they come with the bootstrap. The standard parameter types stay code, held in memory: they are listed read-only, as today, and not editable. Texts and setting keys are the one deliberate exception: they are inert keys the code refers to, so a missing key is added (R-CDC1); templates and reports are content. | A split into 11 code templates run from the jar and 69 data templates, with built-in reports in memory (the draft of 2026-10-20): one Script resources page with two rules, a jar fallback, a database-copy marker and refusals to explain. |
| **S2** (2026-10-20) | **Start-up stops re-creating deleted rows.** The built-in reports `standard`/`draft`/`public` (`FmReportService.java:66-72`) come with the bootstrap (R-CDC9); the standard parameter types (`TemplateInitService.java:47-66`) are held in memory. The master domain (`BootstrapService.java:43-57`) moves to the bootstrap. The lines cited are the code at the ruling. | Writers that undo an operator's delete at every start. |
| **S3** (2026-10-20) | **Danish data leaves core code.** `NiordApp`'s `organisation` `DMA` and `country` `DK` defaults (`NiordApp.java:42-45, 58-61`), `S124Service.DEFAULT_RESPONSIBLE_AGENCY` (`S124Service.java:56`), `S124Mapper.COUNTRY = "DK"` (`S124Mapper.java:119`) and the `.dk` → `da` contact guess (`ContactRestService.java:162-164`) become country settings, country texts or nothing. Taken from the `country` setting by the same rule: `S124Mapper`'s other two Danish tokens, the `DK.` id prefix (`:447`) and the hard-coded `:dk:` in `toMrn` (`:713`), and `org.niord.secom.util.S124Mrn.COUNTRY = "dk"` (`S124Mrn.java:33`). The legacy publication import (`core/publication/series/legacy/`, DK's own rulings) is a listed exception until DK's cutover. | Country data in the country-neutral library. |

The five questions of §13 were decided on 2026-10-20. S1, the Helm seed switch, was folded into §7.4 on 2026-10-20; its id is not reused.

---

## 3. The model

### 3.1 Six kinds

| Kind | What | Lives | Reaches a deployment | Changes by |
|---|---|---|---|---|
| **(a) Code** | Java, TypeScript, the compiled frontend texts, the standard parameter types (held in memory; listed read-only on the Parameter Types page) | the base product jar and bundle; a country's guarded overrides in its jar | runs from the jar | a PR, review, CI, a release |
| **(b) Base product defaults** | backend texts (`web`, `message`, `pdf`, `mail`, `template`), setting keys and their default values | `defaults/` in a jar (§3.3); the language packs' backend half | **added when missing, never changed**; recorded in the ledger | a PR adds a key; a changed value reaches only deployments that never had the key |
| **(c) Configuration** | Spring properties: how the application starts and connects (database, Keycloak, mail server, management), and which subsystems run: `niord.s124.enabled` (the S-124 opt-out, the owner 2026-11-01; on by default) and `niord.secom.enabled` beneath it, which needs it. Niord's own settings are not configuration: they are edited on the Settings page, as today (owner, 2026-10-20) | `application.yml`, the chart's values, environment variables | read at start; never written to the database | a deploy |
| **(d) Data** | everything an operator can see and edit (R-CDC9): all 80 templates and scripts, categories, mailing lists, reports (the built-ins included), domains, areas, series, data dictionaries, promulgation types | the database | the bootstrap (fresh deployments only) or a sysadmin's import | an operator |
| **(e) Bootstrap** | what a brand-new deployment starts with | `bootstrap/` outside the jar | once, on a fresh database, through the import jobs | a PR to the bootstrap; never reaches a running deployment |
| **(f) Sample** | the Danish demo data | `be/niord/sample/` (country-split plan P9) | only into the loopback `niord_sample`, or a country launcher's `niord_sample_<cc>` (a two-letter lower-case country code) | a PR |

### 3.2 What runs

| Thing | Lookup at runtime | Written by |
|---|---|---|
| A text (`DictionaryService.value`, the templates' `text()`) | the database row, as today (`DictionaryVo` falls back to the default language) | the system: start-up adds missing keys (§4.2); an operator: the Dictionaries page and the dictionary import, as today |
| A setting | a system property or environment variable named like the key (as today), then the database row, then the settings file's value, then the code default | the system: start-up adds missing keys (§5.3), never on read; an operator: the Settings page and the settings import, as today |
| A template or script | the database row, and nothing else; never the jar | never the system. An operator: an import, the bootstrap of a fresh database, the editor |
| A report | the database row (the built-ins come with the bootstrap) | never the system. An operator: the Print reports page, an import, the bootstrap |
| A standard parameter type | in memory (code); listed read-only on the Parameter Types page, as today | never; the parameter-type import refuses a standard type's name |
| A computed web value (`s124Enabled`, `secomEnabled` in `/rest/settings/web`) | read from the running properties `niord.s124.enabled` and `niord.secom.enabled` at each request (`SettingsRestService.getWebSettings`, by the rules of the beans' own conditions, `S124SwitchCheck`); never stored | nothing; a restart with other properties changes it |

The chart has no `s124.producerCode` default (the S-124 opt-out, the owner, 2026-11-01): it renders
`niord.s124.enabled` and
`niord.s124.producer-code` only where its values set them (`be/k8s/README.md` § S-124 on or off). That is
configuration, not a data change under R-CDC1: no row is written or changed. A
Helm deployment without the value runs on its image's code (`DK01` for DK and GL).

### 3.3 Where base product defaults live

Each owner gets one clearly named folder inside its jar, never at a path another jar could also supply:

```
niord-base  src/main/resources/defaults/base/
                README.md              "defaults only: a running deployment may differ, and keeps its values"
                texts/en/{web,message,pdf,mail,template}.properties
                settings.json          the base product's values for its settings
                settings-catalog.json  generated (§5.1)
country jar  src/main/resources/defaults/country/
                README.md
                texts/<lang>/<bundle>.properties   the country's texts (§4.4)
                settings.json          replaces the base product's (§5.2)
                settings-catalog.json  generated, country keys only
             target/generated-resources/defaults/packs/<lang>/<bundle>.properties
                                       the language packs' backend half, staged at build (§4.3); never committed
```

A country never writes at the base product's paths: it wins **by the code's rules, not by shadowing a file**.
Its `defaults/country/settings.json` is read instead of the base product's file (R-CDC7), and its texts win
key by key over the pack's and the base product's (§4.1). The guard (`DefaultsGuard`) checks a bounded list of
known names, enumerating every copy of each with `getResources`; it never scans the whole class path. The names
are the base product's `README.md` and `texts/en/<bundle>.properties`, the country's `README.md`, the country's
`texts/<lang>/<bundle>.properties` and the packs' `<lang>/<bundle>.properties` for every model language, and,
the four settings files: `defaults/base/settings.json` and `defaults/base/settings-catalog.json`,
`defaults/country/settings.json` and `defaults/country/settings-catalog.json`. Start-up fails:
- when a known name is found at more than one class-path location, also with equal content — the old
  whole-file shadow (as when DK's `mail_en.properties` hid the base product key `mail.safetynet.cancel`);
- when `defaults/country/` comes from more than one location (two jars both claiming to be the country);
- when a `defaults/packs/en/` exists: the base product's English is not a pack (§4.3).

It ends whole-file shadows of texts and settings by construction.

---

## 4. Texts

### 4.1 The layers

Two layers with different owners. **The language pack** is the base product's text in one language — the
same for every country that uses the language (the `da` pack serves DK and GL; DK, its home, maintains it).
**The country's texts** hold only the keys a country words differently from the pack: its authority's name, its
own terms. They are usually a handful of keys, never a copy of the pack. Kept apart, the pack stays shareable:
GL's "Aktive grønlandske navigationsadvarsler" never reaches DK, and DK's authority name never reaches GL.
(The frontend makes the same split: packs and branding, country-split plan §4.10.)

For each bundle (`web`, `message`, `pdf`, `mail`, `template`; `DictionaryService.java:49`), each key and each
language in `modelLanguages`, the default value is the first one found — **1 wins over 2**:

1. the country's text in that language (`defaults/country/texts/<lang>/`), when the country has one for
   that key;
2. the language pack's text (`defaults/packs/<lang>/`; for `en`, the base product's `defaults/base/texts/en/`);

No other file is read. A root-level `<bundle>_<lang>.properties` on the class path is not a layer (it was a
transitional third one, removed in 4.0.114): a country that still has a root-level
bundle in `en`, in a language with a folder under `defaults/country/texts/` or `defaults/packs/`, or in a model
language fails its build on the text check's `root-bundle` rule (`be/docs/country-api.md`).

A key missing in a language gets no row in that language. At runtime the existing fallback to the default
language applies. Start-up compares the layered result with the database. Nothing reads these files at
runtime.

### 4.2 Add missing, never change; the ledger

`DictionaryService.init` computes the layered defaults and adds
the `(dictionary, key, language)` triples the database lacks (`DictionaryService.addMissingDefaults` over
`DefaultTexts.read`):
- **The ledger.** A table `"DefaultsLedger"`, created by schema changeset V006 with no data. Each row
  holds the kind (`TEXT`, `SETTING`), the dictionary, the key, the language, the sha-256 of the value added,
  its source (for a text `base`, `pack:<lang>`, `country`, `root` for the transitional text layer of versions
  before 4.0.114 (§4.1; such rows are kept, with no migration), `adopted`,
  `deleted`; for a setting `base`, `country`, `root` for the root `niord.json` read (§5.2),
  `home` for the server's
  `${niord.home}/niord.json`, `code` for a code default, or `adopted`), the base product version and the time.
  A `SETTING` row has the empty string as its dictionary and its language. The comment in
  `V006__defaults_ledger.sql` lists the text sources only and stays as it is: editing an applied changeset
  changes its checksum (`ChangelogChecksumPinTest`). It has no JPA entity: the add-missing step writes its
  rows in one JDBC batch. A triple in the ledger is never added again, so a translation an operator cleared
  stays gone. A delete removes the key in every language at once, so it also writes a ledger row for the key
  with the language `*` (source `deleted`, no hash), in the delete's transaction. The add-missing step never adds a key with a `*` row, nor a key the database lacks in every language
  while the ledger holds it in any (one removed some other way), so a key an operator deleted stays gone in a
  language added later too
  (R-CDC5).
- **Adoption.** Every start records as `adopted` each default triple present in the database without a
  ledger row. On the first start with the ledger that is all of them; later it is only a default an operator
  already had (one a new base product version brings, which the operator added by hand or by import). Where
  the row's value equals the layered default's value at that start, the ledger also stores that value's hash
  (used by §4.5). This writes bookkeeping only and never a dictionary row. It decides nothing about the row.
  A key deleted before the first start with the ledger, and missing at it, is added once more, as every start
  did until then.
- **Replicas.** The add-missing step (texts, settings, adoption) first takes `pg_advisory_xact_lock` on one
  fixed key, `DefaultsLedger.ADD_MISSING_LOCK`, which the settings step shares. It reads the database and the
  ledger only after the lock, never from a cache. Two instances starting together therefore run it one after
  the other, and the second reads the first's committed rows and finds nothing missing. No unique index is
  added to `DictionaryEntry`: an existing deployment may already hold duplicates (R-CDC1). The chart runs one replica.
- **A new language** (added to `modelLanguages` later) gets its missing keys once, as today, except the keys the ledger records as deleted (above).
  A key a sysadmin restored with the dictionary import (§4.6) keeps its `*` row, since the ledger is
  insert-only: it gets no default in a language added later, and the import completes it (decided
  2026-10-22; the ledger stays insert-only, and §4.5's "never `asAdded` again" holds).
- **Where the deleted keys are read.** Start-up and `asAdded` read the ledger's
  in-memory snapshot (above), which is per node and refreshed only by a start or by a delete on that node. The
  dictionary import's preview and job read a dictionary's `*` rows from the database
  (`DefaultsLedger.deletedKeys`), so a delete made on any node reaches every node's import at once.
- **A missing pack file** for a configured language logs a warning and adds nothing for it. A missing
  *default-language* file
  stays fatal, because the base product's `en` always exists.
- **No Reload.** No endpoint and no method writes a file's values over the database (§10).
- **Performance (review, 2026-10-20).** `"DefaultsLedger"` has the primary key (kind, key, dictionary,
  language), with `''` as a `SETTING` row's dictionary and language and `*` as a deleted key's language (above;
  a primary-key column cannot be null), and no generated id, so it never draws from the shared `hibernate_sequence` (about 3.4 ms a row).
  Adoption and the add-missing ledger writes are one batched insert, not row-by-row persists: the first start
  of a running deployment then adopts its 600–1,000 rows in well under a second, not 2–4 s. The ledger's hashes
  are loaded into memory at start-up; the ledger is written only then, and by a dictionary delete (its `*` row).

### 4.3 The language packs' backend half

A pack (country-split plan §4.5) carries the backend text of its language beside the applications' text:

```
packs/da/backend/{web,message,pdf,mail,template}.properties
```

- **Homes.** The home that owns a pack owns its backend half: `da` is `niord-dk`'s and `kl` is `niord-gl`'s.
  GL uses DK's `da`. **The base product's English backend text is not a pack folder.** niord-base carries it at
  `defaults/base/texts/en/`, because every consumer already depends on that jar. Moving it to
  `fe/languages/en/` would make a backend text change a frontend path. The base product's `fe/languages/en/` has
  no `backend/` folder.
- **The pack check** (country-split plan §4.6) covers the backend half against the `backend` section of the
  base product's `catalog.json`. That section is generated at publish by the kit's `catalog`
  (`country-kit/catalog.mjs`) from niord-base's
  English backend bundles (`defaults/base/texts/en/`)
  and holds every key, its placeholders (`{{name}}` and MessageFormat's `{n}`) and its English fingerprint.
  The same rows apply: a missing key fails (or is in
  the escape hatch, and the deployment's default language then serves, §4.1); a placeholder or markup difference fails; an
  unknown key is stripped. The backend half is required once a language is a UI language. For a language that is only a
  content language it is optional: a missing backend key is a warning, and the deployment's default language serves in its
  PDFs and mails until the pack has it, so a new language can start with `pack.json` and a flag alone
  (country-split plan §4.6). Such a language cannot be the deployment's `defaultLanguage` until its backend half
  exists: a missing default-language file stays fatal (§4.2).
- **Language names.** `message-support.ftl` renders `text('lang.' + lang)` for every language of a
  message. A language's name in a render language is a `lang.<code>` key in `web`, which a pack's
  backend half and a country's texts may carry for the languages they name (the `da` pack `lang.da = Dansk` and
  `lang.en = Engelsk`, DK's English texts `lang.da = Danish`). A draft PDF therefore has a translated
  heading: an English draft of a DK message prints *Danish Translation*, a Danish one *Engelsk Oversættelse*
  (`message-support.ftl:492-500`, with `pdf.translation`). Where no layer has `lang.<code>` in a model language,
  nothing adds one (no start-up step reads a pack's `pack.json` `label`): the draft print names that
  language by its code in upper case (the `<#attempt>` fallback in `message-support.ftl:495-501`), and the
  kit's pack check warns of a pack whose backend `web` half lacks its own `lang.<code>`
  (`country-kit/pack.mjs`, the "own name" row). The pack check accepts `lang.*` keys without a catalog entry. The base product carries `lang.en` only. The base product therefore names no
  other language (country-split R1), and no pack must know every other pack.
- **How the backend build reaches it.** Packs are OCI artifacts on GHCR (country-split R13), which Maven
  cannot resolve. `setup.mjs` verifies and stages every pack the manifest names, home packs included, into
  `fe/.niord/packs/<lang>/`. The country's `be/pom.xml` adds `fe/.niord/packs/<lang>/backend/` as a generated
  resource at `defaults/packs/<lang>/`. A Maven check fails, with "run `node fe/setup.mjs` first", when a
  language of the country's `modelLanguages` is not staged. The country pipeline therefore runs `setup.mjs`
  before the backend build, and so does a local run (country-split plan §5 step 9). A committed `defaults/packs/` fails the build:
  it is generated. As built: set-up stages every pack but
  `en` — home, pinned or a developer's local copy — into `fe/.niord/packs/<lang>/` in exactly the layout of the pack's
  canonical archive (`pack.json`, `i18n/{admin,info}/<lang>.json`, `backend/<bundle>.properties`, the flag and its
  drawing, `ATTRIBUTION.txt`, `status/fingerprints.json`; country-split plan §4.5), so `backend/` there is the one
  path a country's Maven build reads, home packs included. A country's pom copies
  `fe/.niord/packs/<lang>/backend` with `require-staged-packs` at `validate`, and set-up runs before Maven in
  `ci.yml`'s tests job and `image.yml`'s backend job (DK and GL since their bump onto 4.0.111).
- The packs carry their backend half from their first build (owner, 2026-10-21): the `da` pack has DK's five
  `*_da` bundles and the `kl` pack GL's `*_kl`, and neither country has a root-level bundle of a pack's language
  under `be/`.

### 4.4 Country texts

`defaults/country/texts/<lang>/<bundle>.properties` holds two things:
- the country's own wording of a base product key, only where it differs and only for a key a template or
  Java reads. For DK these are the three keys of `mail_en` that differ from the base product's
  (`mail.active_nav_warnings`, `mail.navtex.cancel`, `mail.navwarn.cancel`, each read by one template);
  `pdf.nm_almanac.desc`, a neutral base key, which DK sets to its own wording; and
  `msg.responsible.agency`, the optional, country-supplied key this section's last bullet declares, which DK sets
  to `Danish Emergency Management Agency` (owner, 2026-10-22). `lang.da` is in DK's `texts/en/web.properties`
  (§4.3). On a migrated database the row wins over each of these. **The rule:** a reworded key that no template
  and no Java reads is shown only by the
  administration app, so it goes to the country's frontend: its text overrides (`fe/text-overrides/<lang>.json`),
  or its branding for the keys the branding schema names, such as `footer.*` (country-split plan §4.10). DK's
  seven differing `web_en` keys were of that kind; as backend rows the admin app skips them while they equal
  what start-up added (§4.5), but a row a deployment already held before the ledger is not `asAdded` where it
  differs from the base product's default, so such a row masks the frontend's text until it is deleted.
  `footer.copyright` and `footer.link` are DK's branding (country-split plan §4.4); `msg.no_pos_messages` is a
  text override; the keys that equalled the administration app's English, and `template.markings.bearing`, which
  nothing reads, are gone. The same rule applied to GL's rewordings.
- the country's own keys. New ones carry the country prefix (`dk.`, `gl.`). An existing unprefixed country key
  is either kept as grandfathered (listed in the country's `defaults/country/README.md`; there is a settings
  catalog for a country but no text catalog), prefixed together with the
  templates that use it, or deleted. GL's were classified so (2026-10-22), each checked against niord-base's
  code, the base product's bootstrap templates and GL's templates; none was a base product key the base's `en`
  lacks:
  - Its five mail keys (`mail.active_nav_warnings_gl`, `mail.navtex.cancel.gl`, `mail.navwarn.cancel.gl`,
    `mail.navwarn.update.gl`, `mail.no_active_nav_warnings`) are read only by GL's `-gl` templates. They stay
    unprefixed as grandfathered, recorded in
    GL's `defaults/country/README.md`: prefixing them would change templates that moved unchanged.
  - `msg.responsible.agency` is the optional, country-supplied key of the last bullet; by the owner's ruling of
    2026-10-22 GL's English text names the Danish Emergency Management Agency.
  - `lang.da`, `lang.kl`: kept as GL's English language names, which an English draft PDF prints (§4.3).
  - `editor.status.publish_short` and `editor.status.verify_short` are the administration app's own keys, so
    they are GL's frontend's.
  - Read by nothing, and deleted: `.MESSAGE_REPORT`, `publication_type`, `publication_type.MESSAGES_REPORT`,
    `menu.action.generate_s124`, `msg.general_messages`, `my.test2`, `pdf.attachment.title` and
    `rmsg.responsible.agency` (a typo).
  - GL's rewordings follow the rule above. Read by a base template, and so in GL's `texts/en/`:
    `mail.active_nav_warnings`, `mail.navtex.cancel`,
    `mail.navwarn.cancel` and `pdf.nm_almanac.desc`. Shown only by the administration app, and so deleted from
    the backend and left to GL's frontend: `footer.copyright`,
    `footer.link`, `msg.no_pos_messages`, `promulgation.confirm.generate`,
    `promulgation.confirm.generate_all`, `publication_type.MESSAGE_REPORT`, `template.markings.bearing`,
    `template.sample_message` and `view_mode.table`.
- `msg.responsible.agency` is read by base product code (`S124Service.responsibleAgency`). The base product catalog
  declares it as an optional, country-supplied key with no base product value. Without it, or with a blank value,
  S-124 uses the `organisation` setting, and refuses to build an exchange set when that is blank too, naming the
  setting.

Checks: a reworded key exists in the base product catalog and keeps its placeholders and markup. An own key is
prefixed or grandfathered: no check enforces this. `DefaultTextsChecks` holds an own key's
placeholders and markup to the country's own English value, and a country's `defaults/country/README.md` lists
its grandfathered keys (GL's five mail keys). A check needs a list it can read; until then review holds it. A
`lang.<code>` key is neither and passes (§4.3).

### 4.5 The administration app's dictionary merge

**The merge:** when the administration app starts, it fetches the database's `web` and
`message` dictionaries and lays them over its own built-in texts, key by key
(`dictionary-overrides.service.ts:46-49`, `merge-dictionary-overrides.ts`; country-split plan §4.10 layer 5).
That is how an operator changes a wording in the admin app from the Dictionaries page, without a release.

**The problem:** the backend's text files carry older Niord wording, and start-up adds their keys to those
dictionaries. Where a key exists in both, the database copy would mask the admin app's newer
text although nobody edited it: 38 of 111 overlapping `web` keys and 9 of 52 `message` keys (inventory
2026-10-19).

**The answer:** the ledger knows which rows the system added and with which value (§4.2).
`GET /rest/dictionaries/dictionary/{name}/entries` marks a translation `asAdded` while its value still equals
what the system added, and the admin app's merge skips those rows, so its own text shows. A row an operator
changed keeps winning, as intended. Adoption records the value of every row still equal to its default
(§4.2), so this works on existing deployments too, for the rows still equal to the default. Nothing in the database changes: the rows stay, they only
stop masking the app's texts. `asAdded` is computed into the cached `DictionaryVo` from the in-memory ledger
hashes (§4.2) and cleared with it, so `/entries`, which the admin app calls on every load, stays a memory read.

How it is built:
- `asAdded` is a flag on each translation (`DictionaryEntryDescVo`), since the ledger's hashes are per
  dictionary, key and language. It is sent only when true and ignored on input: the dictionary import copies
  language, value and long value only.
- The add-missing step evicts the cached dictionaries it wrote rows for after it commits. The ledger updates
  its in-memory hashes only after commit, while start-up warms the cache inside the same transaction, so
  without the eviction a first start would serve no `asAdded` at all. The flag follows the ledger rows of the
  same start.
- A key the ledger records as deleted is never `asAdded` again, whatever value it has since (an operator or an
  import wrote it); its row masks the app's text like an edited one. A key restored by the import keeps its
  `*` row for good (§4.2).
- The export (`ExportedDictionaryVo`) copies each entry and translation without `asAdded`, so an exported file
  never carries the flag and the cached dictionary is never changed by an export.
- The admin app's `dictionary-overrides.service.ts` keeps its choice of translation (the requested language,
  else the default language, else any non-blank one). If that translation is `asAdded`, the key gets no
  override. There is no fall-through past it to another language: an unchanged system-added English row never
  replaces the app's own Danish. An English-only row an operator edited still does, by design (the choice of
  translation is unchanged, only an `asAdded` pick gives no override). A
  missing flag counts as false, so against an older backend the app behaves as before.
  `merge-dictionary-overrides.ts` applies the remaining overrides with its placeholder rule, unchanged.

**What adoption does not cover.** A row written from an older file and never edited differs from the
default: it gets no hash, is never `asAdded`, and the admin app's merge lays it over its own text unless the
placeholders differ (`merge-dictionary-overrides.ts:52-81`). By R-CDC1 nothing decides that such a row is stale,
and no tool compares a deployment's rows with the defaults. The operator's remedy is to delete the row on the
Dictionaries page: the ledger never adds it again (§4.2), and the app's own text shows. That suits a key only the
administration app shows; a key a template reads then fails its renders until a sysadmin restores it with the
dictionary import (§4.6).

### 4.6 Deleting a key that a template uses

A key an operator deleted stays deleted, and `text()` fails a render on a missing key
(`MultiResourceBundleModel.java:113-120`).
Two things cover this:
- The Dictionaries page's delete confirmation names the templates that use the key and the code that reads
  it (the readers of `RequiredTexts`, below). The page asks
  `GET /rest/dictionaries/dictionary/{name}/key-users?key=` (SYSADMIN, as delete is) before the confirmation
  opens; the key is a query parameter so that any key character survives. The answer is the script resource
  paths, the runtime-built prefix the scan also used, and the reader ids, which the app turns into its own
  texts. This is a read-only scan: one JPQL query with up to four `LIKE` patterns on `ScriptResource.content`,
  the key quoted with `'` and with `"` (so a key does not match a longer key) and, for a key under a
  runtime-built prefix, the prefix quoted both ways; with `!` as the escape character, `!`, `%` and `_` in them
  are escaped, since keys contain `_`. It never loads or parses a template. If the request fails, the confirmation still opens and says the
  users could not be checked; a delete never depends on the scan.
  A key a list parameter type holds as one of its values (`ListParamType.values`, a bare foreign key) cannot
  be deleted: the delete answers 409 naming the types before it writes anything (no `*` row), and `key-users`
  names them (`paramTypes`, sorted, always present), which the confirmation lists first.
  Not every user is a literal. For a key under a runtime-built prefix of §4.7 (`lang.`, `msg.status.`,
  `msg.type.`, `msg.field.`) the scan matches the prefix, since no template names such a key in full. The keys
  Java reads come from the constant list `RequiredTexts` (`org.niord.core.dictionary`), the texts' counterpart
  of `RequiredTemplates`, held by the literal test `RequiredTextsTest`. Its readers are matched by
  dictionary and key, so the same key in another dictionary names none.
  Without its key, `MessageService` (`msg.cancellation.title`, `MessageService.java:901-906`) falls back to
  English or a built-in text, `FiringExerciseService` (`msg.time.date_time_format`, `msg.time.time_format`,
  `msg.firing_exercises.subject`, `FiringExerciseService.java:331-376`) to built-in English texts
  (`:336-341`, `:375-377`), and `S124Service`
  (`msg.responsible.agency`, `S124Service.responsibleAgency`) to the `organisation` setting.
  `MessageCommentRestService` puts `null` into the comment mail's subject
  (`mail.comment.subject`, `MessageCommentRestService.java:324-325`), and `MailingListExecutionService` cannot
  build a mailing-list mail (`mail.date_format.short`, `mail.date_format.long`,
  `MailingListExecutionService.java:392-394`); nor can `AudioPromulgationService` generate a message's audio text
  (`position.*`, `AudioPromulgationService.java:121-125`). Of the template directives, `DateIntervalDirective` fails the
  render (`navwarn.date.*`, `term.and`); `FromToDateDirective` (`msg.time.until_further_notice`,
  `msg.time.date_time_format`) and `LatLonDirective` (`position.*`) print nothing. `IndexIncompleteNotice`
  (`pdf.list.index_incomplete`, the line at the top of a printed list or a mailing-list report made while the
  free-text index refills) prints its built-in English sentence.
- The base product ships its texts as an importable dictionary file: `be/niord/bootstrap/texts.json`, current
  with the code, and a frozen copy in each release folder, `be/niord/bootstrap/releases/<folder>/texts.json`
  (§6.4). Both are in the format the dictionary import reads (`ExportedDictionaryVo`, as `aton-dict.json`):
  the five dictionaries `web`, `message`, `pdf`, `mail` and `template`, language `en`, read from
  `defaults/base/texts/en/` with the reader start-up uses (`DefaultTexts`). Both are generated
  (`ReleaseFilesGenerator`), and `ReleaseFilesCurrentTest` fails while `bootstrap/texts.json` lags the texts.
  `batch-set.json` does not name it, so the bootstrap never applies it (start-up adds the texts). A sysadmin's
  Import, the sanctioned path, restores a key. The dictionary import has the same flow as §6.4:
  `POST /rest/dictionaries/import-preview` (SYSADMIN, the file as a multipart part, a JSON answer, no write)
  lists the keys it would add with their values, the keys it would overwrite with the current and the new
  values (the languages the file carries, the long value and the AtoN filter), how many are unchanged, and the
  refused entries, each with its reason. The unit is the pair (dictionary, key). An add is ticked by default,
  except a key the ledger records as deleted (its `*` row), which is offered unticked: importing the file to
  restore one key does not restore every key an operator deleted (R-CDC5); the preview and the job read the
  `*` rows from the database (§4.2). An overwrite is unticked, and a ticked one writes each language the file
  carries whole, value and long value, keeps the others and replaces the AtoN filter: a long value the file
  lacks for a language it carries is cleared, and the preview shows that line as a clearing.
  `POST /rest/dictionaries/upload-dictionaries` takes the choice as two optional fields, `add` and `overwrite`
  (JSON arrays of `{"dictionary", "key"}`; an `overwrite` element may also carry `basedOn`): without `add` every
  missing key except a deleted one is added, without `overwrite` nothing is overwritten. Each overwrite of the
  preview carries `basedOn`, a fingerprint of the row as the preview read it (`sha256:` over the AtoN filter and
  each language's value and long value, in language order); a ticked key whose row no longer matches its
  `basedOn` when the job runs is kept and logged (`Kept (changed since the preview) <dictionary> <key>`), so an
  edit made between the preview and the job is never overwritten unseen. A tick without `basedOn` behaves as
  before. An entry the schema cannot hold is refused, not failed: a blank dictionary name or key, an entry
  without any translation (`the entry has no translation`), a key or AtoN filter
  over 255 characters, a value or long value over 1,000, a translation without a language or value, a control
  character other than tab and newline, or a key twice in one dictionary of the file
  (`DictionaryService.importRefusal`, shared by the preview and the job, whose log names each one). A blank
  value is accepted: the base product's own `navwarn.date.time_interval_prefix` is empty
  (`defaults/base/texts/en/template.properties:31`, `bootstrap/texts.json` and the frozen release `texts.json`),
  so refusing it would refuse the base product's own restore file.

### 4.7 Checks (R-CDC8)

These run in the base product's Backend CI and in each country's build, over `defaults/**/texts`, the packs'
backend half and the country texts:
- the markup rule of country-split plan §4.6: tag names, attribute names and every `href`/`src` equal the
  English value's, and no markup where English has none;
- at most 1,000 characters (`DictionaryEntryDesc.value`, `V001__baseline_schema.sql:502`) and 255 for a key
  (`DictionaryEntry.dict_key`), no control characters other than tab and newline, valid UTF-8;
- placeholders well formed and equal to the English value's: every `{{`/`}}` belongs to a `{{name}}` token, a
  value with MessageFormat `{n}` placeholders survives MessageFormat (an unescaped apostrophe swallows a
  placeholder, and `text()` formats with MessageFormat), and a country or pack value has the same `{{name}}`
  set and `{n}` index set as the English value;
- every `text('…')` key that a template in the base product's `bootstrap/` uses exists in base product `en`. The
  runtime-built prefixes (`lang.`, `msg.status.`, `msg.type.`, `msg.field.`;
  `message-support.ftl:294,328,389`) are checked instead: `msg.status.`, `msg.type.` and `msg.field.` against
  their enums (`Status`, `Type`, `MessagePartType`), and `lang.` as the paragraph below says.

Where they live: the checker `DefaultTextsChecks` sits in niord-base's test sources, so the published
test-jar carries it. `BaseProductTextsCheckTest` runs it in the base product's unit suite, which Backend CI
runs. Each country's build runs it over its country texts and packs: `CountryDefaultsChecksTest`, a member of
the country build group that `niord-parent`
runs in every country build (country-split plan §4.11 rule 3), runs `DefaultTextsChecks.checkTexts` and
`DefaultSettingsChecks.check`
over the country's class path, and fails on a text finding or a settings error; it skips itself (a JUnit
assumption) while the class path holds no `defaults/country/` and no `defaults/packs/`. A finding names the
file, the key and the rule. The
`lang.` prefix is checked as "the base product carries `lang.en` only" (country-split plan R1): the base
`web.properties` must hold `lang.en`, and no base bundle may hold another `lang.<code>`; another language's name
comes with its pack or a country. No `pack.json` label is to come (§4.3).

---

## 5. Settings

### 5.1 The settings catalog

The catalog is generated from the code: every `@Setting` field and every `static` `Setting` constant (a
`new Setting(...)`), the declarations of the keys only the frontends read among them (below). It
is committed as `defaults/base/settings-catalog.json`, a file `{ "settings": [ … ] }` with one entry per key,
sorted by key:

```json
{ "key": "mapDefaultZoomLevel", "type": "Integer", "codeDefault": 6, "baseValue": 6,
  "scope": "operator", "web": true, "editable": true, "required": false, "cached": true,
  "introducedIn": "4.0.74", "description": "…",
  "declaredIn": ["org.niord.core.settings.FrontendSettings"] }
```

- `declaredIn` lists the classes that declare the key, fully qualified, sorted and distinct. Start-up
  uses it to add a key only on a node where its declaring code runs (§5.3).
- `codeDefault` and `baseValue` are JSON values of the key's type (`6`, `true`, a list, an object), not
  strings: `modelLanguages` and `editorFieldsBase` are a list and an object. `cached` is the declaration's
  flag, which the rows carry.
- `scope` is `operator` or `state`. The code declares it beside the key, with `web` and `editable` (the
  annotation's `editable` defaults to `true`, `settings/annotation/Setting.java:40`). An `@Setting` field
  declares it with the annotation's `scope()`, a constant with `Setting.scope(...)`, and `required` likewise;
  neither is a column or part of any JSON body. `baseValue` is the base product `settings.json` value, or `null`. `required`
  marks a key that no file may leave to the base product default (`country`, `organisation`). As built: both
  are declared in `NiordApp` with no code default, an explicit type
  `String` (as `SettingsService.NIORD_HOME`, since a `null` default carries no type) and `required(true)`; their
  catalog entries have `codeDefault` `null`, `baseValue` `"XX"` and `"Niord"`, `required` `true` and keep
  `introducedIn` `4.0.74`, and they are the catalog's only `required` keys (`SettingsCatalogCurrentTest`).
- **The generator** (`SettingsCatalogGenerator`, niord-base's test sources, so the test-jar carries it to the
  countries; `be/docs/country-api.md` names it for them, and a country's own current-check, as
  `SettingsCatalogCurrentTest`, is its own test, added with its first key) scans the owner's main classes only, never its test classes or another jar, and reads an
  `@Setting` field's default with the injection's own parsing (`SettingInjectionBeanPostProcessor`). It fails,
  naming each class and field, on a declaration without a type; on two declarations of one key that differ in
  type, code default, scope, `web`, `editable`, `required`, `cached` or description; on a `Password` key with
  `web=true`; on a `state` key that is `web`, `editable` or `required`; on a `json` annotation default that
  does not parse; and on a description longer than 255 characters, the width of the
  `Setting.description` column, since add-missing writes every description in one batch and one overflow
  would fail start-up. The reader and writer of the catalog file, `SettingsCatalog`, is in niord-base's main
  jar, so start-up reads the catalog with the parser the build writes it with, and the type rule of
  §5.5 sits there for both; the generator stays in the test sources.
- CI fails when the generated catalog differs from the committed one, so a PR shows every new or changed key:
  `SettingsCatalogCurrentTest` in the unit suite names the added, removed and changed keys and the command that
  regenerates the file, from `be/`:
  `./mvnw -pl niord test -Dtest=SettingsCatalogCurrentTest -Dsurefire.failIfNoSpecifiedTests=false -Dniord.settingsCatalog.write=true -Dniord.settingsCatalog.introducedIn=<version>`.
- `introducedIn` is kept from the committed catalog once a key has it. A publish takes the workflow's run
  number (`4.0.<run>`, with gaps), so the next version is unknowable when a
  PR is written. The first catalog's 73 keys carry `4.0.74`, the newest release when it was made, whose code
  already reads every one of them. A later new key carries the version its author passes with
  `-Dniord.settingsCatalog.introducedIn` (the newest published plus one, a lower bound); without it the write
  fails naming the new keys, and the check needs no version. The upgrade report finds new keys by comparing the
  pinned and the new catalog's keys, so `introducedIn` is informational.
- The catalog is published with each release, in the jar and in the kit package (`backend/defaults/base/`). A country's build and its
  upgrade report (country-split plan §4.12) read the pinned version's catalog.
- A country generates its own catalog from its own code. Its keys use its namespace (`dk.*`, `gl.*`).
- `§5.4` below is illustrative. The catalog is the source of truth. For example, the table does not show that
  `secomEnabled` (`SecomAdminRestService.java:82-85`) is `web=true` and `editable=false`; the catalog does.
- **A computed web value is not a setting** (S-124 opt-out, 2026-11-01). `s124Enabled` and `secomEnabled`
  in `/rest/settings/web` are computed from the running properties (§3.2). `s124Enabled` has no declaration
  and no catalog entry (`SettingsCatalogCurrentTest` asserts it undeclared; the catalog keeps its 73 keys), so
  a settings file naming it fails `undeclared-key`. The `secomEnabled` declaration and a stored row stay
  (add-only), but `/web` puts the computed value after the stored rows, so the row no longer decides what the
  administration app shows.
- **Keys no backend code reads.** Ten keys are read only by the frontends, from `/web`
  (`mapDefaultZoomLevel`/`Latitude`/
  `Longitude`, `siteLanguages`, `infoSiteLanguages`, `infoDefaultLanguage`, `wmsLayerEnabled`,
  `openSeaMapLayerEnabled`, `stdTemplateFields`, `documentationUrl`). They are declared in `FrontendSettings`, a
  small registry class of `new Setting(...)` constants in niord-base that no
  backend code reads, with `adminIntegrationPageEnabled` (`web=true`, default `false`), the gate of the
  administration app's Integration section (owner, 2026-10-21): eleven constants. `niord.home` and
  `disableLucene` are constants beside their readers: `SettingsService.NIORD_HOME` (no
  code default) and `MessagePostgresIndex.DISABLE_LUCENE` (default `false`). Each declaration
  carries its `web` and `editable` flags, its description and a type that follows the rows deployments hold
  (`mapDefaultZoomLevel`/`Latitude`/`Longitude` are `Integer`). The
  three `editorFields*` constants have type
  `json`, and `batchMessageSeriesId` has type `String`, since every
  catalog entry needs one. The two `state` keys are constants declared with `scope` `state`
  (`AreaService.AREA_LAST_UPDATE`, `CategoryService.CATEGORY_LAST_UPDATE`, type `Date`), which `TreeBaseService`
  writes through the internal writer (§5.3). The catalog holds 73 keys.

### 5.2 The settings files

- **Base product:** `defaults/base/settings.json` holds the base product's values, with
  neutral values for `country` and `organisation` (§5.4): `"country": "XX"` (an ISO 3166-1 user-assigned code)
  and `"organisation": "Niord"` (the product's name), so the file holds 26 keys.
  Its format is `{ "values": { … } }`, the key's JSON value under the key; the base file has no
  `useBaseDefault`, and no password setting.
- **One loader.** `SettingsService` loads `defaults/base/settings.json`; niord-base has no root `niord.json`
  (`CoreResourcePackagingTest` asserts its main resources hold none).
- **Country:** `defaults/country/settings.json` **replaces** the base product's file (R-CDC7):

```json
{ "values": { "modelLanguages": ["da", "en"], "country": "DK", "organisation": "…" },
  "useBaseDefault": [ { "key": "mapTileContact", "reason": "no DK-specific contact" } ] }
```

- **"The base product default"** of a key listed in `useBaseDefault` is the pinned base product's `baseValue`
  (its `settings.json` value), or else its `codeDefault`. The country build reads both from the pinned
  catalog, and start-up resolves a listed key the same way. A `required` key cannot be listed.
- Each key's type, scope, web visibility and `editable` flag come from the catalogs, never from the file. A `Password`
  setting is never web-visible.
- **Transitional, until niord-base's test tree moves to `defaults/`:** when no jar supplies `defaults/country/settings.json`,
  the first root-level `/niord.json` on the class path is read as the country's
  settings file, logged at start-up. Of each entry, in the old list format, only the key and the value count;
  type, scope and flags come from the catalogs. A key no catalog declares, or a `state` key, is skipped with a
  warning; a value that does not fit the key's type is skipped
  with an error. When `defaults/country/settings.json` exists, a root `niord.json` is ignored, and the log
  says so. Its source in the ledger is `root`. Neither country carries a root `niord.json` (both have
  `defaults/country/settings.json`): the read is used only by
  niord-base's test tree (`be/niord/src/test/resources/niord.json`, read by every IT), and is deleted once the
  test tree moves to `defaults/`.
- **A `required` key a file lacks does not stop a start** (R-CDC1: an upgrade never breaks a running
  deployment): start-up (`DefaultSettings`) logs a warning for each `required` key the country file or
  the transitional root `niord.json` does not set, naming the key and the file, and the key resolves to the
  base product's neutral value; a database row wins, as for every key. Nothing is logged when only the base
  file is read (`niord-app`). The refusal is the country build's `required` check (§5.5), run
  by `CountryDefaultsChecksTest` once the country has `defaults/country/`; over the transitional root
  `niord.json` it does not run.
- **Loaded early.** `SettingsService` reads the settings files and both catalogs from the class path in its
  constructor. That needs no database or transaction, and `SettingsCache` is already a constructor
  dependency (`:82-86`). Before it reads, the constructor runs the guard over the four settings names (§3.3,
  `DefaultsGuard.checkSettings`), so a shadowed settings file fails start-up before add-missing writes
  anything; the full guard in `DictionaryService.init` stays. `SettingInjectionBeanPostProcessor` (`:67-101`),
  which resolves `@Setting` fields during bean creation, therefore sees the file's value from the first bean
  on. A singleton's injected field still holds the start-up value until restart.
- **An early bean ends start-up with the database's value.** Early loading alone would give a bean created
  before the
  database is reachable the file's value, not the database's (`secomRenewalEnabled=true` in the database
  has no file value at all). So the post-processor records each
  field it could not resolve through the database, injects the file's value (else the base product default)
  meanwhile, and re-injects every recorded field from the database once add-missing has committed (a listener
  at `HIGHEST_PRECEDENCE + 1`, after `init` and before `DictionaryService.init`). An early bean ends start-up
  with the database's value, as every other bean does.
- The server's own `${niord.home}/niord.json` overlay stays (`SettingsService.withServerOverlay`, applied by `DefaultSettings.withOverlay`). It is the
  server's file, not the repository's, and the place for a real credential such as `wmsPassword`
  (`WmsProxyServlet.java:89`), besides the Settings page. It gives values only: type and flags come
  from the catalogs, a key no catalog declares is skipped with a warning, a value of the wrong type with an
  error, and its source in the ledger is `home`. A file that does not parse still fails start-up. The constructor resolves `niord.home` without the database (the system property or environment
  variable, else the files' value); `init` re-reads the overlay when the database's `niord.home` row names
  another folder, since the row wins over the file, as before.

### 5.3 Resolution and add-missing

- **Start-up** (`SettingsService.init`, `@Order(HIGHEST_PRECEDENCE)`, under the advisory
  lock of §4.2) adds every `operator` key of the base product and country catalogs that the database lacks and the
  ledger has not seen. It uses the file's value, or else the base product default (§5.2), and records the key in
  the ledger. Adoption works as for texts. `state` keys are never added.
- **Only where the declaring code runs (2026-10-21, in the spirit of "settings stay as
  today").** A key is added only on a node where the code that declares it runs, as insert-on-read added it
  only when that code read it. The catalog's `declaredIn` (§5.1) names the declaring classes: a class that is
  a Spring component counts when the context holds a bean definition of it (prototype and lazy beans
  included, so `batchMessageSeriesId` of a prototype batch processor counts); any other class always
  (`FrontendSettings`; `WmsProxyServlet`, which `WmsServletConfig` creates with `autowireBean` on its first
  request); a class that does not load never. `SettingsService.NIORD_HOME` counts because `SettingsService`
  is always a bean. A SECOM-disabled node therefore adds
  none of the 17 `secom*` keys, all declared in `@ConditionalOnProperty` classes, `SecomConfiguration`'s
  constants included. Adoption still records every present catalog `operator` key, its code running or not.
- **What is added, and how.** The value is the server overlay's, else the file's (the country file, else the
  transitional root `niord.json`, else the base file), else `baseValue`, else `codeDefault`; a `null` value is
  added, as insert-on-read added it. A key is added whether or not a system property or environment variable
  overrides it: the row is inert while the override wins on every read. Type, description, `cached`, `web` and
  `editable` come from the catalog, so an empty database gets the rows the earlier start-up and insert-on-read
  gave it. The `Setting` rows go in one JDBC batch with `ON CONFLICT (setting_key) DO NOTHING` besides the
  lock, the ledger rows in another; the added keys are evicted from the cache after commit. A present key is
  adopted with the hash of its value when that value equals the resolved default.
- **Reads never write.** `SettingsService.get` never persists a missing key. A missing row resolves in memory
  to the file's value or the base product
  default; a key no catalog declares resolves to its caller's template value. `peek` follows `get` for a
  missing row, so `${niord.home}` expands to the file's value before add-missing has run. `TreeBaseService`'s
  `areaLastUpdate`/`categoryLastUpdate` are `state` keys. The internal writer,
  `SettingsService.writeState`, which `TreeBaseService` calls, creates a `state`
  row with the catalog's type and flags the first time it is written and afterwards changes only its value,
  as an upsert, so two nodes do not collide; an existing `state` row keeps its stored type and description.
- **A missing row is cached like a row.** Its resolved value (the file's, the base default, or null) is cached
  with the same lifespan and evicted on every write, so a key with no row (one removed by hand SQL, a `state` key
  before its first write) costs no database read on every access (review, 2026-10-20).
- **The public `set` and the imports** (`PUT /setting/{key}`, `SettingsRestService.java:155`; the settings
  import, `BatchSettingsImportWriter.java:44`) write any catalog key except a `state` key, as today; a `state` key
  or an unknown key is refused. Only the internal writer writes a `state` key. As built, the public
  `set` answers 400 for a key no catalog declares, also one that has a row (DK's and GL's eight editable rows
  no code reads, `wmsLogin` and `faMrnPrefix` among them), for a `state` key, for a missing row (as before),
  and for a value that does not fit the row's type, else the catalog's (DK's and GL's databases store
  `wmsProtected` as `String`, the catalog says `Boolean`, and the Settings page sends the shape of the row's
  type). The settings import (`BatchSettingsImportProcessor`) creates no row through `get`: it
  skips a key no catalog declares, a `state` key, a key the deployment has no row for and a value of the wrong
  type, each with a line in the job log, and never creates a row.
- **Everything else stays as today (owner, 2026-10-20).** Every setting the catalog marks `editable` (every `operator` key but three, §5.4) is edited on the Settings page,
  including paths, the deployment's own addresses, trust switches and credentials. The per-key system-property
  and environment-variable override and the server's `${niord.home}/niord.json` overlay stay. `/web`,
  `/editable-settings` and `/setting/{key}` (`SettingsRestService.java:79-146`) keep reading the database:
  start-up adds every key a deployment lacks, so a fresh deployment's lists are complete. A key whose row
  was removed by hand SQL is absent from them (the Settings page has no delete, §5.6).
- Add-missing adds `mapTileCachePath`, and a read never writes it: no code writes that setting on purpose.

### 5.4 Scope of today's keys (fixed in the catalog in C3a)

| Scope | Keys |
|---|---|
| **operator**, defaults in the base product's settings file or else in the code; edited on the Settings page as today, except `authServerTrustAllCertificates`, `secomEnabled` and `mapTileCachePath`, which are `editable=false` and stay off it (`KeycloakIntegrationService.java:81`, `SecomAdminRestService.java:82-83`, `MapTileSettings.java:89-93`) | every key below and every other non-state key: paths (`niord.home`, `repoRootPath`, `batchJobRootPath`, `messageIndexPath`, `publicationArchiveRootPath`, `publicationPreviewRootPath`, `mapTileCachePath`, `vipsPath`); the deployment's addresses (`baseUri`, `adminAppUrl`, `authServerUrl`, the SECOM URLs); the trust switches (`authServerTrustAllCertificates`, `niordIntegrationAllowPrivateHosts`, the SECOM security switches); the password settings (`pdfEncryptionPassword`, `wmsPassword`; masked and never web-visible, as today); `mode`, `mailSender`, `mailValidRecipients`, `secomEnabled`, `messageIndexDeleteOnStartup`; languages (`modelLanguages`, `siteLanguages`, `defaultLanguage`, `infoSiteLanguages`, `infoDefaultLanguage`) and the tile and WMS addresses (`osmUrl`, `openSeaMapUrl`, `wmsProvider`), as today (D3); `mapDefaultZoomLevel`/`Latitude`/`Longitude`, `wmsLayerEnabled`, `openSeaMapLayerEnabled`, `editorFieldsBase`/`Nw`/`Nm`, `stdTemplateFields`, `mapImageSize`, `mapImageZoomLevel`, `mailMaxPerMinute`, `mailDeleteAfterDays`, `batchFileExpiryDays`, `firingExerciseScheduleDays`, `repoCacheTimeout`, `batchMessageSeriesId`, `documentationUrl`, `mapTileContact`, `disableLucene`, `messageIndexIncludeDeletedMessages`, `pdfEncryptionEnabled`, `wmsLayers`, `wmsProtected`, `adminIntegrationPageEnabled`, `country` and `organisation` (both `required`), the SECOM operational keys (`secomAlertEmails`, `secomCertExpiryWarnDays`, `secomMaxSubscriptionsPerNode`, `secomOutbox*`, `secomRenewalEnabled`, `secomRenewalWindowDays`) |
| **state** | `areaLastUpdate`, `categoryLastUpdate` |
| **none** (dead, dropped from the files) | `wmsLogin`, `wmsServiceName` (commented out, `WmsProxyServlet.java:76-82`) |

`country` and `organisation` lose their Danish code defaults (S3: no code default, `required`).
The base product file gives the generic deployable neutral values, `XX` and `Niord`, which `niord-app` runs on.
Every country file must set both. `MessageSeriesService.java:383` and
`FmTemplateService.java:126` put `country` into series numbers and templates, and S-124 puts it into its
nationality, ids and MRN (S3), so it is never left to a default. Existing deployments keep their rows. A
country file without
a `required` key starts with a warning, and a database without the row then gets the base product's value
(§5.2, §12).

### 5.5 Checks (R-CDC8, R-CDC7)

These run in the base product's CI over `defaults/base/settings.json`, and in each country's build over its own
file against the pinned base product catalog plus its own. Each finding names the file, the key and the rule;
every rule but `https` fails the build. The rules as built:
- **`format`**: a file that is not an object with `values`, a top-level member other than `values` and
  `useBaseDefault`, a base product file with `useBaseDefault`, or a settings file found at more than one
  class-path location.
- **`missing-key`** (R-CDC7's completeness, scoped to the keys of the base product's settings file): a key of
  the base product's file missing from the country file fails, unless it is listed in `useBaseDefault`. The
  check reports that list with the warnings (where the report lands, see below). A key the base product declares only in code takes its code default and needs no
  entry. The catalog holds 73 keys (the two `state` keys included) and the base file 26 of
  them (`BaseProductSettingsCheckTest` reads 26 values). DK's
  and GL's files set every one of them, so they need no `useBaseDefault` entry. A key a base product release
  adds to its file fails both country builds until it is
  set or listed; the upgrade report lists it.
- **`use-base-default`**: an entry without a reason, an entry of a `required` key, or a key listed twice.
- **`required`**: a `required` key missing from the file (`<file> country: required — a
  required setting: every settings file sets it`). At start-up the same gap is only a warning (§5.2).
- **`undeclared-key`**: a key that neither catalog declares. **`state-key`**: a `state` key in a settings file.
- **`type`**: a value whose JSON shape does not fit its type: `String`, `Password` and `Path` a string;
  `Integer` an integral number in `int` range; `Long` and `Date` an integral number; `Float` and `Double` a
  number; `Boolean` a boolean; `json` anything. `null` is allowed for every type.
- **`secret`** (no real secrets): a `Password` key with a non-empty value — the real value is set on the
  Settings page or in the server's overlay — and any string, also inside a `json` value, that matches a
  private-key header (`-----BEGIN … PRIVATE KEY-----`), a JWT (`eyJ…`, three dot-separated parts), an AWS
  access key id (`AKIA` and 16 characters), a GitHub token (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`), a Slack
  token (`xox[abprs]-`) or a URL with `user:password@`.
- **`https`**, a warning only: an `http://` URL whose host is not a loopback host. The base file's `baseUri` and
  `authServerUrl` are `localhost` development values, so loopback hosts are exempt. The host allow-list in the
  catalog waits for the first declaration that has a fixed host set: the tile hosts are operator choices (D3).
- the backend validates the type again when it adds a key at start-up and when the Settings page writes one
  with the same rule (`SettingsCatalog.hasType`). At start-up a file value of the wrong type is not
  added: it is logged as an error, the key falls to the next layer in memory, and it is added once the file is
  fixed (an existing deployment's server overlay was never checked, and R-CDC1 forbids an upgrade breaking
  it). On the Settings page's write the value must fit the row's type (§5.3).

Where they live, as the text checks (§4.7): the checker `DefaultSettingsChecks` sits in niord-base's test
sources, so the published test-jar carries it, and `BaseProductSettingsCheckTest` runs it in the base product's
unit suite over the base file and catalog; it fails if it read no values. It prints its report (the `https`
warnings and the `useBaseDefault` list) to standard output, which `redirectTestOutputToFile` in `be/pom.xml`
sends to `target/surefire-reports/org.niord.core.defaults.BaseProductSettingsCheckTest-output.txt`, not to
the console. A local run leaves the report in that file, or shows it on the console with
`-Dniord.test.redirectOutput=false`. Backend CI uploads `surefire-reports` (the `test-reports-unit` artefact)
only for a red or cancelled run, so a green CI run keeps no report. A country build prints the report through
the Maven log instead: the country build group's Surefire execution in `niord-parent`
(`niord-country-build`) sets `redirectTestOutputToFile` to `false`, so `CountryDefaultsChecksTest`'s report and
the start-up guard's messages are in the build's log. `SettingsCatalogGenerator` and
`SettingsCatalogCurrentTest` (§5.1) sit there likewise. Each country's build runs the checks through
`CountryDefaultsChecksTest` (§4.7), once it has `defaults/country/`. The
tests build their fixtures in temporary folders, never under the test resources' `defaults/`, which would ship
in the test-jar and trip a country's guard.

### 5.6 The Settings page

The page works as before (owner, 2026-10-20). There is no reset, no delete (`SettingsRestService` has no
`DELETE` endpoint) and no new action.
A row no code declares (DK's and GL's eight dead editable rows, §5.3) is not listed (owner, 2026-10-21) and
cannot be saved, the write answering 400 (§12):
`SettingsService.getAllEditable` keeps only the keys a settings catalog declares, and `GET
/rest/settings/editable-settings`, which the page and its Export read, serves that list. The row stays in the
database, untouched; `GET /setting/{key}` is unchanged. The Export leaving such a row out matches the settings
import, which skips an undeclared key. `SettingsRestServiceITTest` covers it.

---

## 6. Templates and scripts

### 6.1 Classification of niord-base's 80 files

The 80 files of `be/niord/bootstrap/templates/` (verified against the code on 2026-10-20). **All 80 are data**
(R-CDC9). The table only says what refers to each one: the Java code, by path, or data rows on a deployment.

| Path under `templates/` | Named by | Where |
|---|---|---|
| `aton/light-character.ftl` | the code | `LightCharacterService.java:39` |
| `aton/telephony-codes.ftl` | the code | `LightCharacterService.java:40` |
| `geometry/{bullet-list,from-to-table,numbered-list,table,text}.ftl` (5) | the code | `GeometryFormatService.java:57` (`/templates/geometry/%s.ftl`) |
| `messages/message-mail.ftl` | the code | `MessageMailRestService.java:101`, `MessageCommentRestService.java:340` |
| `messages/message-list-pdf.ftl` | the code | the built-in reports: the bootstrap's `reports.json` names it, and it is among the 11 because the built-in reports are required |
| `messages/message-support.ftl` | the code | included by `message-list-pdf.ftl:1` and `message-mail.ftl:1` (and by data templates) |
| `messages/message-list-pdf-footer.ftl` | the code | included by `message-support.ftl:49` |
| `messages/{nm-report,nm-tp-report,nm-annex-report,nm-almanac-report,nm-accumulated-report,fa-list}-pdf.ftl` (6) | data rows | `FmReport.templatePath` rows for five of them; `nm-accumulated` has no row in the base data: only `LegacyTemplateRulings.java:403` names it, as the report id `LegacyImportService.java:2360` writes into a series, and a release of that series fails (no such report) on a deployment without that report row |
| `messages/{nm-report,nm-almanac-report,nm-accumulated-report,fa-list}-pdf-intro.ftl` (4) | data rows; **placeholders**, holding the one-line comment of §6.4 | included by their reports (`nm-report-pdf.ftl:125`, `nm-almanac-report-pdf.ftl:43`, `nm-accumulated-report-pdf.ftl:214`, `fa-list-pdf.ftl:48`) |
| `mailinglist/{active-navwarns,cancel-navtex,cancel-navwarn,cancel-safetynet,publish-navtex,publish-navwarn,publish-safetynet}.ftl`, `publish-safetynet.js` (8) | data rows | `MailingListTrigger.scriptResourcePaths` rows on deployments |
| `mailinglist/publish-navwarn-common.ftl`, `safetynet-intro.ftl` (2) | data rows; `safetynet-intro.ftl` is a **placeholder** too | included by `publish-navwarn.ftl:2`, `publish-safetynet.ftl:26`, `cancel-safetynet.ftl:4` |
| `tmpl/*.ftl` (45) | data rows | `Category.scriptResourcePaths` rows on deployments; includes among themselves (`common.ftl`, `aton-common.ftl`, `aton-replacement-common.ftl`, `obstruction-work.ftl`, `obstruction-found.ftl`, `markings.ftl`, `work-vessel.ftl`) |
| `tmpl/{common,compute-affected-radius,object-adrift,update-areas}.js` (4) | data rows | `Category.scriptResourcePaths` rows on deployments; `common.js` is reached through `load('niord:…')` in `update-areas.js:18` |

So the code names 11 files and data rows name 69; all 80 are data and live in the bootstrap.
Templates include one another across the two groups: data templates `<#include "../messages/message-support.ftl">`
(for example `mailinglist/publish-navtex.ftl:2`), which, like every include, resolves to the deployment's row.

### 6.2 Templates the code names

- **They are data like every other template** (R-CDC9). `FmTemplateLoader` reads the database
  only, for every path, and never the jar: a path with no row resolves to nothing. Nothing loads a template
  from the class path, and
  the loader's constructors are `(ScriptResourceService)` and `(ScriptResourceService, defaultLanguage)`.
  FreeMarker's localized lookup (`FmTemplateService.newConfiguration`) and the default-language fallback
  (`FmTemplateLoader.findTemplateSource`) are unchanged. A fresh deployment gets the 11 from the bootstrap
  (§7.1); a running one has them already, and the missing-path report names any it lacks (§6.6).
- **A constant list names them**, `RequiredTemplates` (`org.niord.core.script`, the 11 paths in the
  `ScriptResource` form, without a leading `/`, and `normalize`, which strips one). It is used only by the
  missing-path report (§6.6) and by `RequiredTemplatesTest`. That test has two cases: every template-path
  literal in `main` (comments stripped, as in `RequiredTextsTest`) is in the list, and for the `%s` pattern of
  `GeometryFormatService.java:57` every bootstrap file it matches is; and the base product's `bootstrap/templates/`
  holds each of the 11 (the completeness case). The code names them in constants (`LightCharacterService.java:39-40`,
  `GeometryFormatService.java:57`, …; the list carries `messages/message-list-pdf.ftl`
  because the built-in reports are required). A new path the code names
  goes into the list and into `bootstrap/templates/` in the same change.
- **When Java changes what a template expects.** A running deployment
  executes its database copy, not the jar's file, and the code adapts to stored copies
  (`MessageMailService.java:124` rewrites hash-routed links that a stored `message-support.ftl` builds; F-X146
  moved mail escaping into Java). The rule makes it explicit: a Java change that a template depends on either
  stays compatible with the templates deployments hold, or ships the updated template in that release's import
  file (§6.4), named in the release notes. Fixes that matter for security go into Java, as F-X146 did.
- **A country's version** of one of these, or a localized variant (`aton/light-character_da.ftl`), is its own
  bootstrap data and release import file, like any template (§9). Nothing about templates is in
  `be/overrides.json`.
- **Reports are data too**, the built-ins `standard`, `draft` and `public` included: they come with the
  bootstrap (§7.1) and are edited on the Print reports page like any other. `FmReportService`'s
  `getStandardReport`, `getDraftReport` and `getPublicReport` are
  plain lookups by id (`getReport` falls back to the standard report, for a message print only; a
  publication issue's render looks its report up strictly and fails on a missing one). A deployment
  without one gets a clear error, a 404 (`NiordWebException`) naming the id and the bootstrap, and the
  missing-path report lists it. `GET /rest/message-reports/detail-reports` lists only the built-in reports that
  exist, so the administration app opens on a database that has not been bootstrapped yet.
- **The standard parameter types stay code**, held in memory in `TemplateExecutionService` (`getParamTypes`,
  `getParamTypeByName`). They are listed read-only on the Parameter Types page (`param-types-page.html`, the `STANDARD` branch), as before: `GET /parameter-types` returns them
  (`TemplateExecutionRestService.getParamTypes`), the export strips them (`exportParamTypes`), and create, update and delete refuse them (`createParamType`, `updateParamType`, `deleteParamType`). `getParamTypes` serves the 7 in-memory types beside the deployment's other rows: the 7 `StandardParamType` rows a running deployment already holds are ignored, never deleted (R-CDC1), and the in-memory type wins by name. This works
  because `TemplateParam.type` names a type and is not a foreign key. The in-memory types are never persisted
  and have no id, so the Parameter Types list tracks its rows by name (`track paramType.name`; names are
  unique). The parameter-type import (`BatchParamTypeImportProcessor`) skips an item with a standard type's
  name and writes a line to the job log; the rest of the file imports. Without the skip, the id-less in-memory
  type `getParamTypeByName` returns would turn the item's update into the create of a second row.

### 6.3 Data templates and scripts

- All 80 template and script files are in `be/niord/bootstrap/templates/` (§7), and no template is in
  any jar. `CoreResourcePackagingTest` asserts it (`noTemplateOrScriptShipsWithCore`: no top-level
  `templates/`, no `*.ftl` anywhere, no file at a `bootstrap/templates/` path; `neitherTheBootstrapNorTheSeedShipsWithCore`:
  nor `bootstrap/`, `releases/`, `texts.json` or the seed; and `dgps.*`), and that
  `META-INF/niord/expected-template-paths.txt`, path names only (§6.6), is there. The test-jar also leaves
  out niord's own test templates and scripts (`templates/**`, `test-scripts/**`, `bootstrap-fixtures/**`);
  DK's test sources use none of them. The two text checks, `BaseProductTextsCheckTest` and
  `RequiredTextsTest`, and the unit tests that render a shipped template read `bootstrap/templates/` by path.
- `JsResourceService.findScriptResource` reads the database only (`ScriptResourceService.findByPath`).
  Every `.js` file is data.
- `GET /rest/script-resources/all` has no `?path=` preload: the page's deep link and the reusable field's
  pencil open the row, or say that the path is not in the database. The endpoint keeps its `@PermitAll` with an
  ADMIN check in code, because the ticketed Export relies on it.
- Nothing reads a template or script from the class path.

### 6.4 Imports (R-CDC3)

The Script resources import (`POST /rest/script-resources/upload-script-resources`,
`ScriptResourceRestService.java:175-178`; class-level `@RolesAllowed(SYSADMIN)`) and the bootstrap (§7) are
the only ways a template reaches a deployment:
- **The preview.** `POST /rest/script-resources/import-preview` (`:156`, SYSADMIN, the file as a multipart
  part, a JSON answer; `ScriptResourceImportService.preview`) only reads. It answers
  `ScriptResourceImportPreviewVo`: `add` (path, type, content, and `listed` when the missing-path report lists
  the path, §6.6), `overwrite` (path, type, the row's content and the file's), `unchanged` (paths) and
  `refused` (path and reason), each sorted by path. A path is compared as the loader and the writer find it,
  case-insensitively (`ScriptResourceService.findByPath`, `lower(path) = lower(:path)`, which
  `createScriptResource` also uses to refuse a case-only duplicate): a file path that differs from a row only
  in case is an overwrite or unchanged of that row, never an add, and two paths of one file that differ only in
  case are a duplicate. The preview compares as the writer does,
  since an exact writer would add rows the loader cannot tell apart. Each overwrite carries `basedOn`,
  `sha256:` of the row's content as the preview read it.
- **The choice.** The upload keeps its path, its file part and its text answer, and takes two optional fields,
  `add` and `overwrite`, each a JSON array of paths; an `overwrite` element may instead be
  `{"path", "basedOn"}`, and a ticked path whose row's content no longer matches its `basedOn` when the job
  runs is kept and logged (`Kept (changed since the preview) <path>`), while a bare path carries no such check;
  a malformed field is answered 400 before any job starts.
  Without `add` every missing path is added; without `overwrite` nothing is overwritten, so an upload that
  skips the preview cannot overwrite a row. The adds are tickable too, ticked by default: §6.6 and the release
  README import only the paths the missing-path report lists, so the preview marks those, and the
  administration app offers to tick only them. An existing path is overwritten only when the sysadmin ticks
  it. The administration app's import dialog shows each overwrite as a diff (the deployment's row beside the
  file's) and always sends both fields.
- **The history.** Every write of the import job records the importing user and `source: import` in
  `ScriptResourceHistory`: a nullable column `source` (`V008__script_resource_history_source.sql`, a schema
  changeset under D2), with the values `import` (the import job, the bootstrap's included) and `editor` (the
  page's create and update); rows written before it stay empty. The history dialog shows it as a Source
  column.
- **The check.** Every file runs the script check (§6.5, `ScriptResourceCheck`): the path rules, the token list
  for a script, and for a template a FreeMarker parse plus a check of every literal `?new` class name against
  the class resolver's rule (`DirectiveClassResolver.isDirective`). A parse alone does not catch a disallowed
  `?new`: the resolver is consulted only when the `?new` is evaluated, at render time. A refused file, or a
  path that appears earlier in the same file, is listed in the preview with its reasons and skipped by the job,
  whose log names it (`Refused <path>: <reasons>`); the job ends with a count of what it added, overwrote,
  kept, did not add and left unchanged. The administration app shows a reason as the backend's English text,
  since it names a token, class or limit verbatim.
- **The batch-set route.** `POST /rest/batch/execute-batch-set` (`BatchRestService`, SYSADMIN) runs
  a batch set outside the preview, so `script-resource-import`, `dictionary-import` and `settings-import` have
  no place in one. A batch set may name only the jobs of `BatchSetService.BATCH_SET_JOBS`: `domain-import`,
  `category-import`, `param-type-import`, `mailing-list-import`, `report-import`, `promulgation-type-import`,
  `chart-import`, `area-import`, `source-import`, `publication-category-import`, `publication-import`,
  `publication-series-import`, `message-import` and `msg-archive-import`. A set that names any other job is
  refused whole, after its manifest is read and before anything is scheduled, and the text answer ends with
  the refusal. Besides the three imports that leaves out `script-executor`, which has its own logged route
  (`execute-javascript`, §6.5) and has no place in a batch set. The bootstrap runner (§7.2) applies
  `script-resource-import` and `dictionary-import` itself, only on a database whose marker reads `pending` and that
  still holds no data at the claim (§7.2, D4 as amended on 2026-10-24); it
  refuses `settings-import` as well (`BootstrapRunner.ACCEPTED_JOBS`).
- There are no folder triggers (D1): no `batch-sets` watcher, no `batch-jobs/*/in` watcher and no `-Dniord.batch-set` start path. The admin app's uploads never used them: an upload copies its file into the job's execution folder and starts the job directly (`BatchService.startBatchJobWithDataFile`).

**Release import files.** A release that offers a new or changed template, one the code names included (§6.2), ships it as
`be/niord/bootstrap/releases/<folder>/script-resources.json` (the export format the import reads: a list of
`ScriptResourceVo`, type `FM` or `JS` by extension, path `templates/<relative path>`, content, no id), with a
`README.md` saying what each file is for. A sysadmin decides whether to import it. The base product's texts
are shipped beside it as a dictionary import file, `texts.json` (§4.6).

**How a release folder is named.** The Maven version, `4.0.<run>`, exists only when
`publish.yml` runs, after the merge, so a folder cannot carry it. A release folder is named
`<yyyy-mm-dd>-<topic>` when its change is made, never changes once it is merged into `main`, and the release
notes of the first published version that contains it name it. Every `niord-bootstrap` zip carries all
release folders (§7.1), so a sysadmin who skips versions still finds each. `ReleaseFilesGenerator` refuses
to create a folder that exists, and `ReleaseFilesCurrentTest` checks every folder: the name pattern, the
README's first words (§7.1), and that its files read in their import formats. The same rule holds for a
country's `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/`.

**The first release folder** is `releases/2026-10-21-templates-from-database/`. It holds
`README.md`; `script-resources.json`, all 80 files as they were in the jar, LF line ends; `texts.json`, the
base product's texts at this release; and `missing-paths.sql`, the missing-path report as read-only SQL for
the release notes (§6.6). The three data files are generated (`ReleaseFilesGenerator`, run through
`ReleaseFilesCurrentTest` with `-Dniord.releaseFiles.write=true -Dniord.release=<folder>`). The five
placeholders (the four report intros and `mailinglist/safetynet-intro.ftl`) are carried as a one-line
FreeMarker comment (`<#-- introduction: this deployment's text goes here -->`), so they are not blank
and the reports and SafetyNet mails that include them render. The bootstrap's own five files in
`bootstrap/templates/` carry the same line, so a fresh deployment renders them too; the release file is
generated from that folder. Its README describes the import as it works
(the preview marks the paths the missing-path report lists; add those and tick no overwrite; every added row
recorded with the sysadmin and `source: import`), so the folder is frozen with a README that is true.

### 6.5 Hardening (from the security review; applies whatever the source)

- **FreeMarker:** a `TemplateClassResolver` admitting only classes directly in `org.niord.core.script.directive` (no sub-package), and
  `setAPIBuiltinEnabled(false)`, in `FmTemplateService`. Without the resolver `?new` would admit any `TemplateModel` class on the class path (FreeMarker's unrestricted default). `setAPIBuiltinEnabled(false)` only pins FreeMarker's default, so `?api` stays off. Every `?new` in the base product and
  in both country repositories names that package, and none uses `?api` (fact check). A test proves that
  `"freemarker.template.utility.Execute"?new()` fails; it renders the template, because the resolver is
  consulted only when the `?new` is evaluated, and such a template parses cleanly. A second test parses every
  data template in the base product's and both countries' `bootstrap/` and checks every literal `?new` class
  name in it against `DirectiveClassResolver.isDirective`, because a database row that used `?new` on another
  class would stop rendering with no build to catch it. That is `BootstrapTemplatesParseTest`,
  over the base product's `bootstrap/templates/` and the FreeMarker entries of every release file: each parses
  with `FmTemplateService.newConfiguration`, each literal `?new` class name passes `isDirective` and loads, and
  a `?new` not preceded by a string literal fails the test. The countries' bootstraps have the same:
  `DkBootstrapFolderTest` and `GlBootstrapFolderTest` run the script check and `BootstrapArchive` over their
  folders.
- **Scripts:** the import, the bootstrap runner and the kit's backend check refuse a script that uses
  `Packages`, `Java.type`, `Java.extend`, a `java.` or `javax.` package other than `java.util.`, `java.time.`,
  `java.text.` and `java.math.`, `java.lang.Runtime`,
  `ProcessBuilder`, `java.io`/`java.nio.file`, reflection, `eval`, `Function(`, `load(` of anything but a
  `niord:` path (`JsResourceService.java:160-170`), `javax.script`, `jdk.nashorn`, `em` writes, or
  `CdiUtils.getBean` of a type outside the allow-list in `be/docs/country-api.md`. The four `java.` packages
  are allowed because the base product's own `tmpl/object-adrift.js` (`java.util.Date`, `java.util.ArrayList`)
  and DK's and GL's `fetch-firing-exercises.js` (`java.util.Calendar` and others) name `java.util`: refusing
  every `java.` package would make the bootstrap and the first release's file refuse themselves. Built
  as `ScriptResourceCheck` (`org.niord.core.script`), whose public constants the code and its test share:
  `ALLOWED_BEAN_TYPES` (the five types below), `ALLOWED_JAVA_PACKAGES` and `SCRIPT_RULES` (17 rules,
  each with the reason it reports: `uses <token>: <why>`; besides the list above,
  a `.executeUpdate(` written through any query, and two more: a package under the `org`,
  `com`, `net` or `edu` roots, which the script engine exposes as it does `java`, other than `org.niord.core.`
  and `org.niord.model.` (`ALLOWED_OTHER_PACKAGES`; the bootstrap's scripts and DK's and GL's `dgps.js` and
  `fetch-firing-exercises.js` name those two); and the `java.util` sub-packages `zip`, `jar`, `logging`, `spi`
  and `concurrent` inside the allowed `java.util.` (`REFUSED_JAVA_UTIL_PACKAGES`; no shipped script names a
  `java.util` sub-package)). No script of the base product's bootstrap, of DK or of GL is refused. The test
  asserts the known
  evasions as accepted, so that the limit is written down: `Java["type"](…)`, `em["remove"](…)` and string
  concatenation (`'java.' + 'io.File'`, `Java['ty' + 'pe']`). The path rules come first: not blank, at most 255 characters (`ScriptResource.path`),
  no `..` segment, no backslash, no leading `/`, `.ftl` or `.js`. The template check (the parse and the literal
  `?new` check) is in it too, so `BootstrapTemplatesParseTest`, the import and the
  bootstrap run the same code; the parse resolves no include. The Script resources editor's save is not
  checked: the rule names the import and the bootstrap, and the editor is the sysadmin's own recorded edit.
  `be/docs/country-api.md` names the list for a country (its section *The script
  allow-list*; `CountryApiDocTest` fails when it and `ALLOWED_BEAN_TYPES` drift apart). The country scripts'
  `CdiUtils.getBean(org.niord.core.…​.class)` pattern stays
  allowed through that list. The list names five types: `MessageService` and `NavtexPromulgationService` (the
  base product's `tmpl/common.js:20, 50`), `SafetyNetPromulgationService` (`mailinglist/publish-safetynet.js:19`),
  `AreaService` (DK's and GL's `tmpl/dgps.js:21`) and `MessageSearchService` (DK's
  `mailinglist/fetch-firing-exercises.js:19`). GL uses the base product's `tmpl/common.js` (§9.2), and its
  `fetch-firing-exercises.js` resolves `MessageSearchService`, which `GlFiringExercisesScriptITTest`
  runs; an existing GL database gets both
  by the overwrites GL's release README names, which is data, not code. **This check is a guard against carelessness, not a boundary.** Scripts keep
  running on `NashornEngines.trusted()` (`JsResourceService.java:137-144`), and string concatenation defeats
  any token list. The boundary is SYSADMIN plus history. Narrowing the engine is outside this document.
- **Archives:** the bootstrap upload (§7.2) and the import files follow the archive rules of the
  country-split plan's security review: regular files only, no `..`, no symbolic links, size and entry-count caps, an allow-listed layout,
  and `batch-set.json` limited to the import jobs of §7.2.
- **Outside this document:** `POST /rest/batch/execute-javascript` (`BatchRestService`) and the
  `script-executor` job run arbitrary JavaScript as SYSADMIN. That is code execution by design. It has no
  folder trigger (D1), and a batch set may not name `script-executor` (§6.4).

### 6.6 Existing deployments after C4

Without copy-on-use, a template path that was never rendered on a running deployment
has no row and resolves nowhere. This includes a localized variant the old jar supplied (`x_da.ftl`),
whose absence silently falls back to `x.ftl`.
- **The missing-path report** (`MissingPathReport`, `org.niord.core.script`). The backend lists
  every path that data names or the code names (`RequiredTemplates`), and every report id that is built in
  (`FmReportService.BUILT_IN_REPORT_IDS`) or that a publication series or issue names
  (`PublicationSeries.reportId`, `PublicationIssue.reportId`), that has no row. The report only reads. It is logged at every start
  where the bootstrap marker reads `existing` or `applied` (a warning with one line per entry, else "no
  missing path"), after the marker's own listener, and is silent on `pending`, `in-progress` and `failed`,
  where the bootstrap brings the rows. It is logged at every such start rather than only at the first start
  of a new version, because knowing that a version is new would need a stored value, a write; the report is
  read only and cheap. `GET /rest/script-resources/missing-paths` (SYSADMIN, the class's role) answers it as
  a list of entries: kind (`TEMPLATE` or `REPORT`), path or report id, and what names it (`code:
  RequiredTemplates`, `Category <id>`, `MailingListTrigger <id>`, `PromulgationType <id>`, `FmReport
  <reportId>`, `include in <path>`, `load in <path>`, `variant of <path>`, and for a report id `code:
  FmReportService.BUILT_IN_REPORT_IDS`, `PublicationSeries <seriesId>` or `PublicationIssue <publicId>`).
- **What it reads.** The named paths are `RequiredTemplates.PATHS`; the `Category`, `MailingListTrigger` and
  `PromulgationType` script paths; `FmReport.templatePath`; every literal `<#include "…">` or `<#import "…">`
  in a FreeMarker row, resolved as FreeMarker resolves it (a leading `/` from the root, otherwise against the
  including row's folder, with `.` and `..` segments removed; an interpolated path is skipped); and every
  `load('niord:…')` in a JavaScript row. Every path loses one leading `/` and is compared exactly with the
  rows' paths. The loader finds a row case-insensitively (`findByPath`, §6.4), so the report may list a path
  whose row differs from it only in case, which the loader would serve; that is harmless (the import then
  finds the row unchanged or an overwrite, never an add), and the report is left exact, as built. A missing
  `#import` is listed with the includes, since it fails a render the same way.
- **Localized variants.** No data row or constant names a variant (`x_da.ftl`), so the report takes them
  from the paths it expects: each `x_<ll>.ftl` in the expected paths whose `x.ftl` is named and that has no
  row. It does not list every path a release file carries: a path nothing names never resolves anywhere, and
  a deployment that does not use the generic templates would never see an empty report. The expected paths
  are `META-INF/niord/expected-template-paths.txt`, a generated list of path names (the files under
  `bootstrap/templates/` and the paths of every `releases/*/script-resources.json`; no content, since no
  template may be in a jar), kept current by `ReleaseFilesCurrentTest`. The report reads every such list on
  the class path, so a country's jar adds its own: DK's lists 56 paths, GL's 86 (2026-10-22). A list in the jar was chosen over a release file a
  sysadmin uploads, because the report also runs at start, with no operator.
- **The same query as SQL.** `MissingPathReport.sql(expected paths)` gives one read-only `SELECT` (the same
  regular expressions; the required paths, the expected paths and the report ids as array literals,
  `unnest(CAST(ARRAY[…] AS text[]))`, ordered as the Java answer), which `ReleaseFilesGenerator` writes into
  each release folder as `missing-paths.sql` for the release notes, so a sysadmin can run it read-only before
  the upgrade (`psql -d <database> -f missing-paths.sql`). `MissingPathReportITTest` holds the SQL and the
  Java to the same answer, on the IT fixture and on a changed, rolled-back database; it also checks each kind of
  name, the include resolution, the endpoint (a sysadmin only), that the report writes no row, and that a
  report that cannot be made does not stop a start. The fixture's report is empty, so the endpoint and
  start-log tests plant a missing path of their own.
- **It never stops a start.** The start-up log runs outside any transaction (`logAtStart`, `NOT_SUPPORTED`),
  so a failed query cannot leave a rollback-only transaction to fail the start; a report that cannot be made
  is logged as a warning that points to the endpoint. The endpoint still answers such a failure as an error.
- The sysadmin imports the listed paths from the release import file (§6.4): the preview marks the paths the
  report lists (`listed`), the sysadmin adds only those and ticks no overwrite. The base product changes no
  row.
- On a running environment, staging included, an import is always the operator's own decision, never a step
  of an upgrade (owner, 2026-10-21). At DK's cutover from the previous Niord the migrated database is handled
  the same way: its missing-path report read, an import the operator's decision.

---

## 7. Bootstrap

### 7.1 Folders

- **`be/niord/bootstrap/` (base product)** is minimal and country-free. It uses the batch-set format the import
  jobs read: `batch-set.json` names the jobs and their data files, as the sample's archive does. It holds:
  - `domains.json`: the master domain `niord-client-master` (name `Master`, active, no series, no time zone).
    Active (the owner's ruling of 2026-11-03): a base-only deployment's sysadmin reaches the sysadmin pages
    through it; every fresh deployment, a
    country's included, has it active, unless the country's own `domains.json`
    restates it with its `domainId` and `"active": false` only (§7.2, *Before the claim*);
  - `templates/`: all 80 templates and scripts, the 11 the code names included (they are the base product's
    generic templates, offered even where no category uses them yet). The script-resource item of
    `batch-set.json` names the folder, and the runner turns its files into the import's JSON (path
    `templates/<relative path>`, FreeMarker or JavaScript by extension), so no generated file is committed and
    the import's reader is unchanged;
  - `reports.json`: the built-in reports `standard`, `draft` and `public` (all three on
    `messages/message-list-pdf.ftl`; `draft` with `draft: true`, `public` with
    `frontPage: false`; no domains);
  - `categories.json`: 13 categories (`Light` and `Light buoy` with their template children and
    template parameters, and **"Firing Exercises"**) without domain links and without `messageId`. The
    firing-schedule generator finds that category by its exact
    English name (`CategoryService.getFiringExercisesCategory`) and adds none when it finds none;
    `FiringScheduleService.generateFiringAreaMessages` logs a warning, once per run, when no such category
    exists. The bootstrap's README names that English name as load-bearing. The name lookup stays: an MRN would
    have to be written into each running deployment's category. The categories are
    English only; a country's bootstrap
    adds its languages and its `Category_Domain` links to them within the run, and a value it gives replaces the
    base's (§7.2, *Within one run*). By the owner's ruling of 2026-11-04
    the bootstrap also carries the other 32 categories, under `Buoy`, `Beacon`,
    `Obstruction`, `Drifting object` and `Radio navigation`, in English, without domain links and without
    `messageId`, with production's sort orders, AtoN filters and scripts; `Radio navigation`/`DGPS` comes without
    its scripts and its template parameter (`tmpl/dgps.ftl`, `tmpl/dgps.js` and the `dgps` type are DK's and GL's
    data). A country completes their names and links as it does for the 13; only fresh databases get them (§7.3);
  - `parameter-types.json`: the parameter types that are not standard (the 7 standard ones are code, §6.2;
    `BootstrapFolderTest` asserts that none is in the file): 32. The composite `dgps` and the list
    `dgps_station_list` are not among them (niord-base names
    no country): they list Danish and Faroese DGPS stations and no base product category or template uses
    them. DK's and GL's bootstraps carry them, and the sample carries them in its own archive
    (`be/niord/sample/archive/parameter-types.json`, and the four stations in `aton-dict.json`);
  - `promulgation-types.json`: `NAVTEX`, `Twitter` and `Audio` (English, priority 50,
    after `Twitter`; the owner moved audio promulgation into the base product on
    2026-10-24),
    without domain links: the
    base product's bootstrap has only the master domain, and a country's bootstrap adds the links. Without
    `promulgateByDefault`, which `PromulgationTypeVo` and the schema do not have (the
    legacy MySQL column was not carried over);
  - `aton-dict.json`: the `aton` data dictionary, 111 entries, without
    the four `radio_navigation.dgps.*` station entries (Skagen, Blåvand, Hammer Odde, Tórshavn) that
    only the two DGPS types use; DK's and GL's `dgps.js` name all four, and DK's and GL's bootstraps carry
    them (GL's with their `kl` descriptions; list values stay in `template`, D5). The base bootstrap is
    English only (R1 of the country-split plan; `aton-dict.json` 111, `categories.json`'s category and
    template-parameter names,
    `parameter-types.json` 35; `BootstrapFolderTest.theDataIsInEnglishOnly`): a later archive of the same run
    adds its language to what an earlier archive created (§7.2, *Within one run*). DK's bootstrap carries the
    `da` of the 111 `aton` keys, of the 35 parameter-type descriptions and of the category names, the
    types and categories as additions. GL's carries the `da` and `kl` of the same
    (and `dgps`'s 2), the types and categories as additions, the `da` equal to DK's;
  - `texts.json`: the base product's texts as a dictionary import file (§4.6), generated and
    current-checked; `batch-set.json` does not name it, so the bootstrap never applies it (start-up adds the
    texts), and a sysadmin imports it to restore a key;
  - `releases/`: the release import folders (§6.4).

  It holds no settings (they come from the settings files at start-up) and no messages.
- **`be/bootstrap/` in `niord-dk` and `niord-gl`** holds the country's domains and series, reports, promulgation
  types (DK: its restatement of `Audio`, and the base types' domain links; GL: its `Audio` in Greenlandic,
  `Navtex GL`, and `NAVTEX`'s link to `niord-gl-nw`; within the first bootstrap
  a later archive's values replace the base's (§7.2, *Within one run*, the owner's ruling of 2026-10-24), so on a
  fresh database DK's `Audio` is `da` with its `niord-nw` link and GL's is `kl` with priority 10 and requirement
  `MANDATORY`), its parameter types and data dictionaries, and its templates and scripts
  (the shadows of base templates, its own, and the scripts its mailing lists name). It is applied after the
  base product's in the same run: a later archive of the run replaces a template and adds
  languages and domain links to what the base archive created, and its values replace the base's
  (§7.2, *Within one run*), so a country's
  bootstrap also carries its categories' languages and `Category_Domain` links as additions. DK's 42 shadows
  and GL's 46 are applied on a fresh database (`DkBootstrapITTest` and `GlBootstrapITTest` assert it). Mailing
  lists
  are not in it: they are sample data (§7.3). GL's holds one domain, `niord-gl-nw`, with its
  series `ako-nw`, and no reports. DK production arrives as a migrated
  legacy database, which the marker reads as `existing`, so DK's `bootstrap/` serves fresh DK environments only.
  Publications and development mailing lists go to the sample, never to a bootstrap.
- **`releases/<yyyy-mm-dd>-<topic>/`** inside each bootstrap folder: `be/niord/bootstrap/releases/<folder>/` in the base product, `be/bootstrap/releases/<folder>/` in a country, named as §6.4 says. The base product's first is `releases/2026-10-21-templates-from-database/`, whose README starts with the same first words as the folder's.
- **Every one of these folders carries a `README.md`** — each bootstrap folder and each release folder
  `releases/<folder>/`, never `releases/` itself, since the bootstrap archive (`BootstrapArchive`) accepts under
  `releases/` only a release folder's `*.json`, `*.md` and `*.sql`; the country kit's `init` writes
  `be/bootstrap/README.md` only, which also says what a release folder holds — and so does the sample's (§8),
  saying in its first lines
  what the files are and are not: *"Data, not code. Loaded only into a fresh deployment (bootstrap), only by a
  sysadmin's import (releases), or only into the demo database (sample). A running deployment's data may differ
  from these files and is the truth; nothing here ever changes it."* A CI check fails if the README is missing.
  In the base product the check is `BootstrapFolderTest`, a unit test over the folder (the README's first
  words and the English name "Firing Exercises", every job of `batch-set.json` in the runner's allow-list and
  every data file present, the 80 templates, no standard parameter type, no settings or message file;
  `texts.json`, the five placeholders non-blank, the 11 of `RequiredTemplates`, and in `releases/`
  only well-named folders, each with its README). Backend
  CI re-includes `be/niord/bootstrap/**` after its Markdown exclusion, so a change to the README alone, or its
  deletion, runs the test.
- Never in `src/main/resources`, a jar or an image; a CI check fails otherwise:
  `CoreResourcePackagingTest` checks that no template, nor `bootstrap/`, its JSON, `texts.json` or `releases/`,
  is in the jar. The base product publishes its bootstrap as
  `org.niord:niord-bootstrap:<version>:zip` beside `niord-base`. The reactor module `be/niord-bootstrap`
  (packaging `pom`, no sources) zips `be/niord/bootstrap/` as it is with the assembly plugin
  (`src/assembly/bootstrap.xml`) and attaches the zip as type `zip` with no classifier (build-helper);
  `publish.yml`'s deploy step deploys it beside
  `niord-parent` and `niord-base` under the same `4.0.<run>`. `start-local.sh` and
  `regen-test-seed-postgres.sh` zip the folder locally with
  the JDK's `jar` tool (the same content). A country's pipeline publishes its own, built from its own folder;
  it applies the pinned base product's first.

### 7.2 Applied once, on a fresh database only (D4)

- **The marker.** A table `"BootstrapState"` (`V007__bootstrap_state.sql`, a schema changeset, created empty)
  with one row of fixed id 1 and the columns `state` (`pending`, `existing`, `in-progress`, `applied` or
  `failed`), `hash` (the SHA-256 over the hashes of the run's archives, in order), `error`, `baseVersion` and
  `updated`. On the first start of a version that has it, `BootstrapMarker` writes the row
  (`INSERT … SELECT … ON CONFLICT DO NOTHING`, at `ApplicationReadyEvent` with `@Order(HIGHEST_PRECEDENCE + 2)`,
  right after the settings' add-missing and re-injection and before the other start-up listeners, under the
  ledger's advisory lock),
  native SQL like `DefaultsLedger`, with no JPA entity. The row says `pending` when the database holds no `Domain`, `Message` or `Area` row (tested with `EXISTS`, never
  `count(*)`: `Message` is large in production), and
  `existing` otherwise; it is written once and never re-decided (the claim checks the whole database again,
  below). Every running deployment, and every seeded database, reads `existing` from then on, and so does a
  database created by a version that made the master domain at start. Such an environment is recreated empty
  if it should be bootstrapped (D4). The bootstrap itself (several
  thousand rows) takes 5–20 s; `bootstrap.sh` waits for it, so an ingress timeout cannot cut it short.
- **The runner.** A Spring bean, `BootstrapRunner`, not transactional, starts the existing import jobs through
  JBeret, one at a time. It accepts only these jobs, in this fixed order: `script-resource-import`,
  `domain-import`, `dictionary-import`, `category-import`, `param-type-import`, `mailing-list-import`,
  `report-import`, `promulgation-type-import`, then the rest in the order of `batch-set.json`: `chart-import`,
  `area-import`, `source-import`, `publication-category-import` and `publication-import`, which the sample's
  archive needs (§8). It refuses `settings-import`, `script-executor`, `message-import`,
  `msg-archive-import` and `publication-series-import`: no settings, no messages, no code (§7.1, §6.5). It
  reads and checks every archive of the run before it writes anything (`BootstrapArchive`, §6.5).
  That check includes the script check (`ScriptResourceCheck`, §6.5) on every `templates/` file, so an archive
  with a refused script or template is answered 400, naming the file and the reason, before the marker is
  claimed, and the marker keeps its state; the import job checks each file once more. It starts
  each job as an upload does, with its data file in the execution folder of its own `BatchData` row
  (`BatchService.startBatchJobWithDataFile`, and `startBatchJobWithJsonData` for the JSON it builds from
  `templates/`), so the handlers and their
  `JobContext` (`AbstractItemHandler.java:40-41, 56-60`) stay unchanged. `jobOperator.start` returns at once
  (`BatchService.java:123-136`), so the runner polls that `JobExecution`, outside a transaction, until it has
  ended before it starts the next; a job that does not end `COMPLETED` fails the run. A job gets 10 minutes.
  Past them the runner writes a note into the marker's `error` (the job did not end within 10 minutes, a stop
  was requested, the run waits for execution N to end, which `GET` and `bootstrap.sh` show), requests
  `jobOperator.stop`, and keeps polling with no deadline until the execution reads `COMPLETED`, `FAILED`,
  `STOPPED` or `ABANDONED`; only then is the run `failed`, so nothing of the run writes once the marker reads
  `failed`. An interruption keeps the wait going (the flag is set again afterwards), a
  status that cannot be read is asked for again, and a marker write that fails while it waits (the note at the
  deadline, the heartbeat) is logged as a warning and the wait goes on, so a database error cannot end the wait
  before the execution has (the warnings of the three share one limit, at most one a minute). A job that reaches `COMPLETED` only after the stop still fails
  the run. The batch set's `delay`s are not
  read. It moves the marker `pending` → `in-progress` with the run's hash (committed before the first job
  starts) → `applied` (after the last has ended), or to `failed` with the job, its file and its status in
  `error`. While it runs it touches the marker's `updated` every 30 s (`BootstrapMarker.HEARTBEAT`), also while
  it waits for a job past its deadline.
- **The claim: two guards** (D4 as amended by the owner on 2026-10-24). A bootstrap runs once, on
  a new and empty database. `BootstrapMarker.claim(hash)`, in one `REQUIRES_NEW` transaction, locks the marker row
  (`SELECT state, error, updated < now() - <STALE_AFTER> FROM "BootstrapState" WHERE id = 1 FOR UPDATE`, the
  staleness read on the database's clock) and checks, in that same transaction, that the marker reads `pending`
  and that the database holds no data (`firstDataTable()`, below). Only then does it move the marker to
  `in-progress` with the run's hash (`UPDATE … WHERE id = 1 AND state = 'pending'`) and commit. It answers a
  `BootstrapMarker.Claim` (claimed, state, error, stale, dataTable), and `BootstrapRunner` words the refusal; a
  refusal writes nothing and leaves the marker as it is, whatever the number of archives (a refusal over data does
  not move it to `existing`: the check refuses every time anyway).
  `BootstrapITTest.theClaimCommitsAtOnceInsideACallersTransaction` asserts the transaction of its own.
  - **No data** means no row in any table of schema `public` apart from the rows a first start writes by itself.
    `BootstrapMarker.dataTables()` reads the catalog: every table of `public`, plain or partitioned (`relkind` `r`
    or `p`), that no extension owns (PostGIS's `spatial_ref_sys` is one), minus `BootstrapMarker.NOT_DATA`, which
    is the marker (`BootstrapState`), the defaults ledger (`DefaultsLedger`), the settings (`Setting`), Liquibase's
    `databasechangelog` and `databasechangeloglock`, the whole `User` table (a row for each user mirrored from
    Keycloak at a first authenticated request, so the users who logged in before the bootstrap never make it
    refuse), and the queued mails, `ScheduledMail` and `ScheduledMailRecipient` (the
    only tables of a queued mail in V001; its attachments are stored inside `ScheduledMail.contents`): a scheduled
    alert queued before the bootstrap is posted (`SecomCertificateAlertService.checkAndAlert`, daily, for a SECOM
    certificate within its warn window; `SecomStoreWatchService.watch`, every minute, for file-backed stores that
    fail to load; both only when `secomAlertEmails` is set) does not make the claim refuse, while a domain row
    beside it still does (`BootstrapITTest` Order 4). The list is ordered by `relname` (the `name` type's C collation: `BatchData` comes before `Sequence`,
    so the table a refusal names is fixed). `firstDataTable()` probes the tables in that order with `EXISTS` and
    stops at the first that holds a row; `Dictionary`, `DictionaryEntry` and `DictionaryEntryDesc` count only for
    rows outside the five default-text dictionaries (`DictionaryService.DEFAULT_BUNDLES`: `web`, `message`, `pdf`,
    `mail` and `template`). A table a future changeset adds is covered with no change. `BatchData` counts: it is
    never deleted, so a database any import ever ran on is refused, and so is one restored, or used, while its marker
    read `pending`. Schema `public` only, since the PostGIS image's `tiger` and `topology` schemas hold rows on
    every fresh database. `LiquibaseBootstrapITTest.startUpAndAFirstRequestWriteNoTableTheClaimCountsAsData` pins
    the list: after the first start and one authenticated request `firstDataTable()` is empty, and the test names
    the table if a start-up or first-request writer fills one. A scheduled writer other than the two alerts that
    fills a table on a fresh cluster database before the bootstrap makes the claim refuse, naming the table: a
    configuration fault, fixed before the database is recreated (`be/k8s/README.md` step 4).
  - **The refusals** (409, `BootstrapRefusedException.STATE`, or `DATA` over data; the state in quotes): a marker
    not written yet ("the backend is still starting; ask again in a moment"); `existing` or `applied` ("this
    database is deployed; a bootstrap never runs on it"); `failed`, its error quoted ("a failed bootstrap is never
    run again on the same database: recreate it empty, start the backend, bootstrap again with the same archives
    (D4)"); an `in-progress` with no sign of life for 5 minutes (`STALE_AFTER`: "a bootstrap broke off", and the
    same remedy); a live `in-progress` ("a bootstrap is running; wait, GET /rest/bootstrap shows its outcome"); and
    a `pending` marker over data ("The database holds data (a row in "<table>"): a bootstrap runs only on a new,
    empty database", and the remedy). A `pending` marker the claim's `UPDATE` did not move (another claim changed
    it meanwhile, an internal race) answers "The claim did not take effect although the marker reads 'pending'
    (another claim changed it meanwhile); ask again" (409, `STATE`). `failed` and `in-progress` are final:
    nothing claims them, so there is no
    in-place retry and no takeover (*No retry*, below). `STALE_AFTER` and the heartbeat only tell a live run
    from one that broke off in that text. No schema change: V007's `CHECK` keeps the five states, and its comment
    stays stale, since its checksum is pinned. `BootstrapMarker.init`'s predicate is unchanged.
  - **Before the claim**, besides the archive rules, the runner refuses (400, `ARCHIVE`, naming the domain and both
    archives) a later archive whose `domains.json` gives a `domainId` an earlier archive of the run gives
    (`BootstrapArchive.domainIds()`, read leniently with the typed read's Jackson-2 defaults, so content after the
    root array is read as the domain import reads it: a file that is not a JSON list of
    objects gives none, and the domain import fails on it later). The domain import keeps an existing domain, so the later archive's values
    would silently not apply (*Within one run*). Neither the base product's, the sample's, DK's nor GL's archive
    restates one. By the owner's ruling of 2026-11-03 a later archive may restate an
    earlier archive's domain with its `domainId` and a boolean `active` only, and within the run that flag wins
    (`BootstrapArchive.activeOnlyDomainIds()`, `BootstrapRun.Kind.DOMAIN`), so a country may leave the base
    product's master domain inactive; any other restatement is still refused.
  - **`apply()` runs only on a marker this run claimed.** It reads the marker first and refuses (`STATE`), before
    `BootstrapRun.begin()` and with nothing written, unless the marker reads `in-progress` with the run's hash: a
    second guard against a future caller that skips the claim.
  - **A bootstrap job is never restarted.** `BatchService.restartExecution` (`PUT /rest/batch/execution/{id}/restart`,
    SYSADMIN, the batch page's restart) refuses (409, before `jobOperator.restart`) an execution whose job's
    `BatchData` carries the run's token (`bootstrapRun`): a restart reuses the job instance and its `BatchData`, so it
    would run a bootstrap job again on the same database by another door. Every other job's restart is unchanged
    (`BatchRestServiceITTest.aSysadminImportsRestartIsAccepted`). **This is the one change on a running system's
    endpoint that the ruling brought, and it is limited to bootstrap jobs** (the owner ruled on 2026-11-03 that
    it stays). Its reach is small: JBeret refuses a `COMPLETED` execution's restart anyway, so in practice it
    covers a failed bootstrap job in the JVM that ran it, on a database that is recreated in any case. JBeret SE's
    in-memory repository is in use (`BatchConfig.java:49` builds a plain `BatchSEEnvironment` and `:63` passes its
    `getJobRepository()` through; there is no `jberet.properties` under `be/`),
    so after a backend restart the old execution is unknown and a restart answers as
    it did before (`NoSuchJobExecutionException`): the 409 fires only in the JVM that ran the bootstrap job. The
    batch page shows the 409's reason in its toast (`admin.batch.toast.restartRefused`).
  - **Not added:** refusing a sysadmin's imports while the marker reads `in-progress`, which
    would change running-system endpoints. The residual: a sysadmin or a `psql` session writing during the 5–20 s
    of a run, after the claim's check, on a database that has no domain roles yet; and a database whose only data
    sits in the not-data tables (settings and default texts edited in the administration app, users), which the
    claim accepts and the bootstrap fills with base rows without touching theirs (it imports no settings, and of the
    dictionaries only `aton-dict.json`).
- **The imports in a bootstrap.** The runner passes the import jobs no `add` or `overwrite` (§6.4), so
  `script-resource-import` and `dictionary-import` add every missing row and overwrite none: on a fresh
  database that is everything. Within one run a later archive's values replace what an earlier archive of the run
  created (*Within one run*, below), so in the sample's regeneration the sample's
  `aton-dict.json`, applied as the second archive of the run, adds the keys and languages the base product's
  archive lacks and gives the base product's values where both have one (equal, so nothing is replaced, §8). A
  batch-set item of an archive may not carry the properties `add` or
  `overwrite`: they are a sysadmin's ticks after a preview, and the bootstrap adds what is missing and
  overwrites nothing, except within one run as the next bullet says; such an archive is answered 400 naming the
  archive, the item and the property, before the claim (neither the base
  product's archive nor the sample's archive carries item properties). The same holds for the property
  `bootstrapRun`, the runner's own token. The guards are tested negatively: `BootstrapRunnerTest` (the deadline, the
  interruption and a marker write that fails while it waits; `aFailedMarkerIsRefusedWithOneArchiveOrMore`,
  `anInProgressMarkerIsRefusedLiveOrStale`, `aDeployedMarkerIsRefused`,
  `aPendingMarkerOverDataIsRefusedNamingTheTable`, `aMarkerNotWrittenYetIsRefused`,
  `aPendingMarkerTheClaimDidNotMoveOnIsRefusedAsARace`,
  `aLaterArchiveRestatingADomainIdIsRefusedBeforeTheMarkerIsClaimed` and
  `applyRefusesAMarkerThisRunDidNotClaim`; beside them the positive cases `aPendingMarkerIsClaimedWithTwoArchives`
  and `aDomainFileThatIsNotJsonGivesNoDomain`); `BootstrapITTest` Order 4
  (`inProgressIsRefused`; `aPendingMarkerOverDataIsRefusedNamingTheTable`: a committed row in `ScriptResource`,
  `Domain`, `Message` or `BatchData`, or an `aton` dictionary with an entry, makes the claim and a `POST` through
  the command answer 409 naming that table, `Dictionary` for the last, since a non-default entry needs a
  non-default dictionary row, and the marker is left as it was; a `web` entry and the start-up settings pass, and
  `User` is never probed; a queued `ScheduledMail` with a recipient passes, and with a `Domain` row beside it the
  claim is refused naming `Domain`; `anInProgressMarkerIsRefusedLiveOrStale`: an `in-progress` touched 6 minutes ago refused
  with one archive and with two, naming the remedy, the marker unchanged) and Order 5 (`aFailedRunIsRecordedAndRefused`:
  the failed run recorded with its job, file, archive and status; one- and two-archive posts and in-process claims
  refused naming `recreate`, the marker still `failed` with its error; the marker forced to `pending` refused naming
  `BatchData`; then the database emptied, `failing.zip` posted through the command and failed in the background,
  emptied again, and the base product's archive posted as the sysadmin and applied, for Orders 6 and 7);
  `BootstrapSeededITTest.theSeededDatabaseForcedToPendingIsRefusedNamingATable`;
  `BootstrapRestServiceTest.aDataRefusalIsAnswered409WithTheTable`; and `BatchRestServiceITTest.aBootstrapJobsRestartIsRefused`
  (409, no new execution). `BootstrapITTest`'s emptying helper uses the claim's own definition of empty: one
  `TRUNCATE … CASCADE` over `dataTables()` but the three dictionary tables, a `DELETE` of the dictionary rows
  outside the default ones (start-up's texts and their cache stay), the marker reset to `pending`, the caches
  evicted, and `firstDataTable()` asserted empty with the `User` rows kept.
- **Within one run** (the owner, 2026-10-22; widened to every value by the owner's ruling of
  2026-10-24, D4 as amended). A bootstrap is the one first-time deployment of a new environment: one
  run that applies the base product's archive first and then the country's (or, in the sample's regeneration, the
  sample's). Inside that run the later archive takes precedence. For every entry it names that an earlier archive of
  the same run created, each value it gives replaces the earlier archive's (a template, a name, a description in a
  language, a priority, a flag, a template parameter's settings, a list), and a value the earlier archive left empty
  is filled. A value the later archive leaves out keeps the earlier archive's. "Gives" means the field is in the
  file and is not null (the owner's decision of 2026-10-25): a list given explicitly, even
  empty, replaces the earlier archive's (a category's standard fields, editor fields and script paths, a
  promulgation type's message types and script paths, a list type's values), so a country's bootstrap can empty a
  base list; a field left out or null keeps the earlier archive's;
  an empty string counts as left out (a required text cannot be blank), and so does an empty object, so a
  bootstrap cannot clear a base text; the administration app can do that afterwards. Template parameters and
  domains stay merged and unioned, so an explicit `[]` there adds and removes nothing, and a report's properties
  and parameters are maps merged by key, not lists, so an empty map adds nothing. No archive of the sample's, of
  DK's or of GL's gives an explicit empty list over a non-empty base list (the sample gives `[]` for categories'
  standard fields and script paths only where
  the base list is empty too). What is new (an
  entry, a language, a template parameter, a domain link, a report's property key) is added, and nothing is
  removed. Rows from before the run are never touched: under the claim's guards (above) the only such rows are
  start-up's own settings and default texts, and they keep start-up's rules; a default-text key a later archive
  restates with other values is kept, and the job log says so (*The job log*, below). The rule applies only to the
  jobs of the live bootstrap run. Nothing else changes: start-up
  still adds only the settings and text keys a deployment never had, a release folder and every sysadmin import
  still go through the import with its preview and ticks, and there is still no reload and no reset. As built:
  - **The key** is creation by an earlier archive of the same attempt, not table emptiness. `BootstrapRun`
    (`org.niord.core.bootstrap`) holds the one live attempt in memory: `BootstrapRunner.apply` begins it before
    the archive loop and ends it in a `finally`, and puts the token `bootstrapRun=<run id>/<archive index>` into
    every job's properties after the item's own, so an item cannot set it. The import handlers record what
    each archive creates and ask whether an earlier archive of the live run created an entry. The token is
    written into the run's own jobs' `BatchData` rows; the batch instances endpoint
    (`GET /rest/batch/{job}/instances`, `BatchService.getJobInstances`) leaves it out of the properties it answers
    (a copy; the row keeps it), an upload carrying it (a form field `bootstrapRun` on any endpoint of
    `AbstractBatchableRestService`, the six import uploads among them) is answered 400 before any job starts, and
    a batch set whose item carries it is refused whole before anything is scheduled (`BatchSetService.checkJobs`),
    as an archive item carrying it is refused like `add` and `overwrite` (above). So a token that names no live
    run, or any sysadmin import, applies no rule. There is no new marker state and no schema change.
  - **What the later archive gives** (`org.niord.core.bootstrap.Given`). The VO-to-entity conversions lose whether
    a field was in the file (a category's active flag defaults to true and its type to `CATEGORY`, a promulgation
    type's priority is an `int` and its active flag turns null into false, a template parameter's flags default to
    false), so the three readers that need it (`BatchCategoryImportReader`, `BatchParamTypeImportReader`,
    `BatchPromulgationTypeImportReader`) read the file a second time as a JSON tree, with the typed read's own
    Jackson-2 defaults (`JsonMapper.builderWithJackson2Defaults()`, so the two reads
    agree item for item, content after the root included), and give each entry the set of
    paths it gives, its parents included (`active`, `descs[<lang>]`, `templateParams[<paramId>#<n>].mandatory`, the
    template parameters keyed by `paramId`, then by their order among equal `paramId`s, the import's own matching).
    They do this only for a job of the live run with an archive index above 0
    (`BootstrapRun.scopeOf(bootstrapRun, job).archive() > 0`); every other job, a sysadmin's, a batch set's or an
    ended run's, reads the file once, as before, and its entries carry no `Given`. The typed read is unchanged. The
    `Given` rides on `@JsonIgnore` fields of the core VOs (`SystemCategoryVo`, `ParamTypeVo`,
    `PromulgationTypeVo`; `org.niord.model`'s `CategoryVo` is untouched, so no REST or file shape changes) and on
    `@Transient` fields of `Category`, `ParamType` and `PromulgationType`. A replace branch reached without one
    throws, so such a job fails loudly instead of applying the wrong rule.
  - **What it covers**, per import, each for an entry an earlier archive of the run created; every write compares
    first, so an equal value writes nothing (no row is recreated, no join row rewritten, no version moved), which
    the byte-identical regeneration of the sample and the IT fixture shows:
    - **A template**, by path, case-insensitively (`findByPath`): replaced, its history
      row source `import` with the sysadmin who ran the bootstrap, as every bootstrap write (§6.4), the job log
      saying "Replaced (an earlier archive of this bootstrap run created it)", counted as overwritten.
    - **A dictionary entry**, by dictionary and key (`DictionaryService.replaceLanguages`): a language the row has
      (compared ignoring case, as `getDesc` does) takes the later value, and its long value when the later archive
      gives a non-empty one; a language the row lacks is added with its long value; a language left out is kept,
      and a language whose value is empty counts as left out (it neither clears a value nor adds a language);
      an AtoN filter given non-empty replaces the row's. `DictionaryEntryDesc` rows are updated in place, never
      through `updateDictionaryEntry` or `copyDesc` (which would clear the base AtoN filters). The import's result
      counts the keys whose values changed (`ImportResult.replacedInRun`) and the keys
      a language was added to, the job's line ending ", replaced in N, languages added to M (an earlier archive of
      this bootstrap run created them)", with a note per key ("Replaced the en of k1 (…): one → ONE"); the cached
      dictionary is saved and evicted when either is above 0.
    - **A category**, by MRN, else by one of its names in that name's language, case-insensitively, under the same
      parent (a root: by name at any depth, CATEGORY2 in `be/docs/potential-errors.md`), so an entry carries the
      English name, and its parents', and a `da`-only entry creates a second category
      (`CategoryService.replaceInCategory`): the type, active flag, sort order and AtoN filter replaced when given
      and different (`updateLineage` and `updateActiveFlag` run only when the active flag changed); the MRN, a
      matching key, filled when the row has none, and a different one kept (logged "the MRN (a matching key)"); the
      message untouched (bootstraps carry none); the names by language replaced or added, a language left out kept;
      the template parameters as for a composite type (below); the standard fields, editor fields and script
      paths replaced when given (an explicit empty list included, which empties them) and different, assigned
      element-wise into the row's own collection, the script paths filtered by their type first as the import
      outside a run filters them (so a list of only unknown paths empties the row's); the domains a union. `TemplateParam` and `CategoryDesc` rows are updated in place, and the category is saved
      only when something changed.
    - **A parameter type**, by name (`TemplateExecutionService.replaceInParamType`): a composite's template
      parameters matched by `paramId`, then by order among equal `paramId`s (`replaceTemplateParams`, shared with
      categories): on a match the type, mandatory and position-list flags replaced only when given, the names by
      language replaced or added in place; a new `paramId` appended; a parameter the later archive lacks kept. A
      list type's values replaced when given (an explicit empty list included) and their keys differ from the
      stored order, and an equal list
      skipped, so `DictionaryService.persistedList` (whose first match across dictionaries could re-resolve an
      entry) is never called for it. A kind change between composite and list fails the job, naming the type and
      both archives (`checkSameKind`). Standard types are still skipped.
    - **A promulgation type**, by `typeId` (`PromulgationTypeService.replaceValues(orig, type, given)`): the name,
      `serviceId`, priority, active flag, requirement and language replaced or filled when given; the message types
      and script paths replaced when given (an explicit empty list included, which empties them) and different;
      the domains a union.
    - **A report**, by `reportId` (`FmReportService.replaceValues(orig, report)`, which needs no `Given`: the
      sort order is an `Integer`, the name and template path null when left out): the name, sort order and template
      path replaced when non-null and different; the properties and parameters merged by key (the later value wins
      per key, a key left out is kept, a new key added; an empty map adds nothing, since they are maps, not the
      lists the owner's decision of 2026-10-25 names); the domains a union.
    - **A domain link**: a domain listed twice is linked once, and a domain id no row carries is not linked
      (`DomainService.missingDomainIds`), on all three link paths.
    - **A domain** is not covered: the domain import keeps an existing domain, inside and outside a run, so a later
      archive restating a `domainId` an earlier archive gives is refused before the claim (400, above). Its
      active flag is the one exception (the owner, 2026-11-03): a later archive's row with only the
      `domainId` and a boolean `active` replaces the flag of a domain an earlier archive of the run created, and
      the job log says so (*Before the claim*, above).
  - **The job log.** Each such import writes one line per entry (`BootstrapRun.Additions`: entry, added, replaced,
    kept and notes): `added …; replaced <field> <old> → <new>; kept …`, with a value the row lacked shown as `none`
    ("the requirement none → MANDATORY"). `kept` lists only what cannot change, a matching key and a domain not
    found; a field the later archive leaves out is not listed; an entry with nothing added, replaced or noted says
    `nothing to add`. An emptied list shows the old list and `[]` ("replaced the message types [LOCAL_WARNING] →
    []", "replaced the editor fields [it-a] → []", "replaced its values [k1] → []"). A key of one of the five
    default-text dictionaries that start-up created and a later archive of the run restates with other values is
    kept, counted `kept (not ticked)`, with one line naming the key: "Dictionary web: Kept menu.atons (start-up
    created it, not this bootstrap run: start-up's rules apply)" (only for a later
    archive of the live run, a key no archive of the run created, and values that differ; a key restated equal is
    counted unchanged, with no line). The category's line comes once per category per job.
  - **Not covered by the rule.** A category that a `domains.json` creates (a domain's own categories, through the
    domain import) is not recorded, so a later archive's category import updates it whole, as before; the base
    product's `domains.json` creates none. A new active category under an inactive one an earlier archive created
    makes that one active (`createCategory` calls `TreeBaseEntity.updateActiveFlag`, up the chain), and the job
    log says so for each such category (the child is not created inactive, which
    would change the country's own new row against its file). The other direction: a later archive's
    `active: false` on a category an earlier archive created makes its active descendants inactive too
    (`TreeBaseEntity.updateActiveFlag`, written through the cascade), rows the later archive did not name, and the
    parent's job-log line names them ("made inactive with it: Category <id> '<name>' …
    (TreeBaseEntity.updateActiveFlag; not covered by the rule)"; a later `active: true`
    that activates inactive ancestors says "made active with it: …"). The mailing-list import and the later jobs
    (chart, area, source, publication-category, publication) keep their update-whole semantics inside a run, with
    no scope, so the later archive's values win there already; neither the base product's archive nor a country's
    carries them. The order of the archives: the first must be the base product's; the command does not check it
    (a country's archive sent first would have its values replaced by the base product's, which its job log
    shows), and `bootstrap.sh`, the bootstrap README and the contract say so.
  - **Whole restatements.** For an entry an earlier archive of the run created, the four imports that update a
    found entry whole outside a run (category, parameter type, promulgation type, report) apply a later archive's
    entry by this rule instead, so a whole restatement and an addition that gives only the matching key and what
    it adds give the same rows when the restatement's other values equal the earlier archive's. Every import
    outside a run keeps its semantics: a sysadmin's release import keeps its preview, its ticks and its
    counts. DK's and GL's archives give additions to the base's composite types and promulgation types, and each
    restates the base product's `Audio`, whose values then apply (§7.1).
  - **No retry** (owner, 2026-10-24). A failed bootstrap stays failed for good, and so does one that broke off
    (`in-progress`): posting again is refused whatever the number of archives, and a restart of one of its jobs
    from the batch page is refused too (above). After a failed or broken-off bootstrap the operator recreates the
    database empty (locally `./start-local.sh --sample --reset`, or `dropdb` of the database; on a staging or test
    cluster a human drops it and the Helm databases Job recreates it, `be/k8s/README.md` step 4; production is not
    covered), starts the backend, and runs the same bootstrap command with the same archives. The registry of what
    each archive created is per attempt (`BootstrapRun.begin()` clears it), so a second attempt on the same
    database would apply a later archive's entries to the first attempt's rows by the imports' whole-update rules;
    on a fresh database a recreate costs nothing.
  - **The sample's archive** (`be/niord/sample/archive/`), applied as
    the second archive of the regeneration's run, adds its `da` descriptions and its domain links to the base
    entries; its `categories.json` carries no `messageId`. Its values would replace the base's too, but it
    restates them equal
    (its `Audio` `en` and 50, its list types' values), since `regen-sample.sh` refuses a changed base row (§8).
    `regen-test-seed-postgres.sh` applies the base product's archive alone and loads the
    sample after it with `load-sample.sh` (§7.4), so the fixture holds these categories as the sample has them.
  - **Tests.** `BootstrapRunTest` (the registry; the `'\0'` separator of `dictionaryKey`, so two pairs never share
    a key; `anAdditionsLineSaysWhatWasAddedReplacedAndKept`), `BootstrapRunnerTest` (the token per archive, an
    item's property never overriding it, the run ended after `applied`, `failed` and an exception; the claim's
    refusals above), `BootstrapArchiveTest` (the `bootstrapRun` refusal; a `domains.json`
    with content after its root still gives its domains), `GivenTest` (left out, given at the import's default
    value, given at another value, explicit null, empty string and object, an empty list given, a repeated
    `paramId`; a parameter without a usable `paramId`, which gives no path and moves
    no `#n`, a `descs` that is not a list, and content after the root read as the typed read reads it),
    `BootstrapRunTest` also tests `Scope.createdBy`, the three readers' tests (no token and the ended run's token bind the same entries with no
    `Given`, a live archive-0 job none, a live archive-1 job one on every entry and parent),
    `TemplateParamsReplaceTest` (the matching, a given flag replacing and a left-out one kept, a new `paramId`
    appended, equal input changing nothing and keeping the same `TemplateParam` instances, and the kind change,
    which a failing second run cannot show under the claim's guards), `BatchPromulgationTypeImportWriterTest` (no
    token, or the ended run's, updates whole and never calls `replaceValues`) and `BootstrapSameRunITTest`, which
    runs one bootstrap with two fixture archives in base product terms (`bootstrap-fixtures/same-run-earlier/` and
    `same-run-later/`, whose READMEs name every deliberate entry). It has no rows from before the run
    (such a run is refused by the claim, and that coverage is the
    claim's refusals and tests 9 and 10; there is no test 7): test 1 the run; 2 the template replaced; 3 the languages added (`k2`
    `{en: two, da: TO}`, and `it_composite`'s new `p3` appended, `[p1, p2, p1, p3]`); 4
    `aValueTheLaterArchiveGivesReplacesTheEarlierOne` (`k1` `ONE` with its long value, `k2`'s `da` `TO`, `k7`'s
    value replaced with its long value and AtoN filter kept, `k8`'s filter replaced, `k9` kept `{en: nine}` though
    the later archive gives an empty `en` and a new empty `da`, `p1`'s `en` `FIRST`, the
    root's `fr` `RACINE` with its `de` `Wurzel`, which the later archive leaves out, kept); 5 the domain links a
    union; 6 `theLaterArchivesFieldsReplaceAndNothingIsRemoved` (sort order, priority, report name and merged
    properties, script paths, a list type's values, `It flags`' given and left-out flags, `It deactivated`,
    `it_mandatory` keeping its mandatory flag and type, `it-domains-only` keeping every value, `it-flags` inactive
    with priority 0 and `MANDATORY` filled, and `It equal`, whose version and `TemplateParam` id equal those of its
    twin `It earlier only`, which only the earlier archive names, so an equal entry writes nothing;
    a value that differs for every replace branch: `it-changes`' name, `serviceId`,
    language, message types, script paths and requirement, `It changes`' type, AtoN filter, editor and standard
    fields and its MRN filled, `It mrn`'s other MRN kept, `it-report-changes`' sort order, template path and a
    `params` key, the type of `it_typed`'s parameter `t1`; the explicitly emptied lists of `it-emptied`, `It emptied` (its standard
    fields left out, kept) and `it_list_emptied`; `It inactive kept`, inactive, whose restatement leaves `active`
    out; `It parent`, whose `active: false` makes `It active child` inactive; and `It inactive parent`,
    inactive with its inactive child `It activated child`, which the later archive makes active under a parent
    entry that leaves `active` out, so the parent is made active too); 8 the token on
    every job; 9 and 10: after the run a sysadmin's import, and a job carrying the ended run's token, of a
    run-created category, parameter type, promulgation type and report give the whole-update outcome (a left-out
    list cleared) and no "replaced" line, a release import's preview and ticks unchanged; and 11,
    `theRunsJobLogsSayWhatWasAddedAndReplaced`, which reads the later archive's own job logs (the `BatchData` rows
    whose token names the run) and pins each line as built (`It activated child`'s
    "made active with it" note and its parent's "nothing to add" among them). `BootstrapITTest` Order 6 asserts that every
    `ScriptResourceHistory` row the bootstrap wrote through the command names the sysadmin. The token's refusals:
    `AbstractBatchableRestServiceTest` (unit: an upload with the field answered 400, no job started),
    `BatchUploadRestServiceITTest` (400 on a chart upload, no `BatchData` row), `BatchSetServiceITTest` (the set
    refused whole, nothing scheduled) and `BatchServiceGapITTest` (the instances answer leaves the token out, the
    row keeps it).
- **The command** `POST /rest/bootstrap` (`BootstrapRestService`, SYSADMIN) is a multipart upload of one or more
  parts named `archive`, applied in the order sent: the base product's archive first, then a country's (or, in
  the sample's regeneration, the sample's archive). It applies the archive rules of §6.5: an archive holds at most 2,000
  entries, 16 MB an entry and 64 MB in all, counted while copying, in the layout `batch-set.json`, `README.md`,
  root-level `*.json` and `templates/**/*.ftl` or `*.js`; the directory entries inside `templates/` that the
  JDK's `jar` tool writes for a zipped folder are skipped, and any other folder is refused. The
  layout also admits `releases/<folder>/*.json`, `*.md` and `*.sql` with the directory entries `releases/` and
  `releases/<folder>/` (one level only), which the published zip and `start-local.sh`'s zip carry: they are
  read, counted against the limits and hashed with the archive, and never applied (only `templates/` and the
  jobs `batch-set.json` names are). A root-level `texts.json` is admitted as any root `*.json` and not
  applied, since `batch-set.json` does not name it. Nothing is extracted
  to disk: the archive is read into memory, so an entry stored as a symbolic link is only ever read as a data
  file's bytes. It claims the marker before it answers, so a refusal
  is immediate (409 naming the state or the first table that holds a row; 400 naming the archive rule, or a later
  archive restating a `domainId` with more than its active flag), then answers 202 and applies the jobs
  asynchronously on the caller's security context, so the `BatchData` rows and the script-resource history name
  the sysadmin. `GET /rest/bootstrap` (SYSADMIN) reads the marker. The web server answers before the start-up
  listeners run, so until every one of them has run (the marker, the default texts and settings) both `GET` and
  `POST` answer 503, "the backend is still starting", and the `POST` refuses before it reads a part:
  a flag, `StartUpState`, is set on Spring Boot's readiness event `ACCEPTING_TRAFFIC`, which
  is published right after the `ApplicationReadyEvent` listeners have returned. An ordered listener on that
  event would not do, since an unordered one has the lowest precedence too. A caller asks again:
  `start-local.sh` and `regen-sample.sh` for up to 120 s, and `bootstrap.sh` posts again every 3 s for up to
  120 s. The bootstrap's imports thus never interleave with the start-up's own writes.
  The upload is bound by the multipart limits first, 50 MB a
  part and 50 MB a request (`niord-core-defaults.yml`: the body limit of the chart's proxy, which a deployment
  that needs more raises with it);
  the archive caps above (16 MB an entry, 64 MB in all) apply within them. The base
  product's archive and the sample's archive are well below them. A larger upload is answered 413, which
  `bootstrap.sh` reports naming the two properties to raise. `be/scripts/bootstrap.sh <base-url>
  <archives…>` wraps both with `curl`: it takes the token from `NIORD_TOKEN`, never from an argument, gives it to `curl` on standard input
  (`curl -K -`) so that no process's arguments carry it, posts the
  archives and polls `GET` until `applied` or `failed`, printing the runner's note when it changes while the
  marker reads `in-progress`. The in-process callers, with no token, are the
  integration tests and `regen-test-seed-postgres.sh`, which starts `org.niord.test.SeedRegen`: a main in the
  test sources that starts the test application, runs the runner on its archives and exits with the outcome
  (the script passes one, the base product's, and loads the sample afterwards with its own loader).
  No application context starts it, so a deployment has no property-triggered start path (D1). They take the
  same JBeret path and its waits.
- **Who calls it.** On an empty database no domain exists, so domain roles are not granted
  (`NiordDomainFilter`). Base-client roles come from `resource_access.niord-client-master` without a database
  lookup (`SecurityConfig.java:99-107`). The precondition is that the realm has the `niord-client-master`
  client with the sysadmin role. Locally, `start-local.sh` runs `bootstrap.sh` with the local realm's sysadmin
  when the marker reads `pending`, and only then. On `failed` or `in-progress` it prints the state
  and the remedy and posts nothing: under `--sample` `./start-local.sh --sample --reset`; otherwise stop the
  backend, then `docker exec niord-db dropdb -U niord $NIORD_DB`, and the next `./start-local.sh` creates the
  database empty and bootstraps it. When its own post fails, it reads the marker once more, with a new token (the
  first may have expired during the run), and names the remedy by state: on `failed`
  the recreation; on `in-progress` (`bootstrap.sh`'s wait ran out, or a backend of another run claimed the marker
  first) that a bootstrap is still running or broke off, to wait until `GET /rest/bootstrap` shows its outcome, and
  the recreation only once it reads `failed`, or `in-progress` with no sign of life for 5 minutes; on `pending`
  (the post itself refused: the 409 over a database that holds data, a 400 archive rule, 413, 401 or 403) that the
  bootstrap was refused before it started, for the reason `bootstrap.sh` printed, and the database left as it was,
  with the recreation for a database that holds data; on `applied` (`bootstrap.sh` gave up on a 401 mid-poll or
  its timeout while the run went on) that the run finished, so `--sample` loads; on anything else (no answer) only
  that the bootstrap did not finish (the function
  `bootstrap_outcome`, which `StartLocalBootstrapTest` sources from the script). It never drops anything itself,
  apart from `--reset`'s `niord_sample`.
  It posts
  the base product's archive alone: `--sample` loads the sample with its own loader after that bootstrap, and
  `--demo` answers exit 2. On a cluster, an
  operator runs it once after install (a Helm hook is a later option); a failed bootstrap is recreated empty
  (D4; `be/k8s/README.md` step 4 for a staging or test cluster).
- **`regen-test-seed-postgres.sh`** uses the runner too: there is no `BootstrapService` and no
  `-Dniord.batch-set` start path. The script
  runs Liquibase, with `ddl-auto=validate`, because `DefaultsLedger` and `BootstrapState` have no JPA entity and
  a schema Hibernate builds lacks them; its data dump leaves out `BootstrapState` and the Liquibase tables, so a
  database seeded from it reads `existing` at its first start. Both its dumps leave out the residue
  list `regen-sample.sh` uses: `spatial_ref_sys`, `BootstrapState`, `DefaultsLedger`, `databasechangelog`,
  `databasechangeloglock`, `User`, `BatchData`, `MessageHistory`, `ScriptResourceHistory`, `Setting` and
  `message_search_text` (§7.4).

### 7.3 The seed's rows

The rows of the legacy development seed went as follows. The integration suite's fixture
(`db/niord-base-data.sql`, loaded by `changelog-with-seed.yaml`, both in niord-base's test resources) is no
longer that seed (§7.4):

| Rows | Goes to |
|---|---|
| master domain; the 12 categories (without `Category_Domain` links); 32 parameter types (the seed's 41 without the 7 standard ones and without `dgps` and `dgps_station_list`, which are DK's and GL's, §7.1); 48 template parameters; promulgation types 1 `NAVTEX` and 3 `Twitter` (without their domain links); the `aton` dictionary (111 entries, without the four `radio_navigation.dgps.*` stations, which go with the two types); all without any `da` description (§7.1) | base product bootstrap |
| the other 5 domains (active) and the categories' links to them (`Category_Domain`), carried as additions to the base categories (§7.2, *Within one run*); promulgation type 2 `Audio` (the base product's bootstrap carries `Audio`, and DK's row is an addition to it) and the domain links of `NAVTEX` and `Twitter`; 6 series; the five `nm-*`/`fa-list` report rows, and an `nm-accumulated` report row naming `messages/nm-accumulated-report-pdf.ftl`, taken from DK's migrated database (the seed had none, §6.1). The domain IDs are those DK's deployments and the local realm use, `niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex`, not the seed's `niord-client-*` (owner, 2026-10-22; §8) | DK bootstrap; the sample carries its own copy of the domains, series, `Audio`, the domain links of `NAVTEX` and `Twitter`, and the categories' links (§8) |
| none of the seed's rows: from GL's own database, the domain `niord-gl-nw` (`NW Greenland`, UTC) with its series `ako-nw`; `NAVTEX`'s link to it; GL's `Audio` (`kl`) and `Navtex GL`; `dgps`, `dgps_station_list` and the four DGPS stations with their `kl` descriptions; the `kl` descriptions of 111 `aton` entries and 37 template parameters. No reports and no categories | GL bootstrap |
| 8 publications; 5 mailing lists (trigger 1 names DK's `fetch-firing-exercises.js` and `audio-broadcast.ftl`); 172 charts, 55 areas, 29 sources (Danish place data) | the sample, with the two scripts and the DGPS pair (`tmpl/dgps.ftl`, `tmpl/dgps.js`, which the archive's category Radio navigation/DGPS names) copied from DK's bootstrap (§8) |
| the 5 text dictionaries' copies; 44 settings (including a `pdfEncryptionPassword` value); batch and sequence residue | dropped: start-up adds texts and settings |

The base product's `categories.json` carries all 45 categories in English, without `messageId` (which in the
legacy archive named a Danish message) and without domain links: the 13 of §7.1 and, by the owner's ruling of
2026-11-04, the other 32. The sample restates them for its `da` and links
(`be/niord/sample/archive/categories.json`); `Radio navigation`/`DGPS` comes without its scripts and template
parameter, which are DK's and GL's
data (the sample adds them in its archive). The 13 keep their place first in the file, so they keep ids 1 to 13 in
a fresh database; the 32 follow `Firing Exercises`, and their ties at one sort order are in production's id order
(`Wreck` before `Uncharted obstruction`). The category import's writer is a no-op
(`BatchCategoryImportWriter`): the processor persists each item, and a second merge in the writer, with no chunk
transaction, would cascade to a parent made stale by its next leaf (`OptimisticLockException`);
`BatchCategoryImportProcessorITTest` runs the job on siblings under one parent.

### 7.4 What still uses the seed

- **The integration suite's fixture.** `niord-base-data.sql` and `changelog-with-seed.yaml`
  are in niord-base's test resources *and in the published test-jar*, at fixed class-path
  paths. A regeneration leaves the seed changeset's path unchanged and changes its checksum, which only a fresh
  IT database applies. DK's and GL's ITs depend
  on it: those that extend the test-jar's `ITTest`, which selects the seed changelog (`DkBootstrapITTest` and
  `GlBootstrapITTest` run on their own empty containers). The fixture is in the test-jar, unlike
  `niord-test-seed.sql` (the full dump, kept beside it
  as the readable reference), which the test-jar excludes (`niord/pom.xml`).
  - **What it is.** The fixture is a `pg_dump` of a database bootstrapped with the base product's
    archive through the runner and then loaded with the sample by the sample's own loader.
    `regen-test-seed-postgres.sh` builds it in a throwaway PostGIS container (`niord-seed-pg`, its database named
    `niord_sample`, published on `127.0.0.1:15432` only, so every rule of the loader and of the sample's SQL
    holds): `SeedRegen` starts the suite's own application and applies `niord/bootstrap/` alone, zipped with the
    JDK's `jar` tool; `load-sample.sh --container niord-seed-pg` (the option's default is
    `niord-db`) loads `niord/sample/niord-sample.sql`; the sample's message uid prefix (the uids' common start
    up to their fourth dash) is replaced by the fixture's own, which keeps the first three hex digits that name
    the repository folders (`messages/5/a3/`) and continues `f1c70-0000-4000-8000-`, in `"Message"`'s `uid`,
    `repoPath` and `thumbnailPath`, in `"Attachment"`'s `path`, and in `"Reference"`'s `messageId` and
    `"IssueMember"`'s and `"IssueOverride"`'s `messageUid` (the last three hold no sample uid, so their
    updates write no row; the no-sample check treats the prefix as a marker; the script reads it
    from the database and writes no sample uid);
    `created` and `updated` are set to the sample's epoch
    (`2026-10-22 00:00:00`) where they differ, since the bootstrap's rows carry their write time; and every table
    with a primary key is clustered on it, so the row order no longer depends on where an update left a row, and
    its clustering mark is dropped again (`SET WITHOUT CLUSTER`), so the full dump has no `CLUSTER ON` line that
    V001's schema lacks. The script ends (its step 9 of 9) by running `check-no-sample.sh` over the folder it
    wrote the dumps to, and fails on a finding. The
    JVM runs with `-Duser.timezone=UTC`, as `regen-sample.sh` starts `niord-app`. Two runs give the same bytes:
    both dumps leave out `pg_dump`'s `-- Dumped from` and `-- Dumped by` version lines,
    as `regen-sample.sh` does, so a rebuild of the floating image tag changes no byte, and step 1 compiles
    after `clean`, so no stale output of an earlier checkout reaches the class path; `be/.gitattributes` keeps
    `niord/src/test/resources/db/**` in LF on every platform. The script runs after `regen-sample.sh` when both
    are due, since the sample's guard refuses a base it was not made on.
  - **The class path.** The regeneration's application runs on a copy of `niord/target/test-classes`
    (`niord/target/seed-test-classes`) without its root-level `*.properties` and `niord.json`, the test-jar's own
    exclusions. With them the first start writes the test tree's texts, one key more (`web` `lang.da`) shifts the
    `aton` dictionary's `DictionaryEntry` ids by one, and the sample's guard refuses ("DictionaryEntry already
    holds a row where the sample's go (id >= 388)", measured); without them the start writes the base product's
    texts and settings only, the rows `niord-app` writes for `regen-sample.sh`.
  - **What it leaves out.** Both dumps leave out the residue `regen-sample.sh` never captures: `spatial_ref_sys`,
    the marker (`BootstrapState`), the ledger (`DefaultsLedger`), Liquibase's two tables, `User`, `BatchData`,
    `MessageHistory`, `ScriptResourceHistory`, `Setting` and `message_search_text`, and
    the batch jobs' numbers (step 7 deletes the bootstrap's seven `BATCH_JOB_*` `Sequence` rows; a job's
    first run creates its counter at 1). The sequences of the excluded tables (`BatchData_SEQ`,
    `ScriptResourceHistory_SEQ`) keep the value the regeneration advanced them to, which is harmless: it is at or
    past every id. A database seeded from it
    therefore reads `existing` at its first start (its domains exist), and that start writes the settings from
    the class path it runs on (niord-base's test files, a country's `defaults/country/`) and adopts the base
    texts, as a deployment's first start does. With no `BatchData` row there are no stale instance ids.
  - **What it holds.** The base product's 80 templates and the sample's 4 scripts
    (`audio-broadcast.ftl`, `fetch-firing-exercises.js`, `tmpl/dgps.ftl`, `tmpl/dgps.js`) as `ScriptResource`
    rows; the bootstrap's three reports (`standard`, `draft`, `public`); the master domain and the sample's five
    domains; and the sample's series, place data, publications, mailing lists and 34 messages. No
    template file is in any jar: the test-jar carries them only as rows of the fixture. The sample's four
    `Attachment` rows name paths under the fixture's uid prefix and have no files behind them in an IT
    repository: nothing stages the PNGs for the suite (only `load-sample.sh` copies them, into the
    regeneration's own home), and a test that needs an attachment's bytes copies the file itself
    (`be/docs/test-infrastructure.md`).
  - **A dump, not a copy of the sample.** A copy of `niord-sample.sql` in the test-jar would carry the sample's
    sentinel and bytes, which the no-sample check flags. The dump carries neither, nor the sample's message uid
    prefix (rewritten above), and `check-no-sample.sh`, which also reads niord-base's test-jar (`be-ci.yml` globs
    `niord/target/*.jar`), passes it. `TestFixtureCurrentTest.theFixtureCarriesNoSampleMarker` checks both dumps
    for the sentinel and the prefix, read from `sample/` at run time, so no class in the test-jar names them; the
    S-124 IT that needed a sample message looks it up by its short id (`NW-001-26`). The test-jar is no
    deployable and goes into no image, which is what P9's rule names; the check's header and `CheckNoSample`'s
    javadoc say so.
  - **`ITTest` adds nothing.** The fixture carries the `public` report and the base product's 80 template rows,
    so `ITTest` plants neither. The unit test `TestFixtureCurrentTest` compares the fixture's 84 `ScriptResource`
    rows with `bootstrap/templates/` plus `sample/archive/templates/` and fails, naming the script, when a
    template changed without a regeneration.
  - **Rows the suite pins that neither source has.** Niord's own test-only templates and scripts
    (`templates/it/`, the `it-*` mail and mailing-list templates, `test-scripts/`) come in as rows per test class
    through `ScriptResourceFixtures.ensure`, unchanged. DK's `nm-report` and `nm-tp-report`, which only DK's
    bootstrap carries (§7.3), come from `ReportFixtures.ensure` (the rows on their base
    templates, linked to `niord-nm`; committed, they stay for the rest of the JVM, so no IT asserts the
    fixture's report count exactly, as for `ScriptResourceFixtures`' rows). Legacy rows an older deployment holds (the 7 standard `ParamType` rows, a
    setting no catalog declares) and DK's text state are planted by the test that needs them and rolled back or
    removed afterwards.
  - **The IT realm** (`src/test/resources/keycloak/niord-realm.json`) names its five domain clients `niord-nw`,
    `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex`, the IDs of the sample's domains, of DK's
    bootstrap and of the local realm (§8), beside `niord-client-master`.
  - **A country's templates.** A country's ITs on the test-jar have no `bootstrap/` folder of niord-base, and
    the fixture holds the base rows: DK's `DkBootstrapTemplates.writeDksTemplates` and GL's
    `GlBootstrapTemplates.writeTemplates` write only the country's templates over the fixture's rows (create or
    replace, an equal row left; `be/docs/country-api.md`). The base product's bootstrap,
    `org.niord:niord-bootstrap:<base>:zip`, which
    the country's build unpacks at `pre-integration-test` (no dependency, so `package -DskipTests` never resolves
    it), serves the
    countries' bootstrap ITs (GL's comparison of its shadows too); no test writes it to the IT database.
  - **The pinned ITs.** `NavtexPromulgationGenerationITTest`, `PromulgationTypeServiceITTest` ("contains
    exactly"), `SafetyNet*ITTest`, `Issue*ITTest` except `IssueRenderRealITTest`,
    `MemberResolutionDifferentialITTest` and `BootstrapSeededITTest` pin facts of the fixture (§12); the corpus
    cases whose thresholds lie above the sample's 34
    messages skip.
- **There is no `demo` profile, no `--demo` and no seed folder.** `./start-local.sh --sample` is the one local
  start with content (`--demo` answers with exit 2 and a pointer to `--sample`), and the legacy development
  archive's content is in the sample's archive (§8).
- **There is no Helm seed switch.** A fresh cluster deployment is bootstrapped once with
  `be/scripts/bootstrap.sh` after
  install. An existing seeded database is unaffected [I]: the bare master changelog does not list the
  seed changeset, and Liquibase ignores applied changesets that the changelog no longer names.
- **Liquibase** (D2): a changeset changes the schema only. It may also **reshape existing values a schema change requires** — split a column, convert a type, move values into a new table — but never insert new content, delete rows, or change what a value says. Changesets already applied (`V005`'s deletion of three settings) stay as they are. The publications go-live import, the bootstrap and backup/restore are unaffected: the first two run through the application, the last outside Liquibase.

---

## 8. Sample (country-split plan P9)

The sample keeps its safety rules (country-split plan §3.4, P9): never on a classpath or in an image,
loaded with `psql` into the loopback `niord_sample`, or a country launcher's `niord_sample_<cc>` (a two-letter
lower-case country code), with messages written for the sample. It
is layered: the base product bootstrap through the command, then the sample rows. The sample carries the DK rows
it needs itself, as Danish sample content (country-split plan R11): DK's domains and series, `Audio`, the domain
links of `NAVTEX` and `Twitter`, the two scripts its mailing lists name (`fetch-firing-exercises.js`,
`audio-broadcast.ftl`) and the DGPS pair its category Radio navigation/DGPS names (`tmpl/dgps.ftl`,
`tmpl/dgps.js`; without it `missing-paths` is not `[]`). It fetches nothing from
`niord-dk` and needs no credential; drift from DK's bootstrap is accepted. Then come the Danish place data,
publications and mailing lists of §7.3, and the sample messages. The IT fixture is made from the base
product bootstrap and the sample (§7.4): a dump of a database the sample was loaded
into, in niord-base's test-jar, which is no deployable and goes into no image; it carries none of the sample's
files, and the no-sample check passes it. The sample's domains are clients of the local
realm (`be/keycloak/niord-realm-local.json`: `niord-nw`, `niord-nm`, …), or the local sysadmin would hold no
role in them: by the owner's ruling of 2026-10-22 DK's bootstrap, the sample and the local realm use the IDs
DK's deployments use, `niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex`.

As built:
- **The folder** `be/niord/sample/` (outside `src/main/resources`): `README.md`, whose first lines are §7.1's;
  `archive/`, a bootstrap archive in the batch-set format, with no report and no item property: DK's
  `domains.json`, `promulgation-types.json` and four scripts as DK's bootstrap had them when the sample was
  made, and the legacy development archive's Danish base data with the domain IDs above
  and without the categories' `messageId` (§7.2), including the `da` descriptions of the `aton` entries,
  parameter types and categories that the base bootstrap does not carry
  (the 34 parameter types restate the base bootstrap's 32, whose 13 composite types gain
  their `da` names while the 19 list types restate the base's values equally, so they change nothing, plus `dgps` and
  `dgps_station_list`). Three `LINK` publications carry no link (the import accepts a publication without one,
  and they stay `ACTIVE`);
  `messages/messages.json`, 34 messages written for the sample (fixed uids, `en` and `da`,
  every title "SAMPLE —", every position at sea where its vicinity says, judged from general geography);
  and two generated outputs,
  never edited: `niord-sample.sql` and `repo/`, the four
  attachment files its rows name. `be/.gitattributes` gives the folder `eol=lf`, so the rebuild is the same on
  any platform.
- **The format** is a `psql` script of the rows the sample adds on top of the base
  product's bootstrap, data-only column inserts with triggers disabled per table, in one transaction, the
  guard first and every touched sequence's `setval` last. It loads on any country's backend after the base
  product's bootstrap (the owner, 2026-10-25). Why: a country's backend writes its own default texts at its first start, before any
  bootstrap (`DefaultTexts.java:60`: `DictionaryService` adds what the text layers have and the database lacks;
  a country's layer adds keys and languages the base lacks: DK's the English `lang.da` and
  `msg.responsible.agency` and Danish for its mail and pdf keys, with more Danish from the da pack once staged,
  GL's the same English keys and its own Danish and Greenlandic), so the base ids of a base-only
  first start never hold on DK's or GL's backend.
  - **The sample's own ids** start at 1,000,001 in every table: a row's id is 1,000,000 plus its rank by the
    build's id, so each table keeps its order (34 tables; the highest id is 1,000,172). Ids are `int4`, and
    every entity has its own `<Entity>_SEQ` with `INCREMENT BY 50` (there is no shared `hibernate_sequence`):
    a base bootstrap and a country's first start write at most a few thousand rows per table, and each JVM
    start skips at most 49 ids per sequence, so a country's own rows stay three orders of magnitude below the
    offset; it also sits clear of the ITs' made-up ids. After the inserts every touched sequence is set to at
    least its table's `max(id)`, so the backend's pooled sequences hand out ids above the sample's.
  - **A reference to a base row** is its natural key, a scalar subselect in one of five tables' shapes, which
    one class owns (`org.niord.test.sample.SampleReferences`; the regeneration, the guard, `SampleSafetyCheck`,
    `SampleFolderTest` and the IT go through it): `Dictionary` by name; `DictionaryEntry` by its dictionary's
    name and its key (a key repeats across dictionaries: `term.and`); `PromulgationType` by `typeId`;
    `Category` by MRN or, without one, by its English name with no MRN (the 13 base categories have none, and
    their English names are distinct); `TemplateParam` by its owner (a category, or a composite `ParamType` by
    name), its position in the owner's list (the join table's `indexNo`; `TemplateParam.indexNo` is always 0)
    and its `paramId`, since `paramId`, type and English name repeat 10 times among the 46 base parameters. The
    sample names 13 `Category`, 1 `Dictionary`, 111 `DictionaryEntry`, 3 `PromulgationType` and 46
    `TemplateParam` base rows (11 owned by categories, 35 by composite types); it names no `Domain` (not the
    master domain either) and no `ParamType` by id. A table the sample refers to without a shape stops the
    regeneration, naming it.
  - **The guard** refuses, naming the reason, unless, in order: the database is `niord_sample` or
    `niord_sample_<cc>` (a two-letter lower-case country code); its schema is there and its marker reads
    `applied`; its Liquibase changesets are the recorded list; in each of the 34 tables no row is
    at or above 1,000,001 and its sequence is below it (rows and sequences: a sequence there may already have
    handed out ids in the range); the sample's 5 domains and 6 series are not there; and every natural key the
    sample names resolves to exactly one row, as does each of the 15 base `ParamType` names that its template
    parameters' types name. The refusals split their remedy: the changeset
    refusal means the sample was made on another schema and names `regen-sample.sh`; the offset, domain and
    series, and natural-key refusals mean the database is not one clean run of the base product's bootstrap (an
    interrupted bootstrap, or rows added since; the domain and series refusal adds that a `--sample` run posts
    the base product's archive only) and name `./start-local.sh --sample --reset`, with a regeneration only if
    the base product's bootstrap, schema or entities changed since the sample was made. The others name what the
    load needs (the database name; a schema and the marker `applied`, which `./start-local.sh --sample` gives
    it). A country's own archive beside the sample stays unsupported: the sample's domains and series would
    collide with the country's.
- **The regeneration** (`be/niord/scripts/regen-sample.sh`) builds the sample from its sources through the
  real path, twice: `niord_sample_base` with the base product's archive only, the baseline, and
  `niord_sample` with the base archive and the sample's archive in one bootstrap run, so the same-run rule
  (§7.2) adds the Danish descriptions and domain links to the base entries (and
  would replace a differing value, which the comparison below refuses: the sample restates the base
  values equal), then the messages through the
  message archive import as a sysadmin batch set. It installs niord-base into a Maven repository of its own
  (`.local-run/sample-regen-m2`, the shared `~/.m2/repository` behind it), as a launcher slot does, so a run
  never overwrites the shared jars. It compares the two through `postgres_fdw` and fails when a baseline row
  differs but for `version`, `created`, `updated` and the derived `treeSortOrder` (the residue tables are not
  compared); it captures the other rows of the allow-listed tables, never the build's residue (users, batch
  data, histories, the ledger, the marker, settings, the search index, Liquibase's tables), and fails on a
  sample row in any other table, and on a link the message import dropped (the rows of `Message_Area`,
  `Message_Category`, `Message_Chart`, `Attachment`, `Reference` and `FeatureCollection` against the counts
  `SampleTool counts` reads from `messages.json`, captured first, so a failing `SampleTool` stops the run).
  The capture is normalised
  (`created`, `updated` and the mailing-list triggers' `nextScheduledExecution` set to the
  sample's epoch, 2026-10-22, a message's `createdBy` and `lastUpdatedBy` cleared, the uids drawn at random
  derived from the row ids, `Message.areaSortOrder` rounded to 0.001), so two runs give the same bytes on any
  day. The capture then renumbers (`SAMPLE_FIRST_ID=1000001`, which `SampleFolderTest` holds
  equal to `SampleReferences.FIRST_ID`): after the normalisation, so the uids derived from the row ids
  (`Publication`, `FeatureCollection`, `Feature`) stay as they were, each table with an id is mapped onto
  1,000,001 on in its order, every foreign key between the sample's tables and the lineage of `Area` and
  `Category` (the two text columns that carry ids) go through the map, and the tables without an id keep their
  earlier row order. pg_dump's output, captured to a file first so that a failing `SampleTool` stops the run,
  then goes through `SampleTool references`, which replaces every base id in a base-reference column (from the
  catalog's foreign keys) by its natural-key subselect and passes every other byte through; `SampleTool
  guard-keys` writes the guard's natural-key checks. The run stops on a sample row whose lineage names a base
  row, on a base id at or above 1,000,001, on a dangling foreign key, on a captured table whose id is itself a
  foreign key (a joined subclass; none today), and on a key that does not resolve to exactly one row in
  `niord_sample` with the sample's own rows beside the base (an ambiguous subselect). The diff of a
  regeneration is therefore only ids, foreign keys, lineage and references, and `repo/` does not change. The
  candidate passes `SampleSafetyCheck` (only the statements the regeneration writes, each on an
  allow-listed table, and an `INSERT`'s value only a quoted literal, optionally cast, a number,
  `NULL`, `true`, `false` or one of `SampleReferences`' shapes; an e-mail outside `example.org`; a phone
  pattern; a title without "SAMPLE —"; under
  `repo/`, any file but a PNG whose chunks are exactly IHDR, PLTE, IDAT and IEND; the sentinel line) and is
  loaded with `load-sample.sh` into the baseline renamed `niord_sample`, the schema check, before it is written
  over the committed files. It waits up to 120 s for `pg_isready`, refuses a checkout or
  home path with a space, drops its scratch databases on a failure only once its step 1 has created them,
  numbers its steps [1/8] to [8/8], and `--check` compiles first.
- **The loader** (`be/niord/scripts/load-sample.sh --host --db --home`) refuses (exit 1) unless the host is
  loopback, niord-db publishes its port on loopback only and the database is `niord_sample` or
  `niord_sample_<cc>` with a two-letter lower-case ASCII country code, which a country's launcher uses
  (the host and the database name are checked before any container is asked; the SQL's guard refuses any
  other name itself, and `be/scripts/seed-dev-database.mjs` refuses both names, since real messages never go
  into a sample database). Past those
  refusals it first copies `repo/` into the Niord home, on every run (exit 1 and no SQL run when the copy
  fails); it then writes no row and answers 3 when the sample is already there, or runs the SQL with `psql`
  inside niord-db. It never creates or drops a database. `--container <name>` (default `niord-db`)
  names the container, so `regen-test-seed-postgres.sh` loads the sample into its throwaway container with the
  same rules: a loopback host, the container's port published on loopback only, the database `niord_sample`
  (one of the two names the loader accepts).
- **The launcher**: `./start-local.sh --sample` starts the services, creates `niord_sample` when missing,
  starts the backend, runs the bootstrap command with the base product's archive, stops the backend, loads the
  sample, **starts the backend again** (its caches and message index then see the sample), and only then the
  frontends. SECOM's outbox dispatch is off (`-Dniord.secom.enabled=false`) and mail goes to MailHog. It calls
  the loader on every run: a re-run on a loaded database copies the attachments again, takes the loader's 3 as
  success and starts it again, saying that a changed `be/niord/sample` needs `--reset`; `--reset` drops only
  `niord_sample`, through the launcher library's `sample_reset <db>`
  (`be/scripts/local-run-lib.sh`), which drops only the sample database it names: it refuses any name but
  `niord_sample` and `niord_sample_<cc>` before any `docker` call and while a client session is connected to
  it, then runs `dropdb --if-exists`. `start-local.sh` passes the name written out, never `NIORD_DB`; a
  country's launcher passes its `niord_sample_<cc>`. The base product's own `--sample` uses `niord_sample`
  only. It posts the bootstrap only on a `pending` marker; on `failed` or `in-progress` it
  prints the state, names `--reset` and posts nothing, and when its own post fails it reads the marker again and
  names `--reset` on `failed`, on `in-progress` once it is final, and on `pending` for a database that holds data,
  while `applied` loads the sample (§7.2, *Who calls it*). It takes no `NIORD_DB` but `niord_sample`
  and no `NIORD_HOME_DIR` but `~/.niord-local/niord_sample` (exit 2). The sample users are the
  local realm's four users with `example.org` addresses.
- **No sample in a jar or image**: `CoreResourcePackagingTest` for niord-base's classes (the whole classes
  folder), and `be/k8s/scripts/check-no-sample.sh` over the built jars and the
  bootstrap zip in Backend CI, over the jars and the deployable split in `publish.yml`'s build job before the
  first deploy, and over the image's inputs in its harbor job before the log-in. It reads jars, zips and
  `docker export` tars; a `docker save` tar (`manifest.json`, `oci-layout` or `index.json`
  at its root) exits 2, every entry is also searched for the messages' uid prefix (read from `messages.json`
  at run time, never written elsewhere) beside the sentinel, an entry over the 512 MiB in-memory limit that
  is itself a zip or a tar exits 2, and every entry of a duplicated zip name is checked.

---

## 9. The country repositories

How the two country backends' resources are classified (counted on 2026-10-20 against `niord-base`; both countries moved theirs on 2026-10-22). Every data shadow is compared with the base product's file. An equal copy is dropped: the base product's bootstrap carries it. A different copy goes to the country's `bootstrap/`. A copy that only lags a base product fix is dropped (GL's `tmpl/common.js`). DK's 42 shadows moved unchanged: each differs from the base file by DK's own lines, and laying them onto the current base files would change DK's renders; the audit of each shadow against the current base file is DK's follow-up, shipped as a release folder of its own. In the tables, `bootstrap/` is the country's `be/bootstrap/` (§7.1).

### 9.1 `niord-dk`: 70 resource files, 46 shadows

| Files | Count | Kind | Goes to |
|---|---|---|---|
| `templates/tmpl/*.ftl` (40), `templates/mailinglist/publish-navwarn.ftl` | 41 shadows | data | `bootstrap/` |
| `templates/mailinglist/{audio-broadcast.ftl,fetch-firing-exercises.js}`, `templates/tmpl/dgps.{ftl,js}`, the eight `templates/messages/{fa-list,nm-report,nm-almanac-report,nm-accumulated-report}-pdf-intro_{da,en}.ftl` | 12 own | data | `bootstrap/` |
| `templates/messages/message-list-pdf-footer.ftl` | 1 shadow | data (named by code) | `bootstrap/` |
| `templates/aton/{light-character,telephony-codes}_da.ftl` | 2 own | data (localized variants) | `bootstrap/` |
| `web_en.properties`, `mail_en.properties` | 2 shadows | texts | `mail_en`'s three differing keys and `web_en`'s `lang.da` → `defaults/country/texts/en/`; `web_en`'s seven differing keys, which no template or Java reads → DK's branding (`footer.*`) and one text override (`msg.no_pos_messages`); the other four go (§4.4); `mail.safetynet.cancel` then comes from the base product |
| `{web,message,pdf,mail,template}_da.properties` | 5 own | texts | the `da` pack's backend half (DK is its home), its `lang.*` names included (§4.3), in neutral Danish: DK's own wording of the four keys its `texts/en/` words (`mail.active_nav_warnings`, `mail.navtex.cancel`, `mail.navwarn.cancel`, `pdf.nm_almanac.desc`) to `defaults/country/texts/da/`; `footer.copyright` and `footer.link` the base product's English values, DK's administration branding carrying DK's; `mail.safetynet.cancel` added and `navwarn.date.html_day_suffix` `{{suffix}}<sup></sup>`; DK's pom copies `fe/packs/da/backend/` into the jar, the root files deleted |
| `niord.json` | 1 shadow | settings | `defaults/country/settings.json`, with its keys plus `country` (`DK`) and `organisation` (both `required`, §5.4). `wmsLogin`/`wmsServiceName` are dead and go. Its `wmsPassword` key is read by live code (`WmsProxyServlet.java:89, 129`); the committed value is a placeholder, not a real credential (owner, 2026-10-20), and is emptied in the file: the real value is set on the Settings page or in the server's overlay |
| `application.yml`, `application-local.yml` | 2 own | configuration | stay; `application-local.yml` does not select the seed changelog, which is in niord-base's test-jar only: a DK local run is bootstrapped instead |
| `META-INF/resources/img/company-logo.png` | 1 shadow | image (code asset) | guarded override |
| `img/company-logo-da.png`, `img/firing_areas.png`, `img/flags/da.png` | 3 own | images | stay; `img/flags/da.png` comes from the `da` pack |

Before the move the override check found 56 `template-in-jar` findings in DK's jar, every `templates/**` file
of the table (42 shadows and 14 own), and one `unlisted-shadow`,
`META-INF/resources/img/company-logo.png`, which is listed in `be/overrides.json` since. The check exits 0.

On a fresh DK database the 42 shadows apply: the base product's archive runs first and
adds each path with the base product's generic template, and DK's archive, later in the same run, replaces it
(§7.2, *Within one run*; owner, 2026-10-22; `DkBootstrapITTest` asserts 42 of 42). Existing (migrated) DK
databases are unaffected: their rows are DK's.

Tests:
- `NiordDkSettingsResourceTest`: against the settings file and its checks.
- `DkTemplateResourcesTest`: against `bootstrap/`.
- `DkWebBundlePublicationTypeTest`: against the layered texts.
- `DkDgpsTemplateITTest` and `DkLightDestroyedTemplateITTest`: import from `bootstrap/`.
- `DkLightCharacterTest`: renders the `_da` variant from `bootstrap/templates/aton/` and asserts each result
  non-blank. `DkNavWarnDateFormatterTest` reads no template; its Danish side reads the pack.
- `DkDanishTextsTest`: the pack in the jar once, no root-level `*_da`, DK's wording from `texts/da/` over
  the pack, the jar's flag the pack's.
- `AppBootSmokeITTest` and `ContextLoadsITTest`: on the test-jar's fixture (their Javadoc names
  `defaults/country/`); the boot on an empty schema plus the runner is
  `DkBootstrapITTest`.
- `CoreDefaultsPrecedenceITTest`: kept as is.

### 9.2 `niord-gl`: 131 resource files, 75 shadows

| Files | Count | Kind | Goes to |
|---|---|---|---|
| `templates/tmpl/*` (46), `templates/mailinglist/*` (6), `templates/messages/{fa-list,nm-report,nm-tp-report,nm-annex-report,nm-almanac-report,nm-accumulated-report}-pdf.ftl` (6) | 58 shadows | data | 12 equal to the base product's (`tmpl/{compute-affected-radius.js,navtex.ftl,object-adrift.js,twitter.ftl}`, `mailinglist/{active-navwarns,cancel-navtex,cancel-navwarn,publish-navtex,publish-navwarn-common}.ftl`, `messages/{fa-list,nm-almanac-report,nm-annex-report}-pdf.ftl`) and three copies that only lack base product fixes, each equal to an earlier niord-base file with no GL line: `tmpl/common.js`, `messages/nm-report-pdf.ftl` and `messages/nm-tp-report-pdf.ftl` (the `?filter` speed-up, the edition line), dropped; 43 → `bootstrap/` |
| the `-gl` mailing-list templates and their `_da`/`_kl` variants, `audio-broadcast*`, `fetch-firing-exercises.js`, `publish-navwarn_{da,kl}`, the report intros `_{da,en,kl}` and the report variants `_{da,kl}`, `tmpl/dgps.{ftl,js}` | 32 own | data | `bootstrap/` |
| `templates/aton/light-character.ftl`, `templates/geometry/*` (5), `templates/messages/{message-list-pdf,message-list-pdf-footer,message-mail,message-support}.ftl` | 10 shadows | data (named by code) | 7 equal to the base product's (`aton/light-character.ftl`, `geometry/*`, `messages/message-list-pdf.ftl`) dropped; 3 → `bootstrap/` |
| `templates/aton/{light-character,telephony-codes}_{da,kl}.ftl`, `templates/messages/{message-list-pdf,message-list-pdf-footer}_{da,kl}.ftl` | 8 own | data (localized variants) | `bootstrap/` |
| all five `*_en.properties` | 5 shadows | texts | `defaults/country/texts/en/`: the differing keys a template or Java reads (the others go to GL's frontend text overrides or branding, §4.4), `lang.da`/`lang.kl`, and the own keys of §4.4 as classified |
| all five `*_da.properties` | 5 own | texts | compared with DK's `da` pack: GL's own wording to `defaults/country/texts/da/` (the three base mail keys GL words differently and its five own, `pdf.nm_almanac.desc`, `lang.kl`, `msg.responsible.agency`); the root files are deleted, and the pack's wording won for the eight keys that differed |
| all five `*_kl.properties` | 5 own | texts | the `kl` pack's backend half (GL is its home), which the pom copies into the jar with the pack's flag; GL's five own mail keys are in `defaults/country/texts/kl/`, `pdf.attachment.title` (read by nothing) is dropped, and the root files are deleted |
| `niord.json` | 1 shadow | settings | `defaults/country/settings.json`, with its keys plus `organisation` (its `country` is `GL`; both `required`, §5.4). `wmsLogin`/`wmsServiceName` are dead and go, and its `wmsPassword` placeholder is emptied. `organisation` is `DMA`, the value GL's deployments run with. Its map centre is 72/-40 at zoom 4, as GL's public-site configuration has it (the earlier file held Denmark's 56/11 at zoom 6). Existing deployments keep their rows |
| `application.yml`, `application-local.yml` | 2 own | configuration | stay |
| `META-INF/resources/img/company-logo.png` | 1 shadow | image | guarded override |
| `img/company-logo-da.png`, `img/firing_areas.png`, `img/flags/{da,kl}.png` | 4 own | images | stay; the flags come from the packs: `img/flags/kl.png` from the `kl` pack, `img/flags/da.png` from the pinned `da` pack |

Before the move the override check found 108 `template-in-jar` findings in GL's jar, every `templates/**` file
of the table (58 + 10 shadows, 32 + 8
own), and one `unlisted-shadow`,
`META-INF/resources/img/company-logo.png`, which is listed in `be/overrides.json` since. The check exits 0.

In all, GL dropped 22 files (19 equal, 3 lagging) and moved 86 to `bootstrap/` (46
shadows, 40 own), unchanged but for `fetch-firing-exercises.js`'s line 19. As DK's, the 46 shadows apply on a
fresh database (`GlBootstrapITTest` asserts 46 of 46).

**GL has no database pipeline.** GL's data templates are files in `bootstrap/` maintained by hand, and a
translation aid works on those files only: no generator writes templates from a database snapshot, and no
script writes rows back with SQL or upserts settings. A template reaches a GL deployment by the release
folder's import, `lang.kl` by the layered texts, and the `kl` data rows by GL's bootstrap: `aton-dict.json` (111
`kl` descriptions of base `aton` keys, applied within the run, §7.2, and the four DGPS stations) and
`parameter-types.json` (37 `kl` template-parameter names). GL's bootstrap also carries the `kl` and `da` names of
the base product's first 13 categories and their 11 template parameters as additions; the other 32
categories are the base product's, in its archive in English (the owner, 2026-11-04), and their `da` and
`kl` names come with country changes on the owner's accept. The
`kl` of areas, sources and publication categories is place and publication data of a deployment, not bootstrap
data.

Tests:
- `NiordGlSettingsResourceTest`: against the settings file.
- `GlTemplateTest`: a parse test over the `kl` variants; its image test reads
  `be/overrides.json`; its dictionary test reads `lang.kl` (`da`, `en`, `kl`) through the layered
  defaults as start-up computes them (`DefaultTexts.read` over the class path, the `kl` pack and `texts/da/`
  among them): `lang.kl` is a text that start-up adds, not bootstrap data.
- `GlBootstrapFolderTest`, `GlReleaseFilesCurrentTest`, `GlBootstrapITTest` (with the
  restated-entries equality test) and `GlFiringExercisesScriptITTest`.
- `GlKalaallisutTextsTest`: the `kl` pack in the jar once and byte-equal to `fe/packs/kl/backend/`, no
  root-level `*_kl`, GL's own keys from `texts/kl/`, the suffix passing the markup row, the jar's `kl` flag the
  pack's, and GL's Danish wording from `texts/da/` over the pack's.

---

## 10. What is deleted

These do not exist, and none comes back in another form (R-CDC4, S2, D1).

**Backend:**
- "Reload from system", for dictionaries (`PUT /rest/dictionaries/reload-resource-bundles`,
  `DictionaryService.updateDictFromResourceBundle`, the `override` parameter of
  `loadDefaultResourceBundles`) and for script resources (`POST /rest/script-resources/reload`,
  `ScriptResourceService.reloadScriptResourcesFromClassPath`).
- Copy-on-use: the `path` parameter of `GET /rest/script-resources/all` and
  `ScriptResourceService.findOrLoadFromClassPath`; `FmTemplateLoader.checkLoadTemplateFromClassPath` (the
  class-path read and its persistence),
  `ScriptResourceService.readScriptResourceFromClassPath` and `JsResourceService.checkLoadScriptFromClassPath`;
  any template or script in a jar (§6.3).
- Insert-on-read: `SettingsService.get`'s insert and its `"/"` sink;
  `MapTileSettings.storeTileCacheSetting`.
- The start-up writers (S2): `FmReportService.init` and the persistence in
  `getStandardReport`/`getDraftReport`/`getPublicReport`; `TemplateInitService`; `BootstrapService`.
- The `-Dniord.batch-set` start path (`BatchSetService.init`) and the unattended folders (D1;
  the `batch-sets` and `batch-jobs/*/in` watchers). The owner checked on 2026-10-21 that nothing outside Niord
  uses the `batch-sets` or `batch-jobs/*/in` folders.
- `DictionaryService.importDictionary(Dictionary)`, the one-argument import that overwrote every key it
  carried: the import is `importDictionary(Dictionary, ImportChoice)`.
- The Danish defaults of S3.
- The transitional root-bundle text layer (§4.1; removed in 4.0.114). The root `niord.json` read (§5.2) goes
  once niord-base's test tree (`be/niord/src/test/resources/niord.json`) moves to `defaults/`.
- The development seed as a way into a deployment: `be/niord/seed/`, `niord-app`'s `demo` profile
  (`application-demo.yml`, `changelog-local-demo.yaml`, `-Pdemo`), `start-local.sh --demo` and the Helm
  `postgres.seed.enabled` switch (§7.4).

**Not built** (they belonged to the rejected design): a read-only resolver chain for texts or data
templates, synthesised dictionaries, `GET /dictionary/{name}/overrides`, a settings registry replacing the
database's metadata, a configuration transition layer, a transition or comparison tool, a `basedOnDefault`
column, an "Override" action, `?scope=` exports; and, from the draft of 2026-10-20 (R-CDC9), a jar fallback for
templates, a `CodeTemplates` list, a "database copy of a code template" marker, refusals at code-template
paths or built-in report names, in-memory built-in reports.

**Frontend (`fe/ui-apps/admin`):** the Reload menu items, handlers, API methods and texts of the Dictionaries
and Script resources pages. The Script resources page's deep link and the reusable field's pencil are plain
lookups (§6.3). The two pages have their own import dialogs, with the preview (§4.6, §6.4), and the
script-resource history dialog has a Source column.

**The tests that hold it:**
- the **no-write IT**: after start-up, an anonymous PDF, a mail, an ADMIN `GET /all` and a settings read leave
  the row counts of `ScriptResource*`, `Setting`, `FmReport`, `ParamType`, `Domain` and `Dictionary*`
  unchanged; on an empty database `/web` returns `mode` and `osmUrl` (`SettingsNoWriteITTest`: its case
  `noReadWritesATemplateASettingAReportAParameterTypeADomainOrAText` checks nine table counts and an md5 over
  every `ScriptResource` across an anonymous message PDF, a template-rendered mail, an ADMIN `GET /all`, a
  settings read and `/web`, the missing-path report and both import previews; `SettingsFreshDatabaseITTest`'s
  `/web` case on an empty database also asserts that no row is written);
- the ledger (`DefaultsLedgerITTest`, `DictionaryServiceITTest`): deleted stays deleted, a cleared translation,
  adoption with and without a hash, a new
  language, and a key deleted before a new language is added (it stays deleted in that language too);
- two add-missing runs started together on one database, the second blocked on the advisory lock until the
  first commits (`DictionaryDefaultsConcurrencyITTest`, `SettingsAddMissingITTest`);
- the layering and the duplicate-defaults guard (`DictionaryDefaultsLayeringITTest`, `DefaultTextsTest`,
  `DefaultsGuardTest`);
- `CoreResourcePackagingTest`: no template, `*.ftl`, `bootstrap/`, `releases/`, `texts.json` or seed in the jar;
- `LiquibaseBootstrapITTest`: start-up writes no report, domain or standard parameter type, and the marker
  reads `pending`.

Open entries of `be/docs/potential-errors.md` that belong to these rules: CDI1 (`CdiUtils`' static
last-started context), SETTINGS-5 (`mailSender`'s code default) and SCRIPT8 (a deployment's stored script
row that predates the port, which only a sysadmin's release import replaces).

---

## 12. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| A Java change that a template depends on | a deployment's template breaks, or a base product fix to a template never runs there (`MessageMailService.java:124`, F-X146) | the compatibility rule: stay compatible, or ship the template in the release import file named in the release notes (§6.2); fixes that matter for security go into Java |
| A template path never rendered on a deployment while templates were copied on use (a code-named one included) | that template or language variant stops rendering, or falls back to English | the missing-path report (`MissingPathReport`: logged at start, `GET /rest/script-resources/missing-paths`, and the pre-upgrade SQL `missing-paths.sql` in the release folder); the first release's import file, whose import is the operator's decision (§6.6) |
| An operator deletes a key a template uses | every render using it fails, and the key no longer returns at restart | the delete confirmation names its users; the dictionary import file restores it (§4.6) |
| Adoption misses keys deleted since the last start | such a key is added once more | the same as every start before the ledger; after adoption the ledger holds |
| A bootstrap runs twice or halfway | edits overwritten, or a half-installed system | the claim's two guards, checked in one locked transaction: the marker reads `pending` and the database holds no data (no row outside the not-data list, `BatchData` counting), so `existing` and `applied` are refused, and so is a database any import ran on; `failed` and `in-progress` are final, whatever the number of archives, so a bootstrap is never run again on the same database nor taken over, and a bootstrap job's restart is refused; the remedy is a database recreated empty (D4 as amended by the owner on 2026-10-24, §7.2); a runner that starts each job only after the previous one has ended and, past a job's 10 minutes, requests a stop and waits for the execution to end before the run is `failed`; negative tests (§7.2) |
| A seeded or populated database read as fresh | a bootstrap over real data | `existing` written on the first start when any domain, message or area exists; the claim checks again that the database holds no data, naming the first table that holds a row; tested (`BootstrapSeededITTest`'s seeded database forced to `pending` is refused) |
| The not-data list misses a table a first start or a scheduled job fills | a fresh database refused | the safe direction: the refusal names the table; `LiquibaseBootstrapITTest` pins the list after a first start and one authenticated request; the queued mails a scheduled alert writes (`ScheduledMail`, `ScheduledMailRecipient`) are on the list; a refusal naming another table no bootstrap wrote on a fresh cluster database means a configuration fault, fixed before the database is recreated (`be/k8s/README.md` step 4). Writes during the 5–20 s of a run, after the claim, are not stopped (§7.2, *Not added*) |
| A country settings file lags the base product | a key new in the base product's settings file would take the base value there | the build fails until the country sets it or lists it (R-CDC7); the upgrade report lists new keys |
| The IT fixture is regenerated | ITs that pin ids and contents break | the suite is measured on the new fixture first, and a pinned IT moves in the same change, with its cause in the test, none weakened (a count derived, an id moved, a row from a named fixture or planted and removed by the test); `ReportFixtures` for the rows only DK's bootstrap has; `TestFixtureCurrentTest` against a fixture stale after a template change; the sample's guard against a stale sample during the regeneration; a product defect the new data exposes is recorded, not patched over; the regeneration rewrites the sample's message uid prefix to the fixture's own and ends with the no-sample check over its dumps, and `TestFixtureCurrentTest.theFixtureCarriesNoSampleMarker` fails on a fixture that carries the sample's sentinel or prefix (§7.4). DK's and GL's ITs move at their pin bump to the version that carries the new fixture |
| A later archive of a bootstrap run restates a base entry with a different value | the country expects its value | within the first bootstrap a later archive's values replace what an earlier archive of the run created, and a value it leaves out keeps the base's (the owner's ruling of 2026-10-24; §7.2, *Within one run*); the job log names each replaced value (`replaced <field> <old> → <new>`); a bootstrap cannot clear a base text (an empty string or object counts as left out), while a list it gives explicitly, even empty, empties the base's (the owner's decision of 2026-10-25, §7.2, *Within one run*); a kind change of a parameter type fails the job; a restated domain is refused before the claim (unless the later archive gives only its `domainId` and a boolean `active`, whose flag then wins) |
| A country still has a root-level text bundle or a root `niord.json` | its root-level texts are not read | the text check's `root-bundle` rule fails a country build that still has a root-level bundle (§4.1; the transitional text layer was removed in 4.0.114, after both countries had moved theirs); the root `niord.json` read stays until niord-base's test tree moves to `defaults/` (§5.2) |
| Script resources run with full privileges | an import is code execution | SYSADMIN only; history with the user and the source (`import`, `editor`); `ScriptResourceCheck` on every imported file and in the bootstrap (a guard against carelessness, not a boundary: it refuses the listed tokens and packages, and the evasions its test asserts pass it); the preview, which adds only the ticked paths and overwrites only the ticked ones, and keeps a ticked row changed since the preview (`basedOn`); the FreeMarker resolver; the batch-set allow-list, which refuses the imports and `script-executor`; D1, no folder watcher |
| A country's settings file (or transitional root `niord.json`) without a `required` key, on a database without the row | `country` or `organisation` takes the base product's neutral value (`XX`, `Niord`) into series numbers, templates and S-124 | a database row wins; start-up logs a warning per missing key (§5.2); the country build's `required` check refuses the file (§5.5), run by the country build group's `CountryDefaultsChecksTest` once the country has `defaults/country/`, as DK and GL have; over the transitional root `niord.json` it does not run |
| GL's S-124 identity follows its `country` setting (S3) | GL's datasets carry nationality `GL`, ids `GL.` and MRN `:gl:` instead of `DK`, `DK.` and `:dk:`; a dataset made before the rule keeps its `:dk:` `mrn` and `uuid` while every new version's GML carries `:gl:`, so the database key and the subscribers' `dataReference` diverge from the GML's identifier, and no code path repairs it | intended by S3; before a GL environment that may hold such datasets takes a version with the rule, the deploy step counts `"S124Dataset"` there and, if it is not 0, either cancels and reissues the affected warnings or rewrites `mrn` and `uuid` (and the pinned `SecomSubscription.dataReference`) by hand SQL in one transaction (the owner's choice); GL's producer code is `DK01`, as DK's, and its producing agency the Danish Emergency Management Agency (owner, 2026-10-22) |
| The sample goes stale when the base bootstrap, the entities or the schema change | the sample's ids or foreign keys no longer fit the bootstrapped base, and a load would insert wrong or colliding rows | the SQL's guard refuses before it inserts a row unless the marker reads `applied`, the Liquibase changesets are the recorded ones, no target table holds a row or has its sequence where the sample's go (at or above 1,000,001), the sample's domains and series are not there, and every base row it refers to resolves by its natural key to exactly one row. The refusals split their remedy: the changeset refusal names `be/niord/scripts/regen-sample.sh`, which rebuilds the sample on the current schema; the offset, domain and series, and natural-key refusals name `./start-local.sh --sample --reset` (a database that is not one clean run of the base bootstrap), and a regeneration only if the base bootstrap, schema or entities changed. The changeset half is checked in CI: `SampleFolderTest` compares the guard's changesets with those `db.changelog-master.yaml` includes, so a new changeset is red until the sample is regenerated, and it fails on a sample id below 1,000,001 or a base row named other than by natural key. A drift of the base bootstrap that the natural keys no longer resolve needs a database and shows only at load time: in `SampleOnACountryBaseITTest`, in the regeneration's schema check and in the IT fixture's regeneration (§8) |
| Several replicas | two starts adding the same key | the advisory lock (§4.2); the chart runs one replica |
| A dead editable `Setting` row, a key no code declares (DK's and GL's eight, `wmsLogin` and `faMrnPrefix` among them) | it cannot be saved on the Settings page: the write answers 400 | hidden: the page and its Export do not list it, and the row stays in the database (owner, 2026-10-21); nothing reads it; the refusal names the key (§5.3, §5.6) |
| A rollback to a version without the ledger | deleted keys come back and reads insert again | accepted. The ledger survives, and on the next upgrade those keys are adopted |

---

## 13. Decisions D1–D5 (owner, 2026-10-20)

| | Question | Decision |
|---|---|---|
| **D1** | Unattended import folders: the `batch-sets` folder, the per-job `batch-jobs/*/in` folders, `-Dniord.batch-set` | **All deleted.** Every import goes through the admin app by a sysadmin, recorded. The UI's imports do not depend on the folders (§6.4). The owner checked on 2026-10-21 that nothing outside Niord uses those folders (§10). |
| **D2** | What a Liquibase changeset may do to data | **Schema only, plus reshaping existing values a schema change requires** (split, convert, move), never adding, deleting or changing content (§7.4). The publications go-live import, the bootstrap and backup/restore are unaffected. |
| **D3** | Languages and tile/WMS addresses: configuration or operator settings | **Both operator settings, as today**: editable on the Settings page, with defaults in the settings file (§5.4). Consequence: the administration app's Content-Security-Policy (country-split plan §4.11 rule 5) is built at runtime from the current settings, so its `img-src` follows the operator's tile addresses (the WMS layer is the same-origin `/wms/` proxy, so the WMS address is not in it). |
| **D4** | How a fresh deployment is detected and the bootstrap applied exactly once | **The marker set at the first start** (§7.2): `pending` only when no domain, message or area exists; a database created by an earlier version counts as existing. **Amended by the owner on 2026-10-24**, in his words: "a first bootstrap needs to be seen as a single united first time deploy following the normal rule of country overwrite - so whatever the country has in settings, configurations, texts, bootstrap data, etc takes preceedence … BUT (and thats a big but) it must NEVER be possible to bootstrap a deployed database/system. There must be safeguards against this if not already", and "bootstrapping is a very rare occation that only happens one time for a new environment". He agreed to three points ("yes agree on all three"): (1) on the first bootstrap the country wins everywhere; (2) within the first bootstrap, a country's value replaces the base product's, as a template does; (3) the safeguard is strict: the claim refuses unless the database is empty, checked again at the claim, and a failed bootstrap is fixed by recreating the database, never by retrying on it. So the runner accepts only `pending` over a database that holds no data, checked with the marker in the claim's locked transaction; `failed` and `in-progress` are final, with no retry and no takeover, and a bootstrap job is never restarted; the remedy is a database recreated empty and the same command with the same archives (§7.2, *No retry*). Only how a bootstrap is deployed changes: start-up's add-missing of settings and texts, release folders and sysadmin imports with their preview and ticks, and no reload and no reset stay exactly as they are. |
| **D5** | Where LIST parameter values live | **In `template`, as today.** The editor keeps steering list values there. They are data: start-up only adds base product text keys that are missing, never changes or deletes a value, so an operator's list values are never touched. A base product text key and a list value should not share a key; the text checks (§4.7) warn when a new base product key in `template` collides with a key a deployment's list parameters already use (listed in the upgrade report). |

---

## The step names the code and the documents use

Comments, tests and documents name the steps by which the rule was built ("since C4.1", "from C4b"). The record
of those steps is not in this document. This table says what each name stands for, and where its rules are now.

| Name | When | What it stands for | Its rules are in |
|---|---|---|---|
| C0, C1 | 2026-10-21, 4.0.74 | both Reload functions, the `?path=` preload and the Helm seed default deleted; the FreeMarker class resolver | §6.5, §10 |
| C2 (C2a, C2b) | 2026-10-21 and 22, by 4.0.76 | texts: `defaults/`, the layered add-missing, the ledger and the guard (C2a); `asAdded`, the delete confirmation that names a key's users, the text checks (C2b) | §3.3, §4 |
| C3 (C3a, C3b) | 2026-10-21 and 22, by 4.0.76 | settings: the catalog, the settings files and their checks (C3a); the loader on those files, add-missing with the ledger, no insert on a read (C3b) | §5 |
| C4 (C4.1, C4.2a, C4.2b) | 2026-10-21 and 22, by 4.0.76 | templates and scripts as data: the bootstrap with its marker, runner and command, the start-up writers gone (C4.1); templates from the database only, the first release folder, the missing-path report, `niord-bootstrap` published (C4.2a); the imports' preview, choice, check and history, the folder watchers gone (C4.2b) | §6, §7 |
| C5 | 2026-10-22, by 4.0.76 | Danish data out of core code: `country` and `organisation` required, S-124's identity taken from them, and the scan of niord-base that the backend's word guard (`CountryWordsTest`) holds since | §2 (S3), §5.4 |
| C5b | 2026-10-22, by 4.0.76 | the country contract: the country build group, the start-up guard, the override check, `be/docs/country-api.md` | `docs/fe-country-split-plan.md` §4.9 and §4.11 rule 3 |
| C6 | 2026-10-22; its base product half by 4.0.87 | the country backends moved onto the rules: their bootstraps and their `defaults/country/`. The base product half: within one bootstrap run a later archive adds to what an earlier one created, and the base bootstrap is English only | §7.2, §9 |
| C7 | 2026-10-23, 4.0.89 | the sample (the country-split plan's P9) | §8 |
| C4b | 2026-10-24, 4.0.97 | the integration suite's fixture made from the bootstrap and the sample; the seed folder deleted | §7.4 |
| P7-bs | 2026-10-25, 4.0.97 | the owner's bootstrap ruling of 2026-10-24, built in the country split's P7: one first-time deployment, never on a database that holds data, no retry | §7.2, §13 (D4) |
| R-CDC1 to R-CDC9, S2, S3 | 2026-10-19 and 20 | the owner's rulings | §2 |
| D1 to D5 | 2026-10-20 | the owner's decisions | §13 |
