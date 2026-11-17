# Code, data and configuration: the repository only adds

**Status:** adopted by the owner on 2026-10-21. C0 and C1 are built (PR #160,
2026-10-21, version 4.0.74; C1 met locally, its read of the GitLab values files: staging at the end, §11).
C2 is built on `feature/code-data-config`: C2a (`defaults/`, the layered add-missing, the ledger, the guard) and
C2b (`asAdded` on `/entries` and in the administration app's merge, the Dictionaries delete confirmation naming a
key's users, the text checks). C2 is verified locally: on an empty database, and on 2026-10-21 with DK's and GL's
jars built against this branch's niord-base and started on scratch copies of their local databases (§11 C2);
staging at the end. C3a is built on the same branch: the settings catalog generated from the code and its
current-check, the declarations, `defaults/base/settings.json`, the settings checks and the guard's settings
names (§11 C3a). C3b is built on the same branch: the loader reads `defaults/base/settings.json` (niord-base's
root `niord.json` deleted), the transitional read of a country's root `niord.json`, the files and catalogs
loaded early, add-missing with the ledger, no insert on read, the internal writer of the `state` keys, and the
public `set` and the settings import refusing what they must (§5.2, §5.3, §11 C3b). C4 is built in three
sub-PRs on the same branch: C4.1, then C4.2 as two, C4.2a and C4.2b. C4.1 is built: the 80 templates and
scripts moved to `be/niord/bootstrap/templates/` (kept in the jar by a temporary resource mapping until C4.2a,
§6.3), the generic bootstrap, the marker, the runner, the command and `bootstrap.sh`, the start-up writers gone
(S2), the seed in test resources, the seed regeneration switched to the runner, and `niord-app`'s demo profile
and the Helm switch deleted. C4.1 is verified locally (§11 C4); staging at the end. C4.2a is built: the loader
on the database only, copy-on-use and the temporary mapping deleted, `RequiredTemplates` and its tests, the
release folder `releases/2026-10-21-templates-from-database/` with all 80 files (the five placeholders made
non-blank) and the texts file, the bootstrap's `texts.json`, the missing-path report and its SQL,
`org.niord:niord-bootstrap:<v>:zip` built and added to the deploy, and the Settings page hiding the rows no
code declares (owner, 2026-10-21, §5.6). C4.2a is verified locally (§11 C4); staging at the end. C4.2b is
built: the Script resources and dictionary imports preview first (`import-preview`), add the ticked missing
paths or keys and overwrite only the ticked ones, and name each refused file or entry; every import write
records the user and `source: import` (`ScriptResourceHistory.source`, V008); the script check
(`ScriptResourceCheck`) on every imported file and in the bootstrap; the batch-set allow-list
(`BatchSetService.BATCH_SET_JOBS`); both folder watchers deleted (D1); the no-write IT in full; and the
administration app's two import dialogs and the history's Source column. C4.2b is verified locally on
2026-10-22 (§11 C4: 70 unit tests and 186 ITs green, each new check shown red once, the admin specs green,
and the first release's import file applied to scratch copies of DK's and GL's databases until their
missing-path report was empty, no existing row changed) and the full suite (`./verify-local`) passed;
staging at the end. With it C4 is built. C5 is built on the same branch: `country` and `organisation` without
code defaults, marked `required`, with the neutral values `XX` and `Niord` in `defaults/base/settings.json`
(26 keys) and a start-up warning when a country's file lacks one; S-124's agency from `msg.responsible.agency`,
else `organisation`, and its nationality, ids and MRN from `country` (`S124Service.country()`, `S124Mapper`,
`S124Mrn`); the `.dk` contact guess removed; `pdf.nm_almanac.desc` neutral; and the scan's allow-list for P7's
guard, which niord-base's `CountryWordsTest` holds since P7-be (#206; §11 C5). C5 is verified locally on 2026-10-22 (§11 C5: 176 unit tests and 233 ITs, the one known
Windows failure only, the `required` rule shown red once, `niord-app` booted on an empty database, and DK's
and GL's jars started on scratch copies of their databases with no row changed beyond the add-missing already
recorded; after the review the S-124 checks of a two-letter `country` and a non-blank `organisation`, 51 unit
tests and 36 ITs green) and the full suite (`./verify-local`) passed; staging at the end, where GL's pre-C5 S-124 datasets, if any, need the action §11 C5
gives. C5b is built on the same branch: the country build group (`@CountryBuildGroup`, 13 test classes of
niord-base's test-jar) that `niord-parent` runs in every country build, with the country's settings and text
checks among them; the start-up guard (`CountryContractGuard`, run by `CountryContractInitializer` from
niord-base's `spring.factories`) refusing a protected bean or packaged property and a handler without a JSR-250
annotation; the override check (`check-overrides.sh`, `CheckOverrides.java`) in `niord-image-tools`;
`be/docs/country-api.md`; `retract-version.yml` deleting `niord-bootstrap` too; and the comments that named the
DK deployable reworded. C5b is verified locally on 2026-10-22 (§11 C5b: 169 unit tests in two runs and 37
ITs green, each new rule shown red once, a scratch country module refused to start on each of four protected
additions and failing the check on an unlisted shadow, `niord-app` started unchanged, and DK's and GL's `main`
built and started on scratch copies of their databases with no refusal and no row changed beyond the
add-missing already recorded; GL's build ran no test of the group, for want of the test-jar, a C6 item; after
the review, the five contract test classes, 56 unit tests, green); the full suite (`./verify-local`) passed; staging and the publish at the end. The two reviews of
PR #179 (`feature/code-data-config` into `main`, 2026-10-22) were answered on the same branch: the override
check permits only static images, the bootstrap waits for a job past its deadline and takes over a run with no
sign of life for 5 minutes (the takeover removed by the owner's ruling of 2026-10-24, §7.2), both import dialogs bind every answer to its file and show a long value an
overwrite clears, and the other items as §4, §6, §7 and §11 C4.2b, C5, C5b and C6 now say (the targeted runs
and the manual check of both dialogs in §11 C4.2b and C5b; the Review record). C6's backend parts are built
for DK (`SIFA-DK/niord-dk` #2, `b5d8b31`) and GL (`SIFA-DK/niord-gl` #2, `5b8f09b`) on 4.0.76, 2026-10-22:
their templates moved to their bootstraps, their `defaults/country/`, the override check and the group count in
their CI (§11 C6, the State lines). C6's base product half is built on `feature/c6-base`: within one bootstrap
run a later archive replaces a template and adds languages and domain links to what an earlier archive of the
run created (§7.2, owner 2026-10-22; since the owner's ruling of 2026-10-24 its values replace too, P7-bs
below), and the base bootstrap is English only (170 Danish descriptions removed).
It is verified locally on 2026-10-22 (§11 C6: 94 unit tests and 141 ITs green, each new rule shown red once,
and `niord-app` bootstrapped on empty scratch databases with the base archive and then DK's or GL's merged
bootstrap: `missing-paths` `[]`, DK's 42 and GL's 46 shadows holding the country's content, GL's 111 `kl`
`aton` descriptions applied); staging at the end. C7 is built with the country-split plan's P9 base product
part, #192, merged into `main` 2026-10-23 (`195d102d`, 4.0.89): the sample in `be/niord/sample/`,
`./start-local.sh --sample` in place of `--demo`, its regeneration and the no-sample check in CI (§8, §11 C7,
verified locally; the reviews of #192 answered on 2026-10-23, §11 C7). C4b is built in PR #196 (`feature/c4b-it-fixture` into `main`,
2026-10-23, built on its sub-branch `c4b/it-fixture`): the integration suite's fixture regenerated from the base product's
bootstrap through the runner with the P9 sample loaded after it, `ITTest`'s two completions removed, the 35 IT
classes whose data moved updated, and `be/niord/seed/` deleted (§7.4, §11 C4b). Before #192 it was verified locally: the
whole backend suite green but for the known Windows failures; it exposed a product defect, S124-1, which it
fixes (§11 C4b). After #192 merged, the fixture was regenerated on #192's changed sample, its message uid prefix
rewritten to the fixture's own so that the no-sample check passes over the test-jar (§7.4; §11 C4b, _After
#192_). The whole IT suite on that fixture had one failure besides the known Windows ones, an order dependency
between SECOM test contexts (`SecomSubscriptionITTest`'s cap test), not the data; it is fixed in the tests, and
the whole suite run again after the fix has only the known Windows ones (§11 C4b, _After #192_).
`./verify-local` passed on `f15c4de2` in 31m47s (the backend's 5168 unit tests and 3361 ITs with only the 10
known Windows-only failures, the administration app, the public site and the example country), and CI on #196
is green on `9bb87e5d` (all 12 checks); its two reviews were answered on 2026-10-24 on `c4b/review-196-fixes`
(§11 C4b, _The review of #196_); staging has nothing of it. The owner's bootstrap ruling of 2026-10-24 (D4 as
amended) is built in P7-bs (`p7/be-first-bootstrap`, a sub-PR of the country-split plan's P7 into
`feature/p7-base-lets-go`, stacked on P7-be): within the first bootstrap a later archive's values replace what an
earlier archive of the run created; the claim refuses a database that holds data; a failed or broken-off bootstrap
is never run again on the same database, nor taken over, and a bootstrap job is never restarted (§7.2). It is met
locally (§11 C6, _The owner's bootstrap ruling of 2026-10-24_: the targeted unit tests and ITs green, the new or
flipped claims shown red on the old code or compile-red by construction, the sample and the IT fixture regenerated byte for byte);
`./verify-local --base origin/feature/p7-base-lets-go` passed in 10m24s on `49aaa74e`, and CI on #204 is green on
`0409350e` (all 12 checks); staging at the end. The review of #204 was answered on `p7/review-204-fixes`, with
the owner's empty-list rule of 2026-10-25 (§7.2, *Within one run*; §11 C6, _The review of #204_): `./verify-local`
passed on `a8db0b32` in 25m30s and CI on #204 is green there (12 of 12). #204 was merged as the squash
`c703d061` and published as 4.0.97 (2026-10-25). DK's and GL's bumps onto it carry C4b's fixture and P7-bs's
rule: `niord-dk` #10 (`9f09d195`) and `niord-gl` #9 (`555b7c73`), both 2026-10-25 (§11 C6's State lines). The
transitional root-bundle text layer (§4.1) is removed in the country-split plan's NG-B (NG-B1, #252, into
`feature/ng-b`; its PR into `main`, #253, merged on 2026-11-02 as `2685be0e` and published as 4.0.114), after
GL's bump onto 4.0.111 (`niord-gl` #10, 2026-11-02) deleted the last root files; the root `niord.json` read
(§5.2) stays. Since the country-split plan's N2 (N2-2, #263, and N2-3, #264, into `feature/n2-plug-and-play`;
its PR into `main` #259), a new country's `be/src/main/resources/defaults/country/settings.json` is written by
the kit's `init`: a complete file generated from the kit package's copies of the base product's settings file
and its catalog (`backend/defaults/base/settings.json` and `settings-catalog.json`, N2-1, #258), the 11 keys the
answers set in `values` (`country`, `organisation`, `niord.home` as `${NIORD_HOME}`, `defaultLanguage`,
`modelLanguages`, `siteLanguages`, `infoSiteLanguages`, `infoDefaultLanguage` and the three `mapDefault*` keys)
and the other 15 of the base file's 26 in `useBaseDefault`, each with a reason; a base key the kit's table does
not know gets the reason `the base product's default at <version>`, and a required catalog key no answer sets
makes `init` refuse (and the kit's scaffold test fail first in niord-ng). The file passes the strict rule from
the first build (§5.5), which the example harness's generated country shows with `CountryDefaultsChecksTest` in
its Maven build (N2-3); DK's and GL's files keep every key in `values`. The rulings in §2 were taken by the owner on
2026-10-19 and 2026-10-20, after an inventory of every path by which repository content reaches a
deployment's database (five read-only inventories of the tree and the two country repositories, verified
traces of how bundles, templates, scripts and `niord.json` load today, and the security review of the
country-split plan). The plan was then checked by a fact check and a design critique and revised (Review
record, at the end). An earlier draft on the same inventory proposed read-only defaults with database
overrides and a one-time transition of existing deployments; the owner rejected that design (R-CDC1).
**Revised 2026-10-20 to the one-rule model (R-CDC9):** every template, script and report is data, the 11
templates the code names included; the code never reads one from the jar.
File and line references are against the branch `docs/fe-country-split-plan` @ `e6a8a733` and
`SIFA-DK/niord-dk`, `SIFA-DK/niord-gl` `main` on 2026-10-20; in the parts C4.1 changed, against the tree after
C4.1 (§11 C4 names its files and classes, all in the tree). **[I]** marks
an inference not confirmed by
code or a run.
**Scope:** every path from a repository into a running deployment's database, and what replaces it: the
backend's texts, settings, templates and scripts, start-up writers, the seed, the bootstrap of a fresh
deployment and the sample. It owns the backend half of the country split (`docs/fe-country-split-plan.md`
§4.5 packs' backend half, §4.9–§4.11, the backend parts of P5 and P6, P9), which points here.
**Repos:** `niord-ng` (niord-base, niord-app, the admin app's Dictionaries, Script resources and Settings
pages), `SIFA-DK/niord-dk`, `SIFA-DK/niord-gl`.
**Not in scope:** the frontend packs, seams and builds (the country-split plan); production, which is touched
only at the owner's request. This plan prepares nothing there.

---

## 1. Summary

**Terms.** *Base product*: the country-unaware Niord in `niord-ng` that every country builds on. *Country*: a
country repository (`niord-dk`, `niord-gl`, …) and the deployments built from it.

**The rule.** A repository change never changes or deletes anything a running deployment has in its
database. It may only add what a deployment has never had, and only inert things: a text key or a setting
key. Everything a deployment runs that an operator can edit is data. Data arrives only by a deliberate act:
the bootstrap of a fresh deployment, or a sysadmin's import. Existing deployments keep their data as it is.

**One rule decides what is data** (R-CDC9): if an operator can see and edit it in admin or sysadmin, it is
data; if not, it is code. Texts and setting keys are the one deliberate exception: they are inert **keys** the
code refers to, so a missing key is added. Templates, scripts and reports are **content**, and content never
arrives without an operator.

| | Today | After |
|---|---|---|
| Backend texts (`*_<lang>.properties`) | merged into the database at every start; a deleted key comes back; a sysadmin Reload writes every file value over the database; a country file hides the base product file whole | base product English, the language pack and the country's texts, merged **per key**; missing keys added at start and never changed; a deleted key stays deleted; no Reload |
| Settings (`niord.json`, `@Setting`) | missing keys inserted at start and on every read; a country file replaces the base product file | the same "add when missing", only at start; a settings catalog generated from the code; the country file still replaces the base product's, now checked; the Settings page shows the same settings as today |
| All 80 templates and scripts (11 of them named by the code) | the 11 copied into the database on first render, the rest on first use, by an anonymous PDF view or an admin GET; a sysadmin Reload overwrites edits | data: in the database only by bootstrap or import; never read from the jar; the code refers to them by path |
| A fresh deployment | the Danish dev seed when a Helm switch (default `true`) says so; otherwise copies on use | a bootstrap archive outside the jar, applied once through the existing import jobs |
| Start-up | re-creates deleted built-in reports, parameter types and the master domain | the built-in reports and the master domain come with the bootstrap; the standard parameter types are held in memory (code) |

**What to implement first (the owner's question, 2026-10-20).** This plan's base product part comes first.
1. **C1 now**, as one small PR into `main` before the country-split plan's P1. It deletes both Reload
   functions (one sysadmin click can overwrite production data today), the `?path=` preload, and the Helm
   seed default, and adds the FreeMarker hardening.
2. **C2–C5b alongside the country-split plan's P1–P4**, on their own feature branch, published before the
   *backend parts* of P5 and P6. P1–P4 wait for nothing here. They are frontend work except for P1's base product backend items (the
   administration app's Content-Security-Policy, the `sandbox`/`nosniff` headers for both applications' branding and legal files (the public site's in its nginx template),
   the publish-workflow hardening); the CSP's `img-src` follows the operator's tile settings, which
   are operator settings today and stay so (D3).
3. **P5 and P6 each split into a frontend part and a backend part.** The frontend part follows P4 as planned
   there. The backend part is this plan's C6 and starts once C2–C5b are published. **C7 goes inside P9.** A
   country's backend moves only once the base product has decided what that backend carries. Its frontend need
   not wait.

---

## 2. Decisions (owner)

| | Decision | Rejected, and why |
|---|---|---|
| **R-CDC1** (2026-10-19/20) | **Add-only.** A repository change never changes or deletes anything a running deployment has in its database. It may add only what a deployment has never had, and only inert things (text keys, setting keys). Existing deployments are not touched: no transition, no migration of their data, no comparison tool. Their data is correct as it is. | Read-only defaults with database overrides and a one-time transition (the draft of 2026-10-19), because a tool would decide, row by row in production, whether a value is an edit or a stale copy. That is a judgement on production data the owner does not want made. Reload-style resynchronisation in any form. |
| **R-CDC2** (2026-10-19/20) | **Six kinds** (§3): code, base product defaults (inert), configuration, data, bootstrap, sample. Templates, scripts and reports are data (R-CDC9). Base product defaults (backend texts and setting keys) are added when missing and never changed. They live in a clearly named place with a README saying a running deployment may differ. Settings stay editable on the Settings page as today (owner, 2026-10-20). A repository settings file holds no real secret. | Every template as code: operators edit message templates, mail bodies and report layouts every day. A split into code templates and data templates (the draft of 2026-10-20): one page with two rules (R-CDC9). |
| **R-CDC3** (2026-10-19/20) | **Executable data is never added by a release.** Templates and scripts reach a running deployment only by a sysadmin's import, recorded with the user. A release that offers a new or changed template ships it as an import file named in its release notes. A Java change that a template depends on stays compatible with the templates deployments hold, or ships the updated template that way (§6.2). New features put behaviour in Java or TypeScript, and add a template only when operators should edit it. The hardening applies whatever the source: the FreeMarker class resolver, limits on what scripts may call, and imports that are SYSADMIN-only with history. | Copy-on-use, or a start-up import of new data templates: executable content would enter production with no human act. The country-split plan's security review proposed updating rows equal to an earlier shipped revision at start-up: it changes production data. |
| **R-CDC4** (2026-10-19/20) | **Deletions.** Both "Reload from system" functions are deleted, frontend and backend, with their specs, i18n keys and docs. There is no "reset to default" anywhere. Copy-on-use is removed: `FmTemplateLoader`'s persistence, `JsResourceService`'s class-path load, `ScriptResourceService.findOrLoadFromClassPath` and the `?path=` preload of `GET /rest/script-resources/all`. An anonymous PDF view or an admin GET never writes a row. | Keeping Reload behind a stronger confirmation: one click would still overwrite every edit. A "reset to default" action: a reload by another name. |
| **R-CDC5** (2026-10-19/20) | **Deleted stays deleted.** The system remembers what it added from defaults (a ledger), so a text key or translation an operator deleted is never added again, in any language, and neither is a setting whose row was removed (the Settings page has no delete, §5.6; a row goes only by hand SQL: `V005`'s retirement of three settings is applied history, and D2 allows no new retirement changeset, §7.4). | Today's merge, which treats a deleted key as missing and adds it back at the next start. |
| **R-CDC6** (2026-10-19/20) | **Texts are layered key by key**: base product `en`, then the language pack, then the country's texts, merged per key, never as a whole-file shadow. The result's missing keys are added to the database and never changed. | Whole-file shadows. Today DK's and GL's `mail_en.properties` hide the base product key `mail.safetynet.cancel`. |
| **R-CDC7** (2026-10-20) | **Settings.** A country's settings file replaces the base product's whole file, because settings are almost never equal between base product and country. Keys are added when missing and never changed. A settings catalog generated from the code (key, type, default, scope, web, editable, introduced-in), in which every key of the base product's settings file has a code declaration, is published with each base product release. Each country generates its own, with its own keys in its own namespace (`dk.*`, `gl.*`). The country build fails on a key of the base product's settings file missing from its file (unless listed as "use the base product default" with a reason), on a key nobody declares, or on a wrong type. New base product keys appear in the upgrade report. Insert-on-read is replaced by the start-up add-missing step. | A merged, values-only country file (ruled 2026-10-19, reversed 2026-10-20). Insert-on-read (SETTINGS-1, SETTINGS-2, RENDER2). |
| **R-CDC8** (2026-10-19/20) | **Inert values are checked at change time**, in base product and country CI. Texts: the markup rule (country-split plan §4.6), length limits and control-character limits. Settings: type validation, a warning for a non-`https` URL or an unexpected host, and no real secrets (a password setting is empty in a repository file). The backend repeats the type validation on insert. | Runtime validation only: a bad value would first be found by a mariner. SQL-injection checks: values go through parameterised JPA, so injection is not the risk. |
| **R-CDC9** (2026-10-20) | **One rule: what an operator can see and edit in admin or sysadmin is data; anything else is code.** All 80 templates and scripts are data, including the 11 the code names; the code refers to them by path and never reads one from the jar. All reports are data, including the built-ins `standard`, `draft` and `public`; they come with the bootstrap. The standard parameter types stay code, held in memory: they are listed read-only, as today, and not editable. Texts and setting keys are the one deliberate exception: they are inert keys the code refers to, so a missing key is added (R-CDC1); templates and reports are content. | A split into 11 code templates run from the jar and 69 data templates, with built-in reports in memory (the draft of 2026-10-20): one Script resources page with two rules, a jar fallback, a database-copy marker and refusals to explain. |
| **S2** (2026-10-20) | **Start-up stops re-creating deleted rows.** The built-in reports `standard`/`draft`/`public` (`FmReportService.java:66-72`) come with the bootstrap (R-CDC9); the standard parameter types (`TemplateInitService.java:47-66`) are held in memory. The master domain (`BootstrapService.java:43-57`) moves to the bootstrap. Built in C4.1; the lines cited are the code at the ruling. | Writers that undo an operator's delete at every start. |
| **S3** (2026-10-20) | **Danish data leaves core code.** `NiordApp`'s `organisation` `DMA` and `country` `DK` defaults (`NiordApp.java:42-45, 58-61`), `S124Service.DEFAULT_RESPONSIBLE_AGENCY` (`S124Service.java:56`), `S124Mapper.COUNTRY = "DK"` (`S124Mapper.java:119`) and the `.dk` → `da` contact guess (`ContactRestService.java:162-164`) become country settings, country texts or nothing. Found after this ruling was written, and taken from the `country` setting in C5 by the same rule: `S124Mapper`'s other two Danish tokens, the `DK.` id prefix (`:447`) and the hard-coded `:dk:` in `toMrn` (`:713`), and `org.niord.secom.util.S124Mrn.COUNTRY = "dk"` (`S124Mrn.java:33`). The legacy publication import (`core/publication/series/legacy/`, DK's own rulings) is a listed exception until DK's cutover (§11 C5). | Country data in the country-neutral library. |

The five questions of §13 were decided on 2026-10-20. S1, the Helm seed switch, was folded into §7.4 and C1 on 2026-10-20; its id is not reused.

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

The S-124 opt-out (the owner, 2026-11-01) removed the chart's `s124.producerCode` default `DK00`, which the
chart rendered always and which overrode the image's own code: the chart now renders `niord.s124.enabled` and
`niord.s124.producer-code` only where its values set them (`be/k8s/README.md` § S-124 on or off). That is a
change of configuration behaviour on upgrade, not a data change under R-CDC1: no row is written or changed. A
Helm deployment that relied on the default runs on its image's code from then on (`DK01` for DK and GL), so
the GitLab values of every Helm environment are checked for `s124.producerCode` before the change merges.

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
from C3a, the four settings files: `defaults/base/settings.json` and `defaults/base/settings-catalog.json`,
`defaults/country/settings.json` and `defaults/country/settings-catalog.json`. Start-up fails:
- when a known name is found at more than one class-path location, also with equal content — the old
  whole-file shadow (until C2a DK's `mail_en.properties` hid the base product key `mail.safetynet.cancel`, TEXT1);
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

Until NG-B a third, transitional layer followed: a root-level `<bundle>_<lang>.properties` from the class path,
read from every jar that had it (`getResources`) and merged per key, its ledger source `root`. It let DK and GL
take a C2+ base product version before their own C6 without losing a text, and it ended TEXT1. From the countries'
frontend moves it served GL's five root-level `*_da` only, and GL's bump onto 4.0.111 (`niord-gl` #10, 2026-11-02)
deleted them, its pom copying the staged `da` pack's backend half and the pack's wording winning; NG-B then
removed the layer (#252). Two layers remain, and no other file is read: a country that still has a root-level
bundle in `en`, in a language with a folder under `defaults/country/texts/` or `defaults/packs/`, or in a model
language fails its build on the text check's `root-bundle` rule (`be/docs/country-api.md`).

A key missing in a language gets no row in that language. At runtime the existing fallback to the default
language applies. Start-up compares the layered result with the database. Nothing reads these files at
runtime.

### 4.2 Add missing, never change; the ledger

`DictionaryService.init` (`:62-74`) keeps its place and its shape. It computes the layered defaults and adds
the `(dictionary, key, language)` triples the database lacks (until C2a `mergeDictWithResourceBundle`,
`:360-397`, from one `getResourceAsStream`; now `DictionaryService.addMissingDefaults` over `DefaultTexts.read`). The changes from today:
- **The ledger.** A new table `"DefaultsLedger"`, created by schema changeset V006 with no data. Each row
  holds the kind (`TEXT`, `SETTING`), the dictionary, the key, the language, the sha-256 of the value added,
  its source (for a text `base`, `pack:<lang>`, `country`, `root` for the transitional text layer, which
  NG-B removed (§4.1; rows with it were written before NG-B and are kept, with no migration), `adopted`,
  `deleted`; for a setting, C3b, `base`, `country`, `root` for the root `niord.json` read until it goes (§5.2),
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
  (R-CDC5). Until C2a `deleteEntry` (`:189-209`) was undone at the next start (DICT2, deleted from
  potential-errors.md with C2a).
- **Adoption.** Every start records as `adopted` each default triple present in the database without a
  ledger row. On the first start with the ledger that is all of them; later it is only a default an operator
  already had (one a new base product version brings, which the operator added by hand or by import). Where
  the row's value equals the layered default's value at that start, the ledger also stores that value's hash
  (used by §4.5). This writes bookkeeping only and never a dictionary row. It decides nothing about the row.
  A key deleted before the first start with the ledger, and missing at it, is added once more, as every start
  did until then.
- **Replicas.** The add-missing step (texts, settings, adoption) first takes `pg_advisory_xact_lock` on one
  fixed key, `DefaultsLedger.ADD_MISSING_LOCK`, which C3b's settings step shares. It reads the database and the
  ledger only after the lock, never from a cache. Two instances starting together therefore run it one after
  the other, and the second reads the first's committed rows and finds nothing missing. No unique index is
  added to `DictionaryEntry`: an existing deployment may already hold duplicates (R-CDC1). The chart runs one replica today (`values.yaml:101`).
- **A new language** (added to `modelLanguages` later) gets its missing keys once, as today, except the keys the ledger records as deleted (above).
  A key a sysadmin restored with the dictionary import (§4.6) keeps its `*` row, since the ledger is
  insert-only: it gets no default in a language added later, and the import completes it (decided after the
  review of PR #179, 2026-10-22; the ledger stays insert-only, and §4.5's "never `asAdded` again" holds).
- **Where the deleted keys are read** (after the review of PR #179). Start-up and `asAdded` read the ledger's
  in-memory snapshot (above), which is per node and refreshed only by a start or by a delete on that node. The
  dictionary import's preview and job read a dictionary's `*` rows from the database
  (`DefaultsLedger.deletedKeys`), so a delete made on any node reaches every node's import at once.
- **A missing pack file** for a configured language logs a warning and adds nothing for it. Today this
  already holds for every language except the default one (`:330-335`). A missing *default-language* file
  stays fatal, because the base product's `en` always exists (DICT1, narrowed).
- **No Reload.** `loadDefaultResourceBundles(true)` and `updateDictFromResourceBundle` (`:405-432`) go with
  the endpoint (§10).
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
  base product's `catalog.json`. That section is generated at publish by the kit's `catalog` (country-split plan P2) from niord-base's
  English backend bundles (today `be/niord/src/main/resources/*_en.properties`, from C2 `defaults/base/texts/en/`)
  and holds every key, its placeholders (`{{name}}` and MessageFormat's `{n}`) and its English fingerprint. Since
  P2b `catalog` (`fe/country-kit/catalog.mjs`, in the kit since P3) reads both locations and refuses a
  bundle found in both, so C2 needs no catalog change whichever of the two branches reaches `main` first. The same rows apply: a missing key fails (or is in
  the escape hatch, and the deployment's default language then serves, §4.1); a placeholder or markup difference fails; an
  unknown key is stripped. The backend half is required once a language is a UI language. For a language that is only a
  content language it is optional: a missing backend key is a warning, and the deployment's default language serves in its
  PDFs and mails until the pack has it, so a new language can start with `pack.json` and a flag alone
  (country-split plan §4.6). Such a language cannot be the deployment's `defaultLanguage` until its backend half
  exists: a missing default-language file stays fatal (§4.2).
- **Language names.** `message-support.ftl:496,500` renders `text('lang.' + lang)` for every language of a
  message. Today `lang.*` sits in `web` (`web_en.properties:2` `lang.en`, DK's `web_en` `lang.da`). Under
  this plan, a language's name in a render language is a `lang.<code>` key in `web`, which a pack's
  backend half and a country's texts may carry for the languages they name (the `da` pack `lang.da = Dansk` and
  `lang.en = Engelsk`, DK's English texts `lang.da = Danish`). A draft PDF therefore keeps today's translated
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
  it is generated. As built in the kit's half of country-split P5 (P5k, 2026-10-22): set-up stages every pack but
  `en` — home, pinned or a developer's local copy — into `fe/.niord/packs/<lang>/` in exactly the layout of the pack's
  canonical archive (`pack.json`, `i18n/{admin,info}/<lang>.json`, `backend/<bundle>.properties`, the flag and its
  drawing, `ATTRIBUTION.txt`, `status/fingerprints.json`; country-split plan §4.5), so `backend/` there is the one
  path a country's Maven build will read, home packs included. **When** (the review of country-split PR #190,
  2026-10-23): the pom's re-point at `fe/.niord/packs/<lang>/backend/`, `setup.mjs` run before Maven in the
  country's backend job and the Maven check above come together, with the kit version that adds a backend job to
  the templates (a kit follow-up of P5b and P6b: a backend job in the `image.yml` template). None of the three is a
  step of the countries' bump to P5k's version: neither country's backend job runs set-up (DK's and GL's `ci.yml`
  run it in the frontend job only), and the check is built nowhere yet. Until then a country's pom copies its home
  pack's `fe/packs/<lang>/backend/` in place into `defaults/packs/<lang>/`, as DK's and GL's frontend moves built
  it (`niord-dk` #5, `niord-gl` #4, on their feature branches, 2026-10-22). DK has since pinned 4.0.87, which
  carries P5k's kit (P5e, `niord-dk` #6, 2026-10-23): set-up stages `da` into `fe/.niord/packs/da/`, and DK's pom
  still copies `fe/packs/da/backend/` in place; the backend job, and with it the re-point, comes with a later kit
  version. Done in DK's and GL's single bump onto 4.0.111 (`niord-dk` #11, `niord-gl` #10, 2026-11-02): both poms
  copy `fe/.niord/packs/<lang>/backend` with `require-staged-packs` at `validate`, and set-up runs before Maven in
  `ci.yml`'s tests job and `image.yml`'s backend job (country-split plan §6 P8, _MAIN, DK-1 and GL-1 as built_).
- The packs carry their backend half from their first build: P5's frontend part copies DK's five `*_da` bundles into the `da` pack, so the first `niord-lang-da` has them, and P6's copies GL's `*_kl` into the `kl` pack (owner, 2026-10-21). The moves then deleted the home language's root-level bundles: DK's move branch has no `*_da` under `be/` and GL's no `*_kl`, each pom copying the home pack's backend half in place (above). From their merges into the countries' `main` the transitional layer (§4.1) therefore serves GL's five root-level `*_da` only. GL keeps them until its Maven build reads the pinned `niord-lang-da`, which set-up stages since GL's bump to 4.0.87 (P6e, 2026-10-24) (each bundle compared with DK's pack first, the differences to GL's country texts, §9.2), and the build reading it needs the backend job above; the base product keeps its transitional root-bundle layer until then. C6's backend parts, built first for DK and GL (2026-10-22), left the root `*_da` / `*_kl` bundles to the transitional layer until the moves. GL's bump onto 4.0.111 deleted the last of them, its five `*_da` (`niord-gl` #10), and NG-B removed the layer (#252, §4.1).

### 4.4 Country texts

`defaults/country/texts/<lang>/<bundle>.properties` holds two things:
- the country's own wording of a base product key, only where it differs and only for a key a template or
  Java reads. For DK these are the three keys of `mail_en` that differ from the base product's
  (`mail.active_nav_warnings`, `mail.navtex.cancel`, `mail.navwarn.cancel`, each read by one template);
  `pdf.nm_almanac.desc`, the base key C5 made neutral, which DK sets back to its pre-C5 wording; and
  `msg.responsible.agency`, the optional, country-supplied key this section's last bullet declares, which DK sets
  to `Danish Emergency Management Agency` (owner, 2026-10-22, `niord-dk` #4; C6's #2 had set `Danish Maritime
  Authority`, the value `S124Service` used until C5). The rest are deleted. `web_en`'s
  `lang.da` goes to `texts/en/web.properties` (§4.3). On a migrated database the row wins: niord_test holds
  `msg.responsible.agency` = `Unknown Agency` (§11 C6, the owner questions). **The rule:** a reworded key that no template and no Java reads is shown only by the
  administration app, so it goes to the country's frontend: its text overrides (`fe/text-overrides/<lang>.json`),
  or its branding for the keys the branding schema names, such as `footer.*` (country-split plan §4.10). DK's
  seven differing `web_en` keys are of that kind; as backend rows the admin app would skip them while they equal
  what start-up added (§4.5), but a row a deployment already held before C2 is not `asAdded` where it differs
  from the base product's default (C2's record), so such a row masks the frontend's text until it is deleted.
  `footer.copyright` and `footer.link` are DK's branding from P1 (country-split plan §4.4); DK's and GL's
  existing English rows of the two keys are of that kind, and C6 says how they go. `view_mode.table`,
  `promulgation.confirm.generate` and `promulgation.confirm.generate_all` already equal
  the administration app's English and go. `msg.no_pos_messages` becomes a text override. Nothing reads
  `template.markings.bearing` (the app's key is `editor.template.markings.bearing`), so it goes. The same rule
  applies to GL's rewordings in C6.
- the country's own keys. New ones carry the country prefix (`dk.`, `gl.`). Existing unprefixed country keys
  are classified in C6 and either kept as grandfathered (listed in the country's `defaults/country/README.md`;
  4.0.76 has a settings catalog for a country but no text catalog), prefixed together with the
  templates that use them, or deleted. In GL's `_en` bundles these are (fact check, 2026-10-20):
  - web: `.MESSAGE_REPORT`, `editor.status.publish_short`, `editor.status.verify_short`,
    `menu.action.generate_s124`, `msg.general_messages`, `my.test2` (delete), `publication_type`,
    `publication_type.MESSAGES_REPORT`
  - mail: `mail.active_nav_warnings_gl`, `mail.navtex.cancel.gl`, `mail.navwarn.cancel.gl`,
    `mail.navwarn.update.gl`, `mail.no_active_nav_warnings`
  - pdf: `pdf.attachment.title`
  - message: `msg.responsible.agency`, `rmsg.responsible.agency` (a typo; delete)
  - `lang.da`, `lang.kl`: kept as GL's English language names, which an English draft PDF prints (§4.3)

  Some of these keys might have been base product keys that never reached the base product's `en`. C6
  (2026-10-22) checked each one against niord-base 4.0.76's code, the base product's bootstrap templates and GL's
  templates. None is a base product key the base's `en` lacks.
  - The five mail keys are read only by GL's `-gl` templates. They stay unprefixed as grandfathered, recorded in
    GL's `defaults/country/README.md`: prefixing them would change templates that moved unchanged.
  - `msg.responsible.agency` is the optional, country-supplied key of the last bullet. C6's #2 kept GL's
    `Unknown Agency`; by the owner's ruling of 2026-10-22 GL's English text names the Danish Emergency
    Management Agency (`niord-gl` #3; §11 C6).
  - `editor.status.publish_short` and `editor.status.verify_short` are the administration app's own keys. They
    go to P6b with GL's rewordings.
  - Read by nothing, and deleted: `.MESSAGE_REPORT`, `publication_type`, `publication_type.MESSAGES_REPORT`,
    `menu.action.generate_s124`, `msg.general_messages`, `my.test2`, `pdf.attachment.title` and
    `rmsg.responsible.agency`.
  - GL's rewordings follow the rule above.
    - Read by a base template, and moved to `texts/en/`: `mail.active_nav_warnings`, `mail.navtex.cancel`,
      `mail.navwarn.cancel` and `pdf.nm_almanac.desc`, GL's pre-C5 wording.
    - Shown only by the administration app, deleted from the backend and listed for P6b: `footer.copyright`,
      `footer.link`, `msg.no_pos_messages`, `promulgation.confirm.generate`,
      `promulgation.confirm.generate_all`, `publication_type.MESSAGE_REPORT`, `template.markings.bearing`,
      `template.sample_message` and `view_mode.table`.
- `msg.responsible.agency` is read by base product code (`S124Service.responsibleAgency`). The base product catalog
  declares it as an optional, country-supplied key with no base product value. Without it, or with a blank value,
  S-124 uses the `organisation` setting, and refuses to build an exchange set when that is blank too, naming the
  setting (built in C5).

Checks: a reworded key exists in the base product catalog and keeps its placeholders and markup. An own key is
prefixed or grandfathered: no check enforces this in 4.0.76. `DefaultTextsChecks` holds an own key's
placeholders and markup to the country's own English value, and a country's `defaults/country/README.md` lists
its grandfathered keys (GL's five mail keys). A check needs a list it can read; until then review holds it. A
`lang.<code>` key is neither and passes (§4.3).

### 4.5 The administration app's dictionary merge

**Today (unchanged by this plan):** when the administration app starts, it fetches the database's `web` and
`message` dictionaries and lays them over its own built-in texts, key by key
(`dictionary-overrides.service.ts:46-49`, `merge-dictionary-overrides.ts`; country-split plan §4.10 layer 5).
That is how an operator changes a wording in the admin app from the Dictionaries page, without a release.

**The problem today:** the backend has copied its own text files into those dictionaries at every start. Those
files carry older Niord wording. Where a key exists in both, the database copy masks the admin app's newer
text although nobody edited it: 38 of 111 overlapping `web` keys and 9 of 52 `message` keys (inventory
2026-10-19).

**What this plan adds:** the ledger knows which rows the system added and with which value (§4.2).
`GET /rest/dictionaries/dictionary/{name}/entries` marks a translation `asAdded` while its value still equals
what the system added, and the admin app's merge skips those rows, so its own text shows. A row an operator
changed keeps winning, as intended. Adoption records the value of every row still equal to its default
(§4.2), so this works on existing deployments too, for the rows still equal to today's default. Nothing in the database changes: the rows stay, they only
stop masking the app's texts. `asAdded` is computed into the cached `DictionaryVo` from the in-memory ledger
hashes (§4.2) and cleared with it, so `/entries`, which the admin app calls on every load, stays a memory read.

How it is built (C2b):
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
  never carries the flag and the cached dictionary is never changed by an export (after the review of
  PR #179).
- The admin app's `dictionary-overrides.service.ts` keeps its choice of translation (the requested language,
  else the default language, else any non-blank one). If that translation is `asAdded`, the key gets no
  override. There is no fall-through past it to another language: an unchanged system-added English row never
  replaces the app's own Danish. An English-only row an operator edited still does, by design (corrected after
  the review of PR #184: the choice of translation is unchanged, only an `asAdded` pick gives no override). A
  missing flag counts as false, so against an older backend the app behaves as before.
  `merge-dictionary-overrides.ts` applies the remaining overrides with its placeholder rule, unchanged.

**What adoption does not cover.** A row written from an older file and never edited differs from today's
default: it gets no hash, is never `asAdded`, and the admin app's merge lays it over its own text unless the
placeholders differ (`merge-dictionary-overrides.ts:52-81`). By R-CDC1 nothing decides that such a row is stale,
and no tool compares a deployment's rows with the defaults. The operator's remedy is to delete the row on the
Dictionaries page: the ledger never adds it again (§4.2), and the app's own text shows. That suits a key only the
administration app shows; a key a template reads then fails its renders until a sysadmin restores it with the
dictionary import (§4.6).

### 4.6 Deleting a key that a template uses

Until C2a a key an operator deleted came back at the next restart, which accidentally protected templates.
Since C2a it stays deleted, and `text()` fails a render on a missing key (`MultiResourceBundleModel.java:113-120`).
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
  names them (`paramTypes`, sorted, always present), which the confirmation lists first (after the review of
  PR #179; until then such a delete failed at commit with a 500).
  Not every user is a literal. For a key under a runtime-built prefix of §4.7 (`lang.`, `msg.status.`,
  `msg.type.`, `msg.field.`) the scan matches the prefix, since no template names such a key in full. The keys
  Java reads come from the constant list `RequiredTexts` (`org.niord.core.dictionary`), the texts' counterpart
  of C4's `RequiredTemplates`, held by the literal test `RequiredTextsTest`. Its readers are matched by
  dictionary and key, so the same key in another dictionary names none.
  Without its key, `MessageService` (`msg.cancellation.title`, `MessageService.java:901-906`) falls back to
  English or a built-in text, `FiringExerciseService` (`msg.time.date_time_format`, `msg.time.time_format`,
  `msg.firing_exercises.subject`, `FiringExerciseService.java:331-376`) to built-in English texts
  (`:336-341`, `:375-377`), and `S124Service`
  (`msg.responsible.agency`, `S124Service.responsibleAgency`) to the `organisation` setting (since C5).
  `MessageCommentRestService` puts `null` into the comment mail's subject
  (`mail.comment.subject`, `MessageCommentRestService.java:324-325`), and `MailingListExecutionService` cannot
  build a mailing-list mail (`mail.date_format.short`, `mail.date_format.long`,
  `MailingListExecutionService.java:392-394`); nor can `AudioPromulgationService` generate a message's audio text
  (`position.*`, `AudioPromulgationService.java:121-125`). Of the template directives, `DateIntervalDirective` fails the
  render (`navwarn.date.*`, `term.and`); `FromToDateDirective` (`msg.time.until_further_notice`,
  `msg.time.date_time_format`) and `LatLonDirective` (`position.*`) print nothing.
- The base product ships its texts as an importable dictionary file: `be/niord/bootstrap/texts.json`, current
  with the code, and a frozen copy in each release folder, `be/niord/bootstrap/releases/<folder>/texts.json`
  (§6.4). Both are in the format the dictionary import reads (`ExportedDictionaryVo`, as `aton-dict.json`):
  the five dictionaries `web`, `message`, `pdf`, `mail` and `template`, language `en`, read from
  `defaults/base/texts/en/` with the reader start-up uses (`DefaultTexts`). Both are generated
  (`ReleaseFilesGenerator`), and `ReleaseFilesCurrentTest` fails while `bootstrap/texts.json` lags the texts.
  `batch-set.json` does not name it, so the bootstrap never applies it (start-up adds the texts). A sysadmin's
  Import, the sanctioned path, restores a key. The dictionary import has the same flow as §6.4 (C4.2b):
  `POST /rest/dictionaries/import-preview` (SYSADMIN, the file as a multipart part, a JSON answer, no write)
  lists the keys it would add with their values, the keys it would overwrite with the current and the new
  values (the languages the file carries, the long value and the AtoN filter), how many are unchanged, and the
  refused entries, each with its reason. The unit is the pair (dictionary, key). An add is ticked by default,
  except a key the ledger records as deleted (its `*` row), which is offered unticked: importing the file to
  restore one key does not restore every key an operator deleted (R-CDC5); the preview and the job read the
  `*` rows from the database (§4.2). An overwrite is unticked, and a ticked one writes each language the file
  carries whole, value and long value, keeps the others and replaces the AtoN filter: a long value the file
  lacks for a language it carries is cleared, and the preview shows that line as a clearing (after the review
  of PR #179; until then the dialog showed only the long values the file carried).
  `POST /rest/dictionaries/upload-dictionaries` takes the choice as two optional fields, `add` and `overwrite`
  (JSON arrays of `{"dictionary", "key"}`; an `overwrite` element may also carry `basedOn`): without `add` every
  missing key except a deleted one is added, without `overwrite` nothing is overwritten. Each overwrite of the
  preview carries `basedOn`, a fingerprint of the row as the preview read it (`sha256:` over the AtoN filter and
  each language's value and long value, in language order); a ticked key whose row no longer matches its
  `basedOn` when the job runs is kept and logged (`Kept (changed since the preview) <dictionary> <key>`), so an
  edit made between the preview and the job is never overwritten unseen. A tick without `basedOn` behaves as
  before. An entry the schema cannot hold is refused, not failed: a blank dictionary name or key, an entry
  without any translation (`the entry has no translation`, after the review of PR #179), a key or AtoN filter
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

Where they live (C2b): the checker `DefaultTextsChecks` sits in niord-base's test sources, so the published
test-jar carries it. `BaseProductTextsCheckTest` runs it in the base product's unit suite, which Backend CI
runs; no CI job is added. Each country's build runs it from C5b, over the country texts and packs that C6
brings. As built (C5b): `CountryDefaultsChecksTest`, a member of the country build group that `niord-parent`
runs in every country build (§11 C5b), runs `DefaultTextsChecks.checkTexts` and `DefaultSettingsChecks.check`
over the country's class path, and fails on a text finding or a settings error; it skips itself (a JUnit
assumption naming C6) while the class path holds no `defaults/country/` and no `defaults/packs/`, which is
DK's and GL's state until their C6. A finding names the file, the key and the rule. Until C4 moves the templates to `bootstrap/`, the
template-key rule reads the class-path `templates/`. The
`lang.` prefix is checked as "the base product carries `lang.en` only" (country-split plan R1): the base
`web.properties` must hold `lang.en`, and no base bundle may hold another `lang.<code>`; another language's name
comes with its pack or a country. No `pack.json` label is to come (§4.3).

---

## 5. Settings

### 5.1 The settings catalog

The catalog is generated from the code: every `@Setting` field and every `static` `Setting` constant (a
`new Setting(...)`), with the declarations C3a adds for the keys of today's `niord.json` that have none (below);
on 2026-10-21, 45 live `@Setting` fields in 22 files and 18 constants in 5 files, 58 keys, and 73 after C3a. It
is committed as `defaults/base/settings-catalog.json`, a file `{ "settings": [ … ] }` with one entry per key,
sorted by key:

```json
{ "key": "mapDefaultZoomLevel", "type": "Integer", "codeDefault": 6, "baseValue": 6,
  "scope": "operator", "web": true, "editable": true, "required": false, "cached": true,
  "introducedIn": "4.0.74", "description": "…",
  "declaredIn": ["org.niord.core.settings.FrontendSettings"] }
```

- `declaredIn` (C3b) lists the classes that declare the key, fully qualified, sorted and distinct. Start-up
  uses it to add a key only on a node where its declaring code runs (§5.3).
- `codeDefault` and `baseValue` are JSON values of the key's type (`6`, `true`, a list, an object), not
  strings: `modelLanguages` and `editorFieldsBase` are a list and an object. `cached` is the declaration's
  flag, which the rows carry today; C3b creates rows equal to today's from it.
- `scope` is `operator` or `state`. The code declares it beside the key, with `web` and `editable` (the
  annotation's `editable` defaults to `true`, `settings/annotation/Setting.java:40`). An `@Setting` field
  declares it with the annotation's `scope()`, a constant with `Setting.scope(...)`, and `required` likewise;
  neither is a column or part of any JSON body. `baseValue` is the base product `settings.json` value, or `null`. `required`
  marks a key that no file may leave to the base product default (`country`, `organisation`, from C5; nothing
  is `required` in C3a). As built (C5): both are declared in `NiordApp` with no code default, an explicit type
  `String` (as `SettingsService.NIORD_HOME`, since a `null` default carries no type) and `required(true)`; their
  catalog entries have `codeDefault` `null`, `baseValue` `"XX"` and `"Niord"`, `required` `true` and keep
  `introducedIn` `4.0.74`, and they are the catalog's only `required` keys (`SettingsCatalogCurrentTest`).
- **The generator** (`SettingsCatalogGenerator`, niord-base's test sources, so the test-jar carries it to the
  countries; since C5b `be/docs/country-api.md` names it for them, and a country's own current-check, as
  `SettingsCatalogCurrentTest`, is its own test, added in C6 with its first key) scans the owner's main classes only, never its test classes or another jar, and reads an
  `@Setting` field's default with the injection's own parsing (`SettingInjectionBeanPostProcessor`). It fails,
  naming each class and field, on a declaration without a type; on two declarations of one key that differ in
  type, code default, scope, `web`, `editable`, `required`, `cached` or description; on a `Password` key with
  `web=true`; on a `state` key that is `web`, `editable` or `required`; on a `json` annotation default that
  does not parse; and, from C3b, on a description longer than 255 characters, the width of the
  `Setting.description` column, since add-missing writes every description in one batch and one overflow
  would fail start-up. The reader and writer of the catalog file, `SettingsCatalog`, is in niord-base's main
  jar from C3b, so start-up reads the catalog with the parser the build writes it with, and the type rule of
  §5.5 sits there for both; the generator stays in the test sources.
- CI fails when the generated catalog differs from the committed one, so a PR shows every new or changed key:
  `SettingsCatalogCurrentTest` in the unit suite names the added, removed and changed keys and the command that
  regenerates the file, from `be/`:
  `./mvnw -pl niord test -Dtest=SettingsCatalogCurrentTest -Dsurefire.failIfNoSpecifiedTests=false -Dniord.settingsCatalog.write=true -Dniord.settingsCatalog.introducedIn=<version>`.
- `introducedIn` is kept from the committed catalog once a key has it. A publish takes the workflow's run
  number (`4.0.<run>`, with gaps: `v4.0.66`, `70`, `71`, `72`, `74`), so the next version is unknowable when a
  PR is written. The first catalog's 73 keys carry `4.0.74`, the newest release when it was made, whose code
  already reads every one of them. A later new key carries the version its author passes with
  `-Dniord.settingsCatalog.introducedIn` (the newest published plus one, a lower bound); without it the write
  fails naming the new keys, and the check needs no version. The upgrade report finds new keys by comparing the
  pinned and the new catalog's keys, so `introducedIn` is informational.
- The catalog is published with each release, in the jar and in the source zip. A country's build and its
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
- **Keys with no declaration today** (C3a). Twelve of the 24 keys of today's `niord.json` have neither an
  `@Setting` nor a `new Setting(...)`: the ten the frontends read from `/web` (`mapDefaultZoomLevel`/`Latitude`/
  `Longitude`, `siteLanguages`, `infoSiteLanguages`, `infoDefaultLanguage`, `wmsLayerEnabled`,
  `openSeaMapLayerEnabled`, `stdTemplateFields`, `documentationUrl`), `niord.home` (read by `peek`,
  `SettingsService.loadSettingsFromNiordHome`, `SettingsService.java:211`) and `disableLucene` (a bare key, `MessagePostgresIndex.java:68`). C3a declares
  the ten in `FrontendSettings`, a small registry class of `new Setting(...)` constants in niord-base that no
  backend code reads, and the other two as constants beside their readers: `SettingsService.NIORD_HOME` (no
  code default, as today) and `MessagePostgresIndex.DISABLE_LUCENE` (default `false`). `FrontendSettings` also
  declares `adminIntegrationPageEnabled` (`web=true`, default `false`), the gate of the administration app's
  Integration section (`fe/ui-apps/admin/src/app/features/admin/section-descriptors.ts:92`), declared nowhere
  since #134 (owner, 2026-10-21). That makes eleven constants in `FrontendSettings` and thirteen declarations
  in all. Each declaration
  carries the file's `web` and `editable` flags and its description, and a type that follows today's rows
  (`niord.json`'s `6`, `56` and `11` make `mapDefaultZoomLevel`/`Latitude`/`Longitude` `Integer`). So do the
  three `editorFields*` constants (`EditorFieldsService.java:57-71`), which carry none today and now get type
  `json`. `batchMessageSeriesId` (`BatchMessageImportProcessor.java:53-59`) gets type `String`, since every
  catalog entry needs one. The code descriptions of `defaultLanguage` and `authServerUrl` take `niord.json`'s
  wording, and the two declarations of `messageIndexIncludeDeletedMessages` (`MessagePostgresIndex`,
  `MessageLuceneIndex`) one wording, so the catalog reproduces today's rows. The two `state` keys, plain strings
  today (`AreaService.java:59`, `CategoryService.java:54`), become constants declared with `scope` `state`
  (`AreaService.AREA_LAST_UPDATE`, `CategoryService.CATEGORY_LAST_UPDATE`, type `Date`); `TreeBaseService`
  (`:230, 253`) is rewired to them in C3b, with the internal writer. The counts hold: 58 keys, plus 12, plus
  `adminIntegrationPageEnabled`, plus the two `state` keys, are the catalog's 73.

### 5.2 The settings files

- **Base product:** `defaults/base/settings.json` is today's `niord.json`, with all its keys, as today, and from C5
  neutral values for `country` and `organisation` (§5.4): `"country": "XX"` (an ISO 3166-1 user-assigned code)
  and `"organisation": "Niord"` (the product's name), after `adminAppUrl`, so the file holds 26 keys. `documentationUrl` becomes `https`
  (`niord.json:47` is `http://docs.niord.org`, which would warn in its own check; `https` answers, `http`
  redirects to it). Its format is `{ "values": { … } }`, the key's JSON value under the key, in `niord.json`'s
  order; the base file has no `useBaseDefault`, and no password setting.
- **The switch (C3b).** Until C3b the root `niord.json` was the file `SettingsService` loaded, and
  `defaults/base/settings.json` (C3a) was read only by the checks and the catalog's `baseValue`; parity methods in
  `NiordBaseSettingsResourceTest` held the two equal, `documentationUrl` excepted, and the catalog's flags,
  types and descriptions equal to what `niord.json` gave the rows. C3b switched the loader to
  `defaults/base/settings.json` and deleted niord-base's root `niord.json` and the parity methods
  (`CoreResourcePackagingTest` asserts niord-base's main resources hold no `niord.json`).
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
  the first root-level `/niord.json` on the class path (the country file until C3b, `SettingsService.java:62`,
  read by `loadSettingsFromClasspath`, `:147`; after C3b only a country jar has one) is read as the country's
  settings file, logged at start-up. Of each entry, in today's list format, only the key and the value count;
  type, scope and flags come from the catalogs. A key no catalog declares, or a `state` key, is skipped with a
  warning (DK's and GL's `wmsLogin` and `wmsServiceName`); a value that does not fit the key's type is skipped
  with an error. When `defaults/country/settings.json` exists, a root `niord.json` is ignored, and the log
  says so. Its source in the ledger is `root`. DK and GL can then take a C3b+ base product before their own
  C6 and keep their settings read as today. Each adds the `required` keys it lacks (§9) to that file when it
  takes a C5+ version. Until it does, start-up does not refuse it (R-CDC1: an upgrade never breaks a running
  deployment): as built (C5, `DefaultSettings`), it logs a warning for each `required` key the country file or
  the transitional root `niord.json` does not set, naming the key and the file, and the key resolves to the
  base product's neutral value; a database row wins, as for every key. Nothing is logged when only the base
  file is read (`niord-app`). The refusal is the country build's `required` check (§5.5), run there from C5b
  by `CountryDefaultsChecksTest` once the country has `defaults/country/` (C6); over the transitional root
  `niord.json` it does not run.
  DK's and GL's local databases hold the rows (`niord_test` `DK` and `DMA`, `niord_gl` `GL` and `DMA`, read
  2026-10-22), so both start on a C5 base product with their settings unchanged. Neither country carries a root
  `niord.json` since its C6 (2026-10-22): both have `defaults/country/settings.json`. The read is now used only by
  niord-base's test tree (`be/niord/src/test/resources/niord.json`, read by every IT); the regeneration's
  `be/niord/seed/niord.json` was deleted with its folder in C4b, whose regeneration leaves the test tree's root
  `niord.json` off its class path (§7.4). The read is deleted once the test tree moves to `defaults/`, not in C6.
- **Loaded early.** `SettingsService` reads the settings files and both catalogs from the class path in its
  constructor. That needs no database or transaction, and `SettingsCache` is already a constructor
  dependency (`:82-86`). Before it reads, the constructor runs the guard over the four settings names (§3.3,
  `DefaultsGuard.checkSettings`), so a shadowed settings file fails start-up before add-missing writes
  anything; the full guard in `DictionaryService.init` stays. `SettingInjectionBeanPostProcessor` (`:67-101`),
  which resolves `@Setting` fields during bean creation, therefore sees the file's value from the first bean
  on. Until C3b the files were read in `init` at `ApplicationReadyEvent` (`:105-112`), after every bean
  existed. A singleton's injected field still holds the start-up value until restart, as today (SECOM-UI-1).
- **Correction (C3b, the tree against this plan).** Early loading alone gives a bean created before the
  database is reachable the file's value, not the database's: the case SETTINGS-1 recorded,
  `secomRenewalEnabled=true` in the database, has no file value at all. So the post-processor records each
  field it could not resolve through the database, injects the file's value (else the base product default)
  meanwhile, and re-injects every recorded field from the database once add-missing has committed (a listener
  at `HIGHEST_PRECEDENCE + 1`, after `init` and before `DictionaryService.init`). An early bean ends start-up
  with the database's value, as every other bean does.
- The server's own `${niord.home}/niord.json` overlay stays (until C3b `SettingsService.loadSettingsFromNiordHome`, `:206-232`; now `SettingsService.withServerOverlay`, applied by `DefaultSettings.withOverlay`). It is the
  server's file, not the repository's, and the place for a real credential such as `wmsPassword`
  (`WmsProxyServlet.java:89`), besides the Settings page. From C3b it gives values only: type and flags come
  from the catalogs, a key no catalog declares is skipped with a warning, a value of the wrong type with an
  error, and its source in the ledger is `home`. A file that does not parse still fails start-up. The constructor resolves `niord.home` without the database (the system property or environment
  variable, else the files' value); `init` re-reads the overlay when the database's `niord.home` row names
  another folder, since the row wins over the file, as before.

### 5.3 Resolution and add-missing

- **Start-up** (`SettingsService.init`, `@Order(HIGHEST_PRECEDENCE)`, `SettingsService.java:105-110`, kept, under the advisory
  lock of §4.2) adds every `operator` key of the base product and country catalogs that the database lacks and the
  ledger has not seen. It uses the file's value, or else the base product default (§5.2), and records the key in
  the ledger. Adoption works as for texts. `state` keys are never added.
- **Only where the declaring code runs (orchestrator, 2026-10-21, in the spirit of "settings stay as
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
  `editable` come from the catalog, so an empty database gets the rows today's start-up and insert-on-read
  gave it. The `Setting` rows go in one JDBC batch with `ON CONFLICT (setting_key) DO NOTHING` besides the
  lock, the ledger rows in another; the added keys are evicted from the cache after commit. A present key is
  adopted with the hash of its value when that value equals the resolved default.
- **Reads never write.** `SettingsService.get` no longer persists a missing key (`:302-306`) and no longer returns `"/"` on a
  read-only transaction (`:314-316`). A missing row resolves in memory to the file's value or the base product
  default; a key no catalog declares resolves to its caller's template value. `peek` follows `get` for a
  missing row, so `${niord.home}` expands to the file's value before add-missing has run. This closes
  SETTINGS-1 (with the re-injection of §5.2), SETTINGS-2 and RENDER2; the three entries were deleted from
  `be/docs/potential-errors.md` with C3b. `TreeBaseService`'s
  `areaLastUpdate`/`categoryLastUpdate` (`:230, 253`) are `state` keys. The internal writer,
  `SettingsService.writeState`, which `TreeBaseService` calls in place of `setDate` (`:253`), creates a `state`
  row with the catalog's type and flags the first time it is written and afterwards changes only its value,
  as an upsert, so two nodes do not collide; an existing `state` row keeps its stored type and description
  (until C3b `set` threw on a missing row, `SettingsService.java:421-425`).
- **A missing row is cached like a row.** Its resolved value (the file's, the base default, or null) is cached
  with the same lifespan and evicted on every write, so a key with no row (one removed by hand SQL, a `state` key
  before its first write) costs no database read on every access (review, 2026-10-20).
- **The public `set` and the imports** (`PUT /setting/{key}`, `SettingsRestService.java:155`; the settings
  import, `BatchSettingsImportWriter.java:44`) write any catalog key except a `state` key, as today; a `state` key
  or an unknown key is refused. Only the internal writer writes a `state` key. As built (C3b), the public
  `set` answers 400 for a key no catalog declares, also one that has a row (DK's and GL's eight editable rows
  no code reads, `wmsLogin` and `faMrnPrefix` among them), for a `state` key, for a missing row (as before),
  and for a value that does not fit the row's type, else the catalog's (DK's and GL's databases store
  `wmsProtected` as `String`, the catalog says `Boolean`, and the Settings page sends the shape of the row's
  type). The settings import (`BatchSettingsImportProcessor.java:46`) stops creating rows through `get`: it
  skips a key no catalog declares, a `state` key, a key the deployment has no row for and a value of the wrong
  type, each with a line in the job log, and never creates a row.
- **Everything else stays as today (owner, 2026-10-20).** Every setting the catalog marks `editable` (every `operator` key but three, §5.4) is edited on the Settings page,
  including paths, the deployment's own addresses, trust switches and credentials. The per-key system-property
  and environment-variable override and the server's `${niord.home}/niord.json` overlay stay. `/web`,
  `/editable-settings` and `/setting/{key}` (`SettingsRestService.java:79-146`) keep reading the database:
  start-up adds every key a deployment lacks, so a fresh deployment's lists are complete. A key whose row
  was removed by hand SQL is absent from them (the Settings page has no delete, §5.6).
- `MapTileSettings.storeTileCacheSetting` (`:215-233`), which writes `mapTileCachePath` on purpose, is
  deleted: add-missing adds `mapTileCachePath`, and a read never writes it.

### 5.4 Scope of today's keys (fixed in the catalog in C3a)

| Scope | Keys |
|---|---|
| **operator**, defaults in the base product's settings file or else in the code; edited on the Settings page as today, except `authServerTrustAllCertificates`, `secomEnabled` and `mapTileCachePath`, which are `editable=false` and stay off it (`KeycloakIntegrationService.java:81`, `SecomAdminRestService.java:82-83`, `MapTileSettings.java:89-93`) | every key below and every other non-state key: paths (`niord.home`, `repoRootPath`, `batchJobRootPath`, `messageIndexPath`, `publicationArchiveRootPath`, `publicationPreviewRootPath`, `mapTileCachePath`, `vipsPath`); the deployment's addresses (`baseUri`, `adminAppUrl`, `authServerUrl`, the SECOM URLs); the trust switches (`authServerTrustAllCertificates`, `niordIntegrationAllowPrivateHosts`, the SECOM security switches); the password settings (`pdfEncryptionPassword`, `wmsPassword`; masked and never web-visible, as today); `mode`, `mailSender`, `mailValidRecipients`, `secomEnabled`, `messageIndexDeleteOnStartup`; languages (`modelLanguages`, `siteLanguages`, `defaultLanguage`, `infoSiteLanguages`, `infoDefaultLanguage`) and the tile and WMS addresses (`osmUrl`, `openSeaMapUrl`, `wmsProvider`), as today (D3); `mapDefaultZoomLevel`/`Latitude`/`Longitude`, `wmsLayerEnabled`, `openSeaMapLayerEnabled`, `editorFieldsBase`/`Nw`/`Nm`, `stdTemplateFields`, `mapImageSize`, `mapImageZoomLevel`, `mailMaxPerMinute`, `mailDeleteAfterDays`, `batchFileExpiryDays`, `firingExerciseScheduleDays`, `repoCacheTimeout`, `batchMessageSeriesId`, `documentationUrl`, `mapTileContact`, `disableLucene`, `messageIndexIncludeDeletedMessages`, `pdfEncryptionEnabled`, `wmsLayers`, `wmsProtected`, `adminIntegrationPageEnabled`, `country` and `organisation` (both `required`), the SECOM operational keys (`secomAlertEmails`, `secomCertExpiryWarnDays`, `secomMaxSubscriptionsPerNode`, `secomOutbox*`, `secomRenewalEnabled`, `secomRenewalWindowDays`) |
| **state** | `areaLastUpdate`, `categoryLastUpdate` |
| **none** (dead, dropped from the files) | `wmsLogin`, `wmsServiceName` (commented out, `WmsProxyServlet.java:76-82`) |

`country` and `organisation` lose their Danish code defaults (S3; built in C5: no code default, `required`).
The base product file gives the generic deployable neutral values, `XX` and `Niord`, which `niord-app` runs on.
Every country file must set both. `MessageSeriesService.java:383` and
`FmTemplateService.java:126` put `country` into series numbers and templates, and S-124 puts it into its
nationality, ids and MRN (S3), so it is never left to a default. Existing deployments keep their rows: DK's and
GL's local rows are `DK`/`DMA` and `GL`/`DMA` (read 2026-10-22), and C5 changed neither. A country file without
a `required` key starts with a warning, and a database without the row then gets the base product's value
(§5.2, §12).

### 5.5 Checks (R-CDC8, R-CDC7)

These run in the base product's CI over `defaults/base/settings.json`, and in each country's build over its own
file against the pinned base product catalog plus its own. Each finding names the file, the key and the rule;
every rule but `https` fails the build. The rules as built (C3a):
- **`format`**: a file that is not an object with `values`, a top-level member other than `values` and
  `useBaseDefault`, a base product file with `useBaseDefault`, or a settings file found at more than one
  class-path location.
- **`missing-key`** (R-CDC7's completeness, scoped to the keys of the base product's settings file): a key of
  the base product's file missing from the country file fails, unless it is listed in `useBaseDefault`. The
  check reports that list with the warnings (where the report lands, see below). A key the base product declares only in code takes its code default and needs no
  entry. After C3a the catalog holds 73 keys (2026-10-21, the two `state` keys included) and the base file 24 of
  them (26 from C5, with `country` and `organisation`; `BaseProductSettingsCheckTest` reads 26 values). DK's
  and GL's files hold the 24 others, so C6 needs no `useBaseDefault` entry; it adds `country` (DK; GL has it)
  and `organisation` (both) to their files, as the `required` rule demands. A key a base product release adds to its file fails both country builds until it is
  set or listed; the upgrade report lists it.
- **`use-base-default`**: an entry without a reason, an entry of a `required` key, or a key listed twice.
- **`required`**: a `required` key missing from the file. Shown red once in C5: with `country` removed from
  the base file, `BaseProductSettingsCheckTest` failed with `defaults/base/settings.json country: required — a
  required setting: every settings file sets it`. At start-up the same gap is only a warning (§5.2).
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
  (C3b), with the same rule (`SettingsCatalog.hasType`). At start-up a file value of the wrong type is not
  added: it is logged as an error, the key falls to the next layer in memory, and it is added once the file is
  fixed (an existing deployment's server overlay was never checked, and R-CDC1 forbids an upgrade breaking
  it). On the Settings page's write the value must fit the row's type (§5.3).

Where they live (C3a), as the text checks (§4.7): the checker `DefaultSettingsChecks` sits in niord-base's test
sources, so the published test-jar carries it, and `BaseProductSettingsCheckTest` runs it in the base product's
unit suite over the base file and catalog; it fails if it read no values. It prints its report (the `https`
warnings and the `useBaseDefault` list) to standard output, which `redirectTestOutputToFile` in `be/pom.xml`
sends to `target/surefire-reports/org.niord.core.defaults.BaseProductSettingsCheckTest-output.txt`, not to
the console. A local run leaves the report in that file, or shows it on the console with
`-Dniord.test.redirectOutput=false`. Backend CI uploads `surefire-reports` (the `test-reports-unit` artefact)
only for a red or cancelled run, so a green CI run keeps no report. A country build prints the report through
the Maven log instead (settled in C5b): the country build group's Surefire execution in `niord-parent`
(`niord-country-build`) sets `redirectTestOutputToFile` to `false`, so `CountryDefaultsChecksTest`'s report and
the start-up guard's messages are in the build's log. `SettingsCatalogGenerator` and
`SettingsCatalogCurrentTest` (§5.1) sit there likewise. Each country's build runs the checks from C5b, through
`CountryDefaultsChecksTest` (§4.7), once it has `defaults/country/` (C6). The
tests build their fixtures in temporary folders, never under the test resources' `defaults/`, which would ship
in the test-jar and trip a country's guard.

### 5.6 The Settings page

The page works as today (owner, 2026-10-20). There is no reset, no delete (`SettingsRestService` has no `DELETE` endpoint) and no new action.
From C3b a row no code declares (DK's and GL's eight dead editable rows, §5.3) is still listed but cannot be
saved: the write answers 400 (§12). Since C4.2a the page no longer lists such a row (owner, 2026-10-21):
`SettingsService.getAllEditable` keeps only the keys a settings catalog declares, and `GET
/rest/settings/editable-settings`, which the page and its Export read, serves that list. The row stays in the
database, untouched; `GET /setting/{key}` is unchanged. The Export leaving such a row out matches the settings
import, which skips an undeclared key since C3b. `SettingsRestServiceITTest` covers it.

---

## 6. Templates and scripts

### 6.1 Classification of niord-base's 80 files

Verified against the code on 2026-10-20 (`be/niord/src/main/resources/templates/`, since C4.1
`be/niord/bootstrap/templates/`). **All 80 are data**
(R-CDC9). The table only says what refers to each one: the Java code, by path, or data rows on a deployment.

| Path under `templates/` | Named by | Where |
|---|---|---|
| `aton/light-character.ftl` | the code | `LightCharacterService.java:39` |
| `aton/telephony-codes.ftl` | the code | `LightCharacterService.java:40` |
| `geometry/{bullet-list,from-to-table,numbered-list,table,text}.ftl` (5) | the code | `GeometryFormatService.java:57` (`/templates/geometry/%s.ftl`) |
| `messages/message-mail.ftl` | the code | `MessageMailRestService.java:101`, `MessageCommentRestService.java:340` |
| `messages/message-list-pdf.ftl` | the code | the built-in reports: `FmReportService` set it when it created them until C4.1; since C4.1 the bootstrap's `reports.json` names it, and it stays among the 11 because the built-in reports are required |
| `messages/message-support.ftl` | the code | included by `message-list-pdf.ftl:1` and `message-mail.ftl:1` (and by data templates) |
| `messages/message-list-pdf-footer.ftl` | the code | included by `message-support.ftl:49` |
| `messages/{nm-report,nm-tp-report,nm-annex-report,nm-almanac-report,nm-accumulated-report,fa-list}-pdf.ftl` (6) | data rows | `FmReport.templatePath` rows for five of them (seed `niord-base-data.sql:1845-1849`); `nm-accumulated` has no seed row: only `LegacyTemplateRulings.java:403` names it, as the report id `LegacyImportService.java:2360` writes into a series, and `FmReportService.getReport` (`:167-173`) renders that series with the standard report (RENDER3) |
| `messages/{nm-report,nm-almanac-report,nm-accumulated-report,fa-list}-pdf-intro.ftl` (4) | data rows; **placeholders**, zero-byte until C4.2a and the one-line comment of §6.4 since | included by their reports (`nm-report-pdf.ftl:125`, `nm-almanac-report-pdf.ftl:43`, `nm-accumulated-report-pdf.ftl:214`, `fa-list-pdf.ftl:48`) |
| `mailinglist/{active-navwarns,cancel-navtex,cancel-navwarn,cancel-safetynet,publish-navtex,publish-navwarn,publish-safetynet}.ftl`, `publish-safetynet.js` (8) | data rows | `MailingListTrigger.scriptResourcePaths` rows on deployments. The seed (`:1949-1957`) names only `active-navwarns`, `cancel-navtex`, `cancel-navwarn`, `publish-navtex` and `publish-navwarn`, plus DK's `audio-broadcast.ftl` and `fetch-firing-exercises.js` |
| `mailinglist/publish-navwarn-common.ftl`, `safetynet-intro.ftl` (2) | data rows; `safetynet-intro.ftl` is a **placeholder** too (zero-byte until C4.2a, the one-line comment since) | included by `publish-navwarn.ftl:2`, `publish-safetynet.ftl:26`, `cancel-safetynet.ftl:4` |
| `tmpl/*.ftl` (45) | data rows | `Category.scriptResourcePaths` rows on deployments (the seed names the 10 `light-*` files); includes among themselves (`common.ftl`, `aton-common.ftl`, `aton-replacement-common.ftl`, `obstruction-work.ftl`, `obstruction-found.ftl`, `markings.ftl`, `work-vessel.ftl`) |
| `tmpl/{common,compute-affected-radius,object-adrift,update-areas}.js` (4) | data rows | the seed names `compute-affected-radius.js`; `common.js` is reached through `load('niord:…')` in `update-areas.js:18` |

So the code names 11 files and data rows name 69; all 80 are data and live in the bootstrap. The seed has no
`PromulgationType_scriptResourcePaths` rows. Its 12 categories reach 11 of the 69 files that data rows name.
Templates include one another across the two groups: data templates `<#include "../messages/message-support.ftl">`
(for example `mailinglist/publish-navtex.ftl:2`), which, like every include, resolves to the deployment's row.

### 6.2 Templates the code names

- **They are data like every other template** (R-CDC9). Since C4.2a `FmTemplateLoader` reads the database
  only, for every path, and never the jar: a path with no row resolves to nothing. `checkLoadTemplateFromClassPath`
  with its `createScriptResource` and `ScriptResourceService.readScriptResourceFromClassPath` are deleted, and
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
  `GeometryFormatService.java:57`, …; `FmReportService` named `messages/message-list-pdf.ftl` until C4.1,
  and since then the list carries it because the built-in reports are required). A new path the code names
  goes into the list and into `bootstrap/templates/` in the same change.
- **When Java changes what a template expects.** This is today's situation already: a running deployment
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
  `getStandardReport`, `getDraftReport` and `getPublicReport`, which persisted a missing report until C4.1, are
  plain lookups by id since C4.1 (`getReport` falls back to the standard report as before). A deployment
  without one gets a clear error, a 404 (`NiordWebException`) naming the id and the bootstrap, and the
  missing-path report lists it. (Until the review of PR #179 it was an `IllegalStateException`, which the REST
  layer answered 409 with a WARN stack trace on the anonymous PDF paths.) `GET /rest/message-reports/detail-reports` lists only the built-in reports that
  exist, so the administration app opens on a database that has not been bootstrapped yet.
- **The standard parameter types stay code**, held in memory in `TemplateExecutionService` (`getParamTypes`,
  `getParamTypeByName`) since C4.1. They are listed read-only on the Parameter Types page (`param-types-page.html`, the `STANDARD` branch), as before: `GET /parameter-types` returns them
  (`TemplateExecutionRestService.getParamTypes`), the export strips them (`exportParamTypes`), and create, update and delete refuse them (`createParamType`, `updateParamType`, `deleteParamType`). `getParamTypes` serves the 7 in-memory types beside the deployment's other rows: the 7 `StandardParamType` rows a running deployment already holds are ignored, never deleted (R-CDC1), and the in-memory type wins by name. This works
  because `TemplateParam.type` names a type and is not a foreign key. The in-memory types are never persisted
  and have no id, so the Parameter Types list tracks its rows by name (`track paramType.name`; names are
  unique). The parameter-type import (`BatchParamTypeImportProcessor`) skips an item with a standard type's
  name and writes a line to the job log; the rest of the file imports. Without the skip, the id-less in-memory
  type `getParamTypeByName` returns would turn the item's update into the create of a second row.

### 6.3 Data templates and scripts

- All 80 template and script files left `src/main/resources` for `be/niord/bootstrap/templates/` in C4.1 (§7;
  `git mv`, so their history follows). From C4.1 until C4.2a a **temporary Maven resource mapping** in
  `be/niord/pom.xml` packed `bootstrap/templates/` into the niord-base jar under `templates/`, so copy-on-use
  kept finding the files there. C4.2a deleted copy-on-use and the mapping in the same change: no template is in
  any jar since. `CoreResourcePackagingTest` asserts it (`noTemplateOrScriptShipsWithCore`: no top-level
  `templates/`, no `*.ftl` anywhere, no file at a `bootstrap/templates/` path; `neitherTheBootstrapNorTheSeedShipsWithCore`:
  nor `bootstrap/`, `releases/`, `texts.json` or the seed; and `dgps.*` as before), and that
  `META-INF/niord/expected-template-paths.txt`, path names only (§6.6), is there. The test-jar also leaves
  out niord's own test templates and scripts (`templates/**`, `test-scripts/**`, `bootstrap-fixtures/**`);
  DK's test sources use none of them. The two text checks, `BaseProductTextsCheckTest` and
  `RequiredTextsTest`, read `bootstrap/templates/` by path since C4.1, so removing the mapping did not empty
  their scan; the unit tests that rendered a shipped template from the class path read the bootstrap folder by
  path since C4.2a.
- `JsResourceService.findScriptResource` reads the database only (`ScriptResourceService.findByPath`).
  `checkLoadScriptFromClassPath` is deleted (C4.2a). Every `.js` file is data.
- `ScriptResourceService.findOrLoadFromClassPath` (`:99-121`) and the `?path=` preload
  (`ScriptResourceRestService.java:88-92`) are deleted. Two deep links used the preload: the page's
  (`script-resources-page.ts:124-145`) and the reusable field's pencil (`script-resources-field.ts:39,
  183-184`). Both now open the row, or say that the path is not in the database. `GET /rest/script-resources/all` keeps its `@PermitAll` with an
  ADMIN check in code (`:77-86`), because the ticketed Export relies on it. Only the `path` parameter goes.
- `readScriptResourceFromClassPath` is deleted (C4.2a): nothing reads a template from the class path.

### 6.4 Imports (R-CDC3)

The Script resources import (`POST /rest/script-resources/upload-script-resources`,
`ScriptResourceRestService.java:175-178`; class-level `@RolesAllowed(SYSADMIN)`) and the bootstrap (§7) are
the only ways a template reaches a deployment. Built in C4.2b:
- **The preview.** `POST /rest/script-resources/import-preview` (`:156`, SYSADMIN, the file as a multipart
  part, a JSON answer; `ScriptResourceImportService.preview`) only reads. It answers
  `ScriptResourceImportPreviewVo`: `add` (path, type, content, and `listed` when the missing-path report lists
  the path, §6.6), `overwrite` (path, type, the row's content and the file's), `unchanged` (paths) and
  `refused` (path and reason), each sorted by path. A path is compared as the loader and the writer find it,
  case-insensitively (`ScriptResourceService.findByPath`, `lower(path) = lower(:path)`, which
  `createScriptResource` also uses to refuse a case-only duplicate): a file path that differs from a row only
  in case is an overwrite or unchanged of that row, never an add, and two paths of one file that differ only in
  case are a duplicate. Until the review of PR #179 the preview compared exactly; the preview changed, not the
  writer, since an exact writer would add rows the loader cannot tell apart. Each overwrite carries `basedOn`,
  `sha256:` of the row's content as the preview read it.
- **The choice.** The upload keeps its path, its file part and its text answer, and takes two optional fields,
  `add` and `overwrite`, each a JSON array of paths; an `overwrite` element may instead be
  `{"path", "basedOn"}`, and a ticked path whose row's content no longer matches its `basedOn` when the job
  runs is kept and logged (`Kept (changed since the preview) <path>`), while a bare path behaves as before (after
  the review of PR #179); a malformed field is answered 400 before any job starts.
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
- **The batch-set route.** `POST /rest/batch/execute-batch-set` (`BatchRestService.java:304`, SYSADMIN) ran
  any batch set, `script-resource-import`, `dictionary-import` and `settings-import` included, outside the
  preview. A batch set may now name only the jobs of `BatchSetService.BATCH_SET_JOBS`: `domain-import`,
  `category-import`, `param-type-import`, `mailing-list-import`, `report-import`, `promulgation-type-import`,
  `chart-import`, `area-import`, `source-import`, `publication-category-import`, `publication-import`,
  `publication-series-import`, `message-import` and `msg-archive-import`. A set that names any other job is
  refused whole, after its manifest is read and before anything is scheduled, and the text answer ends with
  the refusal. Besides the three imports that leaves out `script-executor`, which has its own logged route
  (`execute-javascript`, §6.5) and has no place in a batch set. The bootstrap runner (§7.2) applies
  `script-resource-import` and `dictionary-import` itself, only on a database whose marker reads `pending` and that
  still holds no data at the claim (§7.2, D4 as amended on 2026-10-24); it
  refuses `settings-import` as well (`BootstrapRunner.ACCEPTED_JOBS`).
- The folder triggers were deleted (D1): the `batch-sets` watcher (`BatchSetService.monitorBatchJobInFolderInitiation`, with `BATCH_SETS_FOLDER` and `executeBatchSetFromArchiveOrFolder`), the `batch-jobs/*/in` watcher (`BatchService.monitorBatchJobInFolderInitiation`, with `getDirectoryFiles`) and the `-Dniord.batch-set` start path. The start path went in C4.1, once `regen-test-seed-postgres.sh` used the runner (§7.2); the two watchers went in C4.2b. The admin app's uploads do not use them: an upload copies its file into the job's execution folder and starts the job directly (`BatchService.startBatchJobWithDataFile`, `:183-204`).

**Release import files.** A release that offers a new or changed template, one the code names included (§6.2), ships it as
`be/niord/bootstrap/releases/<folder>/script-resources.json` (the export format the import reads: a list of
`ScriptResourceVo`, type `FM` or `JS` by extension, path `templates/<relative path>`, content, no id), with a
`README.md` saying what each file is for. A sysadmin decides whether to import it. The base product's texts
are shipped beside it as a dictionary import file, `texts.json` (§4.6).

**How a release folder is named** (settled in C4.2a). The Maven version, `4.0.<run>`, exists only when
`publish.yml` runs, after the merge, so a folder cannot carry it. A release folder is named
`<yyyy-mm-dd>-<topic>` when its change is made, never changes once it is merged into `main`, and the release
notes of the first published version that contains it name it. Every `niord-bootstrap` zip carries all
release folders (§7.1), so a sysadmin who skips versions still finds each. `ReleaseFilesGenerator` refuses
to create a folder that exists, and `ReleaseFilesCurrentTest` checks every folder: the name pattern, the
README's first words (§7.1), and that its files read in their import formats. The same rule holds for a
country's `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` (C6).

**The first release with C4** is `releases/2026-10-21-templates-from-database/` (C4.2a). It holds
`README.md`; `script-resources.json`, all 80 files as they were in the jar, LF line ends; `texts.json`, the
base product's texts at this release; and `missing-paths.sql`, the missing-path report as read-only SQL for
the release notes (§6.6). The three data files are generated (`ReleaseFilesGenerator`, run through
`ReleaseFilesCurrentTest` with `-Dniord.releaseFiles.write=true -Dniord.release=<folder>`). The five zero-byte
placeholders (the four report intros and `mailinglist/safetynet-intro.ftl`) are carried as a one-line
FreeMarker comment (`<#-- introduction: this deployment's text goes here -->`), so they are no longer blank
and the reports and SafetyNet mails that include them render. The bootstrap's own five files in
`bootstrap/templates/` carry the same line, so a fresh deployment renders them too; the release file is
generated from that folder. This closed RENDER1 (C4.2a). Its README describes the import as C4.2b built it
(the preview marks the paths the missing-path report lists; add those and tick no overwrite; every added row
recorded with the sysadmin and `source: import`), so the folder is frozen with a README that is true.

### 6.5 Hardening (from the security review; applies whatever the source)

- **FreeMarker:** a `TemplateClassResolver` admitting only classes directly in `org.niord.core.script.directive` (no sub-package), and
  `setAPIBuiltinEnabled(false)`, in `FmTemplateService` (`:181-187`), in C1. The resolver is the change: none is set today, so `?new` admits any `TemplateModel` class on the class path (FreeMarker's unrestricted default). `setAPIBuiltinEnabled(false)` only pins FreeMarker's default, so `?api` stays off as it is today. Every `?new` in the base product and
  in both country repositories names that package, and none uses `?api` (fact check). A test proves that
  `"freemarker.template.utility.Execute"?new()` fails; it renders the template, because the resolver is
  consulted only when the `?new` is evaluated, and such a template parses cleanly. A second test parses every
  data template in the base product's and both countries' `bootstrap/` and checks every literal `?new` class
  name in it against `DirectiveClassResolver.isDirective`, because a database row that used `?new` on another
  class would stop rendering with no build to catch it. Since C4.2a that is `BootstrapTemplatesParseTest`,
  over the base product's `bootstrap/templates/` and the FreeMarker entries of every release file: each parses
  with `FmTemplateService.newConfiguration`, each literal `?new` class name passes `isDirective` and loads, and
  a `?new` not preceded by a string literal fails the test. The countries' bootstraps joined in C6 (2026-10-22):
  `DkBootstrapFolderTest` and `GlBootstrapFolderTest` run the script check and `BootstrapArchive` over their
  folders, each shown red once with an `Execute` template put in.
- **Scripts:** the import, the bootstrap runner and the kit's backend check refuse a script that uses
  `Packages`, `Java.type`, `Java.extend`, a `java.` or `javax.` package other than `java.util.`, `java.time.`,
  `java.text.` and `java.math.`, `java.lang.Runtime`,
  `ProcessBuilder`, `java.io`/`java.nio.file`, reflection, `eval`, `Function(`, `load(` of anything but a
  `niord:` path (`JsResourceService.java:160-170`), `javax.script`, `jdk.nashorn`, `em` writes, or
  `CdiUtils.getBean` of a type outside the allow-list in `be/docs/country-api.md`. The four `java.` packages
  are allowed because the base product's own `tmpl/object-adrift.js` (`java.util.Date`, `java.util.ArrayList`)
  and DK's and GL's `fetch-firing-exercises.js` (`java.util.Calendar` and others) name `java.util`: refusing
  every `java.` package would make the bootstrap and the first release's file refuse themselves. Built in
  C4.2b as `ScriptResourceCheck` (`org.niord.core.script`), whose public constants the code and its test share:
  `ALLOWED_BEAN_TYPES` (the five types below), `ALLOWED_JAVA_PACKAGES` and `SCRIPT_RULES` (15 rules until the
  review of PR #179, 17 since, each with the reason it reports: `uses <token>: <why>`; besides the list above,
  a `.executeUpdate(` written through any query). The two added after that review: a package under the `org`,
  `com`, `net` or `edu` roots, which the script engine exposes as it does `java`, other than `org.niord.core.`
  and `org.niord.model.` (`ALLOWED_OTHER_PACKAGES`; the bootstrap's scripts and DK's and GL's `dgps.js` and
  `fetch-firing-exercises.js` name those two); and the `java.util` sub-packages `zip`, `jar`, `logging`, `spi`
  and `concurrent` inside the allowed `java.util.` (`REFUSED_JAVA_UTIL_PACKAGES`; no shipped script names a
  `java.util` sub-package). Run once, read-only, over the 12 backend `.js` files of DK's and GL's `main`
  (`a348be0`, `a3b22d0`) and the base product's bootstrap, the two refuse none. The test asserts the known
  evasions as accepted, so that the limit is written down: `Java["type"](…)`, `em["remove"](…)` and string
  concatenation (`'java.' + 'io.File'`, `Java['ty' + 'pe']`). The path rules come first: not blank, at most 255 characters (`ScriptResource.path`),
  no `..` segment, no backslash, no leading `/`, `.ftl` or `.js`. The template check (the parse and the literal
  `?new` check below) moved from `BootstrapTemplatesParseTest` into it, so that test, the import and the
  bootstrap run the same code; the parse resolves no include. The Script resources editor's save is not
  checked: the plan names the import and the bootstrap, and the editor is the sysadmin's own recorded edit.
  `be/docs/country-api.md`, which names the list for a country, is written (C5b, its section *The script
  allow-list*; `CountryApiDocTest` fails when it and `ALLOWED_BEAN_TYPES` drift apart). The country scripts'
  `CdiUtils.getBean(org.niord.core.…​.class)` pattern stays
  allowed through that list. The list names five types: `MessageService` and `NavtexPromulgationService` (the
  base product's `tmpl/common.js:20, 50`), `SafetyNetPromulgationService` (`mailinglist/publish-safetynet.js:19`),
  `AreaService` (DK's and GL's `tmpl/dgps.js:21`) and `MessageSearchService` (DK's
  `mailinglist/fetch-firing-exercises.js:19`). GL's copies of `tmpl/common.js` (`:45`) and
  `fetch-firing-exercises.js` (`:19`, `MessageService`) needed no other type, but both failed on the current
  base product (SCRIPT8): GL's C6 (2026-10-22) dropped the first for the base product's (§9.2) and moved the
  second to GL's `bootstrap/` changed to resolve `MessageSearchService`, which `GlFiringExercisesScriptITTest`
  runs; SCRIPT8 is deleted from `be/docs/potential-errors.md` (C6's base product half). Existing GL databases
  are healed by the overwrites GL's release README names, which is data, not code. **This check is a guard against carelessness, not a boundary.** Scripts keep
  running on `NashornEngines.trusted()` (`JsResourceService.java:137-144`), and string concatenation defeats
  any token list. The boundary is SYSADMIN plus history. Narrowing the engine is outside this plan.
- **Archives:** the bootstrap upload (§7.2) and the import files follow the archive rules of the
  country-split plan's security review: regular files only, no `..`, no symbolic links, size and entry-count caps, an allow-listed layout,
  and `batch-set.json` limited to the import jobs of §7.2.
- **Outside this plan:** `POST /rest/batch/execute-javascript` (`BatchRestService.java:340`) and the
  `script-executor` job run arbitrary JavaScript as SYSADMIN. That is code execution by design. Its folder
  trigger was deleted with the others (D1, C4.2b), and a batch set may not name `script-executor` (§6.4).

### 6.6 Existing deployments after C4

Removing copy-on-use changes one thing for a running deployment: a template path that was never rendered
there has no row and now resolves nowhere. This includes a localized variant the old jar supplied (`x_da.ftl`),
whose absence silently falls back to `x.ftl`.
- **The missing-path report** (`MissingPathReport`, `org.niord.core.script`, since C4.2a). The backend lists
  every path that data names or the code names (`RequiredTemplates`), and every built-in report id
  (`FmReportService.BUILT_IN_REPORT_IDS`), that has no row. The report only reads. It is logged at every start
  where the bootstrap marker reads `existing` or `applied` (a warning with one line per entry, else "no
  missing path"), after the marker's own listener, and is silent on `pending`, `in-progress` and `failed`,
  where the bootstrap brings the rows. It is logged at every such start rather than only at the first start
  of a new version, because knowing that a version is new would need a stored value, a write; the report is
  read only and cheap. `GET /rest/script-resources/missing-paths` (SYSADMIN, the class's role) answers it as
  a list of entries: kind (`TEMPLATE` or `REPORT`), path or report id, and what names it (`code:
  RequiredTemplates`, `Category <id>`, `MailingListTrigger <id>`, `PromulgationType <id>`, `FmReport
  <reportId>`, `include in <path>`, `load in <path>`, `variant of <path>`, and for a report id `code:
  FmReportService.BUILT_IN_REPORT_IDS`).
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
  the class path, so a country's jar adds its own in C6: DK's lists 56 paths, GL's 86 (2026-10-22). A list in the jar was chosen over a release file a
  sysadmin uploads, because the report also runs at start, with no operator.
- **The same query as SQL.** `MissingPathReport.sql(expected paths)` gives one read-only `SELECT` (the same
  regular expressions; the required paths, the expected paths and the report ids as array literals,
  `unnest(CAST(ARRAY[…] AS text[]))`, ordered as the Java answer), which `ReleaseFilesGenerator` writes into
  each release folder as `missing-paths.sql` for the release notes, so a sysadmin can run it read-only before
  the upgrade (`psql -d <database> -f missing-paths.sql`). `MissingPathReportITTest` holds the SQL and the
  Java to the same answer, on the IT fixture and on a changed, rolled-back database; it also checks each kind of
  name, the include resolution, the endpoint (a sysadmin only), that the report writes no row, and that a
  report that cannot be made does not stop a start. Until C4b the seed's report listed DK's two trigger scripts
  (SEED1); since C4b the fixture carries both as the sample's rows and its report is empty, and the endpoint and
  start-log tests plant a missing path of their own (§11 C4b).
- **It never stops a start.** The start-up log runs outside any transaction (`logAtStart`, `NOT_SUPPORTED`),
  so a failed query cannot leave a rollback-only transaction to fail the start; a report that cannot be made
  is logged as a warning that points to the endpoint. The endpoint still answers such a failure as an error.
- The sysadmin imports the listed paths from the release import file (§6.4): the preview marks the paths the
  report lists (`listed`), the sysadmin adds only those and ticks no overwrite. The base product changes no
  row.
- On a running environment, staging included, an import is always the operator's own decision, never a step
  of an upgrade. On DK and GL staging the missing-path report is only read. The first release's import file
  is applied only to local copies of their databases (and of a DK database migrated from the legacy one),
  where it must leave the report empty. Staging deploys are coordinated with the owner (owner, 2026-10-21).
  t-dma, the previous Niord on MySQL, is handled the same way at DK's cutover: its missing-path report read,
  an import the operator's decision.

---

## 7. Bootstrap

### 7.1 Folders

- **`be/niord/bootstrap/` (base product)** is minimal and country-free. It uses the batch-set format the import
  jobs read: `batch-set.json` names the jobs and their data files, as the demo archive
  `be/niord/seed/niord-dev-basedata.zip` did (deleted in C4b) and the sample's archive does. From C4.1 it holds:
  - `domains.json`: the master domain `niord-client-master` (name `Master`, active, no series, no time zone).
    Active since 2026-11-03 (BOOT1, the owner's ruling, decision 138), as the start-up's master domain was before
    C4.1: a base-only deployment's sysadmin reaches the sysadmin pages through it; every fresh deployment, a
    country's included, has it active, unless (from decision 141, the same day) the country's own `domains.json`
    restates it with its `domainId` and `"active": false` only (§7.2, *Before the claim*);
  - `templates/`: all 80 templates and scripts, the 11 the code names included (they are the base product's
    generic templates, offered even where no category uses them yet). The script-resource item of
    `batch-set.json` names the folder, and the runner turns its files into the import's JSON (path
    `templates/<relative path>`, FreeMarker or JavaScript by extension), so no generated file is committed and
    the import's reader is unchanged;
  - `reports.json`: the built-in reports `standard`, `draft` and `public`, as `FmReportService` created them
    until C4.1 (all three on `messages/message-list-pdf.ftl`; `draft` with `draft: true`, `public` with
    `frontPage: false`; no domains);
  - `categories.json`: the seed's 12 categories (`Light` and `Light buoy` with their template children and
    template parameters) without their domain links and without `messageId`, which names a Danish message the
    bootstrap does not carry, and **"Firing Exercises"** from the seed's archive (`categories.json` in
    `niord-dev-basedata.zip`; the dump lacks it). The firing-schedule generator finds that category by its exact
    English name (`CategoryService.getFiringExercisesCategory`) and adds none when it finds none; since C4.1
    `FiringScheduleService.generateFiringAreaMessages` logs a warning, once per run, when no such category
    exists. The bootstrap's README names that English name as load-bearing. The name lookup stays: an MRN would
    have to be written into each running deployment's category. Since C6's base product half the categories are
    English only (their 24 `da` descriptions removed, see the `aton-dict.json` bullet); a country's bootstrap
    adds its languages and its `Category_Domain` links to them within the run, and since the owner's ruling of
    2026-10-24 a value it gives replaces the base's (§7.2, *Within one run*). From 2026-11-04 (the owner's ruling,
    decision 145; CAT45) the bootstrap also carries the other 32 categories, under `Buoy`, `Beacon`,
    `Obstruction`, `Drifting object` and `Radio navigation`, in English, without domain links and without
    `messageId`, with production's sort orders, AtoN filters and scripts; `Radio navigation`/`DGPS` comes without
    its scripts and its template parameter (`tmpl/dgps.ftl`, `tmpl/dgps.js` and the `dgps` type are DK's and GL's
    data). A country completes their names and links as it does for the 13; only fresh databases get them (§7.3);
  - `parameter-types.json`: the parameter types that are not standard (the 7 standard ones are code, §6.2;
    `BootstrapFolderTest` asserts that none is in the file): 34 from C4.1, 32 since the review of PR #179,
    which removed the composite `dgps` and the list `dgps_station_list` under C5's done-when (niord-base names
    no country): they list Danish and Faroese DGPS stations and no base product category or template uses
    them. DK's and GL's bootstraps carry them from C6; the P9 sample carries them in its own archive
    (`be/niord/sample/archive/parameter-types.json`, and the four stations in `aton-dict.json`; `--demo` kept
    them from `niord-dev-basedata.zip` until P9);
  - `promulgation-types.json`: `NAVTEX` and `Twitter`, and since P7-be (#206) `Audio` (English, priority 50,
    after `Twitter`; country-split plan §6 P7: the owner moved audio promulgation into the base product on
    2026-10-24; until P7 `Audio` was DK's and GL's),
    without domain links: the
    base product's bootstrap has only the master domain, and DK's bootstrap adds the links. Since the review of
    PR #179 without `promulgateByDefault`, which `PromulgationTypeVo` and the niord-ng schema do not have (the
    legacy MySQL column was not carried over);
  - `aton-dict.json`: the `aton` data dictionary, 115 entries from C4.1, 111 since the review of PR #179, which
    removed the four `radio_navigation.dgps.*` station entries (Skagen, Blåvand, Hammer Odde, Tórshavn) that
    only the two DGPS types used; DK's and GL's `dgps.js` name all four, and DK's and GL's bootstraps carry
    them from C6 (GL's with their `kl` descriptions; list values stay in `template`, D5). Until C6 the base
    product bootstrap carried 170 Danish (`da`) descriptions (111 of the `aton` entries, 24 of the categories,
    35 of the template parameters of 13 parameter types; the other 6 of the earlier 176 went with the DGPS data
    to DK's bootstrap: 4 `aton` entries, 2 `dgps` template parameters), although R1 makes the base product
    English only: §7.2's bootstrap imports added only what was missing and overwrote nothing, so a country's
    bootstrap applied after the base's could not add its language to a key the base created. C6's base product
    half (2026-10-22) added the rule that lets a later archive of the same run add its language to what an
    earlier archive created (§7.2, *Within one run*) and removed the 170: the base bootstrap is English only
    (`aton-dict.json` 111, `categories.json` 24 = 13 category names and 11 template-parameter names,
    `parameter-types.json` 35; `BootstrapFolderTest.theDataIsInEnglishOnly`). DK's bootstrap carries the 111
    `aton`, the 35 parameter-type and, since its bump to 4.0.87 (P5e, `niord-dk` #6 on `feature/p5-dk-moves`,
    2026-10-23; its PR into `main`, #7, merged 2026-10-24), the 24 category descriptions with equal values, the
    types and categories as additions. GL's carries, since its bump to 4.0.87 (P6e, `niord-gl` #7 on
    `feature/p6-gl-moves`, 2026-10-24; its PR into `main`, #8, merged 2026-10-24), the `da` and `kl` of the 111
    `aton` keys, of the 24 category names (13 categories, 11 template parameters) and of the 35 base template
    parameters (and `dgps`'s 2), the types and categories as additions, the `da` equal to DK's (§11 C6);
  - since C4.2a, `texts.json`: the base product's texts as a dictionary import file (§4.6), generated and
    current-checked; `batch-set.json` does not name it, so the bootstrap never applies it (start-up adds the
    texts), and a sysadmin imports it to restore a key;
  - since C4.2a, `releases/`: the release import folders (§6.4).

  It holds no settings (they come from the settings files at start-up) and no messages.
- **`be/bootstrap/` in `niord-dk` and `niord-gl`** holds the country's domains and series, reports, promulgation
  types (DK: `Audio`, and the base types' domain links; GL: its `Audio` in Greenlandic, `Navtex GL`, and
  `NAVTEX`'s link to `niord-gl-nw`; since P7-be (#206) the base product's bootstrap carries `Audio`, and DK's and
  GL's `Audio` rows restate it since their bumps onto P7's version, 4.0.97 (`niord-dk` #10, `niord-gl` #9,
  2026-10-25), country-split plan §6 P7, its bump records in P5 and P6; within the first bootstrap
  a later archive's values replace the base's (§7.2, *Within one run*, the owner's ruling of 2026-10-24), so on a
  fresh database DK's `Audio` is `da` with its `niord-nw` link and GL's is `kl` with priority 10 and requirement
  `MANDATORY`), its parameter types and data dictionaries, and its templates and scripts
  (the shadows of base templates, its own, and the scripts its mailing lists name). It is applied after the
  base product's in the same run. On a 4.0.76 base, whose bootstrap script-resource and dictionary imports
  only add, its shadows of base templates and its languages on base keys were not applied on a fresh database
  (DK: 0 of 42 shadows, GL: 0 of 46, and GL's 111 `kl` `aton` descriptions, C6's checks). From the base
  product version with C6's base half they apply: a later archive of the run replaces a template and adds
  languages and domain links to what the base archive created, and from P7-bs its values replace the base's
  (§7.2, *Within one run*), so a country's
  bootstrap can also carry its categories' languages and `Category_Domain` links as additions. DK's bump to
  4.0.87 (P5e, `niord-dk` #6) measured 42 of 42 (`DkBootstrapITTest` asserts it) and added its
  `categories.json`. GL's bump to 4.0.87 (P6e, `niord-gl` #7) measured 46 of 46 (`GlBootstrapITTest` asserts
  it) and added its `categories.json`. Mailing lists
  are not in it: they are sample data (§7.3). GL's (C6, 2026-10-22) holds one domain, `niord-gl-nw`, with its
  series `ako-nw`, and no reports (§11 C6, owner question on a fresh GL deployment). It restated the base's 13
  composite parameter types with GL's `kl` template-parameter names until its bump to 4.0.87, which turned them
  into additions carrying `da` and `kl`; GL restates no base entry since. DK production arrives as a migrated
  legacy database, which the marker reads as `existing`, so DK's `bootstrap/` serves fresh DK environments only.
  Publications and development mailing lists go to the sample, never to a bootstrap.
- **`releases/<yyyy-mm-dd>-<topic>/`** inside each bootstrap folder: `be/niord/bootstrap/releases/<folder>/` in the base product, `be/bootstrap/releases/<folder>/` in a country, named as §6.4 says. The base product's first is `releases/2026-10-21-templates-from-database/` (C4.2a), whose README starts with the same first words as the folder's.
- **Every one of these folders carries a `README.md`** — each bootstrap folder and each release folder
  `releases/<folder>/`, never `releases/` itself, since the bootstrap archive (`BootstrapArchive`) accepts under
  `releases/` only a release folder's `*.json`, `*.md` and `*.sql`; the country kit's `init` writes
  `be/bootstrap/README.md` only, which also says what a release folder holds (until the kit's half of country-split
  P5, P5k, it also wrote `be/bootstrap/releases/README.md`, which failed DK's bootstrap folder test and bootstrap
  zip) — and so does the sample's (§8), saying in its first lines
  what the files are and are not: *"Data, not code. Loaded only into a fresh deployment (bootstrap), only by a
  sysadmin's import (releases), or only into the demo database (sample). A running deployment's data may differ
  from these files and is the truth; nothing here ever changes it."* A CI check fails if the README is missing.
  In the base product the check is `BootstrapFolderTest`, a unit test over the folder (the README's first
  words and the English name "Firing Exercises", every job of `batch-set.json` in the runner's allow-list and
  every data file present, the 80 templates, no standard parameter type, no settings or message file; since
  C4.2a also `texts.json`, the five placeholders non-blank, the 11 of `RequiredTemplates`, and in `releases/`
  only well-named folders, each with its README). Backend
  CI re-includes `be/niord/bootstrap/**` after its Markdown exclusion, so a change to the README alone, or its
  deletion, runs the test.
- Never in `src/main/resources`, a jar or an image; a CI check fails otherwise. From C4.1 until C4.2a the
  templates reached the niord-base jar through the temporary mapping (§6.3); since C4.2a
  `CoreResourcePackagingTest` checks that no template, nor `bootstrap/`, its JSON, `texts.json` or `releases/`,
  is in the jar. Since C4.2a the base product publishes its bootstrap as
  `org.niord:niord-bootstrap:<version>:zip` beside `niord-base`. The reactor module `be/niord-bootstrap`
  (packaging `pom`, no sources) zips `be/niord/bootstrap/` as it is with the assembly plugin
  (`src/assembly/bootstrap.xml`) and attaches the zip as type `zip` with no classifier (build-helper);
  `publish.yml`'s existing deploy step deploys it with `:niord-bootstrap` added to its `-pl`, beside
  `niord-parent` and `niord-base` under the same `4.0.<run>`. Attaching the zip to `niord-base` instead would
  have needed no workflow change, but would have published `org.niord.base:niord-base:<v>:zip:bootstrap`, not
  these coordinates. `start-local.sh` and `regen-test-seed-postgres.sh` keep zipping the folder locally with
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
  `existing` otherwise; it is written once and never re-decided (its predicate is unchanged by the owner's ruling
  of 2026-10-24: the claim checks the whole database again, below). Every running deployment, and every seeded database, reads `existing` from then on.
  Until C4.1, `BootstrapService` created the master domain at start, so a database created by an earlier
  version also reads `existing`. Such an environment is recreated empty if it should be bootstrapped (D4). The bootstrap itself (several
  thousand rows) takes 5–20 s; `bootstrap.sh` waits for it, so an ingress timeout cannot cut it short.
- **The runner.** A Spring bean, `BootstrapRunner`, not transactional, starts the existing import jobs through
  JBeret, one at a time. It accepts only these jobs, in this fixed order: `script-resource-import`,
  `domain-import`, `dictionary-import`, `category-import`, `param-type-import`, `mailing-list-import`,
  `report-import`, `promulgation-type-import`, then the rest in the order of `batch-set.json`: `chart-import`,
  `area-import`, `source-import`, `publication-category-import` and `publication-import`, which the demo
  archive needs (§7.4). It refuses `settings-import`, `script-executor`, `message-import`,
  `msg-archive-import` and `publication-series-import`: no settings, no messages, no code (§7.1, §6.5). It
  reads and checks every archive of the run before it writes anything (`BootstrapArchive`, §6.5). Since C4.2b
  that check includes the script check (`ScriptResourceCheck`, §6.5) on every `templates/` file, so an archive
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
  the run (after the review of PR #179; until then the runner returned at the deadline and the run read `failed`
  while the execution still ran). The batch set's `delay`s are not
  read. It moves the marker `pending` → `in-progress` with the run's hash (committed before the first job
  starts) → `applied` (after the last has ended), or to `failed` with the job, its file and its status in
  `error`. While it runs it touches the marker's `updated` every 30 s (`BootstrapMarker.HEARTBEAT`), also while
  it waits for a job past its deadline.
- **The claim: two guards** (D4 as amended by the owner on 2026-10-24; built in P7-bs). A bootstrap runs once, on
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
    refuse), and, since the review of #204, the queued mails, `ScheduledMail` and `ScheduledMailRecipient` (the
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
    configuration fault, fixed before the database is recreated (`be/k8s/README.md` step 4). Until the review of
    #204 the queued mails counted as data, recorded as not verified and as the safe direction.
  - **The refusals** (409, `BootstrapRefusedException.STATE`, or `DATA` over data; the state in quotes): a marker
    not written yet ("the backend is still starting; ask again in a moment"); `existing` or `applied` ("this
    database is deployed; a bootstrap never runs on it"); `failed`, its error quoted ("a failed bootstrap is never
    run again on the same database: recreate it empty, start the backend, bootstrap again with the same archives
    (D4)"); an `in-progress` with no sign of life for 5 minutes (`STALE_AFTER`: "a bootstrap broke off", and the
    same remedy); a live `in-progress` ("a bootstrap is running; wait, GET /rest/bootstrap shows its outcome"); and
    a `pending` marker over data ("The database holds data (a row in "<table>"): a bootstrap runs only on a new,
    empty database", and the remedy). A `pending` marker the claim's `UPDATE` did not move (another claim changed
    it meanwhile, an internal race) answers "The claim did not take effect although the marker reads 'pending'
    (another claim changed it meanwhile); ask again" (409, `STATE`; since the review of #204, which found the
    default arm worded for it). `failed` and `in-progress` are final: nothing claims them, so there is no
    in-place retry and no takeover (*No retry*, below). `STALE_AFTER` and the heartbeat now only tell a live run
    from one that broke off in that text. No schema change: V007's `CHECK` keeps the five states, and its comment
    stays stale, since its checksum is pinned. `BootstrapMarker.init`'s predicate is unchanged.
  - **Before the claim**, besides the archive rules, the runner refuses (400, `ARCHIVE`, naming the domain and both
    archives) a later archive whose `domains.json` gives a `domainId` an earlier archive of the run gives
    (`BootstrapArchive.domainIds()`, read leniently with the typed read's Jackson-2 defaults, so content after the
    root array is read as the domain import reads it, since the review of #204: a file that is not a JSON list of
    objects gives none, and the domain import fails on it later). The domain import keeps an existing domain, so the later archive's values
    would silently not apply (*Within one run*). Neither the base product's, the sample's, DK's nor GL's archive
    restates one. From the E-fixes (the owner's ruling of 2026-11-03, decision 141): a later archive may restate an
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
    endpoint that the ruling brought, and it is limited to bootstrap jobs; it is named here, and in the PR, for the
    owner's review.** The owner ruled on 2026-11-03 (decision 138) that it stays. Its reach is small: JBeret refuses a `COMPLETED` execution's restart anyway, so in practice it
    covers a failed bootstrap job in the JVM that ran it, on a database that is recreated in any case. JBeret SE's
    in-memory repository is in use (`BatchConfig.java:49` builds a plain `BatchSEEnvironment` and `:63` passes its
    `getJobRepository()` through; there is no `jberet.properties` under `be/`; BATCH4 in
    `be/docs/potential-errors.md`), so after a backend restart the old execution is unknown and a restart answers as
    it did before (`NoSuchJobExecutionException`): the 409 fires only in the JVM that ran the bootstrap job. The
    batch page shows the 409's reason in its toast (`admin.batch.toast.restartRefused`, since the review of #204).
  - **Not added** (P7-bs's decision 15): refusing a sysadmin's imports while the marker reads `in-progress`, which
    would change running-system endpoints. The residual: a sysadmin or a `psql` session writing during the 5–20 s
    of a run, after the claim's check, on a database that has no domain roles yet; and a database whose only data
    sits in the not-data tables (settings and default texts edited in the administration app, users), which the
    claim accepts and the bootstrap fills with base rows without touching theirs (it imports no settings, and of the
    dictionaries only `aton-dict.json`).
  - **History.** Until the review of PR #179 an `in-progress` marker was refused for good and could be left only by
    hand SQL. That review added the takeover of a run with no sign of life for 5 minutes, and the review of PR #188
    (2026-10-22) accepted a retry of `failed`, and the takeover, for a run of one archive only
    (`BootstrapMarker.claim(hash, retry)`, `retryable()` and `RETRYABLE`; *No retry*, below). The owner's ruling of
    2026-10-24 removed both, and the guard is two checks instead of the marker alone.
- **The imports in a bootstrap.** The runner passes the import jobs no `add` or `overwrite` (§6.4), so since
  C4.2b `script-resource-import` and `dictionary-import` add every missing row and overwrite none: on a fresh
  database that is everything. Within one run a later archive's values replace what an earlier archive of the run
  created (*Within one run*, below), so in the sample's regeneration (since P9; `--demo` until then) the sample's
  `aton-dict.json`, applied as the second archive of the run, adds the keys and languages the base product's
  archive lacks and gives the base product's values where both have one (equal, so nothing is replaced, §8). A
  batch-set item of an archive may not carry the properties `add` or
  `overwrite`: they are a sysadmin's ticks after a preview, and the bootstrap adds what is missing and
  overwrites nothing, except within one run as the next bullet says; such an archive is answered 400 naming the
  archive, the item and the property, before the claim (after the review of PR #179; neither the base
  product's archive, the demo archive nor the sample's archive carries item properties). Since C6 the same holds for the property
  `bootstrapRun`, the runner's own token. The guards are tested negatively. In P7-bs 8 failures were shown red on
  the old code through a temporary IT on the old API, deleted afterwards, and the runner's failed, stale, apply and
  domain cases, `GivenTest`, the reader cases and the rest are compile-red by construction: they use
  `BootstrapMarker.Claim`, `dataTables()`, `firstDataTable()` and `BootstrapRefusedException.DATA`, none of which
  exists on `25f75068` (§11 C6, _Verification_). The negative tests: `BootstrapRunnerTest` (the deadline, the
  interruption and a marker write that fails while it waits; `aFailedMarkerIsRefusedWithOneArchiveOrMore`,
  `anInProgressMarkerIsRefusedLiveOrStale`, `aDeployedMarkerIsRefused`,
  `aPendingMarkerOverDataIsRefusedNamingTheTable`, `aMarkerNotWrittenYetIsRefused`,
  `aPendingMarkerTheClaimDidNotMoveOnIsRefusedAsARace` (since the review of #204),
  `aLaterArchiveRestatingADomainIdIsRefusedBeforeTheMarkerIsClaimed` and
  `applyRefusesAMarkerThisRunDidNotClaim`; beside them the positive cases `aPendingMarkerIsClaimedWithTwoArchives`
  and `aDomainFileThatIsNotJsonGivesNoDomain`); `BootstrapITTest` Order 4
  (`inProgressIsRefused`; `aPendingMarkerOverDataIsRefusedNamingTheTable`: a committed row in `ScriptResource`,
  `Domain`, `Message` or `BatchData`, or an `aton` dictionary with an entry, makes the claim and a `POST` through
  the command answer 409 naming that table, `Dictionary` for the last, since a non-default entry needs a
  non-default dictionary row, and the marker is left as it was; a `web` entry and the start-up settings pass, and
  `User` is never probed; a queued `ScheduledMail` with a recipient passes, and with a `Domain` row beside it the
  claim is refused naming `Domain` (since the review of #204); `anInProgressMarkerIsRefusedLiveOrStale`: an `in-progress` touched 6 minutes ago refused
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
- **Within one run** (C6's base product half, 2026-10-22, the language rule decided in #179's body and the
  template rule by the owner on 2026-10-22, "Yes, widen the rule"; widened to every value by the owner's ruling of
  2026-10-24, D4 as amended, built in P7-bs). A bootstrap is the one first-time deployment of a new environment: one
  run that applies the base product's archive first and then the country's (or, in the sample's regeneration, the
  sample's). Inside that run the later archive takes precedence. For every entry it names that an earlier archive of
  the same run created, each value it gives replaces the earlier archive's (a template, a name, a description in a
  language, a priority, a flag, a template parameter's settings, a list), and a value the earlier archive left empty
  is filled. A value the later archive leaves out keeps the earlier archive's. "Gives" means the field is in the
  file and is not null (the owner's decision of 2026-10-25, on the review of #204): a list given explicitly, even
  empty, replaces the earlier archive's (a category's standard fields, editor fields and script paths, a
  promulgation type's message types and script paths, a list type's values), so a country's bootstrap can empty a
  base list; a field left out or null keeps the earlier archive's;
  an empty string counts as left out (a required text cannot be blank), and so does an empty object, so a
  bootstrap cannot clear a base text; the administration app can do that afterwards. Template parameters and
  domains stay merged and unioned, so an explicit `[]` there adds and removes nothing, and a report's properties
  and parameters are maps merged by key, not lists, so an empty map adds nothing. No archive of the sample's, DK's
  `main` (`d6fa491`) or GL's `main` (`e866206`) gives an explicit empty list over a non-empty base list (checked
  with a script over all three: the sample gives `[]` for categories' standard fields and script paths only where
  the base list is empty too, and DK and GL give no explicit `[]` of a replaced list at all), so no regeneration
  changes and no country's bootstrap does. Until that decision "gives" also excluded an empty list. What is new (an
  entry, a language, a template parameter, a domain link, a report's property key) is added, and nothing is
  removed. Rows from before the run are never touched: under the claim's guards (above) the only such rows are
  start-up's own settings and default texts, and they keep start-up's rules; a default-text key a later archive
  restates with other values is kept, and the job log says so (*The job log*, below). The rule applies only to the
  jobs of the live bootstrap run. Nothing else changes: start-up
  still adds only the settings and text keys a deployment never had, a release folder and every sysadmin import
  still go through the import with its preview and ticks, and there is still no reload and no reset. Until the
  ruling the rule replaced templates and added languages and domain links only, and a differing value was kept
  and logged. As built:
  - **The key** is creation by an earlier archive of the same attempt, not table emptiness. `BootstrapRun`
    (`org.niord.core.bootstrap`) holds the one live attempt in memory: `BootstrapRunner.apply` begins it before
    the archive loop and ends it in a `finally`, and puts the token `bootstrapRun=<run id>/<archive index>` into
    every job's properties after the item's own, so an item cannot set it. The import handlers record what
    each archive creates and ask whether an earlier archive of the live run created an entry. The token is
    written into the run's own jobs' `BatchData` rows; since the review of PR #188 the batch instances endpoint
    (`GET /rest/batch/{job}/instances`, `BatchService.getJobInstances`) leaves it out of the properties it answers
    (a copy; the row keeps it), an upload carrying it (a form field `bootstrapRun` on any endpoint of
    `AbstractBatchableRestService`, the six import uploads among them) is answered 400 before any job starts, and
    a batch set whose item carries it is refused whole before anything is scheduled (`BatchSetService.checkJobs`),
    as an archive item carrying it is refused like `add` and `overwrite` (above). So a token that names no live
    run, or any sysadmin import, applies no rule (before that review a token copied from a live run's instances
    could be posted with an upload or a batch set). There is no new marker state and no schema change.
  - **What the later archive gives** (`org.niord.core.bootstrap.Given`). The VO-to-entity conversions lose whether
    a field was in the file (a category's active flag defaults to true and its type to `CATEGORY`, a promulgation
    type's priority is an `int` and its active flag turns null into false, a template parameter's flags default to
    false), so the three readers that need it (`BatchCategoryImportReader`, `BatchParamTypeImportReader`,
    `BatchPromulgationTypeImportReader`) read the file a second time as a JSON tree, with the typed read's own
    Jackson-2 defaults (`JsonMapper.builderWithJackson2Defaults()`, since the review of #204, so the two reads
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
    - **A template**, by path, case-insensitively (`findByPath`): replaced (unchanged by the ruling), its history
      row source `import` with the sysadmin who ran the bootstrap, as every bootstrap write (§6.4), the job log
      saying "Replaced (an earlier archive of this bootstrap run created it)", counted as overwritten.
    - **A dictionary entry**, by dictionary and key (`DictionaryService.replaceLanguages`): a language the row has
      (compared ignoring case, as `getDesc` does) takes the later value, and its long value when the later archive
      gives a non-empty one; a language the row lacks is added with its long value; a language left out is kept,
      and a language whose value is empty counts as left out (it neither clears a value nor adds a language; since
      the review of P7-bs); an AtoN filter given non-empty replaces the row's. `DictionaryEntryDesc` rows are updated in place, never
      through `updateDictionaryEntry` or `copyDesc` (which would clear the base AtoN filters). The import's result
      counts the keys whose values changed (`ImportResult.replacedInRun`, which replaced `keptInRun`) and the keys
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
      archive restating a `domainId` an earlier archive gives is refused before the claim (400, above). From
      2026-11-03 (decision 141) its active flag is the one exception: a later archive's row with only the
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
    created it, not this bootstrap run: start-up's rules apply)" (since the review of #204; only for a later
    archive of the live run, a key no archive of the run created, and values that differ; a key restated equal is
    counted unchanged, with no line). The category's line comes once per category per job (since the review of PR #188; until
    then the category's and the parameter type's went to the server log, and the promulgation type's and report's
    differing values to none).
  - **Not covered by the rule.** A category that a `domains.json` creates (a domain's own categories, through the
    domain import) is not recorded, so a later archive's category import updates it whole, as before; the base
    product's `domains.json` creates none. A new active category under an inactive one an earlier archive created
    makes that one active (`createCategory` calls `TreeBaseEntity.updateActiveFlag`, up the chain), and the job
    log says so for each such category (since the review of PR #188; the child is not created inactive, which
    would change the country's own new row against its file). The other direction: a later archive's
    `active: false` on a category an earlier archive created makes its active descendants inactive too
    (`TreeBaseEntity.updateActiveFlag`, written through the cascade), rows the later archive did not name, and the
    parent's job-log line names them ("made inactive with it: Category <id> '<name>' …
    (TreeBaseEntity.updateActiveFlag; not covered by the rule)", since the review of #204; a later `active: true`
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
    outside a run keeps today's semantics: a sysadmin's release import keeps its preview, its ticks and its
    counts. DK's and GL's archives built on 4.0.76 restated 13 composite types and `NAVTEX` (DK also `Twitter`)
    whole, equal to the base's apart from languages and domains; both turned them into additions at their bump to
    4.0.87 (DK in P5e, `niord-dk` #6 on `feature/p5-dk-moves`, 2026-10-23, its PR into `main`, #7, merged
    2026-10-24; GL in P6e, `niord-gl` #7 on `feature/p6-gl-moves`, 2026-10-24). From P7 each restates the base
    product's `Audio`, whose values then apply (§7.1).
  - **No retry** (owner, 2026-10-24). A failed bootstrap stays failed for good, and so does one that broke off
    (`in-progress`): posting again is refused whatever the number of archives, and a restart of one of its jobs
    from the batch page is refused too (above). After a failed or broken-off bootstrap the operator recreates the
    database empty (locally `./start-local.sh --sample --reset`, or `dropdb` of the database; on a staging or test
    cluster a human drops it and the Helm databases Job recreates it, `be/k8s/README.md` step 4; production is not
    covered), starts the backend, and runs the same bootstrap command with the same archives. The registry of what
    each archive created is per attempt (`BootstrapRun.begin()` clears it), so a second attempt on the same
    database would apply a later archive's entries to the first attempt's rows by the imports' whole-update rules;
    on a fresh database a recreate costs nothing. History: from the review of PR #188 (2026-10-22, the owner
    having delegated the choice) until the ruling, the reviewer's option 2 was built: a retry of a failed run, or
    the takeover of one that broke off, was accepted with one archive (the rule cannot apply with one) with a
    warning, and refused with more before the claim (409), naming an empty database (D4); it served a deployment
    whose bootstrap is that one archive. The reviewer's option 1 (recording, for the run's archives, every row the
    import finds and updates too, so a retry behaves like a clean run) was not taken, because a recreate costs
    nothing and option 1 would have changed what "created in this run" means for every import. Until that review
    a retry was called safe because "the writers upsert".
  - **`--demo`, then the sample.** From C6's base half the demo archive added its `da` descriptions and its
    domain links to the base entries, and no longer set the `messageId` of the 9 base categories that carry one
    in it (NW-231-16, NW-003-17, NW-306-16, NW-155-16, NW-179-16, NW-089-16, NW-037-16, NW-251-16, NW-260-16),
    messages the demo database lacked. Since P9 the sample's archive (`be/niord/sample/archive/`), applied as
    the second archive of the regeneration's run, adds the `da` descriptions and the domain links the same way;
    its `categories.json` carries no `messageId` at all (the 26 of the demo archive removed, the 9 on base
    categories among them). Under the ruling its values would replace the base's too, but it restates them equal
    (its `Audio` `en` and 50, its list types' values), since `regen-sample.sh` refuses a changed base row (§8); the
    regeneration after P7-bs is byte for byte the one before. `regen-test-seed-postgres.sh` applied the demo
    archive as its second archive until C4b; since C4b it applies the base product's archive alone and loads the
    sample after it with `load-sample.sh` (§7.4), so the fixture holds these categories as the sample has them.
  - **Tests.** `BootstrapRunTest` (the registry; the `'\0'` separator of `dictionaryKey`, so two pairs never share
    a key; `anAdditionsLineSaysWhatWasAddedReplacedAndKept`), `BootstrapRunnerTest` (the token per archive, an
    item's property never overriding it, the run ended after `applied`, `failed` and an exception; the claim's
    refusals above), `BootstrapArchiveTest` (the `bootstrapRun` refusal; since the review of #204 a `domains.json`
    with content after its root still gives its domains), `GivenTest` (left out, given at the import's default
    value, given at another value, explicit null, empty string and object, an empty list given, a repeated
    `paramId`; since the review of #204 also a parameter without a usable `paramId`, which gives no path and moves
    no `#n`, a `descs` that is not a list, and content after the root read as the typed read reads it),
    `BootstrapRunTest` also tests `Scope.createdBy` (since the review of #204), the three readers' tests (no token and the ended run's token bind the same entries with no
    `Given`, a live archive-0 job none, a live archive-1 job one on every entry and parent),
    `TemplateParamsReplaceTest` (the matching, a given flag replacing and a left-out one kept, a new `paramId`
    appended, equal input changing nothing and keeping the same `TemplateParam` instances, and the kind change,
    which a failing second run cannot show under the claim's guards), `BatchPromulgationTypeImportWriterTest` (no
    token, or the ended run's, updates whole and never calls `replaceValues`) and `BootstrapSameRunITTest`, which
    runs one bootstrap with two fixture archives in base product terms (`bootstrap-fixtures/same-run-earlier/` and
    `same-run-later/`, whose READMEs name every deliberate entry). Since P7-bs it has no rows from before the run
    (its six pre-run entries and test 7 removed: such a run is refused by the claim, and that coverage is the
    claim's refusals and tests 9 and 10): test 1 the run; 2 the template replaced; 3 the languages added (`k2`
    `{en: two, da: TO}`, and `it_composite`'s new `p3` appended, `[p1, p2, p1, p3]`); 4
    `aValueTheLaterArchiveGivesReplacesTheEarlierOne` (`k1` `ONE` with its long value, `k2`'s `da` `TO`, `k7`'s
    value replaced with its long value and AtoN filter kept, `k8`'s filter replaced, `k9` kept `{en: nine}` though
    the later archive gives an empty `en` and a new empty `da`, `p1`'s `en` `FIRST`, the
    root's `fr` `RACINE` with its `de` `Wurzel`, which the later archive leaves out, kept); 5 the domain links a
    union; 6 `theLaterArchivesFieldsReplaceAndNothingIsRemoved` (sort order, priority, report name and merged
    properties, script paths, a list type's values, `It flags`' given and left-out flags, `It deactivated`,
    `it_mandatory` keeping its mandatory flag and type, `it-domains-only` keeping every value, `it-flags` inactive
    with priority 0 and `MANDATORY` filled, and `It equal`, whose version and `TemplateParam` id equal those of its
    twin `It earlier only`, which only the earlier archive names, so an equal entry writes nothing; since the
    review of #204 also a value that differs for every replace branch: `it-changes`' name, `serviceId`,
    language, message types, script paths and requirement, `It changes`' type, AtoN filter, editor and standard
    fields and its MRN filled, `It mrn`'s other MRN kept, `it-report-changes`' sort order, template path and a
    `params` key, the type of `it_typed`'s parameter `t1`; the explicitly emptied lists of `it-emptied`, `It emptied` (its standard
    fields left out, kept) and `it_list_emptied`; `It inactive kept`, inactive, whose restatement leaves `active`
    out; `It parent`, whose `active: false` makes `It active child` inactive; and `It inactive parent`,
    inactive with its inactive child `It activated child`, which the later archive makes active under a parent
    entry that leaves `active` out, so the parent is made active too); 8 the token on
    every job; 9 and 10 extended: after the run a sysadmin's import, and a job carrying the ended run's token, of a
    run-created category, parameter type, promulgation type and report give the whole-update outcome (a left-out
    list cleared) and no "replaced" line, a release import's preview and ticks unchanged; and 11,
    `theRunsJobLogsSayWhatWasAddedAndReplaced`, which reads the later archive's own job logs (the `BatchData` rows
    whose token names the run) and pins each line as built (since the review of #204 also `It activated child`'s
    "made active with it" note and its parent's "nothing to add"). `BootstrapITTest` Order 6 asserts that every
    `ScriptResourceHistory` row the bootstrap wrote through the command names the sysadmin. The token's refusals:
    `AbstractBatchableRestServiceTest` (unit: an upload with the field answered 400, no job started),
    `BatchUploadRestServiceITTest` (400 on a chart upload, no `BatchData` row), `BatchSetServiceITTest` (the set
    refused whole, nothing scheduled) and `BatchServiceGapITTest` (the instances answer leaves the token out, the
    row keeps it).
- **The command** `POST /rest/bootstrap` (`BootstrapRestService`, SYSADMIN) is a multipart upload of one or more
  parts named `archive`, applied in the order sent: the base product's archive first, then a country's (or, in
  the sample's regeneration, the sample's archive; the demo archive until P9). It applies the archive rules of §6.5; in C4.1 an archive holds at most 2,000
  entries, 16 MB an entry and 64 MB in all, counted while copying, in the layout `batch-set.json`, `README.md`,
  root-level `*.json` and `templates/**/*.ftl` or `*.js`; the directory entries inside `templates/` that the
  JDK's `jar` tool writes for a zipped folder are skipped, and any other folder is refused. Since C4.2a the
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
  `POST` answer 503, "the backend is still starting", and the `POST` refuses before it reads a part (since the
  review of PR #192): a flag, `StartUpState`, is set on Spring Boot's readiness event `ACCEPTING_TRAFFIC`, which
  is published right after the `ApplicationReadyEvent` listeners have returned. An ordered listener on that
  event would not do, since an unordered one has the lowest precedence too. A caller asks again:
  `start-local.sh` and `regen-sample.sh` for up to 120 s, and `bootstrap.sh` posts again every 3 s for up to
  120 s. The bootstrap's imports thus never interleave with the start-up's own writes.
  The upload is bound by Spring Boot's multipart limits first, 1 MB a
  part and 10 MB a request, since nothing sets `spring.servlet.multipart.max-file-size` or `max-request-size`;
  the archive caps above (16 MB an entry, 64 MB in all) are reached only where a deployment raises both. The base
  product's archive, the demo archive (until C4b deleted it) and the sample's archive are well below them. A larger upload is answered 413, which
  `bootstrap.sh` reports naming the two properties to raise. A raise for every upload endpoint is recorded, to be
  measured, as UPLOAD-1 in `be/docs/potential-errors.md`. `be/scripts/bootstrap.sh <base-url>
  <archives…>` wraps both with `curl`: it takes the token from `NIORD_TOKEN`, never from an argument, gives it to `curl` on standard input
  (`curl -K -`) so that no process's arguments carry it, posts the
  archives and polls `GET` until `applied` or `failed`, printing the runner's note when it changes while the
  marker reads `in-progress`. The in-process callers, with no token, are the
  integration tests and `regen-test-seed-postgres.sh`, which starts `org.niord.test.SeedRegen`: a main in the
  test sources that starts the test application, runs the runner on its archives and exits with the outcome
  (since C4b the script passes one, the base product's, and loads the sample afterwards with its own loader).
  No application context starts it, so a deployment has no property-triggered start path (D1). They take the
  same JBeret path and its waits.
- **Who calls it.** On an empty database no domain exists, so domain roles are not granted
  (`NiordDomainFilter`). Base-client roles come from `resource_access.niord-client-master` without a database
  lookup (`SecurityConfig.java:99-107`). The precondition is that the realm has the `niord-client-master`
  client with the sysadmin role. Locally, `start-local.sh` runs `bootstrap.sh` with the local realm's sysadmin
  when the marker reads `pending`, and only then (since P7-bs). On `failed` or `in-progress` it prints the state
  and the remedy and posts nothing: under `--sample` `./start-local.sh --sample --reset`; otherwise stop the
  backend, then `docker exec niord-db dropdb -U niord $NIORD_DB`, and the next `./start-local.sh` creates the
  database empty and bootstraps it. When its own post fails, it reads the marker once more, with a new token (the
  first may have expired during the run), and names the remedy by state (since the review of P7-bs): on `failed`
  the recreation; on `in-progress` (`bootstrap.sh`'s wait ran out, or a backend of another run claimed the marker
  first) that a bootstrap is still running or broke off, to wait until `GET /rest/bootstrap` shows its outcome, and
  the recreation only once it reads `failed`, or `in-progress` with no sign of life for 5 minutes; on `pending`
  (the post itself refused: the 409 over a database that holds data, a 400 archive rule, 413, 401 or 403) that the
  bootstrap was refused before it started, for the reason `bootstrap.sh` printed, and the database left as it was,
  with the recreation for a database that holds data; on `applied` (`bootstrap.sh` gave up on a 401 mid-poll or
  its timeout while the run went on) that the run finished, so `--sample` loads; on anything else (no answer) only
  that the bootstrap did not finish (the `pending` and `applied` arms since the review of #204, in the function
  `bootstrap_outcome`, which `StartLocalBootstrapTest` sources from the script). It never drops anything itself,
  apart from `--reset`'s `niord_sample`.
  It posts
  the base product's archive alone: `--sample` loads the sample with its own loader after that bootstrap, and
  `--demo` answers exit 2 (since P9). Until P7-bs it posted on `failed` and `in-progress` too (since the review of
  PR #179), a one-archive retry the runner then accepted, and under `--sample` a retried run that did not finish,
  or a load the sample's guard then refused, named `--reset` (since the review of PR #192). On a cluster, an
  operator runs it once after install (a Helm hook is a later option); a failed bootstrap is recreated empty
  (D4; `be/k8s/README.md` step 4 for a staging or test cluster).
- **It replaced**, in C4.1, `BootstrapService` and the `-Dniord.batch-set` start path (`BatchSetService.init`).
  That start path's only user, `regen-test-seed-postgres.sh`, was switched to the runner first. The script now
  runs Liquibase, with `ddl-auto=validate`, because `DefaultsLedger` and `BootstrapState` have no JPA entity and
  a schema Hibernate builds lacks them; its data dump leaves out `BootstrapState` and the Liquibase tables, so a
  database seeded from it reads `existing` at its first start. Since C4b both its dumps leave out the residue
  list `regen-sample.sh` uses: `spatial_ref_sys`, `BootstrapState`, `DefaultsLedger`, `databasechangelog`,
  `databasechangeloglock`, `User`, `BatchData`, `MessageHistory`, `ScriptResourceHistory`, `Setting` and
  `message_search_text` (§7.4).

### 7.3 The seed's rows

The seed (`db/niord-base-data.sql`, loaded by `changelog-with-seed.yaml`, both in niord-base's test resources
since C4.1) splits as follows:

| Rows | Goes to |
|---|---|
| master domain; the 12 categories (without `Category_Domain` links); 34 parameter types (the seed's 41 without the 7 standard ones; 32 since the review of PR #179, `dgps` and `dgps_station_list` going to DK's and GL's bootstraps in C6, §7.1); 48 template parameters; promulgation types 1 `NAVTEX` and 3 `Twitter` (without their domain links); the `aton` dictionary (115 entries, 162 links; 111 entries since that review, the four `radio_navigation.dgps.*` stations going with the two types); since C6's base product half without any `da` description (the 170 removed, §7.1) | base product bootstrap |
| the other 5 domains (active) and the categories' links to them (`Category_Domain`), carried as additions to the base categories once DK pins a base with C6's base half (§7.2, *Within one run*; since DK's bump to 4.0.87, P5e); promulgation type 2 `Audio` (from P7 the base product's bootstrap carries `Audio` and DK's row becomes an addition, country-split plan §6 P7) and the domain links of `NAVTEX` and `Twitter`; 6 series; the five `nm-*`/`fa-list` report rows, and a new `nm-accumulated` report row naming `messages/nm-accumulated-report-pdf.ftl`, taken from DK's migrated database (the seed has none, §6.1). The domain IDs are those DK's deployments and the local realm use, `niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex`, not the seed's `niord-client-*`, which `niord-dk` #2 carried (owner, 2026-10-22; P5d renamed them in `niord-dk` #3, `1d29b97`; §8) | DK bootstrap; the P9 sample carries its own copy of the domains, series, `Audio`, the domain links of `NAVTEX` and `Twitter`, and the categories' links (§8) |
| none of the seed's rows: from niord_gl, GL's local database, the domain `niord-gl-nw` (`NW Greenland`, UTC) with its series `ako-nw`; `NAVTEX`'s link to it; GL's `Audio` (`kl`) and `Navtex GL`; `dgps`, `dgps_station_list` and the four DGPS stations with their `kl` descriptions; the `kl` descriptions of 111 `aton` entries and 37 template parameters. No reports and no categories (§11 C6, owner question on a fresh GL deployment) | GL bootstrap (C6) |
| 8 publications; 5 mailing lists (trigger 1 names DK's `fetch-firing-exercises.js` and `audio-broadcast.ftl`, `:1949-1950`); 172 charts, 55 areas, 29 sources (Danish place data) | the P9 sample, with the two scripts and the DGPS pair (`tmpl/dgps.ftl`, `tmpl/dgps.js`, which the archive's category Radio navigation/DGPS names) copied from DK's bootstrap (§8) |
| the 5 text dictionaries' copies; 44 settings (including a `pdfEncryptionPassword` value, `:2661`); batch and sequence residue | dropped: start-up adds texts and settings |

The split was settled row by row in C4.1 against the dump. The dump's 12 categories are the first 12 of the 45
in the archive's `categories.json` (`Light` and `Light buoy` with their template children): the 2026-06
capture stopped at the 13th, `Buoy`. The cause, found in C4.1: the category import's writer merged each item
again after the processor had persisted it, and with no chunk transaction the merge cascaded to a parent made
stale by its next leaf, so the first chunk's write failed after its ten leaves were in (`OptimisticLockException`).
C4.1 made the writer a no-op (`BatchCategoryImportWriter`); the bootstrap's and the demo archive's categories
import in full, and `BatchCategoryImportProcessorITTest` runs the job on siblings under one parent. The bootstrap takes those 12, as decided, plus "Firing Exercises" from
the archive (§7.1); the archive's other 32 categories stayed with `--demo` and, since P9, are the sample's
(`be/niord/sample/archive/categories.json`, with the 13 base ones restated for their `da` and domain links). The bootstrap's
category rows carry no `messageId`, which in the archive names a Danish message. From 2026-11-04 (the owner's
ruling, decision 145) they are the base archive's too: the base product's `categories.json` carries all 45 in
English, without `messageId` and without domain links, and the sample restates them for its `da` and links, as it
does the 13; `Radio navigation`/`DGPS` comes without its scripts and template parameter, which are DK's and GL's
data (the sample adds them in its archive). The 13 keep their place first in the file, so they keep ids 1 to 13 in
a fresh database; the 32 follow `Firing Exercises`, and their ties at one sort order are in production's id order
(`Wreck` before `Uncharted obstruction`).

### 7.4 What still uses the seed

- **The integration suite's fixture, regenerated in C4b.** `niord-base-data.sql` and `changelog-with-seed.yaml`
  moved to niord-base's test resources in C4.1 *and stay in the published test-jar*, at the same class-path
  paths. From C4.1 until C4b the fixture was the moved, unchanged seed; C4b (§11) regenerated it, so the seed
  changeset's path is unchanged and its checksum changed, which only a fresh IT database applies. DK's ITs depend
  on it (five of its six extend the test-jar's `ITTest`, which selects the seed changelog; `DkBootstrapITTest`
  runs on its own empty container). GL's `main` has no integration suite and no test-jar dependency; on GL's
  `feature/p6-gl-moves` (C6) `GlFiringExercisesScriptITTest` extends the test-jar's `ITTest` and
  `GlBootstrapITTest` runs on its own empty container. DK's
  `application-local.yml` named the seed changelog until its C6, since that no longer resolves from niord-base's
  main jar (§9.1). The fixture stays in the test-jar, unlike `niord-test-seed.sql` (the full dump, kept beside it
  as the readable reference), which the test-jar excludes (`niord/pom.xml`).
  - **What it is.** Since C4b the fixture is a `pg_dump` of a database bootstrapped with the base product's
    archive through the runner and then loaded with the P9 sample by the sample's own loader.
    `regen-test-seed-postgres.sh` builds it in a throwaway PostGIS container (`niord-seed-pg`, its database named
    `niord_sample`, published on `127.0.0.1:15432` only, so every rule of the loader and of the sample's SQL
    holds): `SeedRegen` starts the suite's own application and applies `niord/bootstrap/` alone, zipped with the
    JDK's `jar` tool; `load-sample.sh --container niord-seed-pg` (the option is new in C4b; its default stays
    `niord-db`) loads `niord/sample/niord-sample.sql`; the sample's message uid prefix (the uids' common start
    up to their fourth dash) is replaced by the fixture's own, which keeps the first three hex digits that name
    the repository folders (`messages/5/a3/`) and continues `f1c70-0000-4000-8000-`, in `"Message"`'s `uid`,
    `repoPath` and `thumbnailPath`, in `"Attachment"`'s `path`, and in `"Reference"`'s `messageId` and
    `"IssueMember"`'s and `"IssueOverride"`'s `messageUid` (the last three hold no sample uid today, so their
    updates write no row; since #192, whose no-sample check treats the prefix as a marker; the script reads it
    from the database and writes no sample uid);
    `created` and `updated` are set to the sample's epoch
    (`2026-10-22 00:00:00`) where they differ, since the bootstrap's rows carry their write time; and every table
    with a primary key is clustered on it, so the row order no longer depends on where an update left a row, and
    its clustering mark is dropped again (`SET WITHOUT CLUSTER`), so the full dump has no `CLUSTER ON` line that
    V001's schema lacks. The script ends (its step 9 of 9) by running `check-no-sample.sh` over the folder it
    wrote the dumps to, and fails on a finding. The
    JVM runs with `-Duser.timezone=UTC`, as `regen-sample.sh` starts `niord-app`. Two runs give the same bytes:
    since the review of #196 both dumps leave out `pg_dump`'s `-- Dumped from` and `-- Dumped by` version lines,
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
    `MessageHistory`, `ScriptResourceHistory`, `Setting` and `message_search_text`, and, since the review of
    #196, the batch jobs' numbers (step 7 deletes the bootstrap's seven `BATCH_JOB_*` `Sequence` rows; a job's
    first run creates its counter at 1). The sequences of the excluded tables (`BatchData_SEQ`,
    `ScriptResourceHistory_SEQ`) keep the value the regeneration advanced them to, which is harmless: it is at or
    past every id. A database seeded from it
    therefore reads `existing` at its first start (its domains exist), and that start writes the settings from
    the class path it runs on (niord-base's test files, a country's `defaults/country/`) and adopts the base
    texts, as a deployment's first start does. With no `BatchData` row there are no stale instance ids (BATCH4).
  - **What it holds.** The data dump has 2790 inserts and 70 setvals (8254 lines, 714,519 bytes); the full dump
    111 tables (9176 lines, 534,454 bytes), as regenerated in the review of #196 (§11 C4b, _The review of
    #196_; on #192's sample 2797 inserts, the seven job counters among them). Among them: the base product's 80 templates and the sample's 4 scripts
    (`audio-broadcast.ftl`, `fetch-firing-exercises.js`, `tmpl/dgps.ftl`, `tmpl/dgps.js`) as `ScriptResource`
    rows; the bootstrap's three reports (`standard`, `draft`, `public`); the inactive master domain (active since
    2026-11-03, BOOT1, decision 138) and the sample's five domains; and the sample's series, place data, publications, mailing lists and 34 messages. No
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
  - **`ITTest`'s completions are gone.** Until C4b, `ITTest` added the `public` report before each test (the
    moved seed had `standard` and `draft` only; `FmReportService.init` created `public` at every start until
    C4.1) and the base product's 80 template rows (`addTheBaseProductTemplatesUntilC4b`, reading
    `bootstrap/templates/` by path), since from C4.2a no template comes from the jar. The fixture now carries
    both, so both steps were removed; no test failed for their absence (measured). Since `ITTest` no longer reads
    `bootstrap/templates/`, the unit test `TestFixtureCurrentTest` compares the fixture's 84 `ScriptResource`
    rows with `bootstrap/templates/` plus `sample/archive/templates/` and fails, naming the script, when a
    template changed without a regeneration.
  - **Rows the suite pins that neither source has.** Niord's own test-only templates and scripts
    (`templates/it/`, the `it-*` mail and mailing-list templates, `test-scripts/`) come in as rows per test class
    through `ScriptResourceFixtures.ensure`, unchanged. DK's `nm-report` and `nm-tp-report`, which only DK's
    bootstrap carries (§7.3), come from `ReportFixtures.ensure` (new in C4b: the old seed's rows on their base
    templates, linked to `niord-nm`; committed, they stay for the rest of the JVM, so no IT asserts the
    fixture's report count exactly, as for `ScriptResourceFixtures`' rows). Legacy rows an older deployment holds (the 7 standard `ParamType` rows, a
    setting no catalog declares) and DK's text state are planted by the test that needs them and rolled back or
    removed afterwards. Nothing went into the sample.
  - **The IT realm** (`src/test/resources/keycloak/niord-realm.json`) names its five domain clients `niord-nw`,
    `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex` since C4b, the IDs of the sample's domains, of DK's
    bootstrap and of the local realm (§8); `niord-client-master` stays. The 61 IT files that named the old
    `niord-client-*` IDs moved with it.
  - **A country's templates.** A country's ITs on the test-jar have no `bootstrap/` folder of niord-base: their
    templates come from their own bootstrap first and, for the paths it lacks (`common.ftl` and the other base
    templates its own include), from the base product's bootstrap, `org.niord:niord-bootstrap:<base>:zip`, which
    the country's build unpacks at `pre-integration-test` (no dependency, so `package -DskipTests` never resolves
    it); DK's fixture is `DkBootstrapTemplates`, GL's `GlBootstrapTemplates` (C6; `be/docs/country-api.md`). Since
    C4b the fixture holds the base rows already, so from DK's and GL's first pin bump to a version carrying C4b
    each one's fixture writes its own templates over them, creating or replacing each path (§11 C4b, *DK's next
    pin bump*; `be/docs/country-api.md`, the C4b row of _Changes_). Done at their bumps to 4.0.97 (`niord-dk` #10,
    `niord-gl` #9, 2026-10-25): DK's `DkBootstrapTemplates.writeDksTemplates` and GL's
    `GlBootstrapTemplates.writeTemplates` write only the country's templates over the fixture's rows (create or
    replace, an equal row left); the base product's half is gone. The unpack of `niord-bootstrap` now serves the
    countries' bootstrap ITs (GL's comparison of its shadows too); no test writes it to the IT database.
  - **The pinned ITs.** `NavtexPromulgationGenerationITTest`, `PromulgationTypeServiceITTest` ("contains
    exactly"), `SafetyNet*ITTest`, `Issue*ITTest` except `IssueRenderRealITTest`,
    `MemberResolutionDifferentialITTest` and `BootstrapSeededITTest` pass on the regenerated fixture with their
    assertions unchanged; only comments that named the old seed's facts were reworded (§12). 35 classes moved,
    each with its cause in the test and in §11 C4b; the corpus cases whose thresholds lie above the sample's 34
    messages still skip.
- **`niord-app`'s `demo` profile** went in C4.1: `application-demo.yml`, `changelog-local-demo.yaml` and the
  `-Pdemo` Maven profile in `niord-app/pom.xml` that selected them. From C4.1 until
  P9, `start-local.sh --demo` kept the demo content without the seed: on a fresh database it called the
  bootstrap command once with the base product's archive and then the archive the seed was captured from,
  `be/niord/seed/niord-dev-basedata.zip` (domains, series, charts, areas, sources, categories, publications,
  mailing lists, reports, promulgation types), which `regen-test-seed-postgres.sh` also ran through the runner
  from C4.1 until C4b. At P9 (C7) `./start-local.sh --sample` replaced `--demo`, which was removed (it now
  answers with exit 2 and a pointer to `--sample`); the zip stayed in `be/niord/seed/` as the input of
  `regen-test-seed-postgres.sh` until C4b, which deleted the folder (the zip, its Danish `niord.json` and its
  README) once the script no longer read it; its content had reached the sample's archive (§8), and git history
  keeps it.
- **The Helm switch** defaulted to `false` in C1 and was deleted in C4.1, with `deploy.sh`'s pin and every
  sentence §10 lists. A fresh cluster deployment is bootstrapped once with `be/scripts/bootstrap.sh` after
  install. An existing seeded database is unaffected [I]: the bare master changelog does not list the
  seed changeset, and Liquibase ignores applied changesets that the changelog no longer names.
- **Liquibase** (D2): from now on a changeset changes the schema only. It may also **reshape existing values a schema change requires** — split a column, convert a type, move values into a new table — but never insert new content, delete rows, or change what a value says. Changesets already applied (`V005`'s deletion of three settings) stay as they are. The publications go-live import, the bootstrap and backup/restore are unaffected: the first two run through the application, the last outside Liquibase.

---

## 8. Sample (country-split plan P9)

The sample is unchanged in principle and in its safety rules (P9): never on a classpath or in an image,
loaded with `psql` into the loopback `niord_sample`, or a country launcher's `niord_sample_<cc>` (a two-letter
lower-case country code; since P8a, NG-8), with messages written for the sample. Under this plan it
is layered: the base product bootstrap through the command, then the sample rows. The sample carries the DK rows
it needs itself, as Danish sample content (country-split plan R11): DK's domains and series, `Audio`, the domain
links of `NAVTEX` and `Twitter`, the two scripts its mailing lists name (`fetch-firing-exercises.js`,
`audio-broadcast.ftl`) and the DGPS pair its category Radio navigation/DGPS names (`tmpl/dgps.ftl`,
`tmpl/dgps.js`; without it `missing-paths` is not `[]`). It fetches nothing from
`niord-dk` and needs no credential; drift from DK's bootstrap is accepted. Then come the Danish place data,
publications and mailing lists of §7.3, and the sample messages. The IT fixture was regenerated from the base
product bootstrap and the sample in C4b, after C7 (§7.4, §11): a dump of a database the sample was loaded
into, in niord-base's test-jar, which is no deployable and goes into no image; it carries none of the sample's
files, and the no-sample check passes it. The sample's domains are clients of the local
realm (`be/keycloak/niord-realm-local.json`: `niord-nw`, `niord-nm`, …), or the local sysadmin holds no role in
them. The demo archive's (`niord-client-*`) are not, and DK's bootstrap carried the same `niord-client-*` IDs
(niord-dk #2), while DK's migrated database uses `niord-nw`, … (found 2026-10-22 by the policy smoke run of PR
#184's review). The owner ruled on 2026-10-22: DK's bootstrap carries the IDs DK's deployments and the local
realm use, `niord-nw`, `niord-nm`, `niord-fa`, `niord-almanac` and `niord-annex` (P5d changed `niord-dk`:
#3, `1d29b97`, merged 2026-10-22; the P9 sample uses the same IDs). The sample's archive carries them, so the
local sysadmin holds its roles in them and `fe/docs/testing.md`'s workaround for the demo archive went with P9.

As built (C7, 2026-10-23; the country-split plan's P9, *State as built*, holds the verification):
- **The folder** `be/niord/sample/` (outside `src/main/resources`): `README.md`, whose first lines are §7.1's;
  `archive/`, a bootstrap archive in the batch-set format, with no report and no item property: DK's
  `domains.json`, `promulgation-types.json` and four scripts copied byte for byte from `niord-dk`'s
  `feature/p5-dk-moves` @ `68b95cf`, and the rest from `niord-dev-basedata.zip` (deleted in C4b) with `niord-client-*` renamed
  `niord-*` and the categories' `messageId` removed (§7.2), the counts kept, including the 111 `aton`, 35
  parameter-type and 24 category `da` descriptions the base bootstrap dropped in C6, so the sample's Danish
  matches the seed's (the 34 parameter types restate the base bootstrap's 32, whose 13 composite types gain
  their `da` names while the 19 list types restate the base's values equally, so they change nothing, plus `dgps` and
  `dgps_station_list`). The review of PR #192 mended the zip's own defects in the sample: the last five
  sources' English descriptions paired with their own organisations again, the space at the end of two source
  names and three chart names removed, the mailing lists' typos corrected, and the six plain-http links of
  three `LINK` publications (a website and documents of 2015 and 2016) left out, no other address put in their
  place (the three stay `ACTIVE`; the import accepts a publication without a link);
  `messages/messages.json`, 34 messages written for the sample (fixed uids, `en` and `da`,
  every title "SAMPLE —", every position at sea where its vicinity says, judged from general geography: the
  review of #192 moved nine there and took a doubled period out of two titles); and two generated outputs,
  never edited: `niord-sample.sql` and `repo/`, the four
  attachment files its rows name. `be/.gitattributes` gives the folder `eol=lf`, so the rebuild is the same on
  any platform.
- **The format** is the one this section chose: a `psql` script of the rows the sample adds on top of the base
  product's bootstrap, data-only column inserts with triggers disabled per table, in one transaction, the
  guard first and every touched sequence's `setval` last. Since P8a (NG-8, decision (c) of 2026-10-25, which the
  owner confirmed: P8a's decisions 87 and 89) it loads on any country's backend after the base
  product's bootstrap. Why: a country's backend writes its own default texts at its first start, before any
  bootstrap (`DefaultTexts.java:60`: `DictionaryService` adds what the text layers have and the database lacks;
  a country's layer adds keys and languages the base lacks: DK's the English `lang.da` and
  `msg.responsible.agency` and Danish for its mail and pdf keys, with more Danish from the da pack once staged,
  GL's the same English keys and root-level Danish and Greenlandic bundles), so the base ids of a base-only
  first start, which the format of C7 continued, never held on DK's or GL's backend.
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
    `applied`; its Liquibase changesets are the recorded list (unchanged); in each of the 34 tables no row is
    at or above 1,000,001 and its sequence is below it (rows and sequences: a sequence there may already have
    handed out ids in the range); the sample's 5 domains and 6 series are not there; and every natural key the
    sample names resolves to exactly one row, as does each of the 15 base `ParamType` names that its template
    parameters' types name. The refusals split their remedy as since the review of PR #192: the changeset
    refusal means the sample was made on another schema and names `regen-sample.sh`; the offset, domain and
    series, and natural-key refusals mean the database is not one clean run of the base product's bootstrap (an
    interrupted bootstrap, or rows added since; the domain and series refusal adds that a `--sample` run posts
    the base product's archive only) and name `./start-local.sh --sample --reset`, with a regeneration only if
    the base product's bootstrap, schema or entities changed since the sample was made. The others name what the
    load needs (the database name; a schema and the marker `applied`, which `./start-local.sh --sample` gives
    it). A country's own archive beside the sample stays unsupported: the sample's domains and series would
    collide with the country's.
  - **Replaced in P8a** (the record of C7, 2026-10-23 until NG-8): the ids continued the bootstrap's own
    sequences, since the same application and base archive, applied by one bootstrap run on a fresh database
    after the start-up's default texts with `modelLanguages` at its default, gave the same base ids; the guard
    held the first free id of each table and, since the review of PR #192, an md5 fingerprint of the base rows
    the foreign keys pointed at and of the 32 base parameter types, whose keys were not all lookup keys
    (`TemplateParam` by `paramId`, type and English name is not unique). C7 rejected "an offset remap of every
    id and foreign key, which needs more code and a stale-base guard all the same"; P8a adopted it, and the
    natural-key checks are its stale-base guard.
- **The regeneration** (`be/niord/scripts/regen-sample.sh`) builds the sample from its sources through the
  real path, twice: `niord_sample_base` with the base product's archive only, the baseline, and
  `niord_sample` with the base archive and the sample's archive in one bootstrap run, so the same-run rule
  (§7.2) adds the Danish descriptions and domain links to the base entries (and, since the owner's ruling of
  2026-10-24, would replace a differing value, which the comparison below refuses: the sample restates the base
  values equal), then the messages through the
  message archive import as a sysadmin batch set (a route taken while the import endpoint failed on every upload:
  MSGIO1, fixed on 2026-11-13). It installs niord-base into a Maven repository of its own
  (`.local-run/sample-regen-m2`, the shared `~/.m2/repository` behind it), as a launcher slot does, so a run
  never overwrites the shared jars. It compares the two through `postgres_fdw` and fails when a baseline row
  differs but for `version`, `created`, `updated` and the derived `treeSortOrder` (the residue tables are not
  compared); it captures the other rows of the allow-listed tables, never the build's residue (users, batch
  data, histories, the ledger, the marker, settings, the search index, Liquibase's tables), and fails on a
  sample row in any other table, and on a link the message import dropped (the rows of `Message_Area`,
  `Message_Category`, `Message_Chart`, `Attachment`, `Reference` and `FeatureCollection` against the counts
  `SampleTool counts` reads from `messages.json`, captured first, so a failing `SampleTool` stops the run;
  since the review of PR #192). The capture is normalised
  (`created`, `updated` and, since that review, the mailing-list triggers' `nextScheduledExecution` set to the
  sample's epoch, 2026-10-22, a message's `createdBy` and `lastUpdatedBy` cleared, the uids drawn at random
  derived from the row ids, `Message.areaSortOrder` rounded to 0.001), so two runs give the same bytes on any
  day. Since P8a (NG-8) the capture then renumbers (`SAMPLE_FIRST_ID=1000001`, which `SampleFolderTest` holds
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
  allow-listed table, and since NG-8 an `INSERT`'s value only a quoted literal, optionally cast, a number,
  `NULL`, `true`, `false` or one of `SampleReferences`' shapes; an e-mail outside `example.org`; a phone
  pattern; a title without "SAMPLE —"; under
  `repo/`, any file but a PNG whose chunks are exactly IHDR, PLTE, IDAT and IEND; the sentinel line) and is
  loaded with `load-sample.sh` into the baseline renamed `niord_sample`, the schema check, before it is written
  over the committed files. Since that review it waits up to 120 s for `pg_isready`, refuses a checkout or
  home path with a space, drops its scratch databases on a failure only once its step 1 has created them,
  numbers its steps [1/8] to [8/8], and `--check` compiles first.
- **The loader** (`be/niord/scripts/load-sample.sh --host --db --home`) refuses (exit 1) unless the host is
  loopback, niord-db publishes its port on loopback only and the database is `niord_sample` or, since P8a
  (NG-8), `niord_sample_<cc>` with a two-letter lower-case ASCII country code, which a country's launcher uses
  (the host and the database name are checked before any container is asked; the SQL's guard refuses any
  other name itself, and `be/scripts/seed-dev-database.mjs` refuses both names, since real messages never go
  into a sample database). Past those
  refusals it first copies `repo/` into the Niord home, on every run (exit 1 and no SQL run when the copy
  fails); it then writes no row and answers 3 when the sample is already there, or runs the SQL with `psql`
  inside niord-db. It never creates or drops a database. Since C4b `--container <name>` (default `niord-db`)
  names the container, so `regen-test-seed-postgres.sh` loads the sample into its throwaway container with the
  same rules: a loopback host, the container's port published on loopback only, the database `niord_sample`
  (one of the two names the loader accepts).
- **The launcher**: `./start-local.sh --sample` starts the services, creates `niord_sample` when missing,
  starts the backend, runs the bootstrap command with the base product's archive, stops the backend, loads the
  sample, **starts the backend again** (its caches and message index then see the sample), and only then the
  frontends. SECOM's outbox dispatch is off (`-Dniord.secom.enabled=false`) and mail goes to MailHog. It calls
  the loader on every run: a re-run on a loaded database copies the attachments again, takes the loader's 3 as
  success and starts it again, saying that a changed `be/niord/sample` needs `--reset`; `--reset` drops only
  `niord_sample`, since P8a (NG-8) through the launcher library's `sample_reset <db>`
  (`be/scripts/local-run-lib.sh`), which drops only the sample database it names: it refuses any name but
  `niord_sample` and `niord_sample_<cc>` before any `docker` call and while a client session is connected to
  it, then runs `dropdb --if-exists`. `start-local.sh` passes the name written out, never `NIORD_DB`; a
  country's launcher will pass its `niord_sample_<cc>` (NG-10). niord-ng's own `--sample` uses `niord_sample`
  only. Since P7-bs it posts the bootstrap only on a `pending` marker; on `failed` or `in-progress` it
  prints the state, names `--reset` and posts nothing, and when its own post fails it reads the marker again and
  names `--reset` on `failed`, on `in-progress` once it is final, and on `pending` for a database that holds data,
  while `applied` loads the sample (§7.2, *Who calls it*) (from the review of PR #192 until then a retried bootstrap was
  posted, and its refusal led to `--reset`). It takes no `NIORD_DB` but `niord_sample`
  and, since that review, no `NIORD_HOME_DIR` but `~/.niord-ng-sample` (exit 2). The sample users are the
  local realm's four users with `example.org` addresses.
- **Verification as run for P8a's format** (NG-8, Windows, 2026-10-25, on `p8a/sample-any-backend`):
  - **Red first**: `SampleOnACountryBaseITTest` (new; a fresh Testcontainers database named `niord_sample`: the
    first start's default texts; then, before any bootstrap, what a country's first start adds, written through
    `DictionaryService` and JPA so the ids come from Hibernate's own sequences: `lang.da` and
    `msg.responsible.agency`, each where the first start left it out, with `en` and `da` texts, and a Danish text for a base key; the base bootstrap
    in-process; the SQL with `psql`), on the C7 SQL and scripts: 7 tests, 4 failures (steps 4 to 7; steps 1 to 3
    passed). Step 4's load was refused (`psql` at line 157 of the SQL): "niord-sample: "DictionaryEntry" already
    holds a row where the sample's go (id >= 388): the database holds more than the base product's bootstrap
    the sample was made on; niord_sample is not one clean run of the base product's bootstrap (a retried or
    interrupted bootstrap, or rows added since): recreate it with ./start-local.sh --sample --reset; regenerate
    the sample with be/niord/scripts/regen-sample.sh only if the base product's bootstrap, schema or entities
    changed since the sample was made". Step 7, on an empty `niord_sample_dk`: "niord-sample: the sample is
    loaded only into the database niord_sample, not niord_sample_dk". The targeted unit run after the tooling
    change and before the regeneration: 57 tests, 2 failures, both the new `SampleFolderTest` checks on the
    old-format SQL; `theSqlKeepsTheSafetyRules` passed with the tightened rule (a).
  - **The regeneration**: `regen-sample.sh` green in 331 s (after two failed runs, each fixed in the script),
    its schema check loading the candidate and then answering 3; a second run into a scratch folder in 300 s
    gave `niord-sample.sql` and `repo/` byte for byte; `repo/` unchanged from C7. `niord-sample.sql` went from
    2508 to 2708 lines: the guard from 146 to 343 lines (`DO $guard$` to `$guard$;`), its lead comment from one
    line to two, and the generated header by 2. 1504 of its 1506 `INSERT`s changed (the 2 unchanged are the
    `Sequence` rows), and a comparison script found every changed value to be
    one of 1165 ids renumbered, 1252 foreign keys renumbered, 87 lineages and 197 base references (32 `Category`,
    4 `Dictionary`, 111 `DictionaryEntry`, 4 `PromulgationType`, 46 `TemplateParam` values, to the 174 base
    rows above); the `INSERT` order, the `SET` lines and the `setval` lines are unchanged, and each table's
    renumbering keeps its order. `--check` on the committed files: exit 0.
  - **The IT fixture** (§7.4), regenerated after the sample with `regen-test-seed-postgres.sh`:
    `niord-base-data.sql` stays at 8254 lines (2790 inserts, 70 `setval`s) and `niord-test-seed.sql` at 9176;
    the base rows are identical, the sample's 1504 `INSERT`s differ only in ids, foreign keys and lineage, and
    the 34 sample tables' `setval` lines moved to 1,0000xx. No IT hard-codes a fixture id that moved (searched:
    literal ids in `em.find`/`getReference`, REST paths, SQL and `setId`; the one fixture row named by id is
    `BootstrapState` 1).
  - **Green**: the IT after the regenerations, 7 tests, 0 failures: the 34 English "SAMPLE —" messages,
    `niord-nw`, the sample's Danish "Fyr" on the base category Light, Danish texts on a base `aton` entry and
    a base `TemplateParam` resolved by key, every added row at 1,000,001 or above, every sequence at or above
    its `max(id)`, a second load refused by the offset check with no row written, `niord_sample_dk` past the
    name rule and refused with "has no Niord schema", and `niord_dk` refused by the name rule. The targeted unit
    run (`SampleSafetyCheckTest`, `SampleToolTest`, `LoadSampleScriptTest`, `SampleFolderTest`,
    `TestFixtureCurrentTest`, `StartLocalBootstrapTest`, `LocalRunLibraryTest`, `CheckNoSampleTest`,
    `CoreResourcePackagingTest`): 82 tests, 0 failures. The 14 IT classes that read the fixture most
    (`MessageSearchRestServiceITTest`, `MessageSearchServiceITTest`, `MessageSeriesServiceITTest`,
    `MessageRestServiceITTest`, `MessageSearchFilterMatrixITTest`, `MessageServiceITTest`,
    `MessagePostgresIndexITTest`, `PublicationSearchAdapterITTest`, `CategoryRestServiceITTest`,
    `AreaRestServiceITTest`, `ParamTypeServiceITTest`, `DictionaryServiceITTest`, `BootstrapSeededITTest`,
    `ApiRestServiceITTest`): 396 tests, 0 failures.
  - **By hand**: `./start-local.sh --sample --reset` on slot 2 (backend 8890, administration 4102, public site
    4402): `niord_sample` dropped through `sample_reset`, the bootstrap applied, "The sample is loaded", the
    backend started again, both frontends answering 200, the 34 English "SAMPLE —" messages at ids 1000001 to
    1000034, served by the public API; then stopped by its process tree, none of the worktree's left.
  - **Run since**: `./verify-local --base origin/feature/p8a-guides` PASS on #220 (backend 13m20s). **Still not
    run**: Backend CI on Linux; the load on DK's real backend with Danish on, and a country launcher calling
    `sample_reset` and `load-sample.sh` with `niord_sample_<cc>` (both NG-10; the IT is their local stand-in).
- **No sample in a jar or image**: `CoreResourcePackagingTest` for niord-base's classes (the whole classes
  folder, since the review of PR #192), and `be/k8s/scripts/check-no-sample.sh` over the built jars and the
  bootstrap zip in Backend CI, over the jars and the deployable split in `publish.yml`'s build job before the
  first deploy, and over the image's inputs in its harbor job before the log-in. It reads jars, zips and
  `docker export` tars; since that review a `docker save` tar (`manifest.json`, `oci-layout` or `index.json`
  at its root) exits 2, every entry is also searched for the messages' uid prefix (read from `messages.json`
  at run time, never written elsewhere) beside the sentinel, an entry over the 512 MiB in-memory limit that
  is itself a zip or a tar exits 2, and every entry of a duplicated zip name is checked.

---

## 9. The country repositories

Counted on 2026-10-20 against `niord-base` @ `e6a8a733`, and re-counted by the fact check and the critique. Every data shadow is compared with the base product's file. An equal copy is dropped: the base product's bootstrap carries it. A different copy goes to the country's `bootstrap/`. A copy that only lags a base product fix is dropped (GL's `tmpl/common.js`, SCRIPT8). DK's 42 shadows moved unchanged with `git mv` (C6, 2026-10-22): each differs from 4.0.76's file by DK's own lines, and laying them onto the current base files would change DK's renders, which C6 verifies unchanged; the audit of each shadow against the current base file is DK's follow-up, shipped as a release folder of its own. In the tables, `bootstrap/` is the country's `be/bootstrap/` (§7.1).

### 9.1 `niord-dk`: 70 resource files, 46 shadows

| Files | Count | Kind | Goes to |
|---|---|---|---|
| `templates/tmpl/*.ftl` (40), `templates/mailinglist/publish-navwarn.ftl` | 41 shadows | data | `bootstrap/` |
| `templates/mailinglist/{audio-broadcast.ftl,fetch-firing-exercises.js}`, `templates/tmpl/dgps.{ftl,js}`, the eight `templates/messages/{fa-list,nm-report,nm-almanac-report,nm-accumulated-report}-pdf-intro_{da,en}.ftl` | 12 own | data | `bootstrap/` |
| `templates/messages/message-list-pdf-footer.ftl` | 1 shadow | data (named by code) | `bootstrap/` |
| `templates/aton/{light-character,telephony-codes}_da.ftl` | 2 own | data (localized variants) | `bootstrap/` |
| `web_en.properties`, `mail_en.properties` | 2 shadows | texts | `mail_en`'s three differing keys and `web_en`'s `lang.da` → `defaults/country/texts/en/`; `web_en`'s seven differing keys, which no template or Java reads → DK's branding (`footer.*`) and one text override (`msg.no_pos_messages`); the other four go (§4.4); `mail.safetynet.cancel` then comes from the base product (TEXT1) |
| `{web,message,pdf,mail,template}_da.properties` | 5 own | texts | the `da` pack's backend half (DK is its home), its `lang.*` names included (§4.3), in neutral Danish (P5b, `niord-dk` #5): DK's own wording of the four keys its `texts/en/` words (`mail.active_nav_warnings`, `mail.navtex.cancel`, `mail.navwarn.cancel`, `pdf.nm_almanac.desc`) to `defaults/country/texts/da/`; `footer.copyright` and `footer.link` the base product's English values, DK's administration branding carrying DK's; `mail.safetynet.cancel` added and `navwarn.date.html_day_suffix` `{{suffix}}<sup></sup>`; DK's pom copies `fe/packs/da/backend/` into the jar, the root files deleted |
| `niord.json` | 1 shadow | settings | `defaults/country/settings.json`, with its keys as today plus `country` (`DK`) and `organisation` (both `required`, §5.4; until C5 both came from code defaults, `NiordApp.java:42-45, 58-61`, and DK's database holds the rows `DK` and `DMA`). `wmsLogin`/`wmsServiceName` are dead and go. Its `wmsPassword` key is read by live code (`WmsProxyServlet.java:89, 129`); the committed value is a placeholder, not a real credential (owner, 2026-10-20), and is emptied in the file: the real value is set on the Settings page or in the server's overlay |
| `application.yml`, `application-local.yml` | 2 own | configuration | stay; `application-local.yml:18-19` stops selecting the seed changelog, which is in niord-base's test-jar only since C4.1: on a C4.1+ base product the line fails Liquibase at start (the C4.1 check overrode it with `-Dspring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml`); a DK local run is bootstrapped instead |
| `META-INF/resources/img/company-logo.png` | 1 shadow | image (code asset) | guarded override |
| `img/company-logo-da.png`, `img/firing_areas.png`, `img/flags/da.png` | 3 own | images | stay; `img/flags/da.png` from the `da` pack since P5b (the committed copy deleted) |

The override check (C5b) over DK's `main` jar (`a348be0`, built against `4.0.0-c5b-local`, 2026-10-22)
exits 1 with 56 `template-in-jar` findings, every `templates/**` file of the table (42 shadows and 14 own),
which go to `bootstrap/`, and one `unlisted-shadow`, `META-INF/resources/img/company-logo.png`
(niord-base's `sha256:f8f40e1f66c72e2c92d4f87bb4a499d9d0aa7e562d0de90e79ff24e06155d44a`), which goes to
`be/overrides.json`. It reports nothing else. After C6 the check exits 0.

On a fresh DK database on a 4.0.76 base the 42 shadows were inert: the base product's archive runs first and
adds each path with the base product's generic template, and on 4.0.76 the bootstrap's script-resource import
only adds (§7.2). Shown by C6's empty-database check (0 of 42) and `DkBootstrapITTest`. From the base product
version with C6's base half they apply: a later archive of the run replaces a template an earlier archive of
the run created (§7.2, *Within one run*; owner, 2026-10-22), measured on this branch's `niord-app` with DK's
merged bootstrap at 42 of 42 (§11 C6). DK's deployments get it from DK's bump to 4.0.87 (P5e, `niord-dk` #6,
2026-10-23; `DkBootstrapITTest` asserts 42 of 42); a fresh DK environment bootstrapped before it renders the
base product's templates for those paths unless a sysadmin ticks the 42 overwrites in the preview of DK's
release file. Existing (migrated) DK databases are unaffected: their rows are DK's.

Tests:
- `NiordDkSettingsResourceTest`: against the settings file and its checks.
- `DkTemplateResourcesTest`: against `bootstrap/`.
- `DkWebBundlePublicationTypeTest`: against the layered texts.
- `DkDgpsTemplateITTest` and `DkLightDestroyedTemplateITTest`: import from `bootstrap/`.
- `DkLightCharacterTest`: renders the `_da` variant from `bootstrap/templates/aton/` and asserts each result
  non-blank (no longer print-only, TESTQ1). `DkNavWarnDateFormatterTest` reads no template and is unchanged;
  its Danish side reads the pack in P5b.
- `DkDanishTextsTest` (P5b): the pack in the jar once, no root-level `*_da`, DK's wording from `texts/da/` over
  the pack, the jar's flag the pack's.
- `AppBootSmokeITTest` and `ContextLoadsITTest`: on the test-jar's fixture (their Javadoc names
  `defaults/country/`), the moved seed until C4b and the regenerated fixture from DK's first pin bump to a
  version carrying C4b, which
  changes neither (§11 C4b, *DK's next pin bump*); the boot on an empty schema plus the runner is
  `DkBootstrapITTest`, new in C6.
- `CoreDefaultsPrecedenceITTest`: kept as is.

### 9.2 `niord-gl`: 131 resource files, 75 shadows

| Files | Count | Kind | Goes to |
|---|---|---|---|
| `templates/tmpl/*` (46), `templates/mailinglist/*` (6), `templates/messages/{fa-list,nm-report,nm-tp-report,nm-annex-report,nm-almanac-report,nm-accumulated-report}-pdf.ftl` (6) | 58 shadows | data | 12 equal to the base product's (`tmpl/{compute-affected-radius.js,navtex.ftl,object-adrift.js,twitter.ftl}`, `mailinglist/{active-navwarns,cancel-navtex,cancel-navwarn,publish-navtex,publish-navwarn-common}.ftl`, `messages/{fa-list,nm-almanac-report,nm-annex-report}-pdf.ftl`) and three copies that only lack base product fixes, each equal to niord-base's own file at `0dc35aa8` with no GL line: `tmpl/common.js` (SCRIPT8), `messages/nm-report-pdf.ftl` and `messages/nm-tp-report-pdf.ftl` (the `?filter` speed-up, the edition line), dropped; 43 → `bootstrap/` |
| the `-gl` mailing-list templates and their `_da`/`_kl` variants, `audio-broadcast*`, `fetch-firing-exercises.js`, `publish-navwarn_{da,kl}`, the report intros `_{da,en,kl}` and the report variants `_{da,kl}`, `tmpl/dgps.{ftl,js}` | 32 own | data | `bootstrap/` |
| `templates/aton/light-character.ftl`, `templates/geometry/*` (5), `templates/messages/{message-list-pdf,message-list-pdf-footer,message-mail,message-support}.ftl` | 10 shadows | data (named by code) | 7 equal to the base product's (`aton/light-character.ftl`, `geometry/*`, `messages/message-list-pdf.ftl`) dropped; 3 → `bootstrap/` |
| `templates/aton/{light-character,telephony-codes}_{da,kl}.ftl`, `templates/messages/{message-list-pdf,message-list-pdf-footer}_{da,kl}.ftl` | 8 own | data (localized variants) | `bootstrap/` |
| all five `*_en.properties` | 5 shadows | texts | `defaults/country/texts/en/`: the differing keys a template or Java reads (the others go to GL's frontend text overrides or branding, §4.4), `lang.da`/`lang.kl`, and the own keys of §4.4 as classified |
| all five `*_da.properties` | 5 own | texts | compared with DK's `da` pack (P6b, `niord-gl` #4, against `niord-dk` `bc9353a`): GL's own wording to `defaults/country/texts/da/` (the three base mail keys GL words differently and its five own, `pdf.nm_almanac.desc`, `lang.kl`, `msg.responsible.agency`); the root files kept, read by the transitional layer, until GL's bump onto 4.0.111 deleted them (`niord-gl` #10, 2026-11-02; the pack's wording won for the eight keys that differed); the layer went in NG-B (#252, §4.1) |
| all five `*_kl.properties` | 5 own | texts | the `kl` pack's backend half (GL is its home); since P6b the pom copies `fe/packs/kl/backend/` and the pack's flag into the jar in place, GL's five own mail keys went to `defaults/country/texts/kl/`, `pdf.attachment.title` (read by nothing) was dropped, and the root files are deleted |
| `niord.json` | 1 shadow | settings | `defaults/country/settings.json`, with its keys as today plus `organisation` (its `country` is `GL` already; both `required`, §5.4). `wmsLogin`/`wmsServiceName` are dead and go, and its `wmsPassword` placeholder (`TO_BE_DEFINED`, as DK's) is emptied. `organisation` is `DMA`, niord_gl's row, the value GL runs with today. GL's map centre was Denmark's: the root `niord.json`, like the base file and niord_gl's rows, held 56/11 at zoom 6. C6 (2026-10-22) sets 72/-40 at zoom 4, from GL's public-site configuration (niord-ng `fe/ui-apps/info/src/app/country-specific/gl/info.config.ts:12`, the root area `urn:mrn:iho:country:gl`, which P6b moves into `niord-gl`). niord_gl's own `niord-gl-nw` domain row centres on 70/-40 at 4. Existing deployments keep their rows |
| `application.yml`, `application-local.yml` | 2 own | configuration | stay |
| `META-INF/resources/img/company-logo.png` | 1 shadow | image | guarded override |
| `img/company-logo-da.png`, `img/firing_areas.png`, `img/flags/{da,kl}.png` | 4 own | images | stay; `img/flags/kl.png` from the `kl` pack since P6b (the committed copy deleted); `img/flags/da.png` stayed until GL's bump onto 4.0.111 deleted it (`niord-gl` #10, 2026-11-02), since when the flag comes from the pinned `da` pack |

The override check (C5b) over GL's `main` jar (`a3b22d0`, built against `4.0.0-c5b-local`, 2026-10-22)
exits 1 with 108 `template-in-jar` findings, every `templates/**` file of the table (58 + 10 shadows, 32 + 8
own), which go to `bootstrap/` or are dropped as the table says, and one `unlisted-shadow`,
`META-INF/resources/img/company-logo.png` (the same niord-base fingerprint as DK's), which goes to
`be/overrides.json`. It reports nothing else. After C6 the check exits 0.

In all, C6 (2026-10-22) dropped 22 files (19 equal, 3 lagging) and moved 86 to `bootstrap/` with `git mv` (46
shadows, 40 own), unchanged but for `fetch-firing-exercises.js`'s line 19. As DK's, the 46 shadows were inert
on a fresh database on a 4.0.76 base (0 of 46, `GlBootstrapITTest`) and apply from the base product version
with C6's base half (46 of 46 on this branch's `niord-app`, §11 C6), which GL's deployments get from GL's bump
to 4.0.87 (P6e, 2026-10-24; `GlBootstrapITTest` asserts 46 of 46); a fresh GL environment bootstrapped before it
renders the base product's templates for those paths unless a sysadmin ticks the 46 overwrites in the preview of
GL's release file.

**GL's database pipeline ends.** Today it works like this:
- `be/scripts/build-resources.mjs:33-60` generates `src/main/resources/templates/**` from the `ScriptResource`
  rows of a local database snapshot, anchored by `translations/source-manifest.json` and machine-translated
  through `translations/kl-demo.json`.
- `import-translations.mjs:133-141` writes rows back with SQL and no history.
- `seed-local.mjs:175-206` UPSERTs settings.

Under these rules, GL's data templates are files in `bootstrap/` maintained by hand, and a translation aid
works on those files only. `import-translations.mjs` and `seed-local.mjs` are deleted with the generator (C6,
2026-10-22). Nothing was left for `import-translations.mjs` to turn into an import file. Its inputs,
`kl-demo.json` and the class-path templates, are gone. Its template upsert is replaced by the release folder's
import, its `lang.kl` SQL by the layered texts, and its `kl` data rows by GL's bootstrap: `aton-dict.json` (111
`kl` descriptions of base `aton` keys, inert on a 4.0.76 base and applied from the base product version with
C6's base half, §7.2, and the four DGPS stations) and `parameter-types.json` (37 `kl` template-parameter names,
applied, because that import updates the type it finds). Both were cross-checked with `kl-demo.json` and
niord_gl: 0 differences. `seed-local.mjs` copied `niord_test` into `niord_gl` and upserted settings, which the
rules forbid; niord-ng's `be/scripts/bootstrap.sh` replaces it, as for DK, which has no wrapper. Not carried in
C6: the `kl` names of `niord_gl`'s 45 categories and 34 category parameters (on a 4.0.76 base the category
import replaces a category whole, as for DK's 24 `da`). GL's bump to 4.0.87 (P6e, 2026-10-24) carries those of
the base product's 13 categories and their 11 template parameters as additions, with their `da`; the other 32
categories and 23 parameters are DK's data in `niord_gl`, not GL's bootstrap (owner question, §11 C6). Answered
on 2026-11-04 (decision 145): the 32 are the base product's, in its archive in English since CAT45; their `da` and
`kl` names come with country PRs into niord-dk and niord-gl on the owner's accept. Nor the
`kl` of areas, sources and publication categories
(place and publication data of the DK-derived local database, not bootstrap data; they stay in niord_gl and in
`kl-demo.json`'s history at `a3b22d0`). The generator, the manifest and `kl-demo.json` are deleted, and
`be/README.md:39-67` (its *Translation maintenance* section) changes with them.

Tests:
- `NiordGlSettingsResourceTest`: against the settings file.
- `GlTemplateTest`: its parse test over the `kl` variants stays; its image test (`:44`) reads
  `be/overrides.json`; its dictionary-seed test reads `lang.kl` (`da`, `en`, `kl`) through the layered
  defaults as start-up computes them (`DefaultTexts.read` over the class path: `texts/en/web.properties`, then
  the root `web_da`/`web_kl` until P6b's packs): `lang.kl` is a text that start-up adds, not bootstrap data.
  New in C6: `GlBootstrapFolderTest`, `GlReleaseFilesCurrentTest`, `GlBootstrapITTest` (with the
  restated-entries equality test) and `GlFiringExercisesScriptITTest` (SCRIPT8). Since P6b its dictionary
  test reads `lang.kl` from the `kl` pack and `texts/da/`.
- `GlKalaallisutTextsTest` (P6b): the `kl` pack in the jar once and byte-equal to `fe/packs/kl/backend/`, no
  root-level `*_kl`, GL's own keys from `texts/kl/`, the suffix passing the markup row, the jar's `kl` flag the
  pack's, and GL's Danish wording from `texts/da/` over the root `*_da`.

---

## 10. What is deleted

**Backend, deleted with no replacement:**
- `PUT /rest/dictionaries/reload-resource-bundles` (`DictionaryRestService.java:177-188`),
  `DictionaryService.updateDictFromResourceBundle` (`:405-432`), the `override` parameter of
  `loadDefaultResourceBundles` (`:309-311`) and the `if (override)` branch of `loadResourceBundle` (`:340-342`).
- `POST /rest/script-resources/reload` (`ScriptResourceRestService.java:137-142`) and
  `ScriptResourceService.reloadScriptResourcesFromClassPath` (`:212-230`).
- The `path` parameter of `GET /rest/script-resources/all` (`:81, 88-92`) and
  `ScriptResourceService.findOrLoadFromClassPath` (`:99-121`).
- `FmTemplateLoader.checkLoadTemplateFromClassPath` (the class-path read and its persistence through
  `CdiUtils`, with the `loadFromClassPath` flag and `nonExistingTemplatePaths`),
  `ScriptResourceService.readScriptResourceFromClassPath` and `JsResourceService.checkLoadScriptFromClassPath`,
  and with them the temporary resource mapping in `be/niord/pom.xml` (§6.3): deleted in C4.2a.
- `SettingsService.get`'s insert (`:302-306`) and its `"/"` sink (`:314-316`);
  `MapTileSettings.storeTileCacheSetting` (`:215-233`).
- `FmReportService.init` and the persistence in `getStandardReport`/`getDraftReport`/`getPublicReport`;
  `TemplateInitService`; `BootstrapService`: deleted in C4.1 (S2).
- The `-Dniord.batch-set` start path (`BatchSetService.init`, deleted in C4.1) and the unattended folders (D1;
  the `batch-sets` and `batch-jobs/*/in` watchers, both `monitorBatchJobInFolderInitiation`, with
  `BatchSetService.BATCH_SETS_FOLDER` and `executeBatchSetFromArchiveOrFolder` and
  `BatchService.getDirectoryFiles`: deleted in C4.2b). The precondition is met: the owner checked on 2026-10-21 that nothing outside Niord uses the `batch-sets` or `batch-jobs/*/in` folders.
- `DictionaryService.importDictionary(Dictionary)`, the one-argument import that overwrote every key it
  carried: replaced by `importDictionary(Dictionary, ImportChoice)` in C4.2b (nothing else called it).
- The Danish defaults of S3.
- In a base product release after GL stages `niord-lang-da` (GL pins the kit version with the backend job, the
  pom re-pointed at `fe/.niord/packs/<lang>/backend/`, together with a pinned `da`; GL keeps its root-level
  `*_da` until then, and its bump to a base version without the layer would stop its start-up, since no layer
  would hold a `da` file for the bundles its `texts/da/` lacks, DICT1):
  the transitional root-bundle layer (§4.1), deleted in NG-B (#252) after GL's bump onto 4.0.111 (`niord-gl` #10,
  2026-11-02) had done both and deleted its root-level `*_da`. Once niord-base's test tree
  (`be/niord/src/test/resources/niord.json`) moves to `defaults/` (the seed's `niord.json` was deleted in C4b):
  the root `niord.json` read (§5.2). (Until the review of
  PR #188 both were tied to "C6+1", the release after both countries' C6; C6's backend parts went first, so the
  root texts were still DK's and GL's, and neither country carries a root `niord.json` since its C6. From that
  review until 2026-10-23 the layer was tied to the first release after P5b and P6b; P6b kept GL's root `*_da`.)

**Backend, changed:**
- the dictionary start-up merge, which reads the layers and the ledger;
- `SettingsService`: files and catalogs read in its constructor, `init` reduced to add-missing, an internal writer for `state` keys, the public `set`
  refusing what it must;
- `SettingInjectionBeanPostProcessor`, which resolves through the early-loaded files and re-injects, after
  add-missing, the fields it could not resolve through the database (§5.2);
- `DefaultSettings` (new, C3b), the files and catalogs read without the database; `SettingsCatalog`, moved
  from the test sources to the main jar with `declaredIn` (§5.1); `DefaultsGuard.checkSettings`;
  `DefaultsLedger`'s `SETTING` rows;
- `BatchSettingsImportProcessor`;
- `TreeBaseService`, `AreaService` and `CategoryService`, which write their `state` keys through
  `SettingsService.writeState`;
- `FmTemplateLoader` and `JsResourceService`, which read the database only (C4.2a);
- `RequiredTemplates` (new, C4.2a), used by the missing-path report and the bootstrap's completeness test;
- `MissingPathReport` (new, C4.2a), with `GET /rest/script-resources/missing-paths` on
  `ScriptResourceRestService`, its start-up log and its SQL; `META-INF/niord/expected-template-paths.txt`
  (new, generated, path names only) (§6.6);
- `BootstrapArchive`, which admits `releases/<folder>/*.json|md|sql` and never applies them (C4.2a, §7.2);
- `SettingsService.getAllEditable`, which keeps only the keys a catalog declares, and so
  `GET /rest/settings/editable-settings` (C4.2a, §5.6);
- the reactor module `be/niord-bootstrap` (new, C4.2a), which builds `org.niord:niord-bootstrap:<v>:zip`, and
  `publish.yml`'s deploy step, which deploys it (§7.1);
- `TemplateExecutionService`, which holds the standard parameter types in memory, and
  `BatchParamTypeImportProcessor`, which skips their names (C4.1);
- `FmReportService`, whose built-in reports become plain lookups, and `MessageReportRestService`'s
  `/detail-reports`, which lists only those that exist (C4.1);
- `BatchCategoryImportWriter`, a no-op since C4.1: the processor persists each category, and the writer's second
  merge failed on a parent made stale by its next leaf (§7.3);
- the bootstrap (new, C4.1): `BootstrapMarker`, `BootstrapArchive`, `BootstrapRunner`, `BootstrapRestService`
  and `be/scripts/bootstrap.sh` (§7.2), and `FiringScheduleService`'s warning (§7.1);
- the script-resource import's preview, choice, refusal and history (C4.2b, §6.4): `ScriptResourceCheck`
  (new, §6.5), `ScriptResourceImportService` and `ScriptResourceImportPreviewVo` (new),
  `POST /rest/script-resources/import-preview` and the upload's `add` and `overwrite` fields on
  `ScriptResourceRestService`, `BatchScriptResourceImportProcessor` (skips a refused file) and
  `BatchScriptResourceImportWriter` (adds the chosen missing paths, overwrites only the ticked ones),
  `ScriptResourceService` (the history's source threaded through), `ScriptResourceHistory.source` and
  `ScriptResourceHistoryVo.source` with `V008__script_resource_history_source.sql`;
- the dictionary import's preview, choice and refusal (C4.2b, §4.6): `DictionaryService.previewImport`,
  `importRefusal`, `importKeys` and `importDictionary(Dictionary, ImportChoice)`, `DictionaryImportPreviewVo`
  (new), `POST /rest/dictionaries/import-preview` and the upload's `add` and `overwrite` fields on
  `DictionaryRestService`, `BatchDictionaryImportProcessor` and `BatchDictionaryImportWriter`;
- `BatchSetService`'s job allow-list, `BATCH_SET_JOBS`, and `BatchRestService.executeBatchSet`'s Javadoc
  (C4.2b, §6.4);
- `BootstrapArchive`, which runs the script check on every `templates/` file before the claim (C4.2b, §7.2);
- `DictionaryRestService` `/entries` with `asAdded` (`DictionaryEntryDescVo`, per translation), and
  `GET /dictionary/{name}/key-users?key=` for the delete confirmation (`DictionaryService.keyUsers`);
- `RequiredTexts` (new), the dictionary keys Java reads and what each reader does without them (§4.6).

**Data and configuration, moved:**
- `be/niord/src/main/resources/{web,message,pdf,mail,template}_en.properties` → `defaults/base/texts/en/`
  (with `lang.en` as the only language label).
- `niord.json` → `defaults/base/settings.json` (all keys, plus `country` and `organisation` from C5; §5.2).
- All 80 templates and scripts → `be/niord/bootstrap/templates/` (C4.1; packed into the jar by the temporary
  mapping until C4.2a, in no jar since).
- The base product's texts as a dictionary import file → `be/niord/bootstrap/texts.json` and each release
  folder's `texts.json` (new, generated, C4.2a; §4.6); the first release folder,
  `be/niord/bootstrap/releases/2026-10-21-templates-from-database/` (new, C4.2a; §6.4).
- `be/niord/seed/*` (`niord-dev-basedata.zip`, its Danish `niord.json`, `README.md`) → the sources of the
  base product bootstrap, DK's bootstrap and the P9 sample (§7.3); the zip stayed in `be/niord/seed/` while
  `regen-test-seed-postgres.sh` loaded it, as `--demo` did until P9, and the folder was deleted in C4b, when the
  script switched to the base product's archive and the sample (§7.4).
- `db/niord-base-data.sql` and `changelog-with-seed.yaml` → test resources, kept in the test-jar (C4.1).
- `niord-app`'s `changelog-local-demo.yaml`, `application-demo.yml` (its only setting was the seed changelog)
  and the `-Pdemo` Maven profile in `niord-app/pom.xml` that selected it → deleted in C4.1.
- The Helm `postgres.seed.enabled` switch → deleted in C4.1, and with it every place that still named it:
  - `be/k8s/scripts/deploy.sh`: its choice of the value (on for a first install, an existing release's value
    kept, which C1 added for the local cluster) and its comments that name the switch, the realm-sync comment's
    "(when postgres.seed.enabled) layers the base-data seed" included. Helm reports no `--set` of a value the
    chart no longer reads, so the `--set` is removed in the same change;
  - the sentences of `be/k8s/README.md` that name the switch or deploy.sh's seed: the base-data paragraph, the
    first rollout's "(and loads the seed when it is on)", the app ConfigMap's "the seed changelog when
    `postgres.seed.enabled`", the values table's row, the paragraph after the override example, the SECOM
    walkthrough's "from deploy.sh's seed" and "Re-seed the database";
  - `be/k8s/helm/niord/templates/NOTES.txt:10`, whose `{{ if .Values.postgres.seed.enabled }} + base-data
    seed{{ end }}` would be a dead branch, and `templates/app/configmap.yaml:21-28` (the comment and the
    branch that selects the seed changelog);
  - `be/docs/cicd/design.md` §6 "Values files hold only overrides" (prod's `postgres.seed.enabled` spelled
    out) and §7 "Ephemeral environments" (the template's values must set the flag).

**Not built** (they belonged to the rejected design): a read-only resolver chain for texts or data
templates, synthesised dictionaries, `GET /dictionary/{name}/overrides`, a settings registry replacing the
database's metadata, a configuration transition layer, a transition or comparison tool, a `basedOnDefault`
column, an "Override" action, `?scope=` exports; and, from the draft of 2026-10-20 (R-CDC9), a jar fallback for
templates, a `CodeTemplates` list, a "database copy of a code template" marker, refusals at code-template
paths or built-in report names, in-memory built-in reports.

**Frontend (`fe/ui-apps/admin`):**
- Dictionaries: the Reload menu item and handler (`dictionaries-page.ts:34` comment, `:143`, `:151-152`,
  `:227-241`); `DictionariesApiService.reloadFromBundles` (`dictionaries-api.service.ts:97-103`) and its spec
  case (`dictionaries-api.service.spec.ts:72-81`).
- Script resources: the Reload menu item and handler (`script-resources-page.ts:109`, `:113`, `:191-205`, the
  cog comment `:32-40`); `ScriptResourcesApiService.reload` (`script-resources-api.service.ts:104-109`), the
  `path` argument of `getAll` (`:76-87`) and its spec case (`script-resources-api.service.spec.ts:52-59`).
  The page's deep-link preload (`script-resources-page.ts:124-145`, spec `script-resources-page.spec.ts:79`)
  becomes a plain lookup. So does the reusable field's pencil target (`script-resources-field.ts:39,
  183-184`).
- i18n keys in `en.json` and `da.json`: `admin.dictionaries.menu.reload` (`:571`), `confirmReload`
  (`:603-606`), `toast.reloaded` (`:610`), `toast.reloadError` (`:613`); `admin.scriptResources.menu.reload`
  (`:1182`), `confirmReload` (`:1245-1248`), `toast.reloaded` (`:1252`), `toast.reloadError` (`:1255`).
- The e2e header comment `e2e/admin-dictionaries.spec.ts:5`.
- Changed:
  - the dictionary merge skips `asAdded` rows (§4.5);
  - the Dictionaries delete confirmation names the key's users (§4.6);
  - the Script resources and Dictionaries pages get their own import dialogs, in place of the shared one-file
    dialog (C4.2b, §4.6, §6.4): `script-resource-import-dialog` (would add, ticked, the listed paths marked
    and an action that ticks only them; would overwrite, unticked, each with a diff in `CodeMergeView`;
    unchanged; refused with reasons) and `dictionary-import-dialog` (adds grouped by dictionary, a key an
    operator deleted unticked; each overwrite a table of language, current and new value); `previewImport` and
    `importFile` on `ScriptResourcesApiService` and `DictionariesApiService`, which always send both fields;
  - the script-resource history dialog gets a Source column (Import, Editor, empty for older rows);

  New Danish strings were listed in `fe/languages/da/status/unaccepted.json` until accepted (until the
  frontend country split reached `main` they carried the `ai_` prefix, which its merge converted); that file was
  in this tree until the country split's P7f, and since then it is `niord-dk`'s
  `fe/packs/da/status/unaccepted.json` (that plan's P5 and P7). The owner
  takes every Danish text in one pass at the very end of the work (ruling of 2026-10-22), not per pull request.

**Tests (niord-ng):**
- **Deleted:**
  - `ScriptResourceServiceITTest`'s class-path section: `findOrLoadFromClassPath` ×2 and reload in C1, and the
    two `readScriptResourceFromClassPath` cases left (`:265-282` before C4.2a) in C4.2a;
  - the reload cases of `DictionaryRestServiceITTest` (Javadoc `:45`, methods `:188-193`, `:358-365`);
  - the reload cases of `ScriptResourceRestServiceITTest` (class Javadoc `:40, :57-58`, `RELOAD` `:79`,
    tests `:228-250`).
- **Rewritten:** `DictionaryServiceITTest` (layers, ledger), `SettingsServiceStartupOrderTest`, the settings
  suites, `FmReportServiceITTest`, `LiquibaseBootstrapITTest` (C4.1: start-up writes no report, domain or
  standard parameter type, and the marker reads `pending`), `ParamTypeServiceITTest` (C4.1: the in-memory
  types), `CoreResourcePackagingTest` (C4.1: neither `bootstrap/` nor the seed in the jar; C4.2a: no template,
  `*.ftl`, `releases/` or `texts.json` in the jar, the expected path names there). In C4.2a, for templates from
  the database only:
  - `FmTemplateLoaderTest` (the two-argument constructors; a path with no row resolves to nothing);
  - `JsResourceServiceITTest` (a row is found, a path with no row gives `null` and writes none);
  - `FmTemplateBuilderTimeZoneTest`, `FmTemplatePdfMapCreditTest` and `FmTemplateClassResolverTest` (the
    template as a stubbed database row, read from the bootstrap folder through the test helper
    `BootstrapTemplates`);
  - `LightCharacterTest`, `MapImageCreditTest`, `AccumulatedReportTemplateTest` and `WeeklyReportTemplateTest`
    (the bootstrap's files read by path instead of the class path);
  - `FmTemplateServiceITTest`, `TemplateExecutionServiceITTest`, `MailingListExecutionServiceITTest`,
    `MessageMailServiceITTest` and `ScriptResourceRestServiceITTest` (their test templates as rows through
    `ScriptResourceFixtures`), `IssueRenderRealITTest` (comments: the rows `ITTest` completes);
  - `BootstrapFolderTest` (`texts.json`, `releases/`, the five placeholders non-blank, `RequiredTemplates`),
    `BootstrapArchiveTest` (`releases/` admitted and never applied), `ITTest` (the 80 template rows until C4b,
    §7.4), and `SettingsServiceITTest`'s `getAllEditable` case (an undeclared editable row left out).
- **New:**
  - a **no-write IT**: after start-up, an anonymous PDF, a mail, an ADMIN `GET /all` and a settings read leave
    the row counts of `ScriptResource*`, `Setting`, `FmReport`, `ParamType`, `Domain` and `Dictionary*`
    unchanged; on an empty database `/web` returns `mode` and `osmUrl` (the settings part in C3b,
    `SettingsNoWriteITTest`; in full in C4.2b: its case
    `noReadWritesATemplateASettingAReportAParameterTypeADomainOrAText` checks nine table counts and an md5 over
    every `ScriptResource` across an anonymous message PDF, a template-rendered mail, an ADMIN `GET /all`, a
    settings read and `/web`, the missing-path report and both import previews; `SettingsFreshDatabaseITTest`'s
    `/web` case on an empty database also asserts that no row is written);
  - the ledger: deleted stays deleted, a cleared translation, adoption with and without a hash, a new
    language, and a key deleted before a new language is added (it stays deleted in that language too);
  - two add-missing runs started together on one database, the second blocked on the advisory lock until the
    first commits (one context, two transactions);
  - the layering, including DK's `mail_en` case and the transitional root layer;
  - the duplicate-defaults guard;
  - C2a's classes: `DictionaryServiceITTest` (rewritten), `DefaultsLedgerITTest`,
    `DictionaryDefaultsLayeringITTest`, `DictionaryDefaultsConcurrencyITTest`, `DefaultTextsTest`,
    `DefaultsGuardTest`;
  - the catalog-current check;
  - the settings and text checks' failing rows;
  - C3a's classes: `SettingsCatalogGeneratorTest` (one failing fixture per generator rule, and the
    deterministic output), `SettingsCatalogCurrentTest` (the catalog-current check), `DefaultSettingsChecksTest`
    (one failing fixture per settings-check rule), `BaseProductSettingsCheckTest` (the settings checks over the
    base product), and the parity methods in `NiordBaseSettingsResourceTest` (the base file against the root
    `niord.json`; removed in C3b);
  - the public `set` and the import refusing unknown and `state` keys, and the internal writer creating a `state` row;
  - C3b's classes. Rewritten: `SettingsServiceITTest` (a read of a missing row writes nothing; `set`'s
    refusals; `writeState`; `peek` of a missing row), `SettingsServiceStartupOrderTest` (the re-injection's
    order), `SettingsServiceLogMaskingTest` (the constructor's load log), `NiordBaseSettingsResourceTest` (the
    parity methods removed), `MessageMapImageSnapshotsITTest` (no write of `mapTileCachePath`),
    `BatchSettingsImportProcessorITTest` (the import's skips). New: `DefaultSettingsTest` (the files, the
    transitional root read, the overlay and the resolution order), `SettingsLivenessTest` (a key is added only
    where its declaring code runs), `SettingInjectionBeanPostProcessorTest` (SETTINGS-1 flipped: the database
    value after re-injection), `SettingsAddMissingITTest` (added with the catalog's flags and the ledger's
    source, adoption with and without a hash, a removed row not added again, no `state` key, and two
    transactions serialised on the advisory lock), `SettingsFreshDatabaseITTest` (SETTINGS-2 flipped, the key
    set of an empty database, `/web`), `SettingsNoWriteITTest` (the settings no-write IT, and RENDER2 flipped:
    two concurrent readers of a missing row, no insert);
  - the marker: `pending` once, `existing` for a seeded or populated database, `in-progress` and `applied`
    refused, `failed` retried (C4.1: `BootstrapITTest` on a fresh database, `BootstrapSeededITTest` on the
    seeded one);
  - C4.1's other classes: `BootstrapFolderTest` (§7.1's check of the bootstrap folder), `BootstrapArchiveTest`
    (the archive rules), and cases in `BatchParamTypeImportProcessorITTest` (a standard name skipped) and
    `FiringScheduleServiceITTest` (the warning);
  - the FreeMarker resolver (asserted by rendering, C1) and the bootstrap test (a parse and the literal `?new`
    check; `BootstrapTemplatesParseTest`, C4.2a);
  - the `RequiredTemplates` literal test and the bootstrap's completeness test (`RequiredTemplatesTest`, C4.2a);
  - C4.2a's other classes: `ReleaseFilesCurrentTest` with `ReleaseFilesGenerator` (`bootstrap/texts.json` and
    the expected path names current, every release folder well formed, the first one's 80 entries with the
    five placeholders), `MissingPathReportITTest` (§6.6), and a case in `SettingsRestServiceITTest` (an editable
    row with an undeclared key is not in `/editable-settings` and stays in the database);
  - C2b's classes: `RequiredTextsTest` (the literal test of `RequiredTexts`), `DefaultTextsChecksTest` (one
    failing fixture per text-check rule), `BaseProductTextsCheckTest` (the text checks over the base product);
  - C4.2b's classes. New: `ScriptResourceCheckTest` (a failing fixture per rule and per path rule, the
    accepted fixtures, and every bootstrap and release file accepted), `ScriptResourceImportITTest` (the
    preview's four lists and `listed`, a preview writes nothing, an unticked existing row kept, a ticked one
    overwritten, `add=[]` adds nothing, the refused files named in the job log, history with the sysadmin and
    `import`, the editor's `editor`, the roles and a malformed field) and `DictionaryImportITTest` (the preview
    with a deleted key unticked and an over-long value refused, the choice, a deleted key restored only when
    ticked). Cases added: the batch-set allow-list in `BatchSetServiceITTest` (each of
    `script-resource-import`, `dictionary-import`, `settings-import` and `script-executor` refuses the whole set
    and schedules nothing) and `BatchRestActionITTest` (a sysadmin's `execute-batch-set` naming
    `dictionary-import` answers the refusal, and no `dictionary-import` `BatchData` row appears); a bad script
    and a bad template in `BootstrapArchiveTest`, and in `BootstrapITTest` (400 naming the file and the reason,
    the marker still `pending`); a refused item in `BatchScriptResourceImportProcessorTest` and
    `BatchDictionaryImportProcessorTest`; the history's `source` in `ScriptResourceRestServiceITTest`; the
    no-write IT in full (above). Changed: `BootstrapTemplatesParseTest` calls `ScriptResourceCheck`,
    `ScheduledCronExpressionTest` lost the two watchers' rows, `ChangelogChecksumPinTest` pins V008 and
    `CoreResourcePackagingTest` expects it in the jar.

**Docs:**
- `fe/docs/features/sysadmin.md:215-243, 290-308` and, in C4.2a, its Settings section (the page lists only
  declared settings); `fe/docs/backend-api-notes.md:389, 594-611`; `fe/docs/platform.md:334-361`.
- In C4.2a: `09-ScriptResources.adoc:66-72` (resources come from the database only, by the bootstrap or a
  sysadmin's import of a release file; the missing-path report) and `14-Settings.adoc:45-46` (the page lists
  a setting only when the code declares it), with `manual.html` regenerated; `be/CLAUDE.md`, `be/README.md`,
  `be/niord/bootstrap/README.md`, `defaults/base/README.md`, `niord-app/pom.xml`'s comments,
  `be/docs/test-infrastructure.md` (the 80 template rows, `ScriptResourceFixtures`) and
  `be/docs/untestable-code.md` (the class-path persist branch's entry deleted with the code).
- The sysadmin manual (`guides/sysadmin-manual/`): `07-Dictionaries.adoc:20, 161-166` (C1, C2) and,
  in C4.2b, as changed: `07-Dictionaries.adoc:125-127` (a deleted key restored by ticking it in the preview)
  and `:177-194` (the import's preview, ticks, refusals and job log); `09-ScriptResources.adoc:66-77` (the
  missing-path report and the preview marking its paths) and `:331-355` (the preview, the diff, the ticks, the
  check, the history's user and source); `15-BatchJobs.adoc:16-24` (jobs start from the pages' uploads; no
  server folder; the `BatchJobFolders.png` reference gone and the image deleted) and `:60-76` (the batch-set
  allow-list, and an example without `dictionary-import`); the in-folder sentences D1 deleted
  (before C4.2b at `04-Domains.adoc:155-156`, `05-PromulgationTypes.adoc:184-185`, `07-Dictionaries.adoc:158-159`,
  `08-ParameterTypes.adoc:165-167`, `09-ScriptResources.adoc:337-338`, `10-Categories.adoc:284-285`,
  `11-PrintReports.adoc:155-156`, `14-Settings.adoc:158-159`, `16-MailingLists.adoc:215-216`); and `manual.html`
  edited by hand in step (asciidoctor is not installed on the workstation).
- The admin manual (`guides/admin-manual/`), in C4.2b: the in-folder sentences D1 deleted ("the
  file can be copied to the `$NIORD_HOME/batch-jobs/<job>/in` folder", before C4.2b at `03-Charts.adoc:109-110`,
  `04-Areas.adoc:175-176`, `05-Publications.adoc:107-108, 420-421` and `06-Sources.adoc:92-93`), and their
  paragraphs in `manual.html`, by hand in step.
- In C4.2b: `be/CLAUDE.md` (imports preview, add, overwrite only what is ticked, record `source: import`,
  refuse what `ScriptResourceCheck` refuses; a batch set refuses the three imports; no watched folder);
  `be/docs/untestable-code.md` (the `BatchSetService` entry without the watcher and with the allow-list
  asserted, the folder monitors' items deleted); the comment of `regen-test-seed-postgres.sh`; the bootstrap
  README and the first release's README (§6.4); the legacy dev guide's batch-sets instruction
  (`dev-guide-v2/06-NiordDockerContainers.adoc:197-201`, `guide.html` in step: upload the zip on the Batch
  Jobs page, or bootstrap a new database with `be/scripts/bootstrap.sh`); `fe/docs/features/sysadmin.md`
  (Dictionaries, Script resources, Batch jobs, Import and export), `fe/docs/backend-api-notes.md` (both
  `import-preview` endpoints, the uploads' fields, the history's `source`) and `fe/CLAUDE.md` (a render reads
  the database only).
- `be/README.md` (the templates, the empty database, the demo profile; C4.1, its release-folder and import
  sentences in C4.2a); `be/niord/seed/README.md`; `be/k8s/README.md` (its Configuration
  section, and every seed sentence listed under *Data and configuration, moved*);
  `be/k8s/helm/niord/templates/NOTES.txt`; `be/docs/cicd/design.md` §6 and §7 (the two sentences that
  named `postgres.seed.enabled`); `README.md` (`--demo` and `./run.sh -Pdemo`). C4.1 changed all of these.
- `start-local.sh` (its header, `--demo` and the bootstrap step); `be/run.sh`; `niord-core-defaults.yml`;
  `changelog-with-seed.yaml`'s header and `ITTest`'s comments (SEED2); `be/docs/untestable-code.md` (the
  `BatchSetService` entry); `be/docs/test-infrastructure.md` (the seed sections); `be/CLAUDE.md` (the code,
  data and configuration idiom). C4.1 changed all of these.
- In C7 (P9): `start-local.sh` (`--sample` in place of `--demo`); `README.md` (its first section *Try it*, the
  CI rows' no-sample checks, the sample users); the root `CLAUDE.md`; `be/CLAUDE.md` (the sample's rule);
  `be/README.md`; `be/docker-compose.yml`'s header; `be/niord/seed/README.md` (the zip's role until C4b);
  `be/niord/bootstrap/README.md`; `be/scripts/bootstrap.sh`'s usage; `be/scripts/seed-dev-database.mjs` (it
  refuses `niord_sample`); `fe/docs/testing.md` and `fe/ui-apps/admin/e2e/auth/README.md` (the `niord-client-*`
  workaround gone); `be/docs/potential-errors.md` (SEED1 reworded, MSGIO1 added; MSGIO1 fixed on 2026-11-13).
- In C4b: `be/niord/seed/README.md` deleted with its folder; `be/k8s/README.md` (step 4 bootstraps a local
  cluster with the base product's archive only, the sample stays local-only; the SECOM walkthrough creates and
  exposes a series by hand); `be/docs/test-infrastructure.md` (the seed sections); `be/docs/cicd/design.md:64`;
  `be/docs/potential-errors.md` (SEED1 deleted; BATCH4, TESTQ3, DICT1 and PORT-15's seed clause reworded; S124-1
  added, then removed when fixed) and `be/docs/s124-secom-spec-gap-analysis.md` §5 (affected charts not encoded); `be/docs/untestable-code.md` (the mailing-list templates' entry); `be/CLAUDE.md` (the sample's rule
  names the fixture); `be/niord/pom.xml`'s test-jar comment; `be/niord/sample/README.md`'s provenance sentence;
  `changelog-with-seed.yaml`'s header; the header of `check-no-sample.sh` and `CheckNoSample`'s javadoc (the
  test-jar's fixture is no finding); `regen-test-seed-postgres.sh`'s and `load-sample.sh`'s headers.
- `be/docs/potential-errors.md`: the entries written with this plan, and four older ones, are deleted as each
  fix lands: DICT3 and SCRIPT6 in C1, which also narrows SCRIPT7 and SEED3 to what it leaves; DICT2 and TEXT1
  in C2; SETTINGS-1, SETTINGS-2, RENDER2 and its symptom IDX1 (the literal `${niord.home}/message-index` folder
  when an early bean got the raw annotation default; an `@Setting` field resolved before the database now goes
  through `resolveDefault`, which expands tokens) in C3b; SEED2 and SEED3 in C4.1; SCRIPT7 and RENDER1 in C4.2a (done); SEED1 in C4b (deleted: until then the demo archive, which `--demo` applied until P9 and `regen-test-seed-postgres.sh` applied until C4b, and the moved fixture named the two DK scripts; the sample carries both since P9, the regenerated fixture holds them as its rows, and `MissingPathReportITTest` asserts an empty report on it; C4b also reworded BATCH4, TESTQ3 and DICT1 to the fixture's new content); COUNTRY1 in
  C5 (done); SCRIPT8, found by the PR #159 review, closed by GL's C6 (2026-10-22, §6.5, §9.2) and deleted in
  C6's base product half. C6's base product half also adds CDI1, open (`CdiUtils`' static last-started
  context, the order dependence DK's and GL's ITs work around with `pointScriptsAt`; recorded, not fixed), and
  narrows TESTQ1 to `DkNavWarnDateFormatterTest`. DICT1 stays open: a missing default-language file
  stays fatal (§4.2). Found by the review of PR #179 and recorded as open, not fixed by this plan: SETTINGS-4
  (`pdfEncryptionPassword`'s non-empty code default, published in the settings catalog), SETTINGS-5
  (`mailSender`'s code default `niord@e-navigation.net`, a DMA address) and UPLOAD-1 (every multipart upload is
  held to Spring Boot's default 1 MB a part and 10 MB a request, since nothing sets
  `spring.servlet.multipart.*`; to be measured before a raise); WEB11 and RENDER5 were reworded by it.
- `docs/fe-country-split-plan.md`: the changes made together with this plan.

---

## 11. Phases and order

**Shape.** C1 is one PR into `main`. C2–C5b are sub-PRs on `feature/code-data-config`, each merged after
`./verify-local --base origin/feature/code-data-config`, and merged to `main` as one PR. C6 is country work
in `niord-dk` and `niord-gl`, with a base product half on `feature/c6-base` (the same-run rule and the base
bootstrap English only, §11 C6), which the countries take at their next pin bump (DK did with 4.0.87, P5e, and GL with 4.0.87, P6e). C7 went ahead in its own PR into `main` (#192, merged 2026-10-23), with the country-split plan's P9 base product part. C4b is not in the range C2–C5b: it is its own PR into `main` after C7.

| Phase | When, relative to the country-split plan |
|---|---|
| C0, C1 | now, before P1 |
| C2–C5b | alongside P1–P4; merged and published before the backend parts of P5 and P6, which only consume C5b |
| C6 | the backend parts of P5 (DK) and P6 (GL). Their frontend parts follow P4 and do not wait for C6 |
| C7 | inside P9 |
| C4b | after C7: its own PR into `main` |

Inside the branch the order is C2 → C3 (C3a, C3b) → C4 (C4.1, C4.2a, C4.2b) → C5 → C5b. The merged PR is done when DK and GL staging run country images
built on the version it publishes, with their missing-path report read there; on local copies of their databases
(and of a DK database migrated from the legacy one) the first release's import file leaves it empty (§6.6;
production untouched; staging deploys coordinated with the owner). t-dma, the previous Niord on MySQL, is
handled the same way at DK's cutover: its missing-path report read, an import the operator's decision. C4b is
its own PR into `main` after C7.

**Staging at the end (owner, 2026-10-21).** The GitLab repositories are not reachable from the workstation
this plan is carried out on, so every step that needs them runs once, after the last phase of both plans:
the pin moves in `devops-niord`'s `envs/staging/{dk,gl}`, the checks after each deploy, and C1's read of the
GitLab values files. Until then a phase checks its staging items locally — the same images or jars against
copies of the local DK and GL databases and of a freshly seeded one — and its record says *met locally;
staging at the end*. The country-split plan's staging items run in the same step. The new environments hold
no data in use; the legacy Niord is not touched.

C2 and the country-split plan's P2 need no order between them. The kit's `catalog` (P2) generates the
`backend` section of `catalog.json` from niord-base's English bundles wherever they are, and C2 moves them
(§10). Since P2b `catalog` reads both the root-level `*_en.properties` and `defaults/base/texts/en/` and refuses a
bundle found in both, so neither branch changes the path for the other; C2 only checks that the catalog still
finds every bundle. Until C2 the catalog is generated from today's root-level `*_en.properties`.

**C0 — the rules written down.**
- Changes: the rulings go into `be/CLAUDE.md` (a "Code, data and configuration" idiom replacing the
  "Settings:" line), into `fe/CLAUDE.md` (no reload, no reset), and into the root `CLAUDE.md`'s docs list.
  D1–D5 were answered on 2026-10-20 (§13).
- Done when: the rules are in the owning files and the open decisions are answered.

**C1 — stop the overwrites.**
- Changes:
  - both Reload functions deleted (backend, frontend, specs, i18n, docs; §10);
  - the `?path=` preload deleted, so both deep links look up without writing;
  - `postgres.seed.enabled` defaults to `false` in the chart and its README; `be/k8s/scripts/deploy.sh`
    switches it on for a first local install and keeps an existing release's value;
  - the FreeMarker class resolver, with `setAPIBuiltinEnabled(false)` keeping `?api` off;
  - the script-resources import records the importing user in history: the admin app's upload, which starts
    the job on the request thread. An import started through `POST /rest/batch/execute-batch-set` or the
    `batch-jobs/*/in` watcher runs on a thread without a security context and still records no user, until C4.2b
    refuses the first route (the batch-set allow-list, §6.4) and deletes the second (D1). C4.2b did both: a
    batch set naming `script-resource-import` is refused whole, and the watcher is gone, so every import write
    records its user;
  - the potential-errors entries C1 fixes deleted (DICT3, SCRIPT6), SCRIPT7 and SEED3 narrowed.
- Verification:
  - the targeted ITs: `DictionaryRestServiceITTest`, `ScriptResourceRestServiceITTest`,
    `ScriptResourceServiceITTest`, and a new FreeMarker resolver test;
  - the admin specs of both pages and of `script-resources-field`;
  - `helm template` with defaults renders the bare master changelog;
  - a list of every values file that relied on the old default, including those in the GitLab devops
    repositories (an existing seeded database is unaffected, §7.4; production values are changed only at the
    owner's request). In the repository, met locally: the chart's own `values.yaml` is the only values file
    there, and the switch's consumers were checked: `be/k8s/scripts/deploy.sh` (switches it on for a first
    install, keeps an existing release's value), `be/k8s/README.md` (the base-data paragraph, the first rollout, the ConfigMap
    description, the values table's row, the paragraph after the override example, the SECOM walkthrough's "from
    deploy.sh's seed", "Re-seed the database"),
    `templates/NOTES.txt:10` and `templates/app/configmap.yaml:25-27` (both only read it). The GitLab values
    files: staging at the end.
- Done when: no endpoint copies a jar file over a database row, and a default install seeds nothing.

**C2 — texts.**
- Changes:
  - `defaults/` with its README and the duplicate guard;
  - base product `en` moved, with `lang.en` only;
  - the layered add-missing with the transitional root layer;
  - the ledger (V006) with adoption and hashes, and the advisory lock;
  - a missing non-default pack file stays non-fatal;
  - `asAdded` on `/entries` and in the admin merge;
  - the Dictionaries delete confirmation;
  - a check that the kit's `catalog` (which reads both locations since P2b) still finds every bundle, now under `defaults/base/texts/en/` (§4.3, §11);
  - the text checks in Backend CI.
- Verification:
  - `DictionaryServiceITTest` rewritten, plus the ledger, layering and two-context ITs;
  - the admin merge spec;
  - a restart after a delete leaves the key deleted;
  - DK's and GL's current jars on the C2 base product still load their texts (the transitional layer);
  - on a copy of the DK database, `/entries` of `web` marks the unchanged system-added rows `asAdded` and not the
    edited ones, and the DK admin app shows the compiled text for one of the 38 masked `web` keys (§4.5).
- State (2026-10-21): met on an empty database (276 base keys added, a second start changes no row, a restart
  after a delete keeps the key deleted). The DK and GL items were met locally on 2026-10-21: DK's and GL's jars
  (their `main`, with the `<parent>` version set to this branch's niord-base in a copy) started on scratch copies
  of their local databases with the bare master changelog. Each start added one translation,
  `mail.safetynet.cancel` `en`, and changed or removed none (DK 812 → 813, GL 1,053 → 1,054). The first start
  adopted 552 (DK) and 705 (GL) triples, 539 and 692 of them with a hash. `/entries` marks 540 and 693
  translations `asAdded` (`web` 304 of 328 and 323 of 336, `message` 124 of 128 and 187 of 187), each one equal
  to its layered default. None of the 11 (DK) and 12 (GL) English keys the country words differently from the
  base product carries it (`mail.navtex.cancel`, `footer.link` among them; for the footer's two keys C6 says what
  follows); all 147 and 209 unchanged `web` and
  `message` keys do. Of the 36 `web` keys whose database row differs from the admin app's English, 4 (DK) and
  3 (GL) still mask it, all rewordings or edits. `key-users` (SYSADMIN) answers `position.deg` with no template
  and the readers `AUDIO_TEXT` and `LAT_LON_DIRECTIVE`, DK's `mail.navtex.cancel` with
  `templates/mailinglist/cancel-navtex.ftl` and GL's `mail.navtex.cancel.gl` with `cancel-navtex-gl.ftl`. A
  restart adds no row and no ledger entry. Staging at the end.
- Done when: a base product text key reaches every deployment that lacks it, and nothing else in a dictionary
  changes.

**C3 — settings**, in two sub-PRs: C3a, the catalog and the files, with the running behaviour unchanged; then
C3b, the switch.
- Changes, C3a:
  - the catalog generator and the committed catalog with scopes, `web`, `editable`, `baseValue`, `required` and
    `cached` (§5.1), and the catalog-current check (`SettingsCatalogCurrentTest`);
  - a code declaration for each key of today's `niord.json` that has none, the two `state` keys and
    `adminIntegrationPageEnabled` (§5.1), with `scope` and `required` on the annotation and on `Setting`;
  - `defaults/base/settings.json` (with `documentationUrl` on `https`), beside the root `niord.json`, which
    stays the loaded file (§5.2);
  - the settings checks (§5.5);
  - the guard's four settings names (§3.3).
- Changes, C3b:
  - the loader reads `defaults/base/settings.json`; niord-base's root `niord.json` and the parity methods go;
  - the transitional read of a country's root `niord.json` (§5.2);
  - early loading in the constructor, with `DefaultsGuard.checkSettings` before it reads, and the deferred
    re-injection of the fields an early bean could not resolve through the database (§5.2);
  - `declaredIn` in the catalog (§5.1);
  - add-missing with the ledger, only where the declaring code runs (§5.3);
  - no insert on read; `state` rows created and written by an internal writer that `TreeBaseService` calls;
  - the public `set` (`PUT /setting/{key}`) and the import refusing state and unknown keys;
  - the backend's type validation on add and on the Settings page's write;
  - `MapTileSettings.storeTileCacheSetting` deleted (§5.3).
- Verification, each item marked with its sub-phase:
  - C3a: the catalog is current (`SettingsCatalogCurrentTest`; a changed `@Setting` description fails it, naming
    the key and the regenerate command); its content (73 entries, 71 `operator` and 2 `state`, the 24 base-file
    keys with a `baseValue`, `editable=false` only for `authServerTrustAllCertificates`, `secomEnabled` and
    `mapTileCachePath`); the generator's rules (`SettingsCatalogGeneratorTest`); each declaration carrying
    today's flags (the parity methods of `NiordBaseSettingsResourceTest`);
  - C3a: the checks (`DefaultSettingsChecksTest`, one failing fixture per rule; `BaseProductSettingsCheckTest`
    green over the base file, and red with an undeclared key added); the guard and the packaging
    (`DefaultsGuardTest`, `CoreResourcePackagingTest`);
  - C3a, then C3b: the settings suites (in C3a green unchanged, every IT context still started from the root
    `niord.json`; C3b rewrites them);
  - C3b: the no-write IT for settings;
  - C3b: a boot of `niord-app` on an empty database lists exactly the catalog's operator keys whose declaring
    code runs on that node, and `/web` serves `mode`, the tile URLs, the map centre and zoom, the layer switches
    and the site languages (in C3a the catalog lists those keys with `web=true`). Expected: with SECOM off 54
    keys (the 71 `operator` keys less the 17 `secom*`), with SECOM on 71; no `state` key; one `SETTING` ledger
    row per key; a restart adds nothing, and a row deleted by SQL stays absent after the next restart;
  - C3b: DK's and GL's current jars on the C3b base product still have their root `niord.json` read (the
    transitional layer), started on scratch copies of their local databases: each gains only keys and
    changes or removes none (expected with SECOM off: DK `authServerTrustAllCertificates` and
    `batchMessageSeriesId`, GL `batchMessageSeriesId`); the log names the root `niord.json` read as the
    country settings file and the skipped `wmsLogin` and `wmsServiceName`;
  - C3b: the SETTINGS-1, SETTINGS-2 and RENDER2 tests flipped.
- Done when: no read writes a setting (C3b), a setting whose row was removed stays removed (C3b), and a settings
  file with an undeclared key, a wrong type or a real secret fails the build (C3a).
- State (C3a, 2026-10-21): met locally; staging at the end. Nothing of C3a needs staging: the running
  application is unchanged.
- State (C3b, 2026-10-21): met locally; staging at the end. Recorded in the body of PR #168 (merged
  2026-10-21, `43703162`) and written here after the review of PR #179. `niord-app` on an empty scratch
  database added exactly the 54 operator keys that run there (SECOM off), with their type and flags. DK's and
  GL's current jars, built against this branch and installed locally as `4.0.0-c3b-local`, on scratch copies of
  their local databases: both read their root `niord.json` and skipped `wmsLogin` and `wmsServiceName`; DK
  added 2 keys and adopted 52, GL added 1 and adopted 53, and neither changed a row. A restart added nothing,
  and a removed row stayed removed. 159 unit tests and 274 ITs green apart from the known Windows-only failure;
  new among them `SettingsAddMissingITTest` (the lock with two transactions included),
  `SettingsFreshDatabaseITTest`, `SettingsNoWriteITTest`, `SettingsLivenessTest`, `DefaultSettingsTest` and
  `SettingInjectionBeanPostProcessorTest`; SETTINGS-1, SETTINGS-2, RENDER2 and IDX1 closed (§10, Docs). The full
  suite (`./verify-local --base origin/feature/code-data-config`, at `d3743e33`) passed: 4,931 unit tests and
  3,286 ITs with only the 10 known Windows-only failures, and every administration-app step. The record does not
  give the ledger rows of the generic boot; C5's generic boot shows `country` and `organisation` with ledger
  source `base` (§11 C5).

**C4 — templates, imports and the bootstrap**, in three sub-PRs: C4.1, the bootstrap and the end of the
start-up writers, with copy-on-use still working; then C4.2 as two: C4.2a, the loader on the database only and
what a deployment needs to find its templates; and C4.2b, the imports.
- Changes, C4.1:
  - all 80 template and script files moved to `be/niord/bootstrap/templates/` (`git mv`), with the temporary
    resource mapping in `be/niord/pom.xml` that keeps them in the niord-base jar for copy-on-use (§6.3);
  - the generic bootstrap in `be/niord/bootstrap/` with its README, "Firing Exercises" (its English name
    load-bearing, §7.1) and the built-in reports; `BootstrapFolderTest`, and Backend CI re-including the folder;
  - the marker (`V007`, `BootstrapMarker`), `BootstrapArchive`, `BootstrapRunner`, the command
    (`POST` and `GET /rest/bootstrap`) and `be/scripts/bootstrap.sh` (§7.2);
  - start-up stops re-creating deleted rows (S2): `FmReportService.init`, `TemplateInitService` and
    `BootstrapService` deleted; the built-in reports plain lookups; the standard parameter types held in memory,
    the parameter-type import skipping their names and the Parameter Types list tracking rows by name (§6.2);
  - `FiringScheduleService` logging a warning when it finds no "Firing Exercises" category;
  - `regen-test-seed-postgres.sh` switched to the runner (`SeedRegen`, Liquibase on), then the
    `-Dniord.batch-set` start path deleted;
  - the seed moved into test resources (kept in the test-jar, unchanged), with `ITTest` adding the `public`
    report until C4b (§7.4);
  - `start-local.sh` switched to the command, and `--demo` to the seed's archive after the base product's (§7.4);
    `niord-app`'s demo profile deleted;
  - the Helm switch deleted, with `deploy.sh`'s pin and the sentences §10 lists;
  - SEED2 and SEED3 deleted from `be/docs/potential-errors.md`.
- Changes, C4.2a:
  - the loader (`FmTemplateLoader`, `JsResourceService`) reading the database only, and `RequiredTemplates`
    with `RequiredTemplatesTest` (the literal case and the completeness case, §6.2);
  - copy-on-use deleted, and with it the temporary resource mapping; no template in any jar, the test-jar's
    test templates included (§6.3);
  - `BootstrapTemplatesParseTest`, §6.5's second test, over the base product's bootstrap and release files;
  - the tests that rendered through copy-on-use given their rows: `ITTest` completes the 80 template rows
    until C4b, and `ScriptResourceFixtures` brings in the test-only ones (§7.4);
  - the release import folder `releases/2026-10-21-templates-from-database/` with its README,
    `script-resources.json` (the 80 files, the five placeholders the one-line comment), `texts.json` and
    `missing-paths.sql`, named by the rule of §6.4; the bootstrap's own five placeholders made non-blank;
  - the base product texts as a dictionary import file, `bootstrap/texts.json` (§4.6);
  - the missing-path report, its endpoint, its start-up log and its SQL, with the expected path names in the
    jar (§6.6); `ReleaseFilesGenerator` and `ReleaseFilesCurrentTest` keeping the generated files current;
  - `BootstrapArchive` admitting `releases/` (§7.2);
  - `org.niord:niord-bootstrap:<v>:zip` built by `be/niord-bootstrap` and added to `publish.yml`'s deploy
    (§7.1);
  - the Settings page hiding the rows no code declares (owner, 2026-10-21, §5.6);
  - SCRIPT7 and RENDER1 deleted from `be/docs/potential-errors.md`.
- Changes, C4.2b:
  - the script-resource import's preview, tick-to-overwrite, refusal and `source: import` history
    (`ScriptResourceImportService`, `import-preview`, the upload's `add` and `overwrite`,
    `ScriptResourceHistory.source` and V008), and the dictionary import's preview, ticks and refusals
    (`DictionaryService.previewImport`, `import-preview`), with the administration app's two import dialogs and
    the history's Source column (§4.6, §6.4);
  - the script check (§6.5's token list and the template check, `ScriptResourceCheck`) on import and in the
    bootstrap (`BootstrapArchive`, before the claim);
  - the batch-set job allow-list on `POST /rest/batch/execute-batch-set` (`BatchSetService.BATCH_SET_JOBS`),
    and the folder triggers deleted (D1: the `batch-sets` and `batch-jobs/*/in` watchers);
  - the no-write IT in full (`SettingsNoWriteITTest`, `SettingsFreshDatabaseITTest`).
- Verification, each item marked with its sub-phase:
  - C4.1: the marker ITs: `BootstrapITTest` on a fresh database (`pending` once; `existing` for a database
    holding a domain, an area or a message; `in-progress` refused; `failed` retried to `applied`; `applied`
    refused; the bootstrap's content) and `BootstrapSeededITTest` (the seeded IT database reads `existing` and
    is refused);
  - C4.1: start-up writes no `Domain`, `Category`, `FmReport`, `ParamType` or `ScriptResource` row on an empty
    database (`BootstrapITTest`, `LiquibaseBootstrapITTest`);
  - C4.1: `BootstrapFolderTest` and `BootstrapArchiveTest`; the templates still render from the jar
    (`FmTemplateServiceITTest`, `ScriptResourceServiceITTest`, `JsResourceServiceITTest`,
    `IssueRenderRealITTest`); the niord-base jar holds the 80 templates and neither `bootstrap/` nor the seed,
    and the test-jar holds the seed;
  - C4.1: `./start-local.sh` from an empty volume reaches a working admin app that can create a message from a
    template, and `--demo` applies the base product's archive and then `niord-dev-basedata.zip` in one run;
  - C4.1: a manual check of the admin shell with zero domains;
  - C4.1: DK's and GL's current jars on the C4.1 base product, started on scratch copies of their local
    databases: the marker reads `existing`, the command is refused, and the start changes no row of `Domain`,
    `Category`, `FmReport`, `ParamType` or `ScriptResource*`;
  - C4.1: a dry run of `regen-test-seed-postgres.sh` into a scratch folder;
  - C4.2a: no jar contains `bootstrap/` or a template (`CoreResourcePackagingTest`; `jar tf` of the installed
    niord-base jar and test-jar);
  - C4.2a: the targeted ITs green with no template in the jar, after `clean`; the IT suite green on the
    unchanged seed with `ITTest`'s completion;
  - C4.2a: `RequiredTemplatesTest`'s two cases and `BootstrapTemplatesParseTest` green, and each shown red
    once (a path removed from the list, a listed path the bootstrap lacks, an `Execute` `?new`);
  - C4.2a: `ReleaseFilesCurrentTest`, `BootstrapFolderTest` and `BootstrapArchiveTest` green;
  - C4.2a: `MissingPathReportITTest`, and the start-up log line on each local start below;
  - C4.2a: the Settings page hides undeclared rows (`SettingsRestServiceITTest`; on DK's and GL's scratch
    copies the eight legacy keys are not listed and still in `Setting`);
  - C4.2a: `./start-local.sh` on a new database bootstraps, and a category's template and a message PDF render
    from the database alone, writing no `ScriptResource*` row; the missing-path report is empty; the
    Maven-built zip passes the archive rules (the command answers 409 `applied`, not 400);
  - C4.2a: `niord-bootstrap:<v>:zip` installed with its entry list, and a deploy dry run with `publish.yml`'s
    `-pl` uploading its zip and pom;
  - C4.2a: DK's and GL's main jars on the C4.2a base product, on scratch copies of their local databases,
    write nothing beyond the recorded add-missing, and their missing-path report is read and each entry
    classified (a base product path the first release's file carries, or a country path for C6);
  - C4.2b: the no-write IT in full;
  - C4.2b: the import preview, refusal and history of both imports; the script check on import and in the
    bootstrap, each new check shown red once; the batch-set allow-list, shown red once; the watchers gone;
  - C4.2b: the first release's import file applied to scratch copies of DK's and GL's databases until their
    missing-path report is empty, with no existing row overwritten;
  - C4.2b: the administration app's specs of both import dialogs, both pages, the history dialog and both API
    services.
- Done when: a fresh deployment gets its data only through the command, once, and a running one only through
  an import; and the missing-path report, run on a copy of the seeded staging database and of a DK database
  migrated from the legacy one, is empty once the first release's import file is applied (production untouched).
- State (C4.1, 2026-10-21): met locally; staging at the end. The marker ITs are green (`BootstrapITTest`, 7
  cases on a fresh database: `pending` once, `existing` for a `Domain`, an `Area` or a `Message` row, the
  command answering a sysadmin only, a broken archive refused with 400, `in-progress` refused, a failed run
  recorded with its job, file, archive and status and retried through the command to `applied`, the
  bootstrap's rows, `applied` refused; `BootstrapSeededITTest`: the seeded database reads `existing` and the
  runner and the command refuse it). So are `LiquibaseBootstrapITTest` (start-up writes no `Domain`,
  `Category`, `FmReport`, `ParamType` or `ScriptResource*` row; the marker reads `pending`), the unit checks
  `BootstrapFolderTest`, `BootstrapArchiveTest` and `CoreResourcePackagingTest`, and the whole IT suite on the
  moved seed (3,301 tests; the only red ones are the known Windows ones, `RepositoryRestServiceITTest` and
  `SettingsServiceITTest.getPathBuildsPathFromStringValue`, and `IssuePreviewAndFileITTest
  .theSweepRemovesOnlyExpiredGenerations`, which counts old previews left in the shared `NIORD_HOME` and is
  green alone). The installed niord-base jar holds the 80 templates and none of `bootstrap/`'s other files,
  nor the seed; its test-jar holds the seed and its changelog. `./start-local.sh` on a new database applied
  the base product's bootstrap through the command (the master domain only, 80 script resources, 13
  categories with "Firing Exercises", the three built-in reports, 34 parameter-type rows and 41 listed with
  the 7 standard ones, `NAVTEX` and `Twitter`), the template of `Light unlit` ran on a new message, and the
  message was saved once a series was added to the master domain (the base product's bootstrap has none). A
  second start loaded nothing (`applied`). `--demo` on another new database applied both archives in one run:
  6 domains, 6 series, 45 categories, 8 reports, 3 promulgation types, 172 charts, 55 areas, 29 sources, 8
  publications and 5 mailing lists. Its `category-import` ended `COMPLETED` only after C4.1 fixed the
  category import's writer (§7.3); `BatchCategoryImportProcessorITTest
  .categoryImportJobImportsSiblingsUnderOneParent` runs the job on one parent with three children in one chunk,
  green with the fixed writer and `FAILED` with the old one (`OptimisticLockException` on the parent). The
  administration app opened as the local sysadmin on a database with
  zero domains (the marker `pending`, `/detail-reports` answering `[]`, no console error). DK's and GL's jars
  (their `main`, with the `<parent>` version set to this branch's niord-base, `4.0.0-c4-1-local`, in a copy)
  started on scratch copies of their local databases with the bare master changelog: each read `existing`,
  answered the command with 409, listed 41 parameter types with the 7 standard ones without an id, and
  changed no row of `Domain`, `Category`, `CategoryDesc`, `FmReport`, `ParamType`, `ScriptResource` and
  `ScriptResourceHistory` (row counts and checksums before and after); the `public` report and the 7
  `StandardParamType` rows are still there. The dry run of `regen-test-seed-postgres.sh` applied both
  archives through `SeedRegen` and wrote both dumps into a scratch folder (45 categories; no marker or
  Liquibase row in the data dump), the committed seed untouched; on Windows it needed the class path in a
  Java argument file, which the script now writes. The review's recheck, after the marker's start-up order
  and `bootstrap.sh`'s token handling changed: the C4.1 unit checks (103 tests) and ITs (140 tests) green, the
  jar and test-jar contents as above, and one more `./start-local.sh` on a new database, applied with the same
  rows (the master domain, 80 script resources, 13 categories, 3 reports, 34 parameter-type rows). Staging, the
  GitLab values files that may still set `postgres.seed.enabled` (which the chart no longer reads) included: at
  the end.
- State (C4.2a, 2026-10-21): met locally; staging at the end. After a `clean` build the 21 targeted ITs
  (`FmTemplateServiceITTest`, `JsResourceServiceITTest`, `ScriptResourceServiceITTest`,
  `ScriptResourceRestServiceITTest`, `IssueRenderRealITTest`, `TemplateExecutionServiceITTest`,
  `MailingListExecutionServiceITTest`, `MessageMailServiceITTest`, `MessageMailRestServiceITTest`,
  `MessageCommentRestServiceITTest`, `GeometryFormatServiceITTest`, `FmReportServiceITTest`,
  `MissingPathReportITTest`, `BootstrapITTest`, `BootstrapSeededITTest`, `LiquibaseBootstrapITTest`, the four
  settings suites and `DictionaryRestServiceITTest`; 293 tests, the review's start-up case included) are green apart from the known Windows
  `SettingsServiceITTest.getPathBuildsPathFromStringValue`. One full `clean verify` on the unchanged seed: 4,971
  unit tests, red only the known `KubernetesSecretKeystoreWriterTest` and `SecomImportBoundaryTest`; 3,308 ITs,
  red only the known `RepositoryRestServiceITTest` (7) and `SettingsServiceITTest.getPathBuildsPathFromStringValue`.
  `RequiredTemplatesTest` and `BootstrapTemplatesParseTest` were each shown red once and reverted: without
  `templates/aton/telephony-codes.ftl` in the list the literal case named
  `org/niord/core/aton/LightCharacterService.java:40 /templates/aton/telephony-codes.ftl`; a listed path the
  bootstrap lacks failed the completeness case (`[templates/aton/zz-absent-from-bootstrap.ftl]`); a temporary
  bootstrap template with `"freemarker.template.utility.Execute"?new` failed the parse test (`?new of
  freemarker.template.utility.Execute, which is not a directive`). `ReleaseFilesCurrentTest`,
  `BootstrapFolderTest`, `BootstrapArchiveTest` and `CoreResourcePackagingTest` are green. Installed as
  `4.0.0-c4-2a-local`, the niord-base jar holds no `*.ftl`, `templates/`, `bootstrap/` or `releases/` and holds
  `META-INF/niord/expected-template-paths.txt`; its test-jar holds no `*.ftl` or test script and still holds
  `db/niord-base-data.sql` and `changelog-with-seed.yaml`. The `niord-bootstrap` zip is 94,777 bytes with 101
  entries: `README.md`, `batch-set.json`, `aton-dict.json`, `categories.json`, `domains.json`,
  `parameter-types.json`, `promulgation-types.json`, `reports.json`, `texts.json`, `templates/` (80 files and 6
  directory entries) and `releases/2026-10-21-templates-from-database/` with its `README.md`,
  `script-resources.json`, `texts.json` and `missing-paths.sql`. A deploy dry run with `publish.yml`'s `-pl`
  (`:niord-parent,:niord-base,:niord-bootstrap`) into a scratch file repository uploaded `niord-parent`'s pom,
  `niord-base`'s pom, jar and test-jar, and `niord-bootstrap-4.0.0-c4-2a-local.pom` and `.zip`, the deployed
  pom with its version resolved. `./start-local.sh` on a new database (`niord_c42a_verify`) went `pending` →
  `applied` with 80 script resources (80 history rows), the five placeholders the 56-byte comment, and the
  reports `standard`, `draft` and `public`. The template of `Light unlit` ran from its database row (`PUT
  /rest/templates/execute`), and the standard and public list PDFs and a single message's PDF (after a series
  was added to the master domain and a draft saved) rendered; the renders left the `ScriptResource` and
  `ScriptResourceHistory` counts and the content checksum unchanged. The missing-path report answered `[]`,
  `POST /rest/bootstrap` with the Maven-built zip answered 409 (`applied`; the archive is read before the
  claim, so the zip passed the archive rules), and a restart logged "Missing-path report: no missing path".
  DK's and GL's jars (their `main`, with the `<parent>` version set to `4.0.0-c4-2a-local` in a copy) started
  on scratch copies of their local databases with the bare master changelog: each marker read `existing`; the
  eight legacy settings were not in `/editable-settings` and were still in `Setting`, editable; `ScriptResource`,
  `ScriptResourceHistory`, `FmReport`, `ParamType`, `Domain`, `Category`, `CategoryDesc` and `Dictionary` were
  unchanged (row counts and checksums), and `Setting`, `DictionaryEntry` and `DictionaryEntryDesc` changed only
  by the add-missing already recorded in C2 and C3b; a second start changed nothing. Their missing-path report
  (the log, the endpoint and the release's `missing-paths.sql` gave the same answer) lists five entries on
  each: `templates/aton/telephony-codes.ftl` (`code: RequiredTemplates`) and the four report intros, named by
  the includes in DK's and GL's `nm-report`, `nm-almanac-report`, `nm-accumulated-report` and `fa-list`
  templates (on GL also by the `_kl` copies of `fa-list-pdf` and `nm-almanac-report-pdf`). All five are base product paths that the first release's
  `script-resources.json` carries. DK and GL hold only their `_da`, `_en` and `_kl` variants, which the loader
  serves today; importing the base paths is harmless, since a variant still wins. No DK or GL path is
  listed yet: the countries' expected path names and release files come in C6. The scratch databases, the
  country copies and the local artifacts were removed. Left for C4.2b, and run there (below): the no-write IT in
  full, and the first release's import file applied to the scratch copies until the report is empty.
- State (C4.2b, 2026-10-22): met locally; staging at the end. One targeted run (`verify` with the unit tests
  and ITs named) was green: 70 unit tests and 186 ITs, no failure. Unit: `ScriptResourceCheckTest` 6,
  `BootstrapArchiveTest` 23, `BootstrapFolderTest` 7, `BootstrapTemplatesParseTest` 2,
  `ScheduledCronExpressionTest` 5 (14 scheduled methods, the two watchers' rows gone),
  `BatchScriptResourceImportProcessorTest` 5, `BatchDictionaryImportProcessorTest` 3, with
  `ChangelogChecksumPinTest` (V008, `niord:008-script-resource-history-source`, pinned
  `9:c1a26c5118d503fccdfe0dd12f1b204c`), `CoreResourcePackagingTest`, `ReleaseFilesCurrentTest` and
  `RequiredTemplatesTest`. ITs: `ScriptResourceImportITTest` 8, `DictionaryImportITTest` 3,
  `ScriptResourceRestServiceITTest` 17, `DictionaryRestServiceITTest` 31, `BatchSetServiceITTest` 7,
  `BatchRestActionITTest` 16, `BootstrapITTest` 7 (a sysadmin's `POST /rest/bootstrap` of an archive with a
  `Java.type` script answers 400 naming the file and `Java.type`, the marker still `pending`),
  `BootstrapSeededITTest` 2, `LiquibaseBootstrapITTest` 1 (validate passes with the new column),
  `SettingsNoWriteITTest` 3, `SettingsFreshDatabaseITTest` 3, `MissingPathReportITTest` 6,
  `DictionaryServiceITTest` 42, with `ScriptResourceServiceITTest`, `BatchRestServiceITTest`,
  `BatchUploadRestServiceITTest` and `BatchJobExecutionITTest` (the admin app's uploads still start their
  jobs). `BatchSetServiceITTest` and `BatchRestActionITTest` were run once more after the refusal's message was
  reworded: 23 tests green. Each new check was shown red once and reverted: a temporary
  `bootstrap/templates/tmpl/zz-red.js` with `Java.type('java.lang.Runtime')` failed `ScriptResourceCheckTest`
  (`uses Java.type('java.lang.Runtime'): Java.type reaches any Java class`, and the `java.lang.Runtime` package
  and `Runtime` rules), and a temporary `zz-red.ftl` with `"freemarker.template.utility.Execute"?new` failed
  `ScriptResourceCheckTest` and `BootstrapTemplatesParseTest` (`?new of freemarker.template.utility.Execute,
  which is not a directive`); `dictionary-import` added to `BATCH_SET_JOBS` failed `BatchSetServiceITTest`
  (2 failures, `dictionary-import is not in the allow-list` among them), and the list was put back to its 14
  jobs. A search of `be/` finds no `monitorBatchJobInFolderInitiation`, `BATCH_SETS_FOLDER`, `batch-sets`
  folder or `batch-jobs/<job>/in` instruction; the `batch-sets` anchor of the sysadmin manual's Batch Sets
  heading is the only hit (the admin manual's five in-folder sentences were deleted after the review; §10,
  Docs). After the review: a batch-set item without a job name, or a `null` item, is refused whole with the
  allow-list's message, and a `null` dictionary entry is skipped by the processor with the preview's reason;
  `BatchSetServiceITTest` 8 and `BatchDictionaryImportProcessorTest` 4 green in one run. Both import readers
  returned a `null` list element as it is, which ended the job's reading there (JSR-352 reads a `null` as the
  end of data) while the preview refused only that element: each now skips it with the preview's reason
  (`BatchDictionaryImportReaderTest` 7 and the new `BatchScriptResourceImportReaderTest` 3; with both
  processor tests 19 unit tests, and `DictionaryImportITTest` 3 and `ScriptResourceImportITTest` 8, green in
  one run). Found outside this sub-PR's changes and recorded as BATCH4 in `be/docs/potential-errors.md`:
  `GET /rest/batch/{jobName}/instances` answers 409 ("Duplicate key") when a stale `BatchData` row shares an
  `instanceId` with the running JVM's (`BatchService.getJobInstances` maps by `instanceId` without a merge
  function; the ITs find their job by file name instead). Route A: niord-base installed as
  `4.0.0-c4-2b-local`; DK's and GL's jars (their `main`, with the `<parent>` version set to it in a copy)
  started on scratch copies of `niord_test` and `niord_gl` with the bare master changelog (V008 applied); as
  the local sysadmin, the preview of the first release's `script-resources.json`, then its import with `add`
  the paths the preview marked `listed` and no overwrite:

  | | DK | GL |
  |---|---|---|
  | Missing-path report before | 5 entries | 5 entries |
  | Preview: add (listed) / overwrite / unchanged / refused | 12 (5) / 48 / 20 / 0 | 12 (5) / 49 / 19 / 0 |
  | Rows the preview wrote | 0 | 0 |
  | Job: added / overwritten / kept / not added / unchanged | 5 / 0 / 48 / 7 / 20 | 5 / 0 / 49 / 7 / 19 |
  | `ScriptResource` rows | 86 → 91 | 102 → 107 |
  | md5 over the rows that were there before | `136247d68b5a11cb3c9b7307c96476d5`, unchanged | `cfb4434b6a7eeb6a1e81c6021cc4d3b3`, unchanged |
  | `ScriptResourceHistory` rows | 146 → 151, the 5 new ones `import` by the sysadmin | 148 → 153, the 5 new ones `import` by the sysadmin |
  | Missing-path report after | `[]` | `[]` |
  | Start-up log after a restart | "Missing-path report: no missing path" | "Missing-path report: no missing path" |

  The five paths added on each are `templates/aton/telephony-codes.ftl` and
  `templates/messages/{fa-list,nm-accumulated-report,nm-almanac-report,nm-report}-pdf-intro.ftl`, the five
  entries C4.2a recorded. The originals were only read (`niord_test` 86 rows and 146 history rows, `niord_gl`
  102 and 148, checksums as before); the scratch databases, the country copies and homes and the
  `4.0.0-c4-2b-local` artifacts were removed, and the containers left stopped as they were found. The
  administration app: the specs of both import dialogs, both pages, the history dialog, both API services, the
  shared `json-import-dialog` and `i18n-key-parity` green (9 files, 76 tests), `npm run lint` and
  `npm run docs:check` green, Prettier on the changed files; `e2e/sysadmin-tools.spec.ts` unchanged, since the
  Script resources dialog keeps the shared picker's texts. Done when, locally: a fresh deployment gets its data
  only through the command, once (`BootstrapITTest`), and a running one only through an import (the import ITs
  and the no-write IT); the missing-path report on the DK and GL copies is empty once the first release's file
  is applied (Route A). On the seed the report still lists only DK's two trigger scripts (SEED1, until C4b),
  which the base product's release file does not carry (`MissingPathReportITTest`). The full suite
  (`./verify-local --base origin/feature/code-data-config`, at `4aaaae73`, 2026-10-22) passed: the backend's
  4,990 unit tests and 3,324 ITs with only the 10 known Windows-only failures, and every administration-app
  step. The manual check of both dialogs against a local backend was not run then; it was run after the review
  of PR #179 (below). Staging, the reading of DK's and GL's missing-path report there and the copy of the
  seeded staging database included: at the end.
  After the review of PR #179 (2026-10-22). The administration app: both dialogs bind every answer to the file
  it was asked for (a new file, a reopen and every close path cancel the pending preview and import, and a
  generation counter drops a late answer); the dictionary preview shows a long value the overwrite clears
  (absent and empty count alike), and a cleared value or AtoN filter, as "(cleared)"; a 4xx shows the
  backend's reason verbatim under the keyed sentence (a 5xx, status 0 or an HTML body shows the sentence only);
  a failed import leaves the ticks open, and only an answer starting `Started batch job '` shows the job link
  and the background note; the ticks send `basedOn`; the delete confirmation lists the parameter types that
  hold a key, and a 409 shows the backend's reason. Specs, each file run alone: `script-resource-import-dialog`
  20, `dictionary-import-dialog` 22, `dictionaries-page` 19, `dictionaries-api.service` 12,
  `script-resources-api.service` 10, `script-resources-page` 7, `i18n-key-parity` 2; then the eight files with
  `secom-problem-detail` in one run, 99 tests, green after Prettier. In the round after the review,
  `problemDetail` moved to `shared/api/problem-detail.ts`, as its comment asked once a third feature used it,
  and the dialogs' common `JOB_STARTED`, `ImportFailure` and `backendReason` to
  `sections/sysadmin/import-failure.ts`; the eight spec files that read them (both dialogs, the Dictionaries page, both API
  services, the SECOM GML dialog and certificates tab, `problem-detail`) in one run, 105 tests, green, and
  `npm run lint`, Prettier and `docs:check` green. Against the old dialogs the new specs were
  red once: the script dialog 9 failed of 20 (the four out-of-order cases, among them the overwrite list
  showing `templates/only-in-a.ftl` while `B.json` was picked, and the five reason and result-area cases), the
  dictionary dialog 14 of 22 (the three long-value clearing cases, the AtoN filter's "(cleared)", the four
  out-of-order cases and the reason, result-area and failed-import cases). `npm run lint` and
  `npm run docs:check` green; the new Danish strings carried the `ai_` prefix (until the frontend country
  split's merge converted them, §10). The backend: the preview's
  case-insensitive paths, `basedOn` and "changed since the preview" in both imports, "the entry has no
  translation", the deleted keys from the database, the 409 on deleting a key a parameter type holds and
  `paramTypes` in `key-users`, the export without `asAdded`, the settings import's and `set`'s required and
  password rules, and the built-in report's 404, in the backend package's targeted runs (C5b's paragraph after
  the review, below). The manual check, against `./start-local.sh` on slot 2 with the new database
  `niord_r179_dialogs` (bootstrapped by the command) and the local sysadmin, the dialogs driven in a browser
  through Playwright: the base product's bootstrap has only the master domain, which is inactive, so a domain
  `niord-nw` was created by `POST /rest/domains/domain` to give the session a domain and reach the sysadmin
  pages. Script resources: `templates/aton/telephony-codes.ftl` deleted and `templates/geometry/text.ftl`
  edited on the page; the first release's `script-resources.json` previewed: add 1 (`telephony-codes.ftl`,
  "In the missing-path report", ticked), overwrite 74 (unticked), unchanged 5. `text.ftl`'s diff showed the
  edited line against the file's; its tick went on, off and on; the import ("Import ticked (2)") answered
  `Started batch job 'script-resource-import'`, the job link and the background note showed, and the job log
  read "Added 1, overwritten 1, kept 73, not added 0, unchanged 5": the edit replaced by the file's content,
  the deleted path back, 80 rows, every write `import` by the sysadmin in the history. 73 of the 74
  overwrites differ only in line ends: on a Windows checkout with `core.autocrlf=true`, `start-local.sh` zips
  the checked-out `bootstrap/` with CRLF, so the rows hold CRLF and the LF release file differs; the published
  zip, built on CI, has LF. Since the round after the review `be/.gitattributes` holds the folder to LF
  (`niord/bootstrap/** text=auto eol=lf`), so a Windows checkout zips it as CI does and previews no such
  overwrites. A file with a `Java.type('java.lang.Runtime')` script was refused with the three
  reasons (`Java.type`, the package rule, `Runtime`) and "The file adds and overwrites nothing". With the
  first preview held back 6 s, the release file was picked, the dialog closed while its preview was pending
  (the request was cancelled, `net::ERR_ABORTED`), reopened empty, and a one-path file picked: only its
  preview showed, and 7 s later still only its; its import started a job "with file b-only-script.json" that
  added that one path. Dictionaries: a hand-made file previewed add 1 (`aton.r179.added`, ticked), overwrite 1
  (`aton.function.aton`, whose file entry carries `en` without a long value: the line "en, long value — the
  buoy — (cleared)") and refused 1 (`aton.r179.refused: the entry has no translation`); with the overwrite
  ticked the import's job log read "added 1, overwritten 1" and "Refused aton aton.r179.refused: the entry has
  no translation", the `en` long value was cleared and `da` kept, and "View batch job" opened
  `/sysadmin/batch/dictionary-import` with the run listed `completed`. The browser console showed two 404s
  outside the dialogs: `GET /rest/settings/setting/secomEnabled` (a node with SECOM off has no such row since
  C3b) and `img/filetypes/generic-32_32.png` on the Batch jobs page. The stack was stopped, the database
  dropped and the containers stopped again. The three things seen outside the dialogs are recorded where the
  repository keeps suspected bugs, not fixed in this phase: the inactive master domain as `BOOT1` in
  `be/docs/potential-errors.md` (`domains.json` carries no `active`, and `DomainVo.active` is a primitive
  `boolean`, so the bootstrap writes the domain inactive and `GET /rest/domains/all` leaves it out; fixed on
  2026-11-03: the bootstrap gives the master domain active, decision 138), and the
  two 404s in `fe/docs/backlog.md` (Admin and sysadmin): the SECOM gate's read on a node without SECOM, and the
  Batch jobs page's icons behind the dev server, whose proxy forwards `/rest` only. The SECOM gate's 404 ended
  with the S-124 opt-out (2026-11-01): the gate reads the computed `secomEnabled` from `/rest/settings/web`
  (§3.2), and its backlog item was removed.

**C5 — Danish data out of core.**
- Changes:
  - `country`/`organisation` without Danish defaults, marked `required` (S3), with neutral values in the base
    product's settings file (`XX`, `Niord`; 26 keys) and the settings catalog regenerated (§5.1, §5.2);
  - `S124Service` without `DEFAULT_RESPONSIBLE_AGENCY`: the agency comes from the `msg.responsible.agency`
    text, else (also when the text is blank) from `organisation`, trimmed; when that is blank too it refuses,
    naming the setting (the producing agency is mandatory in an S-100 Part 17 catalogue);
  - S-124's country taken from the `country` setting: `S124Service.country()` reads it, trimmed and upper case,
    and refuses a blank value, or one that is not two letters (ISO 3166-1 alpha-2), naming the setting, as
    `producerCode()` does; series numbers and templates read the setting unchecked; `S124Mapper` takes it as a
    `toValidated` parameter for all three of its Danish uses (the `DK.` id prefix, the nationality and the
    `:dk:` in `toMrn`, S3); `S124Mrn.COUNTRY` is gone and `S124Mrn.of(message, country)` lower-cases the
    token, called by `S124DatasetService` with `s124Service.country()`. Neither country repository calls
    `S124Mrn`, `S124Mapper`, `getCountry` or `DEFAULT_RESPONSIBLE_AGENCY` (clones read 2026-10-22), so the
    signature changes break no country build;
  - the `.dk` → `da` contact guess removed (`ContactRestService.java:162-164`): an imported contact gets no
    language, as one created with none;
  - the start-up warning for a `required` key the country file or the transitional root `niord.json` does not
    set (`DefaultSettings`, §5.2); no refusal;
  - the base text `pdf.nm_almanac.desc` ('… Danish, Greenlandic and Faroe waters') reworded to 'General
    information for the waters these notices cover', and `bootstrap/texts.json` regenerated; the frozen release
    folder `releases/2026-10-21-templates-from-database/` keeps its record (§4.6). Deployments keep their rows
    (§4.2); DK's and GL's own texts carry their wording from C6;
  - `niord-app` set up with the base product file's neutral values (no settings of its own); the
    administration app's reader text of `S124_AGENCY` (`admin.dictionaries.confirmDelete.reader.s124Agency`,
    `en` and `da`) names the `organisation` setting instead of the built-in default agency; `RequiredTexts`'
    Javadoc likewise; COUNTRY1 deleted from `be/docs/potential-errors.md`; `be/CLAUDE.md`,
    `be/docs/secom-design.md`, `be/docs/s124-cancellation-plan.md` and the base defaults' README updated.
- **Consequence for GL:** GL's S-124 was hard-coded Danish. From C5 its nationality, id prefix and MRN are
  `GL`, `GL.` and `:gl:` (were `DK`, `DK.` and `:dk:`), the intent of S3. `niord_gl` and `niord_test` hold no
  `S124Dataset` and no `S124SeriesConfig` row, so no stored MRN diverges locally; GL's staging is read at the
  end (§12). Where a pre-C5 GL dataset exists, nothing repairs it: `S124DatasetService.upsertDataset` finds
  the row by the message, keeps its `mrn` and `uuid` (derived from the `:dk:` MRN) and publishes a new version
  whose GML carries `:gl:`, so the database key and the subscribers' `dataReference` no longer match the
  GML's `interoperabilityIdentifier`, the divergence `S124Mrn`'s Javadoc rules out. The deploy step's action
  for GL, before the first C5+ start: if `SELECT count(*) FROM "S124Dataset"` is not 0, either (a) cancel the
  affected warnings and issue them anew, or (b) in one transaction set `mrn` from `:dk:` to `:gl:` and `uuid`
  to `MrnToUuid.fromMrn` of the new MRN, update every `SecomSubscription.dataReference` pinned on an old
  `uuid`, and tell the subscribers. Which of the two is the owner's call, a C6 question for GL; (a) is the one
  that needs no hand SQL. C6 for GL (2026-10-22) left GL's `niord.s124.producer-code` at `DK00`, DK's code,
  since GL's own was not known from its repository or database. The owner ruled the same day that DK and GL
  both use the producer code `DK01` and name the Danish Emergency Management Agency as the producing agency
  (`niord-gl` #3, `6b80842`; `niord-dk` #4, `68b95cf`), so GL's dataset file identifier is `124DK01…`, as
  DK's. niord_gl still holds no `S124Dataset` row (read 2026-10-22), and the (a)/(b) choice stays the deploy
  step's.
- **Listed exception:** `core/publication/series/legacy/` (`LegacyTemplateRulings.java:390-430`,
  `LegacyOrphanGrouping.java:93,98`) converts DK's legacy database by DK's own rulings. It stays in niord-base
  until DK's cutover, then moves to `niord-dk` or is deleted. This is a follow-up item, not this plan's. As built
  (P7-be) the backend list has no entry for the folder, because none of its words or patterns matches it
  (`Europe/Copenhagen`, the `Akkumuleret-EfS-${year}.pdf` name and the slugs are not words, and an unused entry
  fails the test), so the legacy folder is outside the list's reach by design rather than allow-listed (after the
  review of #204). `EfS` is not made a word, since it would hit D34's kept `EfS A` default.
- **The scan's allow-list (the backend half of the base product guard's, which niord-base's `CountryWordsTest`
  took over in P7-be, #206; as built at the end of this item).** The
  guard's word list (country-split plan §4.8: `Beredskab`, `Søfart`, `brs.dk`, `dma.dk`, `t-dma`, `kl-GL`,
  `da-DK`, `Nuuk`, `DMA`, `Danish Maritime Authority`, `Denmark`/`Danmark`, `Greenland`/`Grønland`, `.dk`),
  run over every file under niord-base's `src/main` and `bootstrap/` that reads as UTF-8 (Java, XML, YAML,
  JSON, SQL, Markdown) with licence headers skipped (the leading block comment, or a line with `Copyright`),
  and a cross-check list (`DK`, `:dk:`, `Faroe`, `Copenhagen`, `niord-dk`/`niord-gl`, Danish as a language).
  The matching rule: case-insensitive, a regular-expression word boundary before each entry and none after
  (so `Søfart` matches `Søfartsstyrelsens`, `Greenland` matches `Greenlandic`, and `DMA` no longer matches
  `scheduledMail` or `landmark`), one count per line however many entries it holds. A word boundary at both
  ends gives 2 lines fewer after C5 (85: `Søfartsstyrelsens` and `Greenlandic`), and a boundary of letters and
  digits only at both ends 81, which also skips `.dk` after a host name. Every hit left after C5 is one of
  these, each with its reason (file:line against the tree after C5's backend changes):
  - *Third-party package name.* The S-100/S-124 bindings live in `dk.dma.niord.s100`: their imports and
    fully qualified names in `S124Mapper` (`:62-103`, `:117-118`), `S124StandardMappings` (`:23-28`),
    `S124ExchangeSetService` (`:31-35`), `S124RestService` (`:35`) and `S124Service` (`:37`); 57 lines.
  - *The listed exception* `core/publication/series/legacy/` (`LegacyOrphanGrouping:102,149`,
    `LegacySeriesTranslation:297`); 3 lines.
  - *A frozen release record:* `bootstrap/releases/2026-10-21-templates-from-database/texts.json:2090` (the old
    `pdf.nm_almanac.desc`; a release folder is never rewritten, §4.6); 1 line.
  - *Javadoc examples of an import or filter format, and an OpenAPI example that matches the public API
    manual:* `BatchMessageImportReader:50,53,88,93,99,103`, `BatchMsgArchiveImportReader:63,76`,
    `BatchMailingListImportReader:56-57`, `BatchPublicationCategoryImportReader:43,49,53`,
    `BatchPublicationImportReader:44,51,52`, `MailingListTrigger:120`, `PublicationIssuePickerVo:53`,
    `ApiRestService:125,131,464,499` (`dma-nw`).
  - *A comment naming where an algorithm or workaround came from:* `AreaService:623`,
    `PlainTextConverter:402-403`, `WmsProxyServlet:51`.
  - *An explanatory example:* `Area:232-233`, `AreaLineageDirective:45-46`, `PublicationSeries:921`,
    `SeriesValidator:378` (time-zone domains), `MessageMapImageGenerator:141` (the trial geometry off the
    Danish coast), `UTMCoordinateConversion:352`, `IssueAuditService:111`, `IssuePublishService:1153`,
    `PublishChecklistService:252` (Copenhagen as a time zone or UTM example), `SeriesKind:26`,
    `MessageSearchTextBuilder:67`, the `NAVTEX-DK` examples of `PromulgationManager`, `PromulgationType` and
    `PromulgationTypeService`, `SettingsService:451`.
  - *Danish as a language, not a country:* `MessagePostgresIndex:48,287,299`, and the publication code's
    comments on Danish titles and letters (`MessageNaming:51`, `PublicationUtils:133`,
    `IssueArchiveService:50`, `IssueEditService:187,198`, `IssueFileService:100`, `IssueListService:528`,
    `IssueMemberListService:200`, `IssuePickerService:303`, `IssuePublishService:1058,1129`,
    `IssueShape:95,318`, `PublicationSeries:584-585`, `PublishChecklistService:786`, `SeriesIdSlug:31,39,51`,
    `OneOffRestService:1026`, `PublicationIssueRestService:929,985`).
  - *An applied changeset's comment:* `V001__baseline_schema.sql:4` (checksum-pinned).
  - *The S-62 producer-code format example* 'e.g. DK00' in `S124Service`'s two error messages (`:98,113`).
  - *A link to the country repository where a regression test lives:* `SIFA-DK/niord-dk` in
    `NiordCoreDefaultsEnvironmentPostProcessor:36` (`CoreDefaultsPrecedenceITTest`, 'a country deployable's
    test'; reworded in C5b, §11 C5b).

  Outside the word list and left for the owner's later look (C6/P7): `pdf.nm_almanac.title` `EfS A` (DK's
  publication abbreviation) and the base file's map centre 56/11 (Denmark). Not in the scan's scope: the
  frontend, the user manuals under `guides`, and test sources' Danish fixtures. The line numbers
  above move with the code. Both stay unchanged in P7 (country-split plan D34, its open owner question): a
  changed repository default changes every deployment that never set it. Ruled on 2026-11-03 (decision 138): the
  map centre stays as the demo's; `EfS A` is neutralised once DK and GL set their own `en` value of the key, not
  yet the case, so both stay unchanged and the guard's reason holds. From 2026-11-03 (decision 141) the base
  value is the neutral `NtM Almanac`, and DK and GL carry `EfS A` in their own English texts by their own PRs
  (niord-dk #14, `1a4d3be4`, and niord-gl #12, `113ed0a4`, merged on 2026-11-03).
  **Taken over in P7-be (#206):** niord-base's `CountryWordsTest`, a unit test outside the country build group
  (the country-split plan's D21), holds the list by file and pattern, each with its reason, in
  `be/niord/src/test/resources/build/country-words.json`, over `be/niord/bootstrap`, `be/niord/src/main` and
  `be/niord-app/src/main` with this item's matching rule: 17 words, 2 patterns (a Danish locale chosen in a
  template, its JSON-escaped form included, and the place names, D20), 1 strip (the `dk.dma.niord.s100`
  package) and 22 allow entries, each used, 0 findings; 31 allowed and 55 stripped lines in 17 files, as
  measured in P7-be. Green again in P7-bs's unit run and its `verify-local` (#210).
- Verification: `S124Test` and a generic-app boot; the base product guard's word list (country-split plan §4.8)
  finds no Danish authority name in niord-base's `main` outside the listed exception and the allow-list above.
- Done when: niord-base names no country outside the listed exception.
- State (C5, 2026-10-22): met locally; staging at the end. The catalog and the release texts were regenerated
  in one run (`SettingsCatalogCurrentTest`, `ReleaseFilesCurrentTest` with the write flags): the catalog's
  diff is exactly `country` and `organisation` (`codeDefault` `DK`/`DMA` → `null`, `baseValue` → `XX`/`Niord`,
  `required` → `true`; 73 entries), `bootstrap/texts.json` changed only `pdf.nm_almanac.desc`, and nothing
  under `bootstrap/releases/` changed. Unit, one run of 18 classes: 176 tests, 0 failures, 1 skipped (in
  `S124ExchangeSetServiceTest`), among them `S124Test` 30 (country `SE`, organisation `Test Organisation`:
  nationality `SE`, MRN `:se:`, ids `SE.`; the agency from the text, else, also for a blank text, the
  organisation; a blank country refused naming the setting; `' gl '` trimmed and upper-cased to `GL`/`:gl:`),
  `S124MrnTest` 3 (`SE`, `GL`, lower-case input), `DefaultSettingsTest` 12 (the base file alone: no warning;
  the root `niord.json` or a country file without the key: the base value and a warning; a country file that
  sets it: no warning), `BaseProductSettingsCheckTest` (26 values) and `SettingsCatalogCurrentTest` (the
  `required` keys exactly `country` and `organisation`). The `required` rule was shown red once (§5.5) and
  green again once the file was restored. ITs, one run of 15 classes: 233 tests, 1 failure, the known
  Windows-only `SettingsServiceITTest.getPathBuildsPathFromStringValue`; the S-124 and SECOM ITs on the base
  value `XX` (`S124ServiceITTest`, `MessageCancellationWarningITTest`: nationality `XX`, `:xx:`) and with
  `S124Mrn.of(…, s124Service.country())` (`S124DatasetPipelineITTest`, `S124CancellationWarningITTest`);
  `ContactRestServiceITTest` (a `.dk` address imported with no language); `SettingsFreshDatabaseITTest` (an
  empty database: `country` `XX`, `organisation` `Niord`, ledger source `base`); the log shows the warnings
  for the test resources' root `niord.json`, which lacks both keys. `niord-app`'s `ApplicationWiringTest` 3 of
  3 against niord-base installed as `4.0.0-c5-local`. The administration app's `i18n-key-parity` and
  `dictionaries-page` specs green (2 files, 17 tests). Route A: DK's and GL's `main` (`a348be0`, `a3b22d0`),
  with the `<parent>` version set to `4.0.0-c5-local` in a copy, each started twice on a scratch copy of
  `niord_test` and `niord_gl` (the bare master changelog, SECOM off, own `NIORD_HOME`, the local Keycloak).
  The checksums are `md5(string_agg(path || '|' || coalesce(content, ''), ',' ORDER BY path))` over
  `"ScriptResource"`, and likewise over `"Setting"` (`setting_key || '|' || coalesce(setting_value, '')`, by
  `setting_key`) and `"Contact"` (`email || '|' || coalesce(language, '')`, by `email`). Each was compared
  before and after on the same database only: `ORDER BY` follows the database's collation, and a scratch
  copy made with `createdb` need not have the original's, so a digest is not comparable across databases
  and none is recorded here:

  | | DK | GL |
  |---|---|---|
  | `country`/`organisation` rows before (the originals read) | `DK`, `DMA` | `GL`, `DMA` |
  | Start-up refusal | none | none |
  | `required` warnings (root `niord.json`) | `country`, `organisation` | `organisation` |
  | First start added | `mail.safetynet.cancel` `en` (C2), `authServerTrustAllCertificates`, `batchMessageSeriesId` (C3b) | `mail.safetynet.cancel` `en`, `batchMessageSeriesId` |
  | `Setting` / `DictionaryEntry` / `DictionaryEntryDesc` rows | 69 → 71 / 407 → 408 / 812 → 813 | 71 → 72 / 409 → 410 / 1053 → 1054 |
  | `country`/`organisation` rows after | unchanged | unchanged |
  | `ScriptResource` rows and md5 | 86, both unchanged | 102, both unchanged |
  | `ScriptResourceHistory` rows | 146, unchanged | 148, unchanged |
  | `GET /rest/settings/setting/country` | `DK` | `GL` |
  | Published short ids | unchanged (no series format uses `${country}`, no script reads `country`) | unchanged |
  | S-124 of `NW-319-26` | nationality `DK`, `urn:mrn:iho:nw:dk:nw-319-26`, ids `P.DK.` (as before) | nationality `GL`, `urn:mrn:iho:nw:gl:nw-319-26`, ids `P.GL.` (was `DK`; S3) |
  | A `.dk` address through `import-emails` | `Contact.language` `NULL` | `Contact.language` `NULL` |
  | Second start | row counts and the three checksums identical | row counts and the three checksums identical |

  Both databases hold 0 `S124Dataset` and 0 `S124SeriesConfig` rows. No root `niord.json` was edited, since
  the C5 base product starts both countries; C6 adds the keys in the country repositories (§9). The originals'
  counts and checksums were as before. Generic boot: the `niord-app` jar (`4.0.0-c5-local`) on an empty
  scratch database wrote `country` `XX` and `organisation` `Niord` with ledger source `base`, logged no
  `required` warning and no error, and holds the new `pdf.nm_almanac.desc`. Scan (the method and matching rule
  above): word-list hit lines outside licence headers 93 before C5, 87 after, every one in the allow-list; a
  rerun of the rule on 2026-10-22 gives 87 on this branch and 93 on `origin/feature/code-data-config` (an
  earlier rerun over a `git archive` extract of it gave 92; the one line was not traced). Licence-header lines: 603 with a word-list hit, 778
  with a word-list or cross-check hit, before and after. The cross-check totals (174 → 160 hit lines in
  62 → 56 files) are indicative only: their exact patterns were not kept, and a rerun with the word-list rule
  gives 177 → 162; P7's guard uses the word list (`CountryWordsTest`, P7-be). The scratch databases,
  the country copies and homes and every `4.0.0-c5-local` artifact were removed, `niord-db` and
  `niord-keycloak` stopped again as they were found, and no process was left. After the review (2026-10-22),
  `S124Service.country()` also refuses a value that is not two letters and `responsibleAgency()` a blank
  `organisation` without the text, each naming the setting: `S124Test` 32 (`'D K'`, `'DK.'`, `'DNK'`, `'D'`
  refused; `organisation` `null`, `""` and `"  "` with a blank text refused), `S124ExchangeSetServiceTest` 16
  (1 skipped, as before), `S124MrnTest` 3, 0 failures; the ITs `S124ServiceITTest` 10,
  `S124CancellationWarningITTest` 2, `S124DatasetPipelineITTest` 17 and `SecomPostGetITTest` 7, 36 tests, 0
  failures. The full suite (`./verify-local --base origin/feature/code-data-config`, at `75472611`,
  2026-10-22) passed: the backend's 4,996 unit tests and 3,324 ITs with only the 10 known Windows-only
  failures, and every administration-app step. Staging, the generic app's boot there and GL's `S124Dataset` rows (with the action above): at the end.
  After the review of PR #179 (2026-10-22), under this phase's done-when (niord-base names no country), which
  the word list had not caught: the base product bootstrap's `dgps` and `dgps_station_list` parameter types and
  the four `radio_navigation.dgps.*` station entries they use (Skagen, Blåvand, Hammer Odde, Tórshavn) removed,
  32 parameter types and 111 `aton` entries left (§7.1; DK's bootstrap carries them from C6, `--demo` keeps
  them from its archive); and `tmpl/object-adrift.ftl`'s two `renderDate` calls with `lang="da"` set to `"en"`,
  as every other call in the file (the template's locale was switched to Danish; the output was unchanged only
  because the NAVTEX branch formats with `Locale.US`). Only `bootstrap/templates/` changed: the frozen release
  folder keeps its copy, and no new release folder is made, since the output is the same. Both reach only a
  fresh database (R-CDC1). `BootstrapFolderTest`, `BootstrapTemplatesParseTest`, `ScriptResourceCheckTest`
  and `ReleaseFilesCurrentTest` green in the backend package's unit run (C5b's paragraph after the review).
  P7's guard took over `lang="da"`, `locale=da` and these place names as patterns (`CountryWordsTest`, P7-be,
  #206; country-split plan P7, D20), the frozen release folder's copy of `object-adrift.ftl` allow-listed;
  the base bootstrap's 170 `da` descriptions stay until C6 (§7.1; counted as 176 here until DK's C6 recount,
  which found the other 6 gone with the DGPS data). C6's base product half removed them (2026-10-22).

**C5b — the country contract, the base product's half** (after C5, its own sub-PR; the backend parts of P5
and P6 only consume it, by moving their `<parent>`).
- Changes:
  - the security test group in `niord-base`'s test-jar — `RestControllerSecurityAnnotationTest`,
    `RestHandlerSecurityITTest`, the `SecurityConfig` tests, the sanitiser and `MailHtml` tests, the start-up
    guard's tests — which `niord-parent` runs in every country build (country-split plan §4.11 rule 3);
  - the start-up guard in `niord-base`: the protected beans and properties of that rule, and the refusal of a
    handler method that resolves no JSR-250 annotation;
  - the backend override check in `niord-image-tools` (country-split plan §4.9);
  - `be/docs/country-api.md`: the interfaces meant for a country, the `defaults/country/` contract, the
    settings file and its checks, the bootstrap and release folders, the script allow-list.
- As built (2026-10-22):
  - **The country build group.** The members carry `@CountryBuildGroup` (`org.niord.test`, the JUnit tag
    `niord-country-build`): `RestControllerSecurityAnnotationTest`, `RestHandlerSecurityITTest`,
    `SecurityConfigJwtTest`, `SecurityConfigFrameOptionsTest`, `JwtAuthorizedPartyITTest`,
    `MethodSecurityOrderingITTest`, `NiordDomainFilterTest`, `WebAppConfigTest`, `MailHtmlTest` (the HTML
    sanitiser of mails), `FmTemplateClassResolverTest`, `CountryContractGuardTest`,
    `CountryContractStartupTest` and `CountryDefaultsChecksTest`, 13 classes; `CountryBuildGroupTest`
    (untagged) pins exactly that set and `niord-parent`'s wiring. `CountryDefaultsChecksTest` is the country
    side of §4.7 and §5.5 (the text and settings checks over the country's class path), added to the list
    above because those sections and §12 have a country's build run the checks from C5b; it skips itself until
    the country has `defaults/country/` (C6). `MailHtmlTest`'s frontend-parity method skips itself outside a
    niord-ng tree. `niord-parent` (`be/pom.xml`) runs the group with a second Surefire execution
    (`niord-country-build`) and a second Failsafe one (`niord-country-build-it`), each scanning only
    `org.niord.base:niord-base:test-jar:tests` with `groups` `niord-country-build`, `failIfNoSpecifiedTests`
    `false` (a country's `-Dtest=<its own test>` still runs), the Surefire one without output redirection
    (§5.5) and the Failsafe one with a summary file of its own; the default executions are unchanged. Both are
    switched by `niord.countryGroup.skip`: `false` in the parent, `true` in niord-base (which runs the same
    tests in its own suites), `niord-app` (no IT test dependencies; niord-base runs the same tests on the same
    code) and `niord-bootstrap`; the root pom sets it for itself through the profile `niord-base-reactor`,
    active only beside `niord-bootstrap/src/assembly/bootstrap.xml` (Surefire refuses `groups` on a
    pom-packaging project), so no country activates it. The test-jar carries the fixtures
    (`country-contract/*.yml`, and `org/example/country/**`, outside `org.niord` because
    `NiordTestApplication` scans `org.niord`).
  - **The start-up guard.** `CountryContractGuard` (the checks, each a static method returning findings) and
    `CountryContractInitializer`, an `ApplicationContextInitializer` registered in niord-base's
    `META-INF/spring.factories`, so it applies whatever packages a deployable scans. Properties and class
    origins are checked in `initialize()`, before any bean definition is read; the protected bean definitions
    in a registry post-processor, once every definition is registered and before any bean is created; every
    handler of every `RequestMappingHandlerMapping`, and the beans again by their instances' types, in the bean
    `niordCountryContractCheck` (a `SmartInitializingSingleton`), before the web server starts. A refusal is
    one `IllegalStateException` beginning 'Niord country contract: the start is refused, N findings
    (be/docs/country-api.md)', one line per finding. 'From niord-base' means declared by a class (the factory
    method's class, else the bean class, a CGLIB class unwrapped) that loads from niord-base's code source,
    since a package prefix cannot tell niord-base from a country's `org.niord.dk`. The handler rule resolves
    annotations with `CountryContractGuard.JSR250` (`SecurityAnnotationScanners.requireUnique`, the scanner
    `Jsr250AuthorizationManager` uses) on the method and the bean's user type; competing annotations and
    `@CrossOrigin` are findings; `REVIEWED_FRAMEWORK_HANDLERS` (`BasicErrorController`) moved here from
    `RestHandlerSecurityITTest` and is the one list both tests use; role names are not checked (a misspelt role
    fails closed). The protected classes, `SecurityConfig` and the guard itself must load once, from
    niord-base (country-split plan §4.9).
  - **The override check.** `be/k8s/scripts/CheckOverrides.java`, a single-file program run in the JDK's
    source-launch mode (JDK built-ins only), and its wrapper `check-overrides.sh`, which finds `java` as
    `split-jar.sh` does; `publish.yml` packs both into the `niord-image-tools` zip. Usage, from a country's
    `be/`: `target/k8s/scripts/check-overrides.sh target/<jar> .` (wherever the workflow unpacked
    `niord-image-tools`). It compares the country's files (`BOOT-INF/classes/**`, and the
    root `META-INF/**` that Spring Boot's repackaging keeps outside `BOOT-INF/classes/`, its
    `META-INF/resources/` images among them) with the one `BOOT-INF/lib/niord-base-*.jar`, and reports, each
    its own finding: `unlisted-shadow` (printing niord-base's fingerprint to record), `forbidden-shadow`,
    `template-in-jar`, `defaults-base`, `committed-packs`, `stale-basedOn` and `format`; files read together
    (the manifest, `META-INF/maven/**`, `spring.factories`, `spring/*.imports`, `services/**`, Spring's
    configuration metadata, `META-INF/niord/expected-template-paths.txt`) are not shadows. Exit 0 passes, 1 is
    a finding, 2 is wrong usage, an unreadable jar or one with no `BOOT-INF/classes/` (not a Spring Boot
    executable jar); an `overrides.json` that starts with a byte order mark is read. The format of `be/overrides.json` is the frontend
    manifest's override entry without `with` (`file`, `basedOn`, `reason`, `added`), strict.
  - `be/docs/country-api.md`, held to the code's lists (`ALLOWED_BEAN_TYPES`, the guard's protected names
    and property rules, the group's members, the check's rules) by `CountryApiDocTest`; `be-ci.yml`
    re-includes the file in its path filters so a change to it alone runs Backend CI.
  - `retract-version.yml` deletes `org.niord.niord-bootstrap` too (the zip C4.2a publishes); a version without
    it reports it as not present, as before.
  - The comments that named the `niord-dk` module or the DK deployable reworded to 'a country deployable'
    (`Main`, `CorrelationConfig`, `TicketWebConfig`, `NiordCoreDefaultsEnvironmentPostProcessor`, which keeps
    the link `SIFA-DK/niord-dk`; `niord-core-defaults.yml:10,111-113`, its example producer code now `XX00`);
    `BatchService`'s obsolete Wildfly note and its `NiordOrg/niord-dk` link deleted. They left §11 C5's
    allow-list, which keeps the one link.
  - `be/CLAUDE.md`, `be/docs/test-infrastructure.md`, `be/docs/test-authoring-guide.md`,
    `be/docs/cicd/design.md`, `be/docs/cicd/implementation-plan.md` and `be/k8s/README.md` name the contract,
    the group and the check; `ScriptResourceCheck`'s Javadoc points at `be/docs/country-api.md`.
- Decisions (C5b):
  - **Protected properties are refused where a country packages them**: in property sources read from a
    class-path resource (a jar's `application*.yml` or `.properties`, `niord-core-defaults.yml`). A deployment's
    own configuration (environment, system properties, command line, `SPRING_APPLICATION_JSON`, a `file:`
    location such as Helm's `/config/application.yaml`) is not refused: literal enforcement would refuse the
    base product's own Helm deployment, which sets the issuer and key set there, and staging's
    `NIORD_OIDC_ALLOWED_CLIENTS`. `spring.security.oauth2.resourceserver.jwt.issuer-uri` is allowed from the
    class path, because `niord-app`'s, DK's and GL's `application-local.yml` set it for a local run (the `azp`
    validator and the base-client mapping still apply); `jwk-set-uri` stays refused. 'Only with a reason' is a
    non-blank `niord.oidc.allowed-clients-reason` in the same class-path file, in whichever of its `---`
    documents; a reason in another file does not count. `management.endpoint.*.enabled` also
    covers Spring Boot 4's `management.endpoint.<id>.access`, `management.endpoints.access.*` and
    `enabled-by-default`; `exposure.include` may hold only `health`, `exposure.exclude` is accepted; names
    are compared in Spring Boot's relaxed form, indexed list forms included.
  - **'The method-security configuration'** is a bean class outside niord-base with `@EnableMethodSecurity` or
    `@EnableGlobalMethodSecurity`, and a `RoleHierarchy` or `MethodSecurityExpressionHandler` bean declared
    outside niord-base and Spring Security (a role hierarchy would widen every `@RolesAllowed`). **'Any CORS
    configuration'** is a `CorsConfigurationSource` or `CorsFilter` bean declared outside the Spring framework
    (niord-base's own included; Spring MVC's `mvcHandlerMappingIntrospector` is the framework's), a
    `WebMvcConfigurer` that overrides `addCorsMappings` unless it is niord-base's or Spring's, a handler with
    `@CrossOrigin`, and `management.endpoints.web.cors.*` from the class path.
  - **The check is a step of the country's workflow, not of the Maven build.** `niord-image-tools` is a
    deploy-file zip, not a reactor artefact, so `niord-parent` cannot resolve it in a local SNAPSHOT build.
    A country's workflow runs it after the package step, as it runs `split-jar.sh`; C6 adds the step to DK's
    and GL's `ci.yml` and `image.yml`. Done-when's 'runs … the check in its build' therefore holds from C6; the
    group comes with the parent from C5b.
  - **No `failIfNoTests`** on the group's executions: it would make a country without the test-jar fail
    loudly, but also fail any `-Dtest=<its own test>`. A country reads the group's counts in its log;
    `be/docs/country-api.md` says so.
  - Out of the guard, as §4.11 rule 3 does not list them: HTTP surfaces outside Spring MVC's handler mappings
    (servlets and `ServletRegistrationBean`s, filters, `RouterFunction`s, `HttpRequestHandler`s, the actuator's
    own mapping; `NonControllerHttpSurfaceInventoryTest`'s subject) and SECOM's RESTEasy endpoints. The handler
    rule covers the handler methods of Spring MVC's `RequestMappingHandlerMapping` beans, whatever their
    package; a country's only way to add an endpoint is an annotated `@RestController`, and anything else is
    refused by review. Likewise not checked, and refused by review: `jakarta.servlet.Filter` and
    `FilterRegistrationBean`, `SecurityContextHolderStrategy` and `BearerTokenResolver` beans, and a
    `MapPropertySource` a jar's own `EnvironmentPostProcessor` adds (after the review of PR #179, which found
    `be/docs/country-api.md`'s "covers every handler, in any package" wider than the code; the document now
    says this).
  - **`@PropertySource` is refused** (after the review of PR #179): a bean class declared outside niord-base
    and `org.springframework.` that carries `@PropertySource` or `@PropertySources` (merged annotations over
    its type hierarchy) is a finding, since such a file reaches the environment after the property check has
    run and would let a jar package a protected property past it ('a jar's configuration goes in
    `application*.yml`, where the guard reads it'). DK and GL use none.
  - **The override check permits only static images** (after the review of PR #179, which reproduced a listed
    `META-INF/batch-jobs/settings-import.xml` with niord-base's fingerprint passing the check). The decided rule
    (country-split plan §4.9: "Guarded overrides are what is left: a static image at a base product path";
    §4.11 rule 3) is enforced literally: after `templates/` (`template-in-jar`), `defaults/base/` and the
    names read together, a shadow is permitted only when it is a `png`, `jpg`, `jpeg`, `gif`, `webp` or `ico`
    (any case) under `META-INF/resources/img/`, and then only when listed (`unlisted-shadow` otherwise); every
    other shadow, listed or not, fingerprint right or not, is `forbidden-shadow` ('only a static image under
    META-INF/resources/img/ … may be overridden; this file is code, configuration or data: ask for an
    extension point'). SVG is excluded on purpose (a same-origin SVG is a document that can run script;
    niord-base ships none), and so is the root `blank_256.png` (a code asset of `AtonTileRestService`, not
    branding). DK's and GL's jars as route A saw them (56 and 108 `template-in-jar` plus the logo, §9.1, §9.2)
    hold no other shadow, so §9's classification stands and their template shadows move to their bootstraps
    in C6, as planned; the check's refusal of them is C6's. `be/docs/country-api.md`'s rule list says the same,
    and `CountryApiDocTest` holds it, the protected beans and the property table to the code both ways.
- Verification: a scratch country module built against the branch's `niord-parent` fails on each of a country
  `SecurityFilterChain` or `JwtDecoder` bean, a protected property, an unannotated controller outside
  `org.niord` and an unlisted shadow, and builds and starts without them; `niord-app` starts unchanged.
- Done when: a published base product version carries all four, and a country that moves its `<parent>` to it
  runs the group and the check in its build and cannot start with a protected bean or property replaced. The
  check runs in a country's build from its C6 (the workflow step above).
- State (C5b, 2026-10-22): met locally; the full suite (`./verify-local --base origin/feature/code-data-config`, at
  `79c2f184`) passed: the backend's 5,053 unit tests and 3,325 ITs with only the 10 known Windows-only
  failures; staging and the publish
  at the end (the publish and a retract are deploy-step items; no retract is run for this). Unit, one run of
  21 classes: 161 tests, 0 failures, 1 skipped (`CountryDefaultsChecksTest`: no `defaults/country/`), among
  them `CountryContractGuardTest` 27, `CountryContractStartupTest` 4, `CheckOverridesTest` 14 (one negative
  test per rule, the positive cases and one source-launch run), `CountryApiDocTest` 5 and
  `CountryBuildGroupTest` 3; and `NonControllerHttpSurfaceInventoryTest` with
  `PublicationExceptionMapperContractTest`, 8 tests, green. ITs, two runs: `RestHandlerSecurityITTest` 2,
  `JwtAuthorizedPartyITTest` 3, `MethodSecurityOrderingITTest` 9, `SettingsFreshDatabaseITTest` 3 and
  `LiquibaseBootstrapITTest` 1 (18 tests), and `ActuatorEndpointAuthorizationITTest`, `BootstrapITTest`,
  `SecomPostGetITTest` and `IssueRenderRealITTest` (19 tests: inline properties, SECOM's own
  `SecurityFilterChain`, a `BeanFactoryPostProcessor`), 0 failures; every context started with the guard in
  it. Each new rule shown red once and restored: `GrantedAuthorityDefaults` taken off the protected types
  (`aCountrysGrantedAuthorityDefaultsIsRefused` red), `@CountryBuildGroup` taken off `WebAppConfigTest`
  (`CountryBuildGroupTest` red), a wrong expected fingerprint (`CheckOverridesTest.aStaleFingerprintFails`
  red). The root pom alone (`./mvnw -N verify`) first failed on Surefire's refusal of `groups` on a pom
  project and passed with the `niord-base-reactor` profile, the group's executions skipped. Installed as
  `4.0.0-c5b-local` (`niord-parent`, niord-base, `niord-bootstrap`): the parent pom carries both executions,
  niord-base's jar the guard and the initializer key, the test-jar the 13 tagged classes and the fixtures.
  `niord-app`: `ApplicationWiringTest` 3 of 3; the jar, with the local profile, a scratch `user.home` and an
  empty scratch database, started in 23.3 s with no refusal and no `ERROR` line. A scratch country module
  (DK's dependencies; an application class scanning `org.niord` and a package of its own outside it):
  `./mvnw verify` ran the group, 10 unit classes with 109 tests (2 skipped where they do not apply) and 3 IT
  classes with 14 tests, and none of niord-base's other tests; `-Dtest=ScratchOwnTest` passed with the group
  at 0 tests and no failure; it started on an empty database in 24.2 s with no refusal; each of a
  `SecurityFilterChain` bean, a `JwtDecoder` bean, `management.server.port` in its `application.yml` and an
  unannotated `@RestController` outside `org.niord` refused the start, naming it, Tomcat never started. The
  check, from a zip built with `publish.yml`'s file list, exited 0 on the clean jar, 1 with `unlisted-shadow`
  once `META-INF/resources/img/company-logo.png` was added, and 0 once that was listed with the printed
  fingerprint. Route A: DK's and GL's `main` (`a348be0`, `a3b22d0`) copied with the `<parent>` set to
  `4.0.0-c5b-local`, on scratch copies of `niord_test` and `niord_gl`:

  | | DK | GL |
  |---|---|---|
  | `./mvnw verify` (`-fae`) | the group green: 109 unit tests (2 skipped), 14 ITs; its own: 10 unit tests green, 12 ITs with 3 errors, `DkDgpsTemplateITTest` (2) and `DkLightDestroyedTemplateITTest` (1), 'Template not found' or 'Invalid JavaScript resource path': templates are read from the database only since C4.2a, a C6 item | BUILD SUCCESS, but the group ran 0 tests: GL's `pom.xml` declares neither niord-base's test-jar nor the IT test dependencies (a C6 item) |
  | Start-up refusal (two starts) | none | none |
  | First start added | `authServerTrustAllCertificates`, `batchMessageSeriesId` and one dictionary entry, as C5 recorded | `batchMessageSeriesId` and one dictionary entry, as C5 recorded |
  | Existing `Setting` rows | none changed | none changed |
  | `ScriptResource` rows and md5 | 86, both unchanged | 102, both unchanged |
  | `ScriptResourceHistory` / `Contact` rows | 146 / 0, unchanged | 148 / 0, unchanged |
  | Second start | row counts and the three checksums identical | row counts and the three checksums identical |
  | `check-overrides.sh` over the jar | exit 1: 56 `template-in-jar`, 1 `unlisted-shadow` (the logo) | exit 1: 108 `template-in-jar`, 1 `unlisted-shadow` (the logo) |

  The checksums are C5's (§11 C5), compared before and after on the same database only. The only `ERROR` line
  in the starts was the Lucene index error already recorded as LUCENE-2 in `be/docs/potential-errors.md`.
  The check's findings are exactly §9's (§9.1, §9.2). Neither the guard nor the group refused anything DK or
  GL legitimately has, so nothing was narrowed beyond the decisions above; the two route-A gaps are C6 items.
  A grep of niord-base's `src/main` for `niord-dk` and `DK deployable` finds only the allow-listed link and
  `V001__baseline_schema.sql:4`; C5's full word-list scan was not rerun. The four scratch databases were
  dropped and the originals' counts were as before (`Setting` 69 and 71, `ScriptResource` 86 and 102); every
  copy, home, the scratch module, the zip and the three `4.0.0-c5b-local` folders were deleted; `niord-db`
  and `niord-keycloak` were stopped again as they were found, and no process was left.
  After the review (2026-10-22): `CountryContractStartupTest` starts a servlet application (port 0) with an
  unannotated controller outside `org.niord` and is refused before the server listens, naming the handler;
  the check reports a jar with no `BOOT-INF/classes/` as not a Spring Boot executable jar (exit 2) and reads an
  `overrides.json` that starts with a byte order mark; the `allowed-clients` reason counts per class-path file;
  `check-overrides.sh` is recorded executable (`100755`). One unit run of the five contract classes: 56 tests,
  0 failures (`CheckOverridesTest` 15, `CountryApiDocTest` 5, `CountryContractGuardTest` 28,
  `CountryContractStartupTest` 5, `CountryBuildGroupTest` 3). The scratch module and route A above ran before
  these changes and were not repeated.
  After the review of PR #179 (2026-10-22; both reviews' items, the first reviewer's four blocking ones first).
  The override check permits only the static images of the decision above; the bootstrap runner waits, past a
  job's deadline, for the execution to end before the run is `failed`, and the claim takes over an
  `in-progress` with no sign of life for 5 minutes (§7.2; until the owner's ruling of 2026-10-24, §7.2, *No
  retry*, which removed the takeover); `@PropertySource` is refused; `be/docs/country-api.md`
  narrowed to what the code checks (the MVC handlers, the hooks refused by review, the group running where the
  test-jar is declared, which GL does from C6) and held to the code both ways; and the import, settings, report
  and script-check items of §4.2, §4.6, §6.2, §6.4 and §6.5. Unit, one run of 19 classes after every change:
  180 tests, 0 failures (`CheckOverridesTest` 18, `CountryApiDocTest` 8, `CountryContractGuardTest` 29,
  `CountryContractStartupTest` 6, `BootstrapRunnerTest` 4, `BootstrapArchiveTest` 24, `ScriptResourceCheckTest`
  8, `DefaultTextsChecksTest` 20, with `CountryBuildGroupTest`, `BootstrapFolderTest`,
  `BootstrapTemplatesParseTest`, `BaseProductTextsCheckTest`, `RequiredTextsTest`, `RequiredTemplatesTest`,
  `ReleaseFilesCurrentTest`, `CoreResourcePackagingTest` and the two import processor tests and `DictionaryVoTest`).
  ITs, one run of 19 classes: 283 tests, 1 failure, the known Windows-only
  `SettingsServiceITTest.getPathBuildsPathFromStringValue` (`BootstrapITTest` 8: a fresh `in-progress` refused,
  one touched 6 minutes ago claimed, one touched 4 minutes ago refused, the heartbeat and the note scoped to
  their run, a failing archive posted through `/rest/bootstrap` and polled to `failed`; `DictionaryImportITTest`
  8, `ScriptResourceImportITTest` 10, `MissingPathReportITTest` 7, `MessageReportRestServiceITTest` 26,
  `DictionaryRestServiceITTest` 32, `SettingsRestServiceITTest` 34, `BatchSettingsImportProcessorITTest` 10,
  `LiquibaseBootstrapITTest` 1, `BatchSetServiceITTest` 8, `BatchRestActionITTest` 16, among them). Each new rule
  shown red once, with temporary reverts restored byte for byte (9 expected failures): the old `forbidden()`
  let a listed `META-INF/batch-jobs/settings-import.xml` with the right fingerprint, a listed shadow that is no
  permitted image and a forbidden shadow even when listed pass with exit 0; the old return at the deadline
  failed `BootstrapRunnerTest` cases 1 and 2 (`markFailed` while the execution still read `STARTED`, no stop);
  a name only in `country-api.md`'s protected-beans sentence failed `CountryApiDocTest`; the `@PropertySource`
  rule switched off failed `CountryContractGuardTest` and `CountryContractStartupTest` (nothing thrown); the
  old template-key pattern missed `text['k']` (`DefaultTextsChecksTest`). The two new script rules, run once
  read-only over the 12 backend `.js` files of DK's and GL's `main` (`a348be0`, `a3b22d0`) and the base
  bootstrap, refuse none. `bash -n` passes for `bootstrap.sh`, `start-local.sh` and
  `regen-test-seed-postgres.sh`. The administration app's runs and the manual check of both import dialogs
  are in §11 C4.2b's State. Not re-run after these changes: a scratch country module and route A; DK's and
  GL's classification is unchanged by the stricter check, since route A
  reported no shadow but the templates and the logo.
  A second round, on the review of these fixes (2026-10-22): in the runner's wait past the deadline, the marker
  writes (the note and the heartbeat) no longer end the wait when they throw (§7.2), where before such an error
  reached the run's handler and marked the run `failed` while the execution still ran; `BootstrapRunnerTest`
  checks the order of stop, the polls and `markFailed` with `InOrder` and the poll at which `markFailed` came,
  in the deadline, interruption and failing-write cases. `be/docs/country-api.md`'s single C5b row of the
  Changes table now names what C5b checks: only a listed static image under `META-INF/resources/img/` may be
  shadowed, every other shadow is `forbidden-shadow` listed or not, no `@PropertySource`, the two script rules
  refused at import, no `add` or `overwrite` on a batch-set item, and the multipart limits a deployment raises
  for an archive over 1 MB. One unit run of `BootstrapRunnerTest` and `CountryApiDocTest`: 13 tests, 0 failures
  (5 and 8); the new runner case shown red once with the guard removed, the file restored afterwards.
  `BootstrapITTest` was not repeated for this round. The whole suite then ran once over both rounds and the
  leftovers (the shared helpers, `be/.gitattributes`, BOOT1 and the two backlog entries): `./verify-local` at `25df0067` passed in 14 min 47 s (backend unit 5,070 tests and integration 3,340, with only the 10 known Windows-only failures; every step of the administration app).

**C6 — the countries (the backend parts of P5 for DK and P6 for GL), with a base product half.**
- Changes, per repository (§9):
  - `defaults/country/` with the settings file (today's keys plus `country` and `organisation`, §9) and the texts;
  - the packs' backend half (`da` in DK, `kl` in GL, GL pinning `da`): copied in place from the home pack's
    `fe/packs/<lang>/backend/` by the countries' frontend moves (P5, P6), and read from the staged
    `fe/.niord/packs/<lang>/backend/`, with `setup.mjs` run before the Maven build, from the kit version that
    adds a backend job to the templates (§4.3); the backend parts built first (2026-10-22) left the root
    `*_da`/`*_kl` bundles to the transitional layer until the moves (P5b, `niord-dk` #5; P6b, `niord-gl` #4).
    GL's `da` waited for `niord-lang-da`, which GL pins since P6e (2026-10-24), and waits for staging a pinned
    pack before the Maven build: P6b kept GL's root
    `*_da` and `img/flags/da.png`, GL's own Danish wording being in `defaults/country/texts/da/` since then;
  - `be/bootstrap/` with `be/bootstrap/releases/<yyyy-mm-dd>-<topic>/` (§6.4), and its own
    `META-INF/niord/expected-template-paths.txt`, so its localized variants join the missing-path report (§6.6)
    and `BootstrapTemplatesParseTest`'s check covers its bootstrap (§6.5);
  - `be/overrides.json` with images only (templates are bootstrap data), in the format of
    `be/docs/country-api.md` (*Overrides*): today `META-INF/resources/img/company-logo.png` in each, with the
    fingerprint the check prints (§9.1, §9.2);
  - the override check as a step after the package step in each country's `ci.yml` and `image.yml`
    (`target/k8s/scripts/check-overrides.sh target/<jar> .` from `be/`, with the scripts of the pinned
    `niord-image-tools` where the workflow unpacks it — DK's and GL's `image.yml` unpack it into `target/k8s/`
    for `split-jar.sh` — as `split-jar.sh` is run), so the check runs in its build (C5b's Done when); since the
    review of PR #179 the check refuses every shadow but a static image under `META-INF/resources/img/`, so the
    template shadows that leave the jar for `bootstrap/` (§9) are what makes it pass;
  - in `ci.yml`, beside it, a scripted count of the country build group (review of PR #179), one script in the
    country's repository (`be/scripts/check-country-build-group.sh`) that CI and a local check both run: it
    fails unless `target/failsafe-reports/failsafe-summary-niord-country-build.xml` reports more than 0
    completed tests and fewer skipped than completed (Failsafe counts skipped tests as completed, so a run
    without Docker would pass otherwise), and a Surefire report of `CountryContractGuardTest` exists, since the
    group's executions have no `failIfNoTests` (§11 C5b) and a country without the test-jar runs 0 tests with a
    green build. `image.yml` has no count: it packages with `-DskipTests` and follows a green CI run, which has
    counted;
  - the `<parent>` moved to a base product version with C5b, which runs the country build group; GL's
    `pom.xml` declares niord-base's test-jar (type `test-jar`, scope `test`) and the test dependencies
    `be/docs/country-api.md` lists, as DK's does, since without them the group runs 0 tests there (route A,
    §11 C5b); each keeps `spring.security.oauth2.resourceserver.jwt.issuer-uri` as the only `spring.security.*`
    key, and sets no other protected property, in its jar's configuration;
  - DK's and GL's `wmsPassword` placeholders emptied in their settings files;
  - DK's `application-local.yml:18-19` stops selecting `changelog-with-seed.yaml` (§9.1): since C4.1 that
    changelog is in niord-base's test-jar only, so a DK developer run with the `local` profile fails at
    Liquibase on a C4.1+ base product unless `-Dspring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml`
    is passed; the deployed jar (no profile, the master changelog) starts unchanged (review of PR #179);
  - DK's bootstrap carries the DGPS data the base product's left in the review of PR #179: the parameter types
    `dgps` and `dgps_station_list` and the four `radio_navigation.dgps.*` station entries (Skagen, Blåvand,
    Hammer Odde, Tórshavn) of `aton-dict.json`, with DK's `tmpl/dgps.{ftl,js}` (§7.1); GL's `dgps.js` names the
    same four and its `dgps.ftl` the two types, so GL's bootstrap carries all of it too, with GL's `kl`
    descriptions (C6, 2026-10-22);
  - the base product bootstrap's 170 Danish (`da`) descriptions (§7.1) move to DK's bootstrap, with the rule
    that lets a country's bootstrap add a missing language to a key the base created (still add-only, on a
    fresh database). DK's half is done (2026-10-22): the 111 `aton` descriptions as `key` plus `da` entries,
    inert on a 4.0.76 base and applied from the base product version with the rule; the 35 parameter-type
    descriptions inside the 13 types carried whole, since the 4.0.76 import replaces a type's template
    parameters; the 24 category descriptions not carried, since the 4.0.76 category import replaces a category
    whole (from the base product version with the rule a country's `categories.json` adds them, DK's next pin
    bump; carried as additions since DK's bump to 4.0.87, P5e). The base product half is done too (below);
  - **the base product half** (`feature/c6-base`, 2026-10-22): the same-run rule in the bootstrap runner
    (§7.2, *Within one run*: `BootstrapRun`, the run token in every job's properties and refused in an archive
    item, the additions in the script-resource, dictionary, category, parameter-type, promulgation-type and
    report imports); the base bootstrap English only, 170 `da` descriptions removed by a re-dump whose diff is
    deletions only (`aton-dict.json` 111, `categories.json` 24 = 13 category names and 11 template-parameter
    names, `parameter-types.json` 35), `be/niord/bootstrap/README.md` saying so and stating the same-run rule,
    and `BootstrapFolderTest.theDataIsInEnglishOnly`; the tests of §7.2; `be/docs/country-api.md` (GL's four
    stale sentences, the group count with skipped below completed, what DK's and GL's bootstraps hold, the
    `niord-bootstrap` unpack and the IT template-completion pattern of §7.4, and the same-run rule as the
    countries' contract: what a country's archive may add within the run, what it may not, and how each
    addition names its base entry; a Changes row); `be/docs/potential-errors.md` (SCRIPT8 deleted, CDI1 added,
    TESTQ1 narrowed); `be/CLAUDE.md` codifying the rule; and the comments of `publish.yml` and
    `retract-version.yml` that said Renovate on GitLab bumps the deploy repository (decided against on
    2026-10-03, `docs/renovate-plan.md`; a staging deploy is devops-niord's `scripts/staging-bump.py`,
    `docs/pipeline-latency-plan.md` item 17), comments only;
  - GL's `fetch-firing-exercises.js` changed to resolve `MessageSearchService` (§6.5, SCRIPT8);
  - GL's pipeline ended;
  - the country tests, among them DK's that relied on templates in the jar and copy-on-use until C4.2a
    (`DkDgpsTemplateITTest`, `DkLightDestroyedTemplateITTest`, `DkLightCharacterTest`,
    `DkTemplateResourcesTest`), which get their rows from DK's bootstrap (on C5b's route A,
    `DkDgpsTemplateITTest` and `DkLightDestroyedTemplateITTest` error with 'Template not found' or 'Invalid
    JavaScript resource path', 3 tests, as expected since C4.2a);
  - a country's own settings-catalog current-check, as niord-base's `SettingsCatalogCurrentTest`, once it
    declares keys of its own;
  - the footer's two English dictionary rows (review of PR #184): DK's and GL's `footer.copyright` and
    `footer.link` `web` rows in English (`&copy; 2025 Danish Emergency Management Agency`,
    `https://www.brs.dk/`) differ from the base product's default, so #179's start-up did not mark them
    `asAdded` (C2's record), and from the first base bump that carries P1's branding they mask the branding's
    footer in English (the dictionary is layer 5, above the branding's layer 4: country-split plan §4.10), the
    `&copy;` shown literally. C6 leaves the two keys out of each country's texts (`defaults/country/texts/en/`; the
    branding is their source since P1), and the bump PR tells each deployment's sysadmin to delete the two
    English rows on the Dictionaries page, checked with the footer in English afterwards. Nothing changes a
    deployment's rows by itself (R-CDC1). Danish is not affected: its rows equal the default and are `asAdded`.
- Verification:
  - each country boots on an empty database through the base product's bootstrap and its own;
  - on a copy of its staging database (for DK also of a database migrated from the legacy one; for GL, until
    staging is read, a copy of niord_gl, its local database; locally after the base product's and the
    country's release imports, adds only), the start changes no row: a before/after row-count and checksum
    comparison of `Dictionary*`, `Setting` and `ScriptResource*` allows only added keys;
  - its PDFs and mails render as before on that copy; locally, the "before" renderer is the country's `main`
    with its `<parent>` set to the new base version, since the earlier base version is not available without
    registry credentials.
- Done when: a country jar carries no template and no whole-file shadow, and its build runs the checks. For the
  base product half: the same-run rule built and tested, the base bootstrap English only, and
  `be/docs/country-api.md` stating the countries' contract under the rule.
- State (C6 for DK, 2026-10-22): met locally for DK; staging at the end. In `niord-dk` (#2 into
  `feature/p5-dk-moves`, `b5d8b31`), on the published base version 4.0.76 (niord-ng `81361ab5`):
  - **Built.** `be/pom.xml`'s `<parent>` moves from 4.0.66 to 4.0.76; no DK main code changed to build and start
    on it. A new execution `unpack-niord-bootstrap` (pre-integration-test, no dependency, so `package
    -DskipTests` never resolves it) unpacks `org.niord:niord-bootstrap:${project.version}:zip` into
    `target/niord-bootstrap/` for the ITs, after an execution `clean-niord-bootstrap` (maven-clean-plugin, the
    same phase, declared first) deletes the folder and the unpack's marker, so a pin bump never leaves an older
    base's files there. DK's 56 templates moved with `git mv` from `src/main/resources/templates/` to
    `be/bootstrap/templates/` (56 R100 renames, LF through a new `be/.gitattributes`, `bootstrap/** text=auto
    eol=lf`), unchanged: 42 shadows (all 42 differ from 4.0.76's base copies by DK's lines, 0 equal) and 14 own.
    `be/bootstrap/` in the base product's batch-set format, 6 jobs: `templates` (56), `domains.json` (the 5 DK
    domains, active, with their 6 series), `aton-dict.json` (115 entries: the 4 `radio_navigation.dgps.*`
    stations whole, plus 111 entries carrying only `key` and the `da` description of a base `aton` key),
    `parameter-types.json` (15: `dgps` and `dgps_station_list` whole, plus the 13 base composite types that
    have a Danish description, whole and unchanged, because the 4.0.76 import replaces a type's template
    parameters whole), `reports.json` (6: the seed's `nm-report` (volume 133), `nm-tp-report`, `fa-list`,
    `nm-almanac`, `nm-annex`, and `nm-accumulated` taken from niord_test's `FmReport` row: name `NM
    accumulated`, sort order 2, properties `{mapThumbnails:false, ISSN:1397-999X}`, on `niord-client-nm`),
    `promulgation-types.json` (`Audio`, `da`, on `niord-client-nw`; `NAVTEX` and `Twitter` whole with DK's
    domain links, as the 4.0.76 import replaces a type whole; no `promulgateByDefault`). No `categories.json`
    (§7.3). DK's first release folder, `be/bootstrap/releases/2026-10-22-templates-from-database/`: a README in
    the base release's sections (apply the base product's 2026-10-21 release first), `script-resources.json`
    (the 56 files, generated) and `missing-paths.sql` (94 expected paths: the base's 80 and DK's 14 own,
    generated); no `texts.json`. `src/main/resources/META-INF/niord/expected-template-paths.txt` (56 paths,
    generated; Spring Boot puts it at the jar's root, the running jar reads it, the override check does not
    count it as a shadow). `defaults/country/`: `settings.json` (29 values: the base file's 26 keys plus
    `wmsProvider`, `wmsPassword` `""` and `wmsLayers`; `country` `DK`, `organisation` `DMA`;
    `documentationUrl` https; no `wmsLogin`, `wmsServiceName` or `useBaseDefault`), English texts
    `mail.properties` (the 3 differing `mail_en` keys), `web.properties` (`lang.da`), `pdf.properties`
    (`pdf.nm_almanac.desc`, DK's pre-C5 wording) and `message.properties` (`msg.responsible.agency = Danish
    Maritime Authority`, the pre-C5 S-124 agency; changed by `niord-dk` #4 to `Danish Emergency Management
    Agency`, §11 C6), and a README. The root `niord.json`, `web_en.properties` and
    `mail_en.properties` are deleted; the five `*_da` bundles stay at the root (P5b). `application-local.yml`
    no longer selects `changelog-with-seed.yaml`. `be/overrides.json` lists the logo with
    `sha256:f8f40e1f66c72e2c92d4f87bb4a499d9d0aa7e562d0de90e79ff24e06155d44a`, the fingerprint §9.1 recorded,
    confirmed. `be/scripts/check-country-build-group.sh` (mode 100755) is the group count (above). Workflows:
    `ci.yml` gains four steps after the suite (read the base version into `$GITHUB_ENV` after a `^4\.0\.[0-9]+$`
    check, fetch `niord-image-tools` into `target/k8s/`, the override check, the group count); `image.yml`
    gains the override check between the image-tools fetch and `split-jar.sh`, and no group count. Every new run
    block starts with `set -euo pipefail` and expands no `${{ }}`. Tests: the four DK tests C6 names read DK's
    bootstrap; new `DkBootstrapTemplates` (the IT fixture: DK's templates first, then the base's from
    `target/niord-bootstrap/`, every missing row in one transaction of its own, committed once), `DkBootstrapITTest` (the base archive
    and DK's through `BootstrapRunner` on a fresh container; and a second test that holds DK's 13 restated
    parameter types and `NAVTEX`/`Twitter`, domains aside, equal to the pinned base's entries, so a base change
    to them fails DK's build at the pin bump), `DkBootstrapFolderTest` and `DkReleaseFilesCurrentTest` (public
    4.0.76 API only).
  - **(a) The whole suite**, `NIORD_IT_MIN=10 ./test-it` on the final tree (3 min 54 s), BUILD SUCCESS: default
    Surefire 22 tests, the group's Surefire (`niord-country-build`) 113 tests, 0 failures, 1 skipped
    (MailHtmlTest's frontend check), `CountryContractGuardTest` 29 of them; DK's ITs 14, the group's ITs
    (`niord-country-build-it`) 14; the IT report check 28 executed, 0 skipped. `CountryDefaultsChecksTest`
    ran: "Settings checks: {defaults/base/settings.json=26, defaults/country/settings.json=29}", the https
    warning for `wmsProvider`, "useBaseDefault: none". `scripts/check-country-build-group.sh`: exit 0, "14
    integration tests completed, 0 skipped …, CountryContractGuardTest ran 29 tests". `DkDgpsTemplateITTest`
    (2) and `DkLightDestroyedTemplateITTest` (1) green on bootstrap rows; C5b's route-A errors are gone. The
    first whole run, before the review, failed once (`DkDgpsTemplateITTest`'s script test found no area):
    `CdiUtils` keeps the last-started context in a static field (CDI1); DK's fixture re-points it.
  - **(b) The override check** (niord-ng's `check-overrides.sh` over `target/niord-dk-4.0.76.jar`): exit 0 with
    only the logo listed; red once with `mine.ftl` put back under `src/main/resources/templates/tmpl/`, exit 1,
    `template-in-jar`; red once with `overrides.json` moved aside, exit 1, `unlisted-shadow`; both restored,
    exit 0. The jar holds no `BOOT-INF/classes/templates/`, `niord.json`, `web_en` or `mail_en`.
  - **After the review**, targeted runs: `DkBootstrapITTest` 2 tests (13 parameter types, `NAVTEX` and
    `Twitter`; `missing-paths` `[]`; 0 of 42 shadows DK's), red once with `NAVTEX`'s priority changed;
    `DkDgpsTemplateITTest` 2; a stale file planted in `target/niord-bootstrap/` gone after the clean; the group
    script on fabricated summaries (all skipped exit 1, 0 completed exit 1); `DkBootstrapFolderTest` 7. Red
    once: an `Execute` template (`DkBootstrapFolderTest`), an extra expected path
    (`DkReleaseFilesCurrentTest`), the group script without reports; all restored byte for byte.
  - **(c) An empty database.** The new jar started on a fresh scratch database in 18.2 s: no guard refusal,
    every setting from `defaults/country/`, marker `pending`. `bootstrap.sh` with `niord-bootstrap-4.0.76.zip`
    then DK's zip: applied in 12 s; `missing-paths` `[]`. Rows: 5 DK domains active (master inactive), 6 series,
    9 reports, `Audio` (`da`), `NAVTEX` and `Twitter` linked to DK's domains, 34 parameter types, the 4 DGPS
    entries, 94 templates, 13 categories. Of DK's 42 shadow paths 0 hold DK's content on this 4.0.76 base (the
    base archive added them first; applied from the base product half, below); of its 14 own, 14.
  - **(d) A copy of niord_test** (`niord_test_verify_p5a`): the first start added `authServerTrustAllCertificates`,
    `batchMessageSeriesId` and `mail.safetynet.cancel` (`en`), 0 existing rows changed or removed; a second
    start identical. The base product's 2026-10-21 release file (5 listed adds ticked, no overwrite) and DK's
    2026-10-22 release file (preview add 0, overwrite 11, unchanged 45; nothing ticked) applied: the report
    `[]`, 0 existing rows changed or removed. The original niord_test was identical at the start and the end.
  - **(e) Renders "as before"**: DK's `main` (`a348be0`) with its `<parent>` set to 4.0.76 against the new jar,
    each on its own copy of niord_test after both release imports: the NW-350-26 PDF and its debug HTML, the
    nm-report and nm-almanac in `da` and `en`, and two mails: identical apart from the "Generated" time and
    iText's CreationDate/ID.
  - **Not yet run:** the CI steps on GitHub (checked statically); `ci.yml`'s *Fetch the image tools* step (no
    registry credentials here); everything on staging, at the final deploy step.
- State (C6 for GL, 2026-10-22): met locally for GL; staging at the end. In `niord-gl` (#2 into
  `feature/p6-gl-moves`, `5b8f09b`), on the published base version 4.0.76 (niord-ng `81361ab5`):
  - **Built.** `be/pom.xml`'s `<parent>` moves from 4.0.66 to 4.0.76. GL declares niord-base's test-jar and
    exactly the test dependencies `be/docs/country-api.md` lists, as DK's pom does (the explicit
    `junit-jupiter` gone), and DK's two pre-integration-test executions `clean-niord-bootstrap` and
    `unpack-niord-bootstrap`. No GL main code changed; `spring.security.oauth2.resourceserver.jwt.issuer-uri`
    stays GL's only `spring.security.*` key. `be/.gitattributes`, `be/test-it`, `scripts/it-report-check.py`
    and `scripts/check-country-build-group.sh` are DK's files. Templates: 22 deleted (19 equal to the base's at
    `81361ab5`, 3 lagging base fixes: `tmpl/common.js`, `messages/nm-report-pdf.ftl`,
    `messages/nm-tp-report-pdf.ftl`) and 86 moved with `git mv` to `be/bootstrap/templates/` (46 shadows, 40
    own), unchanged but for `mailinglist/fetch-firing-exercises.js`'s line 19, which resolves
    `MessageSearchService` (SCRIPT8). `be/bootstrap/`, 5 jobs: `domains.json` (`niord-gl-nw` with its series
    `ako-nw`), `promulgation-types.json` (`NAVTEX` restated as the base's plus the domain `niord-gl-nw`; GL's
    `Audio`, `kl`, no domain; `Navtex GL`, inactive), `aton-dict.json` (115: the four DGPS stations whole in
    `da`/`en`/`kl`, 111 entries carrying only `key` and `kl`), `parameter-types.json` (15: `dgps` and
    `dgps_station_list` whole, the base's 13 composite types restated with GL's 35 `kl` template-parameter
    names (and the 35 base `da`), GL's 37 `kl` being those 35 and `dgps`'s 2, 0 differences from
    `kl-demo.json`), a README. Release folder
    `be/bootstrap/releases/2026-10-22-templates-from-database/` (86 files, 120 expected paths: the base's 80 and
    GL's 40 own; its README says to tick the overwrites of `tmpl/common.js` and `fetch-firing-exercises.js`);
    `expected-template-paths.txt` 86 paths. `defaults/country/`: `settings.json` (29 values; `country` `GL`,
    `organisation` `DMA`, the map centre 72/-40 at zoom 4, §9.2), four English text files (§4.4) and a README
    with the classification. Deleted: the root `niord.json`, the five `*_en` bundles and GL's
    database-to-repository pipeline (§9.2); the five `*_da` and five `*_kl` stay at the root until P6b. P6b
    (`niord-gl` #4, 2026-10-23) replaced the `*_kl` with the `kl` pack and wrote GL's Danish wording to
    `texts/da/`, the `*_da` staying until GL stages `niord-lang-da` (§4.1).
    `be/overrides.json` lists only the logo, fingerprint as DK's. Workflows as DK's. Tests: `GlTemplateTest`
    (4), `NiordGlSettingsResourceTest` (9), new `GlBootstrapFolderTest` (7), `GlReleaseFilesCurrentTest` (1),
    `GlBootstrapTemplates` (the IT fixture, `pointScriptsAt` re-pointing `CdiUtils`), `GlBootstrapITTest` (2)
    and `GlFiringExercisesScriptITTest` (1).
  - **(a) The whole suite**, `NIORD_IT_MIN=10 ./test-it` (2 min 48 s): BUILD SUCCESS. Default Surefire 21; the
    group's Surefire 113, 0 failures, 1 skipped, `CountryContractGuardTest` 29; GL's ITs 3, the group's 14; the
    IT report check 17 executed, 0 skipped; the group script exit 0 (14 completed, 0 skipped). C5b's route-A
    gap (the group ran 0 tests in GL's build) is closed. Red once, each restored byte for byte: an `Execute`
    template (`GlBootstrapFolderTest`), an extra expected path (`GlReleaseFilesCurrentTest`), `NAVTEX`'s
    priority (`GlBootstrapITTest`), line 19 back on `MessageService` (`GlFiringExercisesScriptITTest`:
    "messageService.search is not a function"), the group script without reports, all skipped and 0 completed.
  - **(b) The override check** over `target/niord-gl-4.0.76.jar`: exit 0 with only the logo; red with
    `tmpl/mine.ftl` put back (`template-in-jar`) and with `be/overrides.json` moved aside (`unlisted-shadow`);
    restored, exit 0.
  - **(c) An empty database.** Started in 18.8 s, no refusal, marker `pending`; the base zip then GL's applied
    in 11.9 s. `niord-gl-nw` active with `ako-nw`; `NAVTEX` linked to it, `Audio` (`kl`), `Navtex GL`; 34
    parameter types; 37 `kl` template-parameter names; the 4 DGPS entries; 4 `aton` `kl` descriptions (the 111
    inert on this 4.0.76 base); 3 reports; 13 categories; 120 templates; `missing-paths` `[]`. Of GL's 40 own
    paths, 40 hold GL's content; of its 46 shadows, 0 on this base (applied from the base product half, below).
  - **(d) A copy of niord_gl**: the first start added `batchMessageSeriesId` and `mail.safetynet.cancel` (`en`),
    0 existing rows changed or removed; the start-up report listed 10 paths. The base product's 2026-10-21 and
    GL's 2026-10-22 release files, the listed paths ticked: +10 rows and +10 history rows, the report then
    `[]`, 0 existing rows changed or removed. niord_gl holds 0 `S124Dataset` and 0 `S124SeriesConfig` rows.
    niord_gl's `fetch-firing-exercises.js` row still fails (SCRIPT8) until a sysadmin ticks that overwrite, as
    the release README says. The original niord_gl was identical at the start and the end.
  - **(e) Renders "as before"**: GL's `main` (`a3b22d0`) on 4.0.76 against the new jar, in `da`, `en` and `kl`:
    GL-044-26's PDF and debug HTML, the nm-report, three `-gl` mails: identical apart from the "Generated" time
    and the PDFs' dates and IDs. The logo and stylesheets were not compared (both renderers fetched them from
    an address where nothing listened).
  - **Not yet run:** the CI steps on GitHub (checked statically); the *Fetch the image tools* step; everything
    on staging (C6 Verification 2 and 3 on GL staging's copy, the `S124Dataset` count, P6's "GL staging boots
    on them"), at the final deploy step.
- State (C6, the base product half, 2026-10-22): met locally; staging at the end. On `feature/c6-base` (the
  list above, the base product half):
  - **Unit**, one run of 14 classes (`BootstrapRunTest`, `BootstrapRunnerTest`, `BootstrapArchiveTest`,
    `BootstrapFolderTest`, `BootstrapTemplatesParseTest`, `ReleaseFilesCurrentTest`, `RequiredTemplatesTest`,
    `CountryApiDocTest`, `CoreResourcePackagingTest` and five import processor tests): 94 tests, 0 failures
    (`BootstrapRunTest` 5, `BootstrapRunnerTest` 9 with 4 new, `BootstrapArchiveTest` 25 with 1 new,
    `BootstrapFolderTest` 8 with 1 new, `CountryApiDocTest` 8).
  - **ITs**, one run of 16 classes (`BootstrapITTest`, `BootstrapSameRunITTest`, `BootstrapSeededITTest`,
    `LiquibaseBootstrapITTest`, `ScriptResourceImportITTest`, `DictionaryImportITTest`, the category,
    parameter-type and promulgation-type import ITs, `CategoryServiceITTest`, `CategoryServiceGapITTest`,
    `PromulgationTypeServiceITTest`, `FmReportServiceITTest`, `MissingPathReportITTest`, and
    `JsResourceServiceITTest` and `BatchScriptExecutorProcessorITTest` to watch the `CdiUtils` order with the
    new context): 141 tests, 0 failures (`BootstrapITTest` 8, `BootstrapSameRunITTest` 10). The fixture then
    gained a `fr` name on its root category and a differing `en` on a template parameter; only
    `BootstrapSameRunITTest` was run again: 10 tests, 0 failures.
  - **Red once**, each mutant restored byte for byte (sha256): the rule off (`createdEarlier` always false):
    6 of `BootstrapSameRunITTest`'s 10 (before R188 added the eleventh, the job logs) failed (the template replaced, the language added, the kept language,
    the domain links, the unchanged field, the ended token); `createdEarlier` true for any entry of the live
    run: the row from before the run failed; existing languages overwritten: the kept-language test (5 asserts)
    and the language-added test failed; one `da` put back in `categories.json`:
    `BootstrapFolderTest.theDataIsInEnglishOnly` failed; the `bootstrapRun` refusal off:
    `BootstrapArchiveTest`'s new case failed. The first two ran on the fixture before its last change.
  - **After the review**: a list parameter type's kept values logged, the dictionary import's "languages
    added" clause only above zero, and `BootstrapSameRunITTest`'s row-from-before-the-run test extended with a
    promulgation type and a report created before the run and restated by both archives. Red once: both
    writers recording a row from before the run as created in the run failed all 4 new asserts (priority 3,
    not 7; domains `[it-earlier, it-later]`; the earlier archive's name; the report's domains likewise); the
    writers restored. Then `BootstrapITTest` and `BootstrapSameRunITTest` in one run: 8 and 10 tests, 0
    failures; and the unit tests `Bootstrap*Test` and `ReleaseFilesCurrentTest` in one run: 75 tests, 0
    failures.
  - **End to end**, `niord-app` on scratch databases in niord-db, created and dropped, with `bootstrap.sh`; the
    baseline is this tree before the change, whose bootstrap data and import code equal `81361ab5` (4.0.76),
    compared by natural key. DK (the base, then a zip of DK's merged `be/bootstrap`, `b5d8b31`):
    `missing-paths` `[]`; DK's shadows holding DK's content 0 of 42 → 42 of 42, each with two history rows,
    both `import`; own templates 14 of 14; the job logged "Added 14, overwritten 42"; `aton` `da` 115 (111 + 4
    DGPS) in both; the only differences the 42 templates and the 24 category `da` gone; parameter types, the 37
    template-parameter `da`, promulgation types (`NAVTEX`, `Twitter`, `Audio` with domains), reports, domains
    and series identical. GL (`5b8f09b`): `missing-paths` `[]`; shadows 0 of 46 → 46 of 46; own 40 of 40;
    "Added 40, overwritten 46"; `aton` `kl` 4 → 115 (the 111 applied), `aton` `da` 115 → 4 (the base no longer
    carries the 111 and GL does not yet); the 24 category `da` gone; everything else identical, the 13 types
    (35 `da`, 35 `kl`), `NAVTEX`'s link to `niord-gl-nw`, `Audio` and `Navtex GL` included. `--demo` (the base,
    then `niord-dev-basedata.zip`): the only difference the 9 base categories without the demo's `messageId`
    (§7.2); `aton` `da` 115, category `da` 45 and 37 category domain links in both; `missing-paths` the same 4
    paths in both (SEED1's two, and `dgps.ftl` and `dgps.js` named by category 45).
  - **Documents**: `CountryApiDocTest` green (above); in `publish.yml` and `retract-version.yml` only comment
    lines changed (`git diff -U0`, after the review's re-wrap: 16 insertions, 14 deletions, each a `#` line),
    and both parse as YAML.
  - **The full suite** (`./verify-local --base origin/feature/c6-base`, Windows, 2026-10-22, on `0f9b3ed1`):
    PASS in 18m44s. The backend's `clean verify` ran 5106 unit tests and 3354 ITs; it ignored the 10 known
    Windows-only failures, none in a class this change touches. The administration app's whole gate passed,
    `build:dk` and `build:gl` included.
  - **Not yet run:** CI (#188); everything on staging. C6 Verification 2 and 3
    are unchanged by this half, which changes no start-up path, no template content, no renderer and no import
    outside a bootstrap run (the release-import asserts of `BootstrapSameRunITTest` hold that); DK's and GL's
    halves ran them on their local databases (above).
  - **After the review of PR #188** (2026-10-22/23, on `c6/review-188-fixes`, Windows, the sub-PR into
    `feature/c6-base`): a retry of more than one archive refused (§7.2, *No retry*, its history), the token closed on uploads,
    batch sets and the instances endpoint, the add-only imports' outcomes in the job log, and the test gaps.
    - **Unit**, one run of 10 classes: 78 tests, 0 failures (`BootstrapRunnerTest` 14 with 5 new,
      `BootstrapRunTest` 7 with 2 new, `AbstractBatchableRestServiceTest` 2, new, `BootstrapArchiveTest` 25,
      `BootstrapFolderTest` 8, `CountryApiDocTest` 8, `ReleaseFilesCurrentTest` 6, and the dictionary,
      promulgation-type and report import processor tests 4, 2 and 2).
    - **ITs**, one run of 17 classes: 186 tests (`BootstrapITTest` 8, `BootstrapSameRunITTest` 11,
      `BootstrapSeededITTest` 2, `BatchSetServiceITTest` 9, `BatchUploadRestServiceITTest` 6,
      `BatchRestServiceITTest` 14, `DictionaryImportITTest` 8, `DictionaryRestServiceITTest` 32,
      `ScriptResourceImportITTest` 10, `CategoryServiceITTest` 41, `CategoryServiceGapITTest` 8, the category,
      parameter-type and promulgation-type import ITs 4, 4 and 3, `PromulgationTypeServiceITTest` 8,
      `FmReportServiceITTest` 12, `BatchServiceGapITTest` 6), all green but one error in the new instances test
      of `BatchServiceGapITTest`: the seed's `BatchData` rows carry instance ids 1-12, which a fresh JVM's job
      repository hands out again (BATCH4, open). The test now starts its empty job again until no other row
      shares its instance id; then `BatchServiceGapITTest` alone, in a fresh JVM: 6 tests, 0 failures.
      `BootstrapSameRunITTest` alone: first 2 failures, because job folders are named by job number, which
      restarts in each fresh database, so under the shared `NIORD_HOME` a log file held an earlier run's lines;
      the test now reads only the lines each job wrote: 11 tests, 0 failures. `BootstrapITTest` alone twice: 8
      tests, 0 failures. Its 9th test, `bothClaimsCommitAtOnceInsideACallersTransaction` (`claim(String)`'s own
      `REQUIRES_NEW`, added after the review below), failed without the annotation and passed with it
      (`BootstrapITTest` 9 tests, 0 failures). A later run of the same 17 classes: 187 tests, 0 failures,
      `BatchServiceGapITTest` included.
    - **Red once**, each file restored byte for byte (sha256): the claim always passing `retry` true
      (`BootstrapRunnerTest`'s four refusal and pending tests, `BootstrapITTest` Order 4 and 5 and those after
      them); the failed-retry warning removed (the `ListAppender` test); the upload refusal removed
      (`AbstractBatchableRestServiceTest` and `BatchUploadRestServiceITTest`); the batch-set refusal removed
      (`BatchSetServiceITTest`); the instances copy keeping the token (`BatchServiceGapITTest`); `dictionaryKey`
      with `'\n'` (`BootstrapRunTest`); the promulgation type's and, alone, the report's distinct removed
      (`BootstrapSameRunITTest`'s domain-link test, and the job-log test); the category processor's once-per-job
      set removed (the job-log test, the root's line twice). `BootstrapITTest` Order 6's history-row user was not
      run as a mutant: it pins that the jobs run on the caller's security context and the import records that
      user.
    - **End to end**, `niord-app` packaged from the reactor (nothing installed) on port 18988 against an empty
      scratch database `niord_verify_r188` in niord-db, with `bootstrap.sh`: the base zip and `failing.zip`:
      `failed`; the same two again: exit 1, "Refused (409)" naming the empty database (D4), the marker still
      `failed` with its error; the base zip alone: applied, the server log's warning naming the retried run,
      `missing-paths` `[]`. The backend stopped and the database dropped.
    - `bash -n` clean on `be/scripts/bootstrap.sh` (its usage prints the whole header) and `start-local.sh`;
      `git diff --check` clean.
    - **Not yet run:** the whole suite (`./verify-local --base origin/feature/c6-base`) and CI on this change;
      everything on staging.
- **The countries' next pin bump** (P5, P6; no change made in `niord-dk` or `niord-gl` by the base product
  half). Until it, each country applies its pinned 4.0.76 base, so no deployment is affected.
  - DK: done 2026-10-23 (P5e, `niord-dk` #6, base 4.0.87; State below). Carry the 24 category `da` descriptions, which no country carries yet, in a new `categories.json` as
    additions to the base categories (each with its English name and its parents', §7.2), with the
    `Category_Domain` links: `Light` = Fyr, `Light unlit` = Fyr slukket, `Light unreliable` = Fyr uregelmæssig,
    `Light damaged` = Fyr ødelagt, `Light buoy` = Lystønde, `Light buoy missing` = Lystønde forsvundet, `Light
    buoy off station` = Lystønde ikke på plads og i orden, `Light buoy unlit` = Lystønde slukket, `Light buoy
    unreliable` = Lystønde uregelmæssig, `Light buoy established` = Lystønde etableret, `Light buoy replaced` =
    Lystønde erstattet, `Light buoy withdrawn` = Lystønde inddraget, `Firing Exercises` = Skydeøvelser; and the
    11 template-parameter names: `aton` "Fyr" on `Light unlit`, `Light unreliable` and `Light damaged`, `aton`
    "Lystønde" on the 7 `Light buoy` children, `duration` "Varighed" on `Light buoy withdrawn`. Turning the 13
    restated types into additions (`type`, name, `paramId` and `da`) and `NAVTEX`/`Twitter` into `typeId` plus
    domains is optional, as §7.2 says: with a retry of more than one archive refused, a restatement and an
    addition give the same rows (the reason until the owner's ruling of 2026-10-24, §7.2, *No retry*; §7.2's
    *Whole restatements* gives today's). The domain IDs per the owner's ruling
    (`niord-nw`, …; P5d, `niord-dk` #3, `1d29b97`, merged 2026-10-22). `DkBootstrapITTest`: its equality test
    compares DK's restated types whole with the pinned base's file, so it goes red at the bump (13 of 13 equal
    against 4.0.76, 0 of 13 against the stripped base, whose types carry no `da`), as GL's does; it went with
    the restatements, which P5e turned into additions, replaced by a test that holds every addition to a pinned
    base entry; the shadow count is asserted, 42 of 42.
  - GL: done 2026-10-24 (P6e, `niord-gl` #7, base 4.0.87; State below). Add `da` to its 111 `aton` additions
    (the base no longer carries them: `aton` `da` 115 → 4 on the new base) and carry the `da` and `kl` names of
    the base product's 13 categories and their 11 template parameters as additions, each carrying both `da` and
    `kl`. Turn the 13 types into additions (with `paramId`,
    `da` and `kl`; optional, as for DK: GL's restatement carries the 35 base `da` with equal values today, so an
    addition with `kl` only would lose them) and `NAVTEX` into `typeId` plus its domain (optional too).
    `GlBootstrapITTest`'s equality test went with the restatements, replaced by a test that holds every addition
    to a pinned base entry; the shadow count is asserted, 46 of 46.
  - Both: the sentence of `defaults/country/README.md` "until both countries have done the plan's C6" gets the
    trigger the plan now uses (the root text bundles until GL stages `niord-lang-da`, that is until GL pins the
    kit version with the backend job together with a pinned `da`, §4.1). DK's README took the trigger of the
    time, "until the first base product release after P5b and P6b", in P5e; it is corrected at DK's next change
    in `niord-dk` (records only: nothing is changed in `niord-dk` here). GL's README took GL's own trigger in
    P6b, worded in P6e (`niord-gl` #7): "until both countries read their packs' backend halves (DK since P5b;
    GL's Danish until its Maven build reads the pinned `niord-lang-da`'s backend half, with the kit version that
    adds a backend job)", which is §4.1's trigger for GL. A failed bootstrap of a country's fresh deployment
    is recreated empty (D4; until the owner's ruling of 2026-10-24 a base-only retry was accepted, for a
    base-only deployment, and refused with the country's archive too: §7.2 *No retry*); the base product's archive
    is posted first, which the command does not check. Neither country constructs `DictionaryService.ImportResult`, reads `BatchInstanceVo` or calls
    `BootstrapMarker.claim` (their bootstrap ITs read the marker only), checked on DK's `bc9353a` and GL's
    `6b80842` on 2026-10-23, so the new component `keptInRun` and the claim's transaction need nothing at the
    bump (P7-bs replaced `keptInRun` by `replacedInRun` and changed the claim's answer; the same holds, below).
- State (C6, DK's pin bump, 2026-10-23): met locally for DK; staging at the end. In `niord-dk` (#6 into
  `feature/p5-dk-moves`, squashed as `e95ba3c`; DK's PR into `main`, #7, merged 2026-10-24), on base 4.0.87 (niord-ng
  `61869e64`, C6's base half and P5k's kit): `categories.json` new (24 `da`, 10 links to `niord-nw`), the 13
  types and `NAVTEX`/`Twitter` as additions; `DkBootstrapITTest` asserts 42 of 42 shadows, 115 `aton` `da`, 24
  category `da` and 37 parameter-type `da`, and its equality test is replaced by
  `dksAdditionsNameThePinnedBaseProductsEntries`; red once each on a misspelt category name, `paramId` and
  domain. The whole suite green (DK's Surefire 28; the group's 114, 1 skipped; Failsafe 14 + 14); the override
  check exit 0, the logo only. Not yet run: CI on GitHub, the first publish of `niord-lang-da` (made since by
  the merge of #7, `niord-dk`'s `Pack` run #1, 2026-10-24), staging.
- State (C6, GL's pin bump, 2026-10-24): met locally for GL; staging at the end. In `niord-gl` (#7 into
  `feature/p6-gl-moves`, squashed as `0104daf`; GL's PR into `main`, #8, merged 2026-10-24 as `e8662062`), on
  base 4.0.87 (niord-ng `61869e64`): `categories.json` (24 `da`, 24 `kl`, no domain links), the 13 types and `NAVTEX` as
  additions, the 111 `aton` `da` added; `GlBootstrapITTest` 46 of 46, 115 `aton` `da` and `kl`, 13 + 11
  category `da` and `kl`, 37 parameter-type `da` and `kl`, the additions-name-base-entries test, red once each in
  `GlBootstrapITTest` on a misspelt category name, a misspelt `paramId` and a missing `kl`, and in
  `GlBootstrapFolderTest` on a link to an unknown domain; the whole suite green (27 / 114, 1 skipped / 3 + 14);
  the override check exit 0, the logo only. CI on GitHub green on #8 (2026-10-24, 2 of 2) and on its merge;
  the merge's `Pack` run validated `kl` and published nothing. Not yet run: Image (disabled until the deploy
  step), staging.
- State (C6, DK's bump onto 4.0.97, 2026-10-25): met locally and on CI for DK; staging at the end. `niord-dk`
  #10, squash-merged by the owner as `9f09d195`, on base 4.0.97 (niord-ng `c703d061`; country-split plan §6 P5,
  *To P7's version*): `DkBootstrapITTest` 2 tests, the additions test expecting `[navtex, audio, twitter]` and
  the bootstrap test asserting `audio`'s priority 50 beside its `da`; red first at `:452` and at
  `DkLightDestroyedTemplateITTest:156`, nothing else, both green with `DkBootstrapTemplates.writeDksTemplates`
  (C4b). The whole suite green (DK's Surefire 28; the group's 114, 1 skipped; Failsafe 14 + 14); the override
  check exit 0, the logo only. CI on GitHub green on the PR and on its merge; the merge's `Pack` run published
  `niord-lang-da:2`. Not yet run: Image (disabled until the deploy step), staging.
- State (C6, GL's bump onto 4.0.97, 2026-10-25): met locally and on CI for GL; staging at the end. `niord-gl`
  #9, squash-merged by the owner as `555b7c73`, on base 4.0.97 (country-split plan §6 P6, *To P7's version*):
  `GlBootstrapITTest` 2 of 2, with `Audio`'s `kl`, priority 10 and `MANDATORY` asserted and the additions test
  expecting `[navtex, audio]`, red first at `:447` on the pom bump alone; 46 of 46 shadows hold GL's content in
  the IT database (0 of 46 with the old create-only fixture), asserted since by
  `GlFiringExercisesScriptITTest.glsTemplatesHoldGlsContentInTheItDatabase`. The whole suite green (27 / 114, 1
  skipped / 4 + 14); the override check exit 0, the logo only. CI on GitHub green on both heads and on the merge.
  Not yet run: Image (disabled until the deploy step), staging.
- **Open owner questions** (from DK's and GL's records; none blocks C6):
  - GL's pre-C5 `S124Dataset` action on GL staging, (a) or (b) of C5's *Consequence for GL*, at the deploy step
    (niord_gl holds 0 rows).
  - The domains, series and reports a fresh GL deployment starts with (GL's bootstrap carries only
    `niord-gl-nw`; niord_gl's other domains, series and six reports are DK's), and whether GL's `Audio` is
    linked to `niord-gl-nw` (it is carried with no domain); since P6e also `niord_gl`'s 32 other categories
    (DK-derived; GL's bootstrap carries the base product's 13 with their `da` and `kl`) and whether the 10
    template categories link to `niord-gl-nw` (carried with no link, which offers them in every domain). On
    2026-11-04 the 32 were ruled the base product's (decision 145; the base archive carries them in English since
    CAT45), and the 13's domain links were ruled none, so they are offered in every domain (the GL inventory's
    group A); the 32's names and links are the countries' to complete, in their PRs on the owner's accept.
  - niord_test's `msg.responsible.agency` rows (`Unknown Agency` / `Ukendt Agency`) and the typo key
    `rmsg.responsible.agency` (`Danish Emergency Management Agency` / `Beredskabsstyrelsen`): the row wins over
    DK's country text (`Danish Emergency Management Agency` since `niord-dk` #4), so a migrated DK deployment's
    S-124 names `Unknown Agency` until the row is corrected on the Dictionaries page (DK's data, before
    go-live); the typo key's value already holds the chosen name.
  - Answered on 2026-10-22: whether the rule also lets a later archive replace a template ("Yes, widen the
    rule", §7.2); which domain IDs DK's bootstrap carries (§8; `niord-dk` #3, `1d29b97`); and GL's S-124
    identity: DK and GL use the producer code `DK01` and name the Danish Emergency Management Agency as the
    producing agency (`niord-gl` #3, `6b80842`: GL's `niord.s124.producer-code` `DK01`, was `DK00`, and its
    English `msg.responsible.agency`, was `Unknown Agency`; `niord-dk` #4, `68b95cf`: DK's producer code `DK01`,
    was `DK00`, its English text, was `Danish Maritime Authority`, and `CoreDefaultsPrecedenceITTest` pins
    `DK01`). The countries' Danish and Greenlandic texts of the key follow with P5b's and P6b's packs.
- **The owner's bootstrap ruling of 2026-10-24** (D4 as amended; built in P7-bs, branch `p7/be-first-bootstrap`,
  a sub-PR of the country-split plan's P7 into `feature/p7-base-lets-go`, stacked on P7-be; recorded here because
  it changes the same-run rule C6's base half built). Within the first bootstrap a later archive's values replace
  what an earlier archive of the run created (§7.2, *Within one run*); the claim refuses a database that holds
  data; `failed` and `in-progress` are final, with no retry and no takeover; a bootstrap job is never restarted
  (the one change on a running system's endpoint, limited to bootstrap jobs, for the owner's review; kept by the
  owner's ruling of 2026-11-03, decision 138); a later
  archive restating a `domainId` is refused before the claim (§7.2).
  - **Built**, in six commits: (1) the two plans; (2) the claim's two guards (`BootstrapMarker.claim(hash)`
    answering `Claim`, `NOT_DATA`, `dataTables()`, `firstDataTable()`, `BootstrapRefusedException.DATA`), the
    pre-claim domain check (`BootstrapArchive.domainIds()`), the refusal texts and `apply()`'s check, with
    `claim(hash, retry)`, `retryable()` and `RETRYABLE` removed; (3) the restart refusal
    (`BatchService.restartExecution`); (4) `Given` and the three readers; (5) the replace methods
    (`DictionaryService.replaceLanguages`, `CategoryService.replaceInCategory`,
    `TemplateExecutionService.replaceInParamType`, `checkSameKind` and `replaceTemplateParams`,
    `PromulgationTypeService.replaceValues`, `FmReportService.replaceValues`), `BootstrapRun.Additions` with
    `replaced`, and `BootstrapSameRunITTest` with its fixtures rewritten; (6) the documents, the javadocs,
    `bootstrap.sh`'s header and `start-local.sh` posting only on `pending`.
  - **What it means for the countries** (nothing changed in `niord-dk` or `niord-gl` here; their bump onto P7's
    version carries it, with P7's `Audio`, in one bump): no code change, since their bootstrap ITs run the runner
    on their own new container, which the claim accepts, and read the marker with `read()` only. DK's `Audio` row
    stays byte for byte: on a fresh database its `da` replaces the base's English and its `niord-nw` link is added;
    `DkBootstrapITTest:302-303` and `DkBootstrapFolderTest:179-180` hold; `dksAdditionsNameThePinnedBaseProductsEntries`
    expects `[navtex, audio, twitter]`, `audio` allowed to carry values (optionally asserting priority 50 and the
    link). GL's `Audio` row stays: its `kl`, priority 10 and `MANDATORY` replace or fill the base's `en`, 50 and
    empty requirement; `GlBootstrapITTest:304-305` holds (asserting `kl` only; priority 10 and `MANDATORY` assertions may be added);
    `glsAdditionsNameThePinnedBaseProductsEntries` expects `[navtex, audio]`; whether it links `niord-gl-nw` stays
    the open question above. Both: the message of the `paramIds` assertion ("a missing one is logged, a new one not
    added") is reworded, the assertion kept; their javadocs and READMEs drop the retry and say the values replace;
    neither restates a base domain in `domains.json`. The line numbers are of their `main` as the critic read it;
    the local clones under `niord-ng-wt/.countries` were older. P7h's scratch bumps (2026-10-25) read DK's
    `main` `d6fa491` and GL's `e866206`, both confirmed as `main` on GitHub, and
    corrected two points: `GlBootstrapITTest:304-305` asserts only GL's `kl`, so its priority 10 and `MANDATORY`
    assertions are an addition GL may make; and the tests that change are the additions tests (DK `:452`, GL
    `:447`), with their javadocs and the `paramIds` messages (DK `:441-442`, GL `:436-437`), while DK's `da` and
    GL's `kl` assertions stay as written (country-split plan §6 P5 and P6, the bump lists to P7's version).
    Both bumps onto 4.0.97 did exactly that (`niord-dk` #10, `niord-gl` #9, 2026-10-25): no code change, test
    code only. DK's bootstrap test asserts `audio`'s priority 50 too, and GL's adds the priority 10 and
    `MANDATORY` assertions (the State lines above).
  - **Verification** (Windows, NIORD_HOME of its own, one Maven run at a time):
    - **Red first on the old code**, with the new tests and fixtures and a temporary IT on the old API, deleted
      afterwards: 8 failures, each for its stated reason (a stale `in-progress` with one archive claimed; the
      one-archive post after a failed run 202, not 409; the post over data 202, not 409;
      `aBootstrapJobsRestartIsRefused` 200, not 409; `BootstrapSameRunITTest` Orders 3, 4, 6 and 11 on their
      assertions). Compile-red by construction, on the new API: the runner's failed, stale, apply and domain cases,
      `GivenTest`, the readers' `Given` cases, `TemplateParamsReplaceTest`, the new line test of `BootstrapRunTest`
      and `BatchPromulgationTypeImportWriterTest`.
    - **Unit**, 19 classes (the bootstrap package, the readers, `Given`, the new replace tests, the archive and
      folder tests, `CountryWordsTest`, `LoadSampleScriptTest`, `SampleFolderTest`, `TestFixtureCurrentTest`,
      `DefaultTextsTest`, `ReleaseFilesCurrentTest`, `SettingsServiceStartupOrderTest`): 156 tests, 0 failures,
      twice.
    - **ITs**: `BootstrapSameRunITTest` 10 of 10; `BootstrapITTest` 10 of 10 (its first run failed on an
      assertion of the test's own, a `User` count no request had written; replaced by the users kept across the
      emptying and `dataTables()` without `User`); `BootstrapSeededITTest` and `LiquibaseBootstrapITTest` 5 of 5;
      `BatchRestServiceITTest` and `BatchRestActionITTest` 32 of 32. Unchanged and green, each once: the import
      ITs (`ScriptResourceImportITTest`, `DictionaryImportITTest` and the category, parameter-type and
      promulgation-type import ITs) 29 of 29; the services (`CategoryServiceITTest`, `CategoryServiceGapITTest`,
      `ParamTypeServiceITTest`, `TemplateExecutionServiceITTest`, `PromulgationTypeServiceITTest`,
      `PromulgationTypeServiceGapITTest`, `FmReportServiceITTest`) 116 of 116; start-up and the token's refusals
      (`SettingsAddMissingITTest`, `SettingsNoWriteITTest`, `SettingsFreshDatabaseITTest`,
      `DictionaryDefaultsLayeringITTest`, `DictionaryDefaultsConcurrencyITTest`, `DefaultsLedgerITTest`,
      `BatchUploadRestServiceITTest`, `BatchSetServiceITTest`, `BatchServiceGapITTest`) 46 of 46. None of the
      unchanged tests is in the diff against P7-be's tip (`464a6e53`, the head P7-bs was stacked on, before
      P7-be's rebase onto P7b; merged as `4a4ae502`, its four commits the same patches), and the diff adds no
      reload or reset endpoint.
    - **The byte-identical proof**: `regen-sample.sh` into the scratchpad, exit 0 in 440 s, `niord-sample.sql` and
      `repo/` equal to P7-be's committed files; `regen-test-seed-postgres.sh` into the scratchpad, exit 0,
      `niord-test-seed.sql` and `niord-base-data.sql` equal to the committed ones. So no write of an equal value
      and no recreated row.
    - **The scripts**: `bash -n` on `start-local.sh` and `bootstrap.sh`; `bootstrap.sh`'s usage prints its whole
      new header; a harness of `start-local.sh`'s `bootstrap_database` (the function copied from the file at run
      time, `curl`, the jar and `bootstrap.sh` stubbed) over `pending` (ok and failing), `failed`, `in-progress`,
      `applied` and `existing`, with and without `--sample`: `bootstrap.sh` called only for `pending`, the remedy
      printed for `failed` and `in-progress`, nothing dropped.
    - **The review's two fixes**, each red on the old code first: `DictionaryService.replaceLanguages` skips a
      language whose value is empty (fixture key `k9`: the earlier archive's `en` `nine`, the later archive's empty
      `en` and new empty `da`); `BootstrapSameRunITTest` red with the old service, 10 run and 2 failures (Order 4:
      `k9` read `{en=, da=}`; Order 11: the log replaced `k9`'s `en` and added its `da`, and the dictionary line did
      not read "unchanged 3"), then 10 of 10. `start-local.sh` reads the marker again after a failed post and names
      the remedy by state (§7.2, *Who calls it*); the harness, its `curl` stub answering the state after the post and
      new cases for `bootstrap.sh` exiting 1 with the marker left `failed`, `in-progress`, `pending` or empty, with and
      without `--sample`: 10 failures on the script with the fix reversed, none on the new one; `bash -n` clean.
    - **`./verify-local --base origin/feature/p7-base-lets-go`**: PASS in 10m24s on `49aaa74e`, 5,213 unit tests
      and 3,365 ITs with only the 10 known Windows-only failures. Its first run (2026-10-25, 02:04 local, in the
      hour the clocks went back) failed 17 time-dependent ITs outside this change, each by exactly one hour
      (`be/docs/potential-errors.md`, TZ1); the re-run after that hour passed. The owner ruled on 2026-10-25 that
      the JVM runs in UTC, set by the chart and the images at the deploy step and stated in the operators' guide
      (country-split plan §6 P8); TZ1 stays open until that is built.
    - **`./start-local.sh --sample --reset`** on this tree: on a newly created `niord_sample` the strict claim let
      the bootstrap through (pending, in-progress, applied, no refusal over start-up's own rows), the sample loaded
      (34 messages, 6 domains; promulgation types `audio` `en` 50, `navtex` `en` 1, `twitter` `en` 100), the backend
      restarted and both frontends came up; 404 s, every process stopped afterwards.
    - **DK's and GL's bootstrap ITs** (P7h, 2026-10-25), in scratch copies of their `main` bumped onto P7's tree
      (niord-base installed into a scratch Maven repository; nothing changed in either repository):
      `DkBootstrapFolderTest` and `GlBootstrapFolderTest` 7 of 7 each; the bootstrap tests green, DK's `da` and
      GL's `kl` `Audio` included; red only on bump items: `dksAdditionsNameThePinnedBaseProductsEntries:452` and
      `glsAdditionsNameThePinnedBaseProductsEntries:447`, which now meet the base archive's `Audio`, and
      `DkLightDestroyedTemplateITTest:156`, the C4b create-or-replace item; `GlFiringExercisesScriptITTest` green.
    - **CI on #204**: green on `0409350e`, all 12 checks, and on `a8db0b32` (the review's fixes), 12 of 12; on
      the merge `c703d061` Backend, Frontend and Info CI and the publish of 4.0.97 green.
    - **Not yet run**: everything on staging. The two points this list named as not verified were settled by the
      review of #204: JBeret SE keeps its job repository in memory, so no job instance survives a backend restart
      (§7.2, *A bootstrap job is never restarted*), and the queued mails a scheduled alert writes are not data
      (§7.2, *No data*).
  - **The review of #204** (2026-10-25, answered on `p7/review-204-fixes`; the country-split plan's §6 P7, _Review
    of #204_). The owner decided that a list given explicitly, even empty, replaces within the first bootstrap
    (§7.2, *Within one run*): `Given` gives a JSON array whenever it is there and not null, and the replace
    branches drop their non-empty guards, so an explicit `[]` empties a category's editor fields, standard fields
    and script paths (the script paths filtered by type first), a promulgation type's message types and script
    paths and a list type's values; template parameters, domains and a report's maps are unchanged. The
    other two questions were decided with the recommended options: `ScheduledMail` and
    `ScheduledMailRecipient` joined `NOT_DATA`,
    and a default-text key a later archive restates is kept with a job-log line naming it (*The job log*). The
    other fixes: `Given` and `BootstrapArchive` read with `JsonMapper.builderWithJackson2Defaults()`, as the typed
    read does; the claim's refusal has a `pending` arm for the race; the parent's job-log line names the children
    a later `active: false` made inactive; `BootstrapRestService`'s javadoc says "values"; `start-local.sh`'s
    after-failure handling became `bootstrap_outcome`, with `pending` and `applied` arms, and
    `StartLocalBootstrapTest` sources it from the script; `CountryWordsTest` skips `Copyright` only inside the
    leading block comment or on a comment line, lets an allow entry give a `text` its line must hold (22 of the 23
    entries give one; the release record's `da` locale entry stays file-wide, with its reason; a new entry,
    `Danish Maritime Authority` with the text `Copyright 2017 Danish Maritime Authority`, covers the five script
    lines of the release record), and fails a file in scope that does not decode as strict UTF-8 unless its
    extension is a binary one; `BootstrapSameRunITTest` and its fixtures give every replace branch a value that
    differs between the archives (a promulgation type, categories, a report with a `params` key, a composite
    type's parameter's type, the emptied lists, an inactive category whose restatement leaves `active` out, a
    parent with an active child, and a `web` dictionary restating `menu.atons`); `BootstrapRunTest` tests
    `Scope.createdBy`; `GivenTest` the `templateParams` and `descs` branches. Red first on the old code: the six
    changed unit classes 80 tests, 10 failures and 1 error, each the intended one, then 80 of 80; the two ITs
    20 run, 3 failures (`BootstrapITTest` Order 4 naming `ScheduledMail`; `BootstrapSameRunITTest` Order 6, its
    four explicitly emptied lists kept and, with the `given.has("active")` guard removed for the run, the
    inactive category turned active; Order 11 at the `web` kept line), then 20 of 20, and the child note red
    alone with its line disabled. Then the unit run of 14 classes 139 of 139, and 10 more IT classes 78 of 78
    (`LiquibaseBootstrapITTest`, `BootstrapSeededITTest`, `BatchRestServiceITTest`, `DictionaryImportITTest`, the
    category, parameter-type and promulgation-type import ITs, `PromulgationTypeServiceITTest`,
    `FmReportServiceITTest`, `ScheduledMailServiceITTest`). `regen-sample.sh` and `regen-test-seed-postgres.sh`
    into a scratch folder, exit 0: `niord-sample.sql` with `repo/`, `niord-test-seed.sql` and
    `niord-base-data.sql` equal to the committed files, so no committed dump changed. Committed as `a8db0b32`
    (sub-PR #214): `./verify-local` PASS in 25m30s on it, and CI on #204's new head green, 12 of 12.

**C7 — the sample (inside P9).** The P9 sample is layered on the base product bootstrap and carries its own DK rows
(§8). `./start-local.sh --sample` replaces `--demo`.
- State (C7, 2026-10-23): met locally; nothing of it goes to staging (the sample is loopback only). On
  `feature/p9-sample` (branch `p9/sample`; PR #192 into `main`, merged 2026-10-23 as `195d102d`), with the
  country-split plan's P9 base product part, whose *State as built* holds the launcher's runs on slot 7.
  - **Built**: `be/niord/sample/` as §8 says (`archive/`, `messages/messages.json` with 34 messages,
    `niord-sample.sql` with 1507 inserts, `repo/` with 4 PNGs, the README); `be/niord/scripts/regen-sample.sh`
    and `load-sample.sh`; `SampleTool`, `SampleSafetyCheck` and `SampleSafetyCheckTest` in
    `org.niord.test.sample`, `SampleFolderTest` and `CoreResourcePackagingTest.theSampleDoesNotShipWithCore`;
    `be/k8s/scripts/check-no-sample.sh` with `CheckNoSample.java`, run in `be-ci.yml`, in `publish.yml`'s build
    and harbor jobs and in `./verify-local`; `be-ci.yml` re-includes `be/niord/sample/**`; the local realm's four
    users with `example.org` addresses; `--sample` in `start-local.sh`, `--demo` removed. The documents of §10
    (the C7 list) and the country-split plan's P9 list.
  - **Unit**: `SampleFolderTest` 6, `SampleSafetyCheckTest` 8, `CoreResourcePackagingTest` 9,
    `BootstrapArchiveTest` 25 and `BootstrapFolderTest` 8 in one run: 56 tests, 0 failures. After the review's
    fixes: `SampleSafetyCheckTest` 9 (every picture up to the limit of 99 inside the frame),
    `SampleFolderTest` 6 and `CoreResourcePackagingTest` 9, 0 failures.
  - **Regeneration**: two runs gave byte-identical `niord-sample.sql` and `repo/` (16m03s and 15m33s on a
    machine loaded by other sessions; 5m48s and 9m04s in earlier, quieter runs), and a third after the review's
    fixes (468 s, niord-base installed into `.local-run/sample-regen-m2`, the shared `~/.m2` jars untouched).
    Each run loads its result into the base-only baseline renamed `niord_sample` (the schema check) and
    asserts that a second load answers 3; since the fixes the loader copies the attachments before the SQL,
    also on that second load.
  - **Launcher** (slot 7, the country-split plan's P9 holds the detail): the review's check ran
    `./start-local.sh --sample --reset` on the committed `niord-sample.sql`, up in 5m35s including the backend
    build, the marker `applied`, `missing-paths` `[]`, the 34 messages as `messages.json`, the attachments and
    the public site's 13 messages; a re-run in 3m17s; `--reset` in 6m28s, the database list unchanged; the
    `existing`-marker refusal, `--reset` refused while a backend was connected, and the guard's "no Niord
    schema" and "marker not applied" refusals, each on a real run.
  - **Red once**: `regen-sample.sh --check` on four altered copies of the SQL, each exit 1 naming its rule (an
    `example.com` address, a phone number in a message title, an insert into `Contact`, a title without
    "SAMPLE —"); the unaltered SQL with `repo/` exit 0. The loader: `--host 192.0.2.1` and `--db niord` exit 1,
    no argument exit 2; the SQL on a throwaway database `niord_p9_guard` refused by its database-name check
    (psql exit 3, nothing written); on a copy of the baseline with its marker set to `pending`, refused by the
    marker check (0 messages written); "already loaded" exit 3. `seed-dev-database.mjs --database
    niord_sample` exit 2. After the review's fixes, by hand on a throwaway `niord_sample`: a `--home` that is a
    file, exit 1 with no SQL run.
  - **No sample in a jar or image**: after one `package -DskipTests` of `niord-app` and `niord-bootstrap`,
    `check-no-sample.sh` over niord-base's jar and test-jar, `niord-app`'s jar and the bootstrap zip exit 0
    (96,022 entries); over a `niord-app` jar copy with the SQL added, the same nested in its niord-base jar, and
    renamed, exit 1 each; over a tar holding `messages.json` and an `archive/` path, exit 1. An image built
    with `build-image.sh` (a local tag) and exported with `docker export`: exit 0 (102,069 entries); it was
    removed with its container and tar. The `be-ci.yml` and `publish.yml` step commands, run from `be/` against
    those jars, a scratchpad split `dist` and an empty `web`: exit 0, and exit 1 on red copies; both workflows
    parse, with the steps in the intended order. Backend CI on PR #192 (run 39907609719, on `7a525456`) ran
    the `be-ci.yml` step green: no sample in 4 artifacts, 96,029 entries read.
  - **Sample users**: a throwaway Keycloak (`127.0.0.1:18180`) importing the new realm file gave `sysadmin`,
    `admin`, `editor` and `user` tokens with `<name>@example.org` and the role in all five sample domains; it was
    removed. The chart's realm file is unchanged.
  - **Clean-up**: `niord`, `niord_test` and `niord_gl` were never written; `niord_sample`, `niord_sample_base`
    and the guard databases were dropped; no process of the run was left.
  - **Found on the way**: MSGIO1 (`POST /rest/message-io/import` fails on every upload: the upload stream is
    closed before the batch job reads it), recorded in `be/docs/potential-errors.md`, not fixed then (fixed on
    2026-11-13 by the error hunt's fix stage). Inside
    `build-image.sh`, `publish-images.sh`'s content check failed locally (the `/app/lib` jars' hash differs from
    the `dist` one), which looks like a Windows quirk of the hash, not investigated. `fe/scripts/release-workflows.test.mjs`
    is flaky on this machine (`EPERM` removing a temporary folder in a `retract-version` test: 28 of 29, the
    retract tests alone 6 of 6).
  - **After the review of #192** (2026-10-23, on `p9/review-192-fixes` from `feature/p9-sample` at
    `7a525456`; the decisions are in §7.2 and §8, the launcher's checks in the country-split plan's P9):
    - **Unit**, one targeted run of `BootstrapRestServiceTest`, `SampleFolderTest`, `SampleSafetyCheckTest`,
      `SampleToolTest`, `CheckNoSampleTest`, `LoadSampleScriptTest`, `CoreResourcePackagingTest`,
      `BootstrapArchiveTest` and `BootstrapFolderTest`. With the new tests written and the old rules, the old
      sample files and a planted `target/classes/x/copy/niord-sample.sql`: 89 tests, 24 failures, each new
      rule or assertion red (the 503 before the flag; a `docker save` tar, the uid prefix, an over-limit
      archive entry and a duplicated zip entry; `--db niord` refused on the container, not on the name; the
      planted SQL; the guard's remedies, the link counts, the zip's defects and two `nextScheduledExecution`
      values not at the epoch; the plain phone forms, a Unicode e-mail domain, the closed rule (a), a non-PNG
      repository file and an iCCP chunk; an escaping attachment path). After the code: 2 failures and 3
      errors, which found a stack overflow in the literal pattern on the long script literals and an
      eight-digit pattern that missed a number ending a sentence; both fixed, after which only the two tests
      that read the regenerated SQL stayed red. After the regeneration: 89 tests, 0 failures
      (`BootstrapRestServiceTest` 2, `SampleFolderTest` 12, `SampleSafetyCheckTest` 18, `SampleToolTest` 3,
      `CheckNoSampleTest` 8, `LoadSampleScriptTest` 3, `CoreResourcePackagingTest` 10, `BootstrapArchiveTest`
      25, `BootstrapFolderTest` 8). `SampleFolderTest`'s changeset test is also red on a scratch copy of the
      changelog with an added `V009` (kept as a test).
    - **ITs**: `BootstrapITTest` 9, `BootstrapSameRunITTest` 11 and `BootstrapSeededITTest` 2, 0 failures
      (2m36s): the start-up gate opens in a started context.
    - **Regeneration**, twice, at the end: 406 s and 309 s by the script's count, exit 0 each, 1,507 inserts
      and 4 files, every link count matched, steps [1/8] to [8/8]; the two outputs byte-equal (`cmp` of the
      SQL, `diff -r` of `repo/`), `repo/` unchanged against the commit, every file under `be/niord/sample` LF.
      The SQL's diff holds only the guard's remedies, the `ParamType` fingerprint and the `TemplateParam` key,
      the epoch `nextScheduledExecution`, the zip's mended rows, the two titles and the Danish vicinity, and
      the nine moved positions. niord-db's database list was the same before and after.
    - **By hand**: `bash -n` on `regen-sample.sh`, `load-sample.sh`, `bootstrap.sh` and `check-no-sample.sh`;
      `regen-sample.sh` with a home holding a space, exit 1 before any container call; `load-sample.sh --db
      niord` and `--host 10.0.0.1`, exit 1 each before any container call; `regen-sample.sh --check` on the
      committed files, compiled first, exit 0 (44 s); after `package -DskipTests`, `check-no-sample.sh` over
      niord's jar and test-jar, exit 0 (2,341 entries), so neither holds the uid prefix or the sentinel line.
    - **After a check of those fixes**: the regeneration read `SampleTool counts` through a here-string, so a
      failing `SampleTool` left nothing to compare and the run still ended green; the counts are now captured
      first, must be the six link tables, and a failure stops the run. `load-sample.sh`'s header (also its
      usage text) named the cure as `../../../start-local.sh`; it now names `./start-local.sh --sample
      --reset` from the repository root. `SampleFolderTest` and `LoadSampleScriptTest` in one run: with the
      two scripts as before, 17 tests, 2 failures (`theRegenerationStopsWhenSampleToolFails`,
      `theUsageNamesTheCureFromTheRepositoryRoot`); with the fixes, `SampleFolderTest` 13 and
      `LoadSampleScriptTest` 4, 0 failures. The new counts block run in bash with three stand-in
      `SampleTool`s: a failing one and a one-line one exit 1, six CRLF lines pass. `bash -n` on both scripts.
      The regeneration was not repeated: neither change alters a byte the script writes.
  - **Not yet run:** on a real loader, a loader failure stopping the launcher and Ctrl+C freeing its three
    ports (seen with a stand-in loader); the regeneration's path that creates a missing container;
    `publish.yml`'s two steps at the first publish after the merge. After the review of #192: `bootstrap.sh`'s
    retry of a 503 `POST` against a backend still starting; the administration app's map view of the nine moved
    positions; Backend CI on Linux for the new tests; a run on a second, clean machine (the review's branch
    passed `./verify-local`, and a clean-machine approximation here started the sample end to end: the
    country-split plan's P9). (C4b followed as its own PR, below.)

**C4b — the IT fixture regenerated** (after C7, its own PR into `main`). Its inputs existed only then: the
fixture is regenerated from the base product bootstrap, through the runner, and the P9 sample loaded after it
(§8). Until then it was the moved, unchanged seed in the test-jar (§7.4). The pinned ITs are updated in the same
PR, each change explained, and `ITTest`'s completions (the `public` report and the base product's 80 templates)
go once the regenerated fixture carries what they complete.
- State (C4b, 2026-10-23): met locally; it exposed a product defect, S124-1 (below), which it fixes;
  nothing of it goes to staging (a test fixture, test code and scripts). On `c4b/it-fixture`, a sub-branch of
  `feature/c4b-it-fixture` (PR #196 into `main`), which already holds C6's base product half (#188)
  and P9's sample (#192).
  - **Measured first.** The fixture was built once in scratch (about 2 minutes: `SeedRegen` on the base archive
    35 s, the sample's SQL 3 s); the first attempt, on the full test class path, was refused by the sample's
    guard ("DictionaryEntry ... id >= 388"), which set the filtered class path (§7.4). The whole IT suite then
    ran on it with `ITTest`'s two completions removed (the fixture and `ITTest` swapped in temporarily and
    restored with `git checkout`): 3359 tests in 9 minutes, 137 failed (115 failures, 22 errors), 10 skipped. 8
    are the known Windows-only failures (`RepositoryRestServiceITTest` ×7,
    `SettingsServiceITTest.getPathBuildsPathFromStringValue`); 129 came from the data, in 35 classes (three of
    them under two causes), and none from the completions' removal:
    - the domain IDs, the realm's `niord-client-*` against the sample's `niord-*`: 109 tests in 26 classes
      (403s and absent domains): `MessageCommentRestServiceITTest` 24, `MessageRestServiceITTest` 12,
      `MessageCreateUpdateHandshakeITTest` 11, `FiringExerciseServiceITTest` 11,
      `MessageCancellationWarningITTest` 8, `MessageTagRestServiceITTest` 6, `MessageRestStatusBatchITTest` 4,
      `FiringScheduleServiceITTest` 3, `MessageTagServiceITTest` 3, `MessageRestCopyReferencedITTest` 3,
      `FiringScheduleServiceGapITTest` 3, `FiringExerciseServiceGapITTest` 3, `DomainServiceITTest` 3,
      `MessageSearchRestServiceITTest` 2, `DomainRestServiceITTest` 2, and 1 each in
      `MessageReportRestServiceITTest`, `MessageSeriesRestServiceITTest`, `TicketServiceITTest`,
      `PublicationServiceGapITTest`, `MessageServiceLifecycleITTest`, `MessageSeriesServiceITTest`,
      `MessageKindITTest`, `FiringScheduleRestServiceITTest`, `DomainServiceGapITTest`,
      `BatchDomainImportProcessorITTest` and `AbstractApiServiceITTest`;
    - the sample's 34 messages and its series numbers: 7 tests in 3 classes (`MessageSearchServiceITTest` 3,
      `MessageSeriesRestServiceITTest` 3, `S124DatasetPipelineITTest` 1);
    - rows of the seed that neither the bootstrap nor the sample has: 8 tests in 6 classes
      (`ParamTypeServiceITTest` 1, `FmReportServiceITTest` 1, `MessageReportRestServiceITTest` 1,
      `IssueRenderRealITTest` 1, `SettingsRestServiceITTest` 2, `DictionaryDefaultsLayeringITTest` 2);
    - rows the fixture now has and the seed lacked: 5 tests in 3 classes (`MissingPathReportITTest` 3,
      `ScriptResourceImportITTest` 1, `FiringScheduleServiceITTest` 1).
    The plan's pinned ITs passed unchanged on it (§7.4, *The pinned ITs*).
  - **Built**: `regen-test-seed-postgres.sh` rewritten as §7.4 says (the base product's archive alone through
    `SeedRegen`, then `load-sample.sh`, the normalisation, the residue left out of both dumps) and both fixture
    files regenerated by it; `load-sample.sh --container`; `be/.gitattributes` (`niord/src/test/resources/db/**
    text=auto eol=lf`; `git ls-files --eol` shows `i/lf w/lf`); `changelog-with-seed.yaml`'s comments (its
    `retire-map-image-settings-after-seed` changeset stays as applied history and deletes nothing now);
    `ITTest` without `addThePublicReportUntilC4b` and `addTheBaseProductTemplatesUntilC4b`; the new
    `ReportFixtures` and the new unit test `TestFixtureCurrentTest` (2 tests, one of them its parser's);
    `SeedRegen`'s, `NiordTestApplication`'s, `NiordTestContainers`' and `LiquibaseBootstrapITTest`'s comments;
    the IT realm's five domain clients renamed with `KeycloakTestTokens` and the 61 IT files that name them;
    `be/niord/seed/` deleted (`git rm`), with `BootstrapArchiveTest`'s `DEMO_ARCHIVE` and
    `theDemoArchiveIsAccepted` (`SampleFolderTest.theArchiveIsOneTheBootstrapRunnerAccepts` reads the sample's
    archive the same way); the documents of §10 (*In C4b*).
  - **The classes whose premise moved**, each with its cause in a comment beside the change (the 26 classes of
    the domain IDs moved with the realm only, and so did the 35 other files that create a `niord-client-*`
    domain of their own, which the renamed realm no longer grants):
    - `MessageSearchServiceITTest` (the sample's messages match the unscoped criteria, 3 became 15 and 5 became
      17): the totals are the count before plus the test's own messages, and the paging case checks the real
      last page (1 or 2 rows by parity) instead of a fixed page 2, without pinning the sample's 12 `dma-nm`
      messages.
    - `MessageSeriesRestServiceITTest` (the sample numbers 8 messages each in `dma-nw` and `dma-nm` for 2026, so
      the next is 9): the "no numbered message, next is 1" cases moved to `dma-fa`, a sample series with no
      `Sequence` row, so they are not tied to the year.
    - `FmReportServiceITTest`, `MessageReportRestServiceITTest`, `IssueRenderRealITTest` (the seed's five
      `nm-*`/`fa-list` reports are gone; the bootstrap has three): `ReportFixtures.ensure` adds `nm-report` and
      `nm-tp-report`; `getAllReports` asserts the bootstrap's three plus the fixture's rows, the count derived;
      the PDF and issue render tests render `nm-tp-report` again (a missing id falls back to `standard`,
      RENDER3).
    - `ParamTypeServiceITTest` (the seed's 7 standard `ParamType` rows, which start-up wrote until C4.1, are not
      in a bootstrapped database): the test plants them as an older deployment's legacy rows inside its
      rolled-back transaction and asserts as before; the count comment derives the fixture's 34 rows (base 32
      plus the sample's DGPS pair). Since no start-up step touches a `ParamType` row after C4.1, the case that
      said start-up never deletes them is named `legacyStandardRowsAreIgnoredAndListedOnce` (the review of the
      change).
    - `DictionaryDefaultsLayeringITTest` (the seed's text rows were DK's; the fixture's are the base product's,
      adopted at the IT's start): each test plants a Danish database's state, runs the start-up's add-missing
      step again and asserts as before, then restores the rows.
    - `SettingsRestServiceITTest` (the fixture holds no `Setting` row; start-up writes them at the IT's first
      start, and the seed's legacy row no catalog declares is gone): it expects the value start-up writes,
      derived in a comment, and plants the legacy row, removed in a `finally`.
    - `FiringScheduleServiceITTest` (the bootstrap carries "Firing Exercises", §7.1, so "no such category" no
      longer holds): the test renames the category inside its rolled-back transaction and asserts the warning
      as before.
    - `MissingPathReportITTest` and `ScriptResourceImportITTest` (SEED1 closed: the fixture carries both DK
      trigger scripts as the sample's rows, so its report is empty): the seeded-database case asserts an empty
      report; the endpoint, start-log and preview cases plant a test-owned absent path through a committed
      `Category_scriptResourcePaths` row on category 2, removed in a `finally`, and assert it where they asserted
      the DK scripts. Both plant it through `ReportFixtures.plantCategoryPath` and `removeCategoryPath`: each in a
      committed transaction of its own, the row at the category's next free `indexNo` (two tests at one fixed
      index would share a primary key), removed by category and path (the review of the change).
    - `DomainRestServiceITTest`: the default view leaves out the inactive `niord-client-master`, the inactive
      view includes it, beside the five sample domains. Since 2026-11-03 (BOOT1, the owner's ruling, decision
      138) the fixture's master is active and both views list all six; that an inactive domain is listed only
      when asked for is proven by `inactiveDomainIsHiddenFromDefaultListingButVisibleWithInactiveFlag`.
    - `S124DatasetPipelineITTest`: `reconcileCreatesMissingDatasetsAndIsIdempotent` unchanged, red on the new
      fixture until S124-1 was fixed (below); `theSampleNwNamingAChartGetsADataset` new, on the sample's
      NW-001-26; and `S124ReconcileFailureITTest` new, beside it.
    Comments only, the assertions passing as they were: `BootstrapSeededITTest`,
    `MemberResolutionDifferentialITTest`, `IssueInvariantsITTest`, `IssueLifecycleITTest`,
    `ResolutionDiagnosticsITTest`, `MessageMailServiceITTest`, `ScriptResourceRestServiceITTest` (they named
    facts of the old seed: no `Message` rows, an empty `ScriptResource` table, `ITTest`'s completions). Unit
    tests that use `niord-client-*` as plain strings were left as they are; they read neither the fixture nor
    the realm.
  - **Decisions** (taken in the run, the recommended option each time): the fixture is a dump, not a copy of
    the sample's SQL (§7.4); the filtered class path rather than booting `niord-app` with Keycloak as
    `regen-sample.sh` does, since the regeneration uses the suite's own application; the sample through its own
    loader with `--container`, not a bare `psql`; the residue left out of both dumps, `Setting` included, so
    every IT start writes the settings from its own class path (niord-base's test files, DK's
    `defaults/country/`), as a fresh deployment does; the normalisation to the sample's epoch and the
    clustering; all 61 IT files renamed with the realm, since renaming only the 26 failing classes would break
    the 35 that create a `niord-client-*` domain; `ReportFixtures` for DK's two reports and test-planted rows
    for the legacy ones, nothing into the sample; the series-number cases on `dma-fa`, since pinning 9 would
    tie them to 2026; `TestFixtureCurrentTest` as the stale-fixture guard (a reviewer may drop it if parsing
    the SQL weighs too much); `be/niord/seed/` deleted whole; the S-124 reconcile test left red, not scoped.
  - **Regeneration**: `regen-test-seed-postgres.sh` ran twice (163 s and 153 s; the second into a scratch
    folder with `NIORD_SEED_OUT`), exit 0 both times, the loader's own call inside it exit 0 ("The sample is
    loaded"); both files byte-equal across the runs. `niord-base-data.sql`: 2797 inserts, 70 `setval`s, 8263
    lines, 715,715 bytes; LF in the working copy. After the review the script drops each table's clustering mark
    (`SET WITHOUT CLUSTER`) and ran twice more (298 s, and 439 s into `NIORD_SEED_OUT`), exit 0 both times, both
    files byte-equal across the runs: `niord-base-data.sql` unchanged (its checksum and the ITs' data with it),
    `niord-test-seed.sql` 111 tables, 9185 lines, 535,209 bytes, without the 91 `CLUSTER ON` lines it had
    (9367 lines, 541,878 bytes).
  - **Seen red** before the tests were updated (the new fixture and the renamed realm): `./test-it --its-only
    -pl niord` over the 12 classes whose premise changes (`DictionaryDefaultsLayering`, `MissingPathReport`,
    `ScriptResourceImport`, `SettingsRestService`, `ParamTypeService`, `FiringScheduleService`,
    `S124DatasetPipeline`, `MessageSearchService`, `MessageSeriesRestService`, `FmReportService`,
    `MessageReportRestService`, `IssueRenderReal`): 216 tests, 19 failures and 1 error, each with the expected
    data cause. The 26 classes of the domain IDs were seen red in the measurement.
  - **Unit**: `BootstrapArchiveTest`, `SampleFolderTest`, `CoreResourcePackagingTest`, `BootstrapFolderTest`,
    `TestFixtureCurrentTest` and `SampleSafetyCheckTest`: 58 tests green (53 s). `TestFixtureCurrentTest` failed
    on a touched bootstrap template, naming `regen-test-seed-postgres.sh`, and the template was restored.
  - **Targeted ITs** (one call, 81 patterns: the 61 renamed classes, the classes whose premise changed,
    `BootstrapSeeded`, `Bootstrap`, `LiquibaseBootstrap`, `NavtexPromulgationGeneration`,
    `PromulgationTypeService` and its gap test, `SafetyNet*`, `Issue*`, `MemberResolutionDifferential`,
    `ResolutionDiagnostics`, `JwtAuthorizedParty`, `MethodSecurityOrdering`, `RestHandlerSecurity`,
    `MessageMailService`, `ScriptResourceRestService`): 1527 tests (339 s), 1 failure (the S-124 reconcile
    test), 10 intentional skips (the corpus thresholds); `BootstrapSeededITTest.theSeededDatabaseReadsExisting`
    green, so a database seeded from the fixture reads `existing` at its first start.
  - **The whole backend** (`./test-it -Dmaven.test.failure.ignore=true`; without the flag the run stops at
    Surefire on the two known Windows unit failures and never reaches the ITs): unit 5129 tests, 2 failures
    (the known `KubernetesSecretKeystoreWriterTest` and `SecomImportBoundaryTest`), 3 skipped; ITs 3359 tests,
    9 failures, 10 skipped (751 s): the 8 known Windows-only ones and the S-124 reconcile test; `niord-app` 3
    tests green. The checker's own runs (2026-10-23) gave the same counts: the targeted call 1527 tests,
    1 failure (S-124), 10 skipped (332 s); the whole backend the same unit and IT counts (807 s). The last
    whole-backend count is `./verify-local`'s on `f15c4de2`, 5168 unit tests and 3361 ITs (_The whole suite_,
    below).
  - **After the review's fixes**: `ParamTypeServiceITTest` 18, `MissingPathReportITTest` 7,
    `ScriptResourceImportITTest` 10 and `TestFixtureCurrentTest` 2, in one call, all green (241 s).
  - **After the S-124 miscount fix** (S124-1, below): `S124DatasetPipelineITTest` 18, the idempotency case
    green again and the new failed-count case green, and `SecomSecurityReloadITTest` 5, in one call (5m20s with
    the compile).
  - **After the chart mapping** (S124-1, below): `S124Test.aMessageNamingAChartValidatesWithoutAnAffectedChartPublication`
    red first on the unchanged mapper (`cvc-complex-type.2.4.b … chartAffected is not complete`), then in one
    call `S124Test` 33 and `S124StandardMappingsTest` 4, and the ITs `S124DatasetPipelineITTest` 18,
    `S124ReconcileFailureITTest` 1, `S124ServiceITTest` 10, `S124RestServiceITTest` 7,
    `S124CancellationWarningITTest` 2, `S124FilelessCancellationITTest` 4 and `SecomSecurityReloadITTest` 5, all
    green (9m54s). The pipeline's new chart case was not run on the unchanged mapper.
  - **No sample in a jar or image**: after `package -DskipTests` of `niord`, `niord-app` and
    `niord-bootstrap`, `check-no-sample.sh niord/sample` over niord-base's jar and test-jar, `niord-app`'s jar
    and the bootstrap zip: exit 0, "no sample in 4 artifact(s), 96032 entries read"; the test-jar carries
    `db/niord-base-data.sql` (715,715 bytes) and `changelog-with-seed.yaml`.
  - **The loader's refusals** with `--container`: no argument, exit 2; `--container` without a value, 2; a
    missing container, 1; a stopped one, 1; a scratch container publishing 5432 on `0.0.0.0`, 1 (removed
    afterwards); `--db niord`, 1; `--host 10.0.0.1`, 1.
  - **Clean-up**: no process of the run left; the throwaway `niord-seed-pg` removed by the script's trap; no
    database created in niord-db.
  - **Found on the way, and fixed in this PR: S124-1.** `S124Mapper` wrote `affectedChartPublications` with a
    `chartAffected` holding `chartNumber` only, while S-124 2.0 (`xsd/124_2.0.0.xsd` of the bindings 0.3.3)
    requires an `editionDate` after the `chartNumber` (`chartPlanNumber` and `lastNoticeDate` are optional) and
    `affectedChartPublications` requires a `language`, which the base mapper never set; so the GML of every NW
    that names a chart failed the schema validation (the review of #196 corrected this premise: Xerces'
    "One of '{chartPlanNumber, editionDate}' is expected" lists the elements allowed next, not alternatives);
    `upsertDataset` logged the error and returned, and
    `reconcileEligible` still answered `CREATED`. No such NW got a dataset or reached a SECOM subscriber, and
    every 5-minute sweep counted it created again.
    `S124DatasetPipelineITTest.reconcileCreatesMissingDatasetsAndIsIdempotent` failed on the sample's six
    published `dma-nw` messages (expected 0, was 6). Two fixes:
    - **The miscount:** `upsertDataset` says whether it wrote, a unit that could not generate answers `FAILED`,
      and `ReconcileResult` gains `failed`, which the scheduled sweep's log line includes;
      `S124ReconcileFailureITTest` pins it with a generator spied to fail for one message. The SECOM page's
      toast does not show `failed` yet (`fe/docs/backlog.md`).
    - **The charts (owner, 2026-10-23, kept 2026-10-24):** the mapper writes no `affectedChartPublications` for
      any chart, since a Niord chart has no edition date and the base mapper set no language;
      `S124Test.aMessageNamingAChartValidatesWithoutAnAffectedChartPublication` was red with
      S124-1's schema error first, and `theSampleNwNamingAChartGetsADataset` shows NW-001-26 getting its dataset.
      A dataset names a chart only where the message's text does. The review of #196 put an alternative to the
      owner: `affectedChartPublications { language, internationalChartAffected }` for the charts with an
      `internationalNumber` (31 of the fixture's 172; the review counted 33, the dump and the sample's
      `charts.json` both give 31), schema-valid without an edition date. The owner kept the drop on 2026-10-24
      (one rule for all charts); that alternative, and an optional edition date on `Chart` emitted as
      `chartAffected` with `language` set, are left to S-124's production-readiness work
      (`be/docs/s124-secom-spec-gap-analysis.md` §5, which records the gap and the alternative considered; the
      potential-errors entry is gone).
    Also left: main-code
    javadoc and OpenAPI examples still say `niord-client-nw`/`nm` (`SecurityConfig.java:102-105`,
    `BatchPromulgationTypeImportReader.java:50`, `BatchReportImportReader.java:47`, `ApiRestService.java:122`),
    examples only; `be/docs/cicd/implementation-plan.md:80` names `be/niord/seed/` in a done history item.
    Fixed in the review of #196 (comments and documents only): the demo archive's present-tense leftovers in
    `BootstrapArchiveTest`, `BootstrapRunner`, `BootstrapRestService` and `be/docs/country-api.md`.
    Fixed after #192 (comments and documents only): the deleted seed's `niord.json` no longer named in
    `DefaultSettings`', `DefaultsLedger`'s, `be/README.md`'s and `defaults/base/README.md`'s account of the root
    `niord.json` read, which goes once niord-base's test tree moves its own to `defaults/` (§5.2); the root-bundle
    trigger (§4.1) in `DefaultTexts`' javadoc, `defaults/base/README.md`, `be/README.md` and potential-errors
    DICT1; P5e's sentence on DK's bootstrap in `be/docs/country-api.md`.
  - **The marker by hand** (the checker's run, 2026-10-23): `select state from "BootstrapState"` on the seeded
    Testcontainer of the whole-suite run read `existing`, beside 34 `Message`, 6 `Domain` and 84
    `ScriptResource` rows.
  - **After #192** (2026-10-23). `feature/p9-sample` was merged into this branch first (`0b371d7c`: 7
    conflicting files, each resolved keeping C4b's record and #192's facts), then `main` (`a721afb9`, the same
    tree as #192's squash `195d102d`). #192 had changed the sample (sources re-paired, nine positions moved,
    publication links left out, mailing-list typos, `nextScheduledExecution` normalised), its loader, the
    no-sample check and the guard's fingerprints, so the fixture was regenerated on it.
    - **Two merge defects**, fixed: `load-sample.sh`'s usage printed lines 2-30, which C4b's `--container` lines
      had pushed the "Exit codes" line out of (now `2,33p`; `LoadSampleScriptTest.theUsageNamesTheCureFromTheRepositoryRoot`
      red first); `check-no-sample.sh`'s printed 2-25, cutting its exit status and the paragraph on
      `CheckNoSample.java` (now `2,30p`; without arguments it prints the whole header and exits 2).
    - **The uid prefix, a finding fixed here.** #192 made the sample's message uid prefix a second no-sample
      marker. The fixture held it on 38 lines (72 occurrences: the 34 messages' `uid` and `repoPath`, the 4
      attachments' `path`), and `S124DatasetPipelineITTest` named a whole sample uid; both reach niord-base's
      test-jar, so after `package -DskipTests` (49 s) the no-sample check over niord-base's jar and test-jar,
      `niord-app`'s jar and the bootstrap zip exited 1 with 2 findings, `db/niord-base-data.sql` and
      `S124DatasetPipelineITTest.class`, and #196's CI would have been red. Fixed in the regeneration, not in
      the check: the script's step 7 reads the prefix from the throwaway database (`SELECT DISTINCT left(uid,
      24) FROM "Message"`), stops unless it gets exactly one well-formed prefix, and rewrites it to the fixture's
      own (§7.4); its new step 9 runs `check-no-sample.sh` over the dumps' folder. The IT looks NW-001-26 up by
      its short id in a `Tx.requiringNew()`, its assertions unchanged. `TestFixtureCurrentTest` gained
      `theFixtureCarriesNoSampleMarker`, red first on the fixture before the regeneration. The check's test-jar
      paragraph and `CheckNoSample`'s javadoc name the rewrite. Rejected: exempting the test-jar or the dump in
      `CheckNoSample` (it weakens the check), and leaving the fixture out of the test-jar (DK's ITs read it).
    - **Regeneration**: twice, 112 s into the committed path and 92 s into `NIORD_SEED_OUT`, exit 0 both times,
      the loader printing "The sample is loaded" and step 9 exit 0 both times; both files byte-equal across the
      runs (sha256 `7776eb77…` and `46906335…`), `i/lf w/lf`. `niord-base-data.sql`: 2797 inserts, 70
      `setval`s, 8263 lines, 715,293 bytes; `niord-test-seed.sql`: 111 tables, 9185 lines, 534,787 bytes. The
      old prefix 0 times in the data dump, the fixture's on 38 lines (72 occurrences). The sample's changes
      moved 93 rows of the data dump: `Message` 34 (the rewrite), `Attachment` 4, `Feature` 9, `SourceDesc` 7,
      `PublicationDesc` 6 (the link now null), `MessagePartDesc` 19, `MessageDesc` 4, `MailingListTrigger` 4,
      `MailingListDesc` 2, `MailingListTriggerDesc` 1, `Chart` 3.
    - **No IT moved with the data**: the re-paired sources, moved positions, link-less publications, renamed
      mailing lists and normalised triggers changed no assertion. The only IT change is the S-124 lookup above,
      made for the no-sample check.
    - **The whole IT suite** on the new fixture (`./test-it --its-only -pl niord
      -Dmaven.test.failure.ignore=true`, 376 s): 3361 tests (C4b's 3359 and the two S-124 tests it added
      later), 9 failures, 0 errors, 10 skipped: the 8 known Windows-only ones and
      `SecomSubscriptionITTest.aNodeCannotHoldMoreSubscriptionsThanTheCap`, a finding, not the data ("Expected
      status code <400> but was <200>"). It failed on the old fixture too (one call of four classes) and was
      green alone on both fixtures: an order dependency that C4b's `S124ReconcileFailureITTest` (its
      `@MockitoSpyBean` makes a third application context) brings to light. The mechanism predates C4b (the
      review of #196): every context refresh re-pointed the servlet's static before it too, since four
      `@DynamicPropertySource` subclasses already formed contexts of their own; C4b's spy context brought the
      class order that exposed it. The targeted call (`S124DatasetPipelineITTest` 18,
      `S124ReconcileFailureITTest` 1, `BootstrapSeededITTest` 2, `SecomSubscriptionITTest` 11; 98 s): 32
      tests, the same 1 failure.
    - **The cause, and the fix** (the review fixes of this sub-PR). The SECOM servlet takes its beans from the
      static `SecomV2Application.applicationContext`, which the context refreshed last sets
      (`SecomConfiguration.secomContextHandover`), and keeps them from its first request on. When the base
      SECOM context exists before the reconcile test's spy context and has served no SECOM request yet,
      `SecomSubscriptionITTest`'s first request starts the base servlet with the spy context's beans, so the
      cap set by reflection on the base context's bean does not hold. Shown before the fix: the reconcile and
      subscription classes together green (12 of 12, 91 s), the pipeline and subscription classes green (29 of
      29, 83 s), the three together 1 failure of 30 (113 s). The fix is in the tests: `SecomITTest.secomSetup`
      sets the static to the test's own context before each test, and `S124ReconcileFailureITTest` carries
      `@DirtiesContext(AFTER_CLASS)`, so no third live context stays on the shared database (alone it would
      leave the static on a closed context). Not a production defect: production has one application context.
      After the fix: the four-class call 32 of 32 (125 s); every SECOM IT (`org/niord/secom/**/*ITTest`, 17
      classes) 137 of 137 (135 s). The whole IT suite after the fix (the checker's run): 3361 tests, 3351
      executed, 8 failures, all known Windows-only (`RepositoryRestServiceITTest` ×7,
      `SettingsServiceITTest.getPathBuildsPathFromStringValue`), 10 skipped, 369 s; the cap test green in it.
    - **The regeneration once more**, after step 7 also rewrites the prefix in `"Reference"`'s `messageId` and
      `"IssueMember"`'s and `"IssueOverride"`'s `messageUid` (each update guarded by `strpos`, so it writes no
      row today): exit 0, 96 s, step 9 "no sample in 1 artifact(s)"; both dumps byte-equal to the committed
      ones (`7776eb77…`, `46906335…`; 2797 inserts, 70 `setval`s, 8263 lines).
    - **#192's tests**, one unit call of 12 classes (30 s): 114 tests, 0 failures (`TestFixtureCurrentTest` 3,
      `SampleFolderTest` 13, `SampleSafetyCheckTest` 18, `SampleToolTest` 3, `CheckNoSampleTest` 8,
      `LoadSampleScriptTest` 4, `BootstrapRestServiceTest` 2, `CoreResourcePackagingTest` 10,
      `BootstrapArchiveTest` 24, `CountryApiDocTest` 8, `DefaultTextsTest` 9, `DefaultSettingsTest` 12).
      `bash -n` clean on `load-sample.sh`, `regen-test-seed-postgres.sh` and `check-no-sample.sh`; the loader's
      refusals by hand in #192's order (a non-loopback host, then a database other than `niord_sample`, then the
      container), `--container` without a value exit 2.
    - **No sample in a jar or image**, after `package -DskipTests` again (22 s): exit 0, "no sample in 4
      artifact(s), 96043 entries read"; the test-jar's `db/niord-base-data.sql` 715,293 bytes.
    - **Clean-up**: `niord-seed-pg` removed by the script's trap; no database created in niord-db; no process
      of the run left.
  - **The whole suite** (2026-10-23/24): `./verify-local --base origin/feature/c4b-it-fixture` on `f15c4de2`
    PASS in 31m47s: the backend 10m06s (5168 unit tests and 3361 ITs, only the 10 known Windows-only failures;
    the IT report check and the no-sample check), the administration app 8m57s, the public site 1m28s, and the
    example country PASS with the `zod` and `undici` overrides; the run before it, on `a2829392`, failed only in
    the example country, on a network outage (`npm audit signatures`: `ENETUNREACH`). CI on #196 green on
    `9bb87e5d`, all 12 checks, on the merge ref with `main`'s #200: the suite on Linux, on the fixture's LF bytes.
  - **Not yet run:** a regeneration on Linux; `be/k8s/README.md`'s step 4 on a local cluster (read only).
  - **DK's next pin bump** (nothing changed in `niord-dk`; read through git on `feature/p5-dk-moves` @
    `bc9353a`, 2026-10-23). DK's bump to 4.0.87 (P5e, `niord-dk` #6) predates C4b's publish, so DK's ITs still
    run on the moved seed; the bump meant here is DK's first to a version carrying #196. The sample messages'
    uids in that fixture carry the fixture's prefix (§7.4); no DK IT was read for a sample uid after #192, so
    that bump checks it. DK's ITs extend the test-jar's `ITTest` and run on its fixture from the bump on.
    `ContextLoadsITTest`, `AppBootSmokeITTest` and `CoreDefaultsPrecedenceITTest` need no change.
    `DkDgpsTemplateITTest` needs none today: the fixture's `tmpl/dgps.ftl` and `tmpl/dgps.js` are the sample's
    byte-equal copies of DK's at `68b95cf`, still equal at `bc9353a`, and the `Danmark` area is in the sample's
    areas. `DkLightDestroyedTemplateITTest` moves: `tmpl/light-destroyed.ftl` is one of DK's 42 shadows of base
    templates (of its 56 bootstrap templates; all 42 differ in content), the fixture now holds the base row, and
    `DkBootstrapTemplates.addMissingTemplates` creates only missing rows, so DK's own template never reaches the
    database. At the bump `DkBootstrapTemplates` writes DK's 56 bootstrap templates over the fixture's rows
    (create or replace), and its base half, the paths DK lacks, becomes unnecessary for the ITs (the unpack of
    `niord-bootstrap` stays for `DkBootstrapITTest`). `DkBootstrapITTest` is unaffected: it runs on its own
    empty container. The country build group's three ITs from the test-jar name no domain and passed in
    niord-base's run. The realm rename touches no DK IT: none names a domain client. The same bump removes
    DK's temporary acceptance of the `zod` advisory GHSA-mrhx-c7xc-m3w5 (`fe/audit-accepted.json`, `niord-dk`
    #9, the owner, 2026-10-24) once the base version it pins has a lock that resolves `zod` 4.6.6 or later:
    4.0.91 does (`main`'s #200, `68ba80a7`; `@angular/cli` 22.2.4 declares `zod` 4.6.6), and #196 itself
    carries no `zod` override after its review. DK's next change, a bump or not, also adds the line for its
    first publish, `niord-lang-da:1` (`niord-dk`'s `Pack` run #1, 2026-10-24), to its `pack-releases.md`
    (country-split plan §6 P5, _DK's next pin bumps after P5e_). At DK's bump onto P7's version its `Audio`
    row restates the base product's type and, within the first bootstrap, its `da` replaces the base's English and
    its `niord-nw` link is added (the owner's ruling of 2026-10-24, §11 C6), and DK deletes its copies of the audio
    editor and viewer and their registration in its `country.providers.ts` (country-split plan §6 P5 and P7).
    **Done** at DK's bump onto 4.0.97 (`niord-dk` #10, `9f09d195`, 2026-10-25): `DkBootstrapTemplates.writeDksTemplates`
    writes DK's 56 over the fixture's rows (create, replace when the content differs, leave an equal one), the
    base half dropped, and `DkLightDestroyedTemplateITTest`, red at `:156` before it, is green, as is DK's whole
    suite on the fixture; `fe/audit-accepted.json` is `[]`; `pack-releases.md:15` has da:1's line; the `Audio` row and the
    deletions as above (§11 C6, the State line of DK's bump onto 4.0.97).
  - **GL's next pin bump** (nothing changed in `niord-gl`; read through git on `feature/p6-gl-moves` @
    `5fd4a9f`, 2026-10-24). GL's `main` has no integration suite; its C6 branch has two ITs:
    `GlBootstrapITTest` on its own empty container, unaffected, and `GlFiringExercisesScriptITTest` on the
    test-jar's `ITTest`, whose fixture `GlBootstrapTemplates` creates only missing rows, as DK's does. At GL's
    first bump to a version carrying #196 `GlBootstrapTemplates` writes GL's bootstrap templates over the
    fixture's rows (create or replace); `fetch-firing-exercises.js` is the sample's byte-equal copy of GL's
    today, so the IT passes either way, but GL's shadows of base templates would never reach the database. GL's
    bump P6e (`niord-gl` #7, 2026-10-24) added an acceptance of the `zod` advisory, since it pins 4.0.87; as
    DK's, it goes at GL's bump to 4.0.91 or later, the first base version whose lock resolves `zod` 4.6.6. At
    its bump onto P7's version the audio step is DK's (GL's `Audio` restates the base type with no domain link,
    and within the first bootstrap its `kl`, priority 10 and `MANDATORY` replace or fill the base's English, 50 and
    empty requirement, §11 C6; its two components and their registration deleted). **Done** at GL's bump onto
    4.0.97 (`niord-gl` #9, `555b7c73`, 2026-10-25): `GlBootstrapTemplates.writeTemplates` writes GL's templates
    over the fixture's rows (create or replace, comparing first), the base half dropped; measured with a
    temporary count, 0 of GL's 46 shadows held GL's content in the IT database with the create-only fixture and
    46 of 46 with `writeTemplates`, asserted since by
    `GlFiringExercisesScriptITTest.glsTemplatesHoldGlsContentInTheItDatabase`; `fe/audit-accepted.json` is `[]`;
    the `Audio` row, its two new assertions and the deletions as above (§11 C6, the State line of GL's bump onto
    4.0.97; §7.4, *A country's templates*).
  - **The review of #196** (2026-10-24, on `c4b/review-196-fixes`, a sub-branch of `feature/c4b-it-fixture`):
    two reviews, one "ready to merge" without findings and one with 24 inline comments, 10 items outside the
    diff and a question for the owner; every item was checked against the tree and the plans.
    - **The owner's rulings of 2026-10-24.** S-124's charts: the drop is kept (S124-1 above, with the corrected
      premise in the mapper's comment, `S124Test`, `S124DatasetPipelineITTest`, the gap analysis §5 and this
      record). Audio promulgation joins the base product in the country-split plan's P7 (§7.1; that plan's §6
      P7). DK's `zod` acceptance (`niord-dk` #9) and any of GL's are removed at their bump to a base whose lock
      resolves `zod` 4.6.6 (4.0.91 or later; the pin-bump items above). Both went at the bumps onto 4.0.97
      (`niord-dk` #10, `niord-gl` #9): each `audit-accepted.json` is `[]`.
    - **The regeneration script.** Both `pg_dump` pipelines drop the `-- Dumped from` and `-- Dumped by` lines;
      step 1 runs `clean test-compile`; step 7 deletes the seven `BATCH_JOB_*` `Sequence` rows; the usage says
      `NIORD_SEED_OUT` holds only the two dumps, and step 9's message says a finding in another file is that
      file's (§7.4).
    - **The fixture**, regenerated once all source changes were in, twice: 133 s into the committed path and
      129 s into an empty `NIORD_SEED_OUT` folder, exit 0 both times, step 9's no-sample check passing; both
      files byte-equal across the runs (sha256 `987ddc7e…` and `a5855a5e…`), `i/lf w/lf`, no banner line and no
      `BATCH_JOB_` row. `niord-base-data.sql`: 2790 inserts (2797 before), 70 `setval` lines, 8254 lines,
      714,519 bytes; `niord-test-seed.sql`: 111 tables, 9176 lines, 534,454 bytes. Each file's diff is the 2
      banner lines and the 7 counter rows and nothing else; the other counts unchanged (`ScriptResource` 84,
      `Category` 45, `ParamType` 34, `PromulgationType` 3, `Chart` 172, `Attachment` 4). `niord-seed-pg` gone
      afterwards.
    - **Tests, each seen red first.** `TestFixtureCurrentTest.aSecondUidPrefixIsRefused` (red against a
      first-match helper; the uid prefix is now read from every uid, as `CheckNoSample` does);
      `LoadSampleScriptTest.anEmptyContainerIsUsage` (red, exit 1, with the empty-value rule taken out of
      `load-sample.sh`, which is unchanged afterwards) and `theNamedContainerIsTheOneAsked`;
      `ScriptResourceRestServiceITTest`'s two `/all` cases on `hasItem("templates/messages/message-list-pdf.ftl")`
      (red with a path the fixture lacks); the failed counts in `S124DatasetPipelineITTest` (0, twice) and
      `S124ReconcileFailureITTest` (exactly 1), red with the base mapper's chart publication of `195d102d` put
      back for one run (failed 6, 6 and 7), the mapper then restored exactly (its diff is the comment alone).
    - **Comments and documents**: the IT realm described as derived from DK's bootstrap realm, the legacy
      rulings' desks, `ReportFixtures`' rows staying for the JVM, the `dma-fa` case renamed (`...WithNoNumberedMessagesIsOne`), the fixture facts
      of `AbstractApiServiceITTest`, `RoleAuthorizationMatrixITTest` and `ITTest` (the test tree's `niord.json`,
      no `Setting` row), the demo archive's leftovers, `be/docs/country-api.md` (the country IT fixture creates
      or replaces its templates; a C4b row in _Changes_), `secom-design.md`'s reconcile shape, BUILD1's and DICT1's
      references, `untestable-code.md`'s two entries, `test-infrastructure.md`, `be/docker-compose.yml`'s realm
      comment; in the frontend the SECOM toast's backlog entry with every file it touches, and a backlog line for
      the kit's `image.yml` comments, which name `niord-gl`'s as the model.
    - **The npm overrides.** With `main`'s #200 merged in, the lock already resolves `zod` 4.6.6 (`@angular/cli`
      22.2.4 declares it; `@angular/forms` and the MCP packages accept it) and `undici` 7.30.1 (`jsdom`'s
      `^7.21.0`, dev-only), so both overrides were dropped and `fe/package-lock.json` did not change; `vite`'s
      8.3.4 stays, since every `@angular/build` 22.2.x declares `vite` 8.3.0 exactly. `fe/README.md`
      (_Requirements_) records the rule and the one override with its advisories, its reason and when to remove
      it. The kit's `--omit=dev` audit had failed on `zod` alone: `undici` is dev-only.
    - **Runs** (Windows, 2026-10-24): unit, `TestFixtureCurrentTest`, `LoadSampleScriptTest`, `S124Test`,
      `BootstrapArchiveTest`, `CountryApiDocTest`, `CheckNoSampleTest` and `SampleFolderTest`, 96 tests green
      after the source changes (66 s) and again after the regeneration (42 s); the targeted ITs, 13 classes
      (the S-124 pipeline and reconcile-failure, `ScriptResourceRestService`, `MessageSeriesRestService`,
      `LegacyTemplateRulings`, `AbstractApiService`, `RoleAuthorizationMatrix`, `BootstrapSeeded`,
      `SequenceService`, `BatchDomainImportProcessor`, `FmReportService`, `ScriptResourceImport`,
      `MissingPathReport`), 172 tests green (119 s); `package -DskipTests` and `check-no-sample.sh` over the 4
      artifacts exit 0 (96043 entries read); `bash -n` on both scripts. Frontend: `npm ci` exit 0; `npm ls`
      `zod` 4.6.6, `undici` 7.30.1, `vite` 8.3.4; `npm audit --omit=dev --audit-level=high` 0 vulnerabilities,
      the full audit 1 moderate (`fast-uri`, dev-only), 0 high; the administration app's unit tests 5509 green
      (222 s), the public site's 920 (26 s).
    - **Not yet run:** `./verify-local` on this sub-branch; CI on #196 after the push; a regeneration on Linux.
      The exact failed counts of the S-124 pipeline and reconcile-failure ITs are proven in the targeted run
      only; should the whole suite fail them, the sweep's failure log names the uid of an eligible published
      numbered warning another IT left behind, and that IT is made to roll back or remove it (the assertions
      stay exact).

---

## 12. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| A Java change that a template depends on | a deployment's template breaks, or a base product fix to a template never runs there (as today: `MessageMailService.java:124`, F-X146) | the compatibility rule: stay compatible, or ship the template in the release import file named in the release notes (§6.2); fixes that matter for security go into Java |
| A template path never rendered on a deployment before C4 (a code-named one included) | that template or language variant stops rendering, or falls back to English | the missing-path report, built in C4.2a (`MissingPathReport`: logged at start, `GET /rest/script-resources/missing-paths`, and the pre-upgrade SQL `missing-paths.sql` in the release folder); the first release's import file; the report read on DK and GL staging, the import file applied only to local copies of their databases (§6.6) |
| An operator deletes a key a template uses | every render using it fails, and the key no longer returns at restart | the delete confirmation names its users; the dictionary import file restores it (§4.6) |
| Adoption misses keys deleted since the last start | such a key is added once more | the same as every start today; after adoption the ledger holds |
| A bootstrap runs twice or halfway | edits overwritten, or a half-installed system | the claim's two guards, checked in one locked transaction: the marker reads `pending` and the database holds no data (no row outside the not-data list, `BatchData` counting), so `existing` and `applied` are refused, and so is a database any import ran on; `failed` and `in-progress` are final, whatever the number of archives, so a bootstrap is never run again on the same database nor taken over, and a bootstrap job's restart is refused; the remedy is a database recreated empty (D4 as amended by the owner on 2026-10-24, §7.2); a runner that starts each job only after the previous one has ended and, past a job's 10 minutes, requests a stop and waits for the execution to end before the run is `failed`; negative tests, each shown red on the old code. Until that ruling `failed` could be retried with one archive and a run that broke off was taken over after 5 minutes with no sign of life (§7.2, *No retry*) |
| A seeded or populated database read as fresh | a bootstrap over real data | `existing` written on the first start when any domain, message or area exists; since P7-bs the claim checks again that the database holds no data, naming the first table that holds a row; tested (`BootstrapSeededITTest`'s seeded database forced to `pending` is refused) |
| The not-data list misses a table a first start or a scheduled job fills | a fresh database refused | the safe direction: the refusal names the table; `LiquibaseBootstrapITTest` pins the list after a first start and one authenticated request; the queued mails a scheduled alert writes (`ScheduledMail`, `ScheduledMailRecipient`) are on the list since the review of #204; a refusal naming another table no bootstrap wrote on a fresh cluster database means a configuration fault, fixed before the database is recreated (`be/k8s/README.md` step 4). Writes during the 5–20 s of a run, after the claim, are not stopped (P7-bs's decision 15) |
| A country settings file lags the base product | a key new in the base product's settings file would take the base value there | the build fails until the country sets it or lists it (R-CDC7); the upgrade report lists new keys |
| The IT fixture is regenerated | ITs that pin ids and contents break | C4 moved the fixture unchanged, and it stayed the moved seed in the test-jar until C4b, its own step after C7 (§11). As built in C4b: the suite was measured on the new fixture first, and the pinned ITs moved in the same PR, each with its cause in the test and in §11 C4b, none weakened (a count derived, an id moved, a row from a named fixture or planted and removed by the test); `ReportFixtures` for the rows only DK's bootstrap has; `TestFixtureCurrentTest` against a fixture stale after a template change; the sample's guard against a stale sample during the regeneration; a product defect the new data exposed is recorded, not patched over (S124-1); the regeneration rewrites the sample's message uid prefix to the fixture's own and ends with the no-sample check over its dumps, and `TestFixtureCurrentTest.theFixtureCarriesNoSampleMarker` fails on a fixture that carries the sample's sentinel or prefix (§7.4, after #192). DK's and GL's ITs move at their first pin bump to a version carrying C4b (§11 C4b) |
| A later archive of a bootstrap run restates a base entry with a different value | the country expects its value | since the owner's ruling of 2026-10-24 within the first bootstrap a later archive's values replace what an earlier archive of the run created, and a value it leaves out keeps the base's (§7.2, *Within one run*); the job log names each replaced value (`replaced <field> <old> → <new>`); a bootstrap cannot clear a base text (an empty string or object counts as left out), while a list it gives explicitly, even empty, empties the base's (the owner's decision of 2026-10-25, §7.2, *Within one run*); a kind change of a parameter type fails the job; a restated domain is refused before the claim (from 2026-11-03, decision 141, unless the later archive gives only its `domainId` and a boolean `active`, whose flag then wins). Until that ruling the differing value was kept and logged, and the base's stayed |
| A country takes a C2+ base product before its C6 | its root-level texts (and from C3b its root `niord.json`) would no longer be read | the transitional root layers (§4.1, §5.2): since the countries' backend parts went first, the text layer stayed until GL staged `niord-lang-da` (GL's bump onto 4.0.111, `niord-gl` #10, 2026-11-02, deleted its root-level `*_da`) and was removed in NG-B (#252), the text check's `root-bundle` rule now failing a country build that still has a root-level bundle; the root `niord.json` read stays until niord-base's test tree moves to `defaults/` (§5.2; the seed's copy went in C4b) |
| Script resources run with full privileges | an import is code execution | built by C4.2b: SYSADMIN only; history with the user and the source (`import`, `editor`); `ScriptResourceCheck` on every imported file and in the bootstrap (a guard against carelessness, not a boundary: it refuses the listed tokens and packages, and the evasions its test asserts pass it); the preview, which adds only the ticked paths and overwrites only the ticked ones, and keeps a ticked row changed since the preview (`basedOn`); the FreeMarker resolver (C1); the batch-set allow-list, which refuses the imports and `script-executor`; D1, the folder watchers deleted |
| A country's settings file (or transitional root `niord.json`) without a `required` key, on a database without the row | `country` or `organisation` takes the base product's neutral value (`XX`, `Niord`) into series numbers, templates and S-124 | the rows exist on DK's and GL's local databases (`DK`/`DMA`, `GL`/`DMA`) and a row wins; start-up logs a warning per missing key (§5.2, built in C5); each country adds the keys when it takes a C5+ version (§5.2, C6); the country build's `required` check refuses the file from C5b (§5.5), run by the country build group's `CountryDefaultsChecksTest` once the country has `defaults/country/` (C6); over the transitional root `niord.json` it does not run |
| GL's S-124 identity changes with C5 | GL's datasets carry nationality `GL`, ids `GL.` and MRN `:gl:` instead of `DK`, `DK.` and `:dk:`; a pre-C5 dataset keeps its `:dk:` `mrn` and `uuid` while every new version's GML carries `:gl:`, so the database key and the subscribers' `dataReference` diverge from the GML's identifier, and no code path repairs it | intended by S3; no `S124Dataset` or `S124SeriesConfig` row on the local databases (§11 C5); before GL's first C5+ start the deploy step counts `"S124Dataset"` on GL staging and, if it is not 0, either cancels and reissues the affected warnings or rewrites `mrn` and `uuid` (and the pinned `SecomSubscription.dataReference`) by hand SQL in one transaction (§11 C5, the owner's choice, a C6 question for GL); GL's producer code is `DK01`, as DK's, and its producing agency the Danish Emergency Management Agency (owner, 2026-10-22; `niord-gl` #3, `niord-dk` #4; §11 C6) |
| The sample goes stale when the base bootstrap, the entities or the schema change | the sample's ids or foreign keys no longer fit the bootstrapped base, and a load would insert wrong or colliding rows | the SQL's guard refuses before it inserts a row unless the marker reads `applied`, the Liquibase changesets are the recorded ones, no target table holds a row or has its sequence where the sample's go (since P8a, NG-8: at or above 1,000,001), the sample's domains and series are not there, and every base row it refers to resolves by its natural key to exactly one row (until NG-8: the first free ids of a base-only first start and a recorded fingerprint of the base rows). The refusals split their remedy: the changeset refusal names `be/niord/scripts/regen-sample.sh`, which rebuilds the sample on the current schema; the offset, domain and series, and natural-key refusals name `./start-local.sh --sample --reset` (a database that is not one clean run of the base bootstrap), and a regeneration only if the base bootstrap, schema or entities changed. The changeset half is checked in CI: `SampleFolderTest` compares the guard's changesets with those `db.changelog-master.yaml` includes, so a new changeset is red until the sample is regenerated, and since NG-8 it fails on a sample id below 1,000,001 or a base row named other than by natural key. A drift of the base bootstrap that the natural keys no longer resolve needs a database and shows only at load time: in `SampleOnACountryBaseITTest` (since NG-8), in the regeneration's schema check and, from C4b on, in the IT fixture's regeneration (#196) (§8, C7) |
| Several replicas | two starts adding the same key | the advisory lock (§4.2); one replica today |
| A dead editable `Setting` row, a key no code declares (DK's and GL's eight, `wmsLogin` and `faMrnPrefix` among them) | from C3b it can no longer be saved on the Settings page: the write answers 400 | hidden since C4.2a: the page and its Export no longer list it, and the row stays in the database (owner, 2026-10-21); nothing reads it; the refusal names the key (§5.3, §5.6) |
| A rollback to a pre-C2 version | deleted keys come back and reads insert again | accepted. The ledger survives, and on the next upgrade those keys are adopted |

---

## 13. Decisions D1–D5 (owner, 2026-10-20)

| | Question | Decision |
|---|---|---|
| **D1** | Unattended import folders: the `batch-sets` folder, the per-job `batch-jobs/*/in` folders, `-Dniord.batch-set` | **All deleted.** Every import goes through the admin app by a sysadmin, recorded. The UI's imports do not depend on the folders (§6.4). The owner checked on 2026-10-21 that nothing outside Niord uses those folders (§10). |
| **D2** | What a Liquibase changeset may do to data | **Schema only, plus reshaping existing values a schema change requires** (split, convert, move), never adding, deleting or changing content (§7.4). The publications go-live import, the bootstrap and backup/restore are unaffected. |
| **D3** | Languages and tile/WMS addresses: configuration or operator settings | **Both operator settings, as today**: editable on the Settings page, with defaults in the settings file (§5.4). Consequence: the administration app's Content-Security-Policy (country-split plan §4.11 rule 5) is built at runtime from the current settings, so its `img-src` follows the operator's tile addresses (the WMS layer is the same-origin `/wms/` proxy, so the WMS address is not in it). |
| **D4** | How a fresh deployment is detected and the bootstrap applied exactly once | **The marker set at the first start** (§7.2): `pending` only when no domain, message or area exists; the runner accepts only `pending` or `failed` (and, since the review of PR #179, an `in-progress` run that has shown no sign of life for 5 minutes; a live run is never taken over); since the review of PR #188 a retry of `failed`, or a takeover, is accepted with one archive only, and with more it is refused, the remedy being an empty database (the owner delegated the choice on 2026-10-22); a database created by an earlier version counts as existing. **Amended by the owner on 2026-10-24**, in his words: "a first bootstrap needs to be seen as a single united first time deploy following the normal rule of country overwrite - so whatever the country has in settings, configurations, texts, bootstrap data, etc takes preceedence … BUT (and thats a big but) it must NEVER be possible to bootstrap a deployed database/system. There must be safeguards against this if not already", and "bootstrapping is a very rare occation that only happens one time for a new environment". He agreed to three points ("yes agree on all three"): (1) on the first bootstrap the country wins everywhere; (2) within the first bootstrap, a country's value replaces the base product's, as a template does; (3) the safeguard is strict: the claim refuses unless the database is empty, checked again at the claim, and a failed bootstrap is fixed by recreating the database, never by retrying on it. So the runner accepts only `pending` over a database that holds no data, checked with the marker in the claim's locked transaction; `failed` and `in-progress` are final, with no retry and no takeover, and a bootstrap job is never restarted; the remedy is a database recreated empty and the same command with the same archives (§7.2, *No retry*). Only how a bootstrap is deployed changes: start-up's add-missing of settings and texts, release folders and sysadmin imports with their preview and ticks, and no reload and no reset stay exactly as they are. Built in P7-bs (§7.2, §11 C6). |
| **D5** | Where LIST parameter values live | **In `template`, as today.** The editor keeps steering list values there. They are data: start-up only adds base product text keys that are missing, never changes or deletes a value, so an operator's list values are never touched. A base product text key and a list value should not share a key; the text checks (§4.7) warn when a new base product key in `template` collides with a key a deployment's list parameters already use (listed in the upgrade report). |

---

## 14. What changes in the rules and docs

- **`be/CLAUDE.md`:** a "Code, data and configuration" idiom replacing the "Settings:" line: what an operator
  can see and edit in admin or sysadmin is data (every template, script and report, the built-ins included),
  anything else is code, and Spring properties are configuration, read at start and never written to the
  database (R-CDC9; the idiom names these three, not §3.1's six kinds); no write on read; no reload or reset;
  new data templates only by import; a new setting declares its scope; defaults live under `defaults/`; a
  template the code names goes into `RequiredTemplates` and the bootstrap.
- **`fe/CLAUDE.md`:** no function copies repository content into the database (no reload from the server's
  files, no reset to default, a deep link only looks a row up, a render reads the database only).
  `fe/ui-apps/admin/CLAUDE.md` does not repeat it: it holds the administration app's own rules, and
  `fe/CLAUDE.md`'s apply to both applications.
- **Root `CLAUDE.md`:** this plan in the `docs/` list.
- **`docs/fe-country-split-plan.md`:** these places change together with this plan: its status and scope
  lines, §2's heading, R3, R10, R11 and a new row R14; §3.1 and §3.3; §4.1, §4.2, §4.5, §4.6, §4.9 and §4.10;
  §4.11 rules 2, 3 and 5; §4.12 and §4.13; §5 steps 1, 4 and 8; §6's shape and order, P5, P6 and P9; §7 and §9.
- **`be/docs/country-api.md`** (written in C5b): the `defaults/country/` contract, the settings file and its
  checks, the bootstrap and release folders, the script allow-list.
- The docs of §10.

---

## Review record

Two reviews (a fact check and a design critique, 2026-10-20). Each point was checked against the tree before
it was accepted or rejected.

- Fact check 1 / critique H1, configuration keys vanish from `/web`, `/editable-settings`, `/setting`: **accepted**, catalog-based endpoints (§5.3).
- Fact check 2, `execute-batch-set` bypasses the import preview; `script-executor` folder; `execute-javascript`: **accepted** (§6.4, §6.5, D1).
- Fact check 3 / critique H2, `@Setting` injection runs before `init`: **accepted**, files and catalogs read in the constructor (§5.2).
- Fact check 4, "base product default" ambiguous: **accepted**, `baseValue` else `codeDefault` (§5.2).
- Fact check 5 / critique M6, `lang.*` is in `web`, not `template`; GL has about 18 own `en` keys; `msg.responsible.agency` is read by base product code: **accepted**, pack labels for `lang.*`, the GL list given, the key optional with an `organisation` fallback (§4.3, §4.4).
- Fact check 6, more Danish data in core (`S124Mapper`, legacy rulings, `ContactRestService`): **accepted**, with the legacy package as a listed exception (S3, C5).
- Fact check 7, seed citations overstated: **accepted**, column reworded (§6.1).
- Fact check 8, four zero-byte intros: **accepted**, carried as non-blank placeholders, closing RENDER1 (§6.4).
- Fact check 9, potential-errors codes do not exist; DICT1 half fixed: **accepted**, entries written with the plan, DICT1 narrowed (§4.2, §10).
- Fact check 10, fixture regeneration breaks pinned ITs; test-jar: **accepted**, the fixture moves unchanged and stays in the test-jar, regeneration in C4b (§7.4).
- Fact check 11 / critique M3, Maven cannot reach GHCR packs: **accepted**, `setup.mjs` before Maven with a failing check (§4.3). **Rejected:** moving the base product's English backend text to `fe/languages/en/`; it stays in niord-base, which every consumer already depends on.
- Fact check 12 / critique M2, `asAdded` helps only new rows: **accepted**, adoption stores the hash when the row equals the default (bookkeeping only) (§4.2, §4.5).
- Fact check 13, the field's deep link uses `?path=`: **accepted** (§6.3, §10).
- Fact check 14, test citations incomplete; DK tests missing: **accepted** (§9.1, §10).
- Fact check 15, `wmsLogin`/`wmsServiceName` dead; DK commits a `wmsPassword` value: **accepted**. Note: `wmsPassword` is live (`WmsProxyServlet.java:89`). The dead keys are dropped; the committed values are placeholders, not real credentials (owner, 2026-10-20) (§5.4, §9.1).
- Fact check 16, `Audio` and the category domain links are DK's: **accepted** (§7.1, §7.3). (On 2026-10-24 the owner moved audio promulgation into the base product, in the country-split plan's P7: §7.1.)
- Fact check 17, countries lose root bundles before C6: **accepted**, a transitional root layer (§4.1).
- Fact check 18, R14 date vs §2 heading; `be/niord/seed/*` missing from §10: **accepted**.
- Critique H3, security keys writable and honoured through the database: **accepted**, `set` refuses and trust switches and credentials never read a row (§5.3).
- Critique M1, replica race on the ledger and the marker: **accepted**, an advisory lock with no new unique index on `DictionaryEntry` (§4.2).
- Critique M4, P5/P6 tied to the whole backend programme: **accepted**, each split into a frontend and a backend part (§1, §11).
- Critique M5, the script check is a bypassable denylist; no archive hygiene: **accepted**, the check is stated as a guard, its tokens extended, the security review's archive rules applied (§6.5).
- Critique M7, a deleted key breaks rendering for good: **accepted**, the confirmation names its users and the dictionary import file restores it (§4.6).
- Critique L1, an empty `country` reaches series numbers: **accepted**, `required` (§5.4).
- Critique L2, `failed` is terminal and the runner is REST-only: **accepted** (§7.2).
- Critique L3a, drop the settings ledger: **rejected**, because R-CDC5 names settings explicitly and the ledger costs one column. The settings half serves a row removed by hand SQL: the Settings page has no delete (§5.6), and a retirement changeset's keys leave the catalog with it. L3b, a static list instead of a generated index: **accepted** (§6.2).
- Critique L4, keep the ticketed `GET /all` read: **accepted** (§6.3).
- Critique L5, name the "row wins" price and log it; editing an existing copy stays allowed: **accepted** (§6.2).
- Critique L6, `documentationUrl` fails its own check; the table is illustrative: **accepted** (§5.1, §5.2).
- Critique L7, DK's bootstrap serves fresh DK environments only: **accepted** (§7.1).
- Critique L8, the Helm flip reaches values outside the repository: **accepted**, listed in C1; existing seeded databases are unaffected [I].
- Critique L9, rollback re-adds keys: **accepted** as a risk (§12).
- Critique L10, FE-plan consistency gaps: **accepted**, the edits added. The §4.5 base-product-shape change is replaced by the M3 decision.
- Critique missing items (bootstrap parse test, generated `defaults/packs`, folder job allow-list in code, the C1–C4 marker window, the release import per environment): **accepted**.
- **Owner, 2026-10-20 (after the reviews):** every setting stays editable on the Settings page, as today. This supersedes the configuration-only settings of fact check 1, fact check 15 and critique H3: no key moves to configuration, `set` refuses only unknown and `state` keys, the endpoints and the overlay stay as they are. A repository settings file holds no real secret.
- **Owner, 2026-10-20 (the one-rule model, R-CDC9):** every template, script and report is data; the code never reads one from the jar. This supersedes the 11 code templates run from the jar, the `CodeTemplates` list, the database-copy marker, the refusals at code-template paths and at built-in report names, the in-memory built-in reports, critique L3b's list (now `RequiredTemplates`, for the check only) and critique L5. The performance review's jar-memo sentence is dropped: nothing reads the jar.
- **PR #159, kaspernielsen, 2026-10-20 (10 points):** the two on this plan fixed in 20e9abbd: P1's base product backend items named in the order paragraph, and a duplicated sentence in §3.3.
- **PR #159, kaspernielsen, 2026-10-20 evening (106 points on both plans and their files):** fixed 2026-10-21 in 2419e5c7; none rejected, 3 accepted in part and 8 with another fix. The country-split plan's §11 summarises the round. Among the accepted points: C4b leaves the feature branch and becomes its own PR after C7 and P9, and a new C5b, the base product's half of the country contract, ends that branch (now C2–C5b) and is published with it before the backend parts of P5 and P6 (§11).
- **Owner, 2026-10-21: this plan is adopted.** The country-split plan's R14 is decided; its §2.1 keeps the path without this plan only as the record of the alternative weighed.
- **Owner, 2026-10-21, the rulings that touch this plan:**
  - `adminIntegrationPageEnabled` is declared in niord-base in C3 as a real setting (`web`, default `false`): an ordinary operator key, editable on the Settings page (§5.1).
  - An internal writer, which `TreeBaseService` calls, creates and updates `state` rows; the public set behind `PUT /setting/{key}` and the settings import refuse `state` keys and unknown keys (§5.3, C3).
  - A content-only language with no backend half yet is served by the deployment's default language, then any language, as `DictionaryVo.localizedValue` and `FmTemplateLoader` do today — not English. It cannot be the backend's `defaultLanguage` until its backend half exists; DICT1's fatal case stays. No backend code change (§4.3).
  - The first `niord-lang-da` carries DK's five `*_da.properties` as its backend half from its first publish; DK's Maven build reads its root-level copies until P5's backend part switches to the staged pack (§4.3).
  - A country's pom stays `be/pom.xml` with `be/mvnw`; there is no root pom (§4.3).
  - P9's sample carries its own DK rows under `be/niord/sample/` (DK's domains and series, `Audio`, the two scripts its mailing lists name) as Danish sample content; nothing is fetched from `niord-dk`, and drift from DK's bootstrap is accepted (§7.3, §8).
- **PR #179, two reviews, 2026-10-22 (4 inline findings the first reviewer judged blocking; 42 inline comments
  and the review body's items from the second, who found nothing blocking):** answered on
  `cdc/review-179-fixes` into `feature/code-data-config`. Every item was checked against the code and this plan
  before it was taken. Fixed: the four blocking ones (both import dialogs showing a late preview against another
  file; the dictionary preview hiding a long value the overwrite clears, with the second reviewer's major on the
  same line; a bootstrap whose job outlives its deadline releasing the marker while the execution still runs;
  the override check passing a listed shadow that is not a static image), each with a negative test shown red
  once. Settled the other way from a reviewer: script-resource paths are compared case-insensitively in the
  preview, as the loader and the writer find them, rather than exactly in the writer (§6.4). Recorded where the
  plan puts them: the group-count workflow step and DK's `application-local.yml` line (C6), the base bootstrap's
  `da` descriptions (until C6, §7.1), P7's patterns (country-split plan P7), and three pre-existing
  defaults found through the catalog (`be/docs/potential-errors.md`). The plan sentences that were wider or
  narrower than the code (§6.2's exception, §6.4's path comparison, §7.2's `in-progress`, the parameter-type
  and `aton` counts) were corrected toward the decided intent.
- **Points not taken as suggested** (numbered in the review's posting order):
  - Point 6, SEED3's dangling `(S1)`: it goes with SEED3's planned-fix sentence. The S1 row is not restored: 696ba502 folded its substance into §7.4 and C1, and its id is not reused.
  - Point 15, rows written from older bundles: the claim is limited to rows still equal to today's default, and the operator's remedy is named (delete the row). The optional comparison report is not added: R-CDC1 rules out a comparison tool.
  - Point 30, the "Firing Exercises" lookup: the name lookup stays, the bootstrap README names the English name as load-bearing and C4 logs a warning when no such category exists. No MRN lookup: every running deployment's category would need an MRN written into it, which the data rule forbids. §7.1 now names the seed's archive as the category's source.
  - Point 33, `--demo` between C4 and P9: not a local changelog loading the unchanged seed. From C4 its SQL is only in niord-base's test-jar, beside stub beans that would replace real ones, and it holds no script resource. `--demo` runs the bootstrap command with the base product's archive and then `be/niord/seed/niord-dev-basedata.zip`, until P9's `--sample` replaces it (§7.4, C4, C7).
  - Point 39, §10's `niord.json` line: the YAML half is dropped as suggested, but not a `wmsLogin`/`wmsServiceName` clause: the base product's file holds neither key. The line names C5's `country` and `organisation` instead.
  - Point 44, the catalog's backend section: no order between C2 and the country-split plan's P2 is needed. `catalog` reads whichever English backend bundles the checkout has, so whichever lands second changes that one path (§4.3, C2).
- **Owner, 2026-10-22, after PR A (#184) merged, the rulings that touch this plan:**
  - "Yes, widen the rule": within one bootstrap run, a later archive may replace a template an earlier archive
    of that run created, fresh database only. DK's and GL's records asked it: on a fresh database 0 of DK's 42
    and 0 of GL's 46 template shadows held the country's content, so a fresh environment rendered the base
    product's generic templates unless a sysadmin ticked the overwrites; putting the country's archive first was
    rejected, since the base's later category, parameter-type and promulgation-type imports update what they
    find. Built in C6's base product half with the language rule and the domain links (§7.2, *Within one run*).
  - DK's bootstrap carries the domain IDs DK's deployments and the local realm use, `niord-nw`, `niord-nm`,
    `niord-fa`, `niord-almanac` and `niord-annex`, not the old dev data's `niord-client-*`; the P9 sample uses
    the same IDs (§7.3, §8; P5d changed `niord-dk`: #3, `1d29b97`).
- **C6's records for DK and GL (P5a, `niord-dk` #2; P6a, `niord-gl` #2; 2026-10-22), the plan corrections
  taken**, each toward what was built:
  - §7.1, 176 → 170 `da` descriptions (also in C5 and C6): counted at `81361ab5`; the other 6 had gone with
    the DGPS data.
  - C6, the Danish-descriptions bullet: what DK's bootstrap could carry under the 4.0.76 imports, which replace
    a type's template parameters and a category whole; adjusted for the base product half, under which the
    additions apply.
  - §7.1, the countries' `be/bootstrap/` bullet (DK and GL): what each holds; the records called the shadows and
    the language additions inert, which held on a 4.0.76 base only: from the base product version with C6's
    base half they apply (measured 42 of 42, 46 of 46, GL's 111 `kl`), so the plan says "inert on a 4.0.76
    base". The categories' links and languages, which DK's record said a country could not carry (the category
    import replaced a category whole, and `DomainVo.categories` maps to the other relation), are additions from
    that version.
  - §7.3, the DK row (the domains active, the links once the rule exists, `NAVTEX`'s and `Twitter`'s links, the
    `nm-accumulated` source) and a new GL row: §7.3 had no GL row.
  - §9.1, the shadows on a fresh database (DK's verification (c)); §9, "laid onto the current base product
    file" (DK's 42 shadows moved unchanged, since laying them onto the current base files would change DK's
    renders, which C6 verifies unchanged); §9.1's tests (`DkLightCharacterTest` asserts, the empty-schema boot
    is `DkBootstrapITTest`).
  - §4.4, DK's country texts (`pdf.nm_almanac.desc`, `msg.responsible.agency`: C5 promised that the countries'
    own texts carry their wording from C6); GL's own keys as classified (4.0.76 has no text catalog, so the
    country's README is the record); the Checks (no check enforces prefix or grandfather in 4.0.76:
    `DefaultTextsChecks` has no such rule and cannot tell a reworded key from an own key).
  - C6's group count (a count in `image.yml` would always fail: it packages with `-DskipTests`), C6's DGPS
    bullet (GL's bootstrap carries all of DK's DGPS data), C6's packs bullet and §4.3 (the backend parts went
    first, so the packs' backend half comes with P5b and P6b), C6's Verification (what was run locally for GL
    and for the "before" renders).
  - §7.4, a country's IT templates (the test-jar carries no template, and DK's and GL's templates include base
    ones, so the countries unpack `niord-bootstrap`).
  - §9.2, the 58 shadows (three lagging copies dropped, not one: §9's rule applies to the two report templates
    as to `common.js`), GL's tests, GL's pipeline (deleted, nothing left to convert) and GL's map centre.
  - C5's *Consequence for GL* and §12's S-124 row: GL's producer code is left for the owner, since the task
    forbade inventing one. The owner ruled on 2026-10-22 (`DK01` and the Danish Emergency Management Agency for
    DK and GL, `niord-gl` #3 and `niord-dk` #4), and both places now say so.
- **PR #188 (C6's base product half), one review, 2026-10-22 (32 inline comments: 1 major, 10 minor, 21 nits;
  and the review body's 18 items):** answered on `c6/review-188-fixes` into `feature/c6-base`, each item checked
  against the code and this plan first. 46 fixed, 2 recorded, 2 declined. The major (a retry after a failed run
  would apply a country's addition-shaped entries as whole updates) went to the reviewer's option 2, the owner
  having delegated the choice on 2026-10-22: a retry, or a takeover, is accepted with one archive only (for a
  deployment whose bootstrap is that one archive), and with more refused before the claim, naming an empty
  database (D4), which is also a country deployment's remedy; option 1 is recorded as the alternative not
  taken (§7.2, *No retry*, its history; both retries were removed by the owner's ruling of 2026-10-24). The token is refused on uploads and batch sets and left out of the instances answer;
  the add-only imports write what they added and kept to the job log; the test gaps are closed. Recorded, not
  fixed: `Category.hasChanged`'s list equality (CATEGORY1) and the archive order, which the command does not
  check (§7.2, the contract, `bootstrap.sh`). Declined with evidence: the PR body's "6 of 10" for the rule off
  is right (tests 2-6 and 10: test 10's last assertion reads the template test 2 expects replaced), and
  `be/docs/cicd/design.md` needs no change (its §4 records the 2026-10-03 decision). The plan sentences wider or
  narrower than the code were corrected toward the decided intent: the C6+1 trigger (§4.1, §4.2, §5.2, §10,
  §12), GL's 35 against 37 (§11 C6), GL's additions with `da` and `kl` (§7.1, §11 C6), the IT fixtures' one
  transaction (§7.4, §11 C6), "logged" (§7.2, §12), and "optional" as the one wording for turning restatements
  into additions (§7.2, §11 C6, §12).
- **Owner, 2026-10-24, the bootstrap ruling** (D4 as amended, quoted there; built in P7-bs,
  `p7/be-first-bootstrap`). A design and a critique preceded the build, each point checked against the tree. The
  design built the rule in the six same-run branches behind the existing created-earlier gate, not by merging the
  archives' JSON before the import, which would interleave the base and country ids in the shared sequence and
  break the sample's ids, its guard's fingerprints and the regeneration's base-row comparison; it decided the
  presence of a field by a `Given` built from the file's JSON tree for a live job of archive index above 0 only,
  not by wrapper types in `org.niord.model`'s REST types and not by treating the import's defaults as left out,
  which would drop a country's explicit `false` or `0`; it treats an explicit null or an empty value as left out,
  merges keyed lists per key, replaces value lists whole only when they differ, takes the union of domain links,
  keeps the MRN as a matching key, fails a parameter type's kind change, refuses a restated `domainId` before the
  claim, checks emptiness from the catalog with the not-data list (`BatchData` counting, schema `public` only, no
  ratchet of the marker on a refusal), keeps `STALE_AFTER` and the heartbeat for the refusal's wording, adds
  `apply()`'s check, removes `BootstrapSameRunITTest`'s pre-run rows and its test 7, and leaves
  `BootstrapMarker.init`'s predicate and the sample's generated guard text unchanged. Not added: refusing a
  sysadmin's imports during a run (it would change running-system endpoints; the residual in §7.2). The
  critique's findings, all taken: the six pre-run fixture entries removed with Order 1's pre-run rows and test 11's
  counts rewritten; the dictionary cache also saved and evicted on a replaced value; `firstDataTable()` probing
  in `ORDER BY relname`, so `BatchData` comes before `Sequence`; the emptying helper deleting the dictionary rows
  outside the default ones instead of truncating them, and posting the applied run through the command as the
  sysadmin; template parameters matched by `paramId`, then by order, in the `Given` paths too; the restart
  refusal's IT starting its job through `BatchService`; the recreate procedure naming a `ScheduledMail` refusal
  (until the review of #204 put the queued mails on the not-data list); the countries' `paramIds` assertion message; `ImportResult`'s change in `be/docs/country-api.md`; test 3's
  `k2` and the root's `fr` (given by the later archive, so replaced; the kept-language case is a `de` the later
  archive leaves out); the whole `User` table on the not-data list, not the poster's row; and the restart refusal,
  which the critique would have put to the owner, built as point 3's "never by retrying on it", limited to
  bootstrap jobs and named in §7.2 and the PR for the owner's review. The owner kept it on 2026-11-03 (decision
  138). Plan corrections from the build, taken
  toward what was built: test 3 also flips `it_composite`'s list (`[p1, p2, p1, p3]`, a new `p3` appended), not
  only `k2`; the `User` table is shown not to be data by `dataTables()` and by a sysadmin row kept across the
  emptying, since a `GET` mirrors no user (only a call that needs the current user, such as a job start, writes
  one), so no request of test 3 writes the admin and sysadmin rows; the `aton` case of the data refusal names
  `Dictionary`, since a non-default entry needs a non-default dictionary row; and the job log's arrow is `→`,
  with a value the row lacked shown as `none`. The intermediate commits 2 to 4 are not each green
  (`BootstrapSameRunITTest`'s old pre-run rows are refused by commit 2's claim until commit 5 rewrites it); the
  branch's tip is.
