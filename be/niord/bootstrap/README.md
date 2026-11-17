Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# The base product's bootstrap

What a brand-new Niord deployment starts with: the base product's generic, country-free data. It is applied
once, by the bootstrap command, `POST /rest/bootstrap` (SYSADMIN), which `be/scripts/bootstrap.sh` wraps, to a
new, empty database: one the backend's first start found empty (the marker reads `pending`) and that still holds
no data when the command claims it. A database that held a domain, an area or a message at that first start
reads `existing` and is never bootstrapped, and one that holds data at the claim is refused, naming the table. A
country's own bootstrap is applied after this one in the same run: this archive is the first part of the command,
which does not check the order. Within that run a later archive's values win: for an entry this archive created,
each value the later archive gives replaces this archive's (a template, a name or description in a language, a
priority, a flag, a template parameter's settings, a list), a value it leaves out keeps this archive's, what is
new is added, and nothing is removed (`BootstrapRun`). A list given explicitly, even empty, replaces this
archive's; a field left out or null keeps it; an empty string counts as left out. The rows a first start writes by
itself (the marker, the settings, the defaults ledger, the five default-text dictionaries), the mirrored users and
the queued mails a scheduled alert writes do not count as data at the claim. A sysadmin's import is unchanged by
this: it adds what is missing and overwrites only what the sysadmin ticks. The rules are in `docs/code-data-config-plan.md` §7 (this
rule in §7.2) and, for a country, in `be/docs/country-api.md`.

The data is in English only (R1). The 170 Danish descriptions the folder carried until the code/data/config
plan's C6 are gone (`aton-dict.json` 111, `categories.json` 24, `parameter-types.json` 35): a country's
bootstrap adds the languages it needs to these entries in the same run.

The folder is in the batch-set format the import jobs read: `batch-set.json` names each job and its data
file, and each data file is in the format of that job's import (the same files the administration app's
exports write).

| File | Job | What |
|---|---|---|
| `templates/` | `script-resource-import` | all 80 templates and scripts, path `templates/<path in the folder>`, FreeMarker for `*.ftl`, JavaScript for `*.js`; the bootstrap runner turns the folder into the import's JSON |
| `domains.json` | `domain-import` | the master domain `niord-client-master` (name `Master`, active, no series, no time zone); active, so that a deployment with only this bootstrap has a session domain and its sysadmin reaches the sysadmin pages; a country's own domains come with its archive (a later archive may restate it with its `domainId` and `active` only, so a country may leave it inactive within the first bootstrap; any other restatement is refused) |
| `aton-dict.json` | `dictionary-import` | the `aton` data dictionary, 111 entries, in English (the DGPS stations are Danish data, in DK's and GL's bootstraps from the code/data/config plan's C6) |
| `categories.json` | `category-import` | 45 categories in English under 8 roots: `Light` and `Light buoy` with their template children and template parameters, `Buoy`, `Beacon`, `Obstruction`, `Drifting object` and `Radio navigation` with theirs, and `Firing Exercises`; no domain links and no `messageId`. `Radio navigation`/`DGPS` names no script and no template parameter: `tmpl/dgps.ftl`, `tmpl/dgps.js` and the `dgps` types are Danish data, in DK's and GL's bootstraps. A country's own `categories.json` completes the names in its languages and the domain links (and DGPS's scripts and parameter): it names each category by its English name exactly as here, nested under its parents, and within the run its languages and links are added (`be/docs/country-api.md`) |
| `parameter-types.json` | `param-type-import` | the 32 parameter types that are not standard, in English (the 7 standard ones are code; `dgps` and `dgps_station_list` are Danish data, in DK's and GL's bootstraps from C6) |
| `reports.json` | `report-import` | the built-in reports `standard`, `draft` and `public`, all three on `messages/message-list-pdf.ftl` |
| `promulgation-types.json` | `promulgation-type-import` | `NAVTEX`, `Twitter` and `Audio` (`typeId` and `serviceId` `audio`, priority 50, between NAVTEX's 1 and Twitter's 100), in English, without domain links |
| `texts.json` | none: not applied by the bootstrap | the base product's English texts as a dictionary import file (the five dictionaries of `defaults/base/texts/en/`); start-up adds the texts, and a sysadmin imports this file on the Dictionaries page to restore a key a deployment deleted: the preview offers that key unticked, and only a ticked one is restored. Generated, never edited |
| `releases/<yyyy-mm-dd>-<topic>/` | none: a sysadmin's import | the release import files: a release that offers a new or changed template ships it here, as `script-resources.json` in the format the Script resources import reads, with a `README.md`, the texts at that release (`texts.json`) and the missing-path report as read-only SQL (`missing-paths.sql`). A folder is named by the date and topic of its change, never changes once it is merged into main, and the release notes of the first version that contains it name it. The first is `2026-10-21-templates-from-database` (all 80 files, as the jar had them) |

There are no settings here (start-up adds them from the settings files) and no messages.

**The English name "Firing Exercises" is load-bearing.** The firing-schedule generator finds the category its
messages get by that exact English name (`CategoryService.getFiringExercisesCategory`), and logs a warning
when no such category exists. Renaming it here, or on a deployment, leaves those messages without a category.

The templates the code names are listed in `RequiredTemplates` (`org.niord.core.script`), and
`RequiredTemplatesTest` fails when this folder lacks one. Five templates are placeholders a deployment fills in
with its own text: the four report introductions `messages/nm-report-pdf-intro.ftl`,
`messages/nm-almanac-report-pdf-intro.ftl`, `messages/nm-accumulated-report-pdf-intro.ftl` and
`messages/fa-list-pdf-intro.ftl`, and `mailinglist/safetynet-intro.ftl`. Each is the one-line comment
`<#-- introduction: this deployment's text goes here -->`, never an empty file, so the templates that include
them render.

Nothing in this folder is in any jar or image: Niord reads every template from the database only, and a
deployment gets it from this bootstrap or from a sysadmin's import of a release file. The folder is published as
`org.niord:niord-bootstrap:<version>:zip` (the `be/niord-bootstrap` module), beside niord-base. The template path
names, without content, are listed for the missing-path report in
`niord/src/main/resources/META-INF/niord/expected-template-paths.txt`; that list, `texts.json` and a new release
folder's generated files are written by `ReleaseFilesGenerator`:

```
./mvnw -pl niord test -Dtest=ReleaseFilesCurrentTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dniord.releaseFiles.write=true [-Dniord.release=<yyyy-mm-dd>-<topic>, for a new release folder]
```

`BootstrapFolderTest` checks this folder: this README, every job of `batch-set.json` and its data file, the 80
templates with those the code names and the five placeholders non-blank, no standard parameter type, the 45
categories with no domain link or message and no script or parameter type the folder does not ship, no
description or promulgation type in a language other than English, no settings or message file, and release
folders named `<yyyy-mm-dd>-<topic>` with their README.
`ReleaseFilesCurrentTest` fails when `texts.json` or the path list is stale, and checks every release folder's
files; `BootstrapTemplatesParseTest` parses every template here and in the release files, and allows `?new`
only on the directives.

To apply it locally, `./start-local.sh` zips this folder with the JDK's `jar` tool and runs
`be/scripts/bootstrap.sh` when the marker reads `pending`, and only then. A job past its 10 minutes is asked to
stop, and the run reads `failed` only once that job has ended. There is no retry: a `failed` run, and one whose
backend stopped mid-run (it leaves `in-progress`), are refused for good (409), with one archive or more, and a
bootstrap job is never restarted from the batch page (its toast shows the refusal's reason). The remedy is a
database recreated empty (D4): the backend started again on it and the same command run with the same archives.
`./start-local.sh` prints the remedy and posts nothing (`./start-local.sh --sample --reset` for the sample's
database). When its own post fails, it reads the marker again: `failed` names the remedy, `in-progress` says to
wait for `GET /rest/bootstrap` to show the outcome, `pending` says the bootstrap was refused before it started (the
reason `bootstrap.sh` printed) and names the remedy for a database that holds data, `applied` says the run finished
after `bootstrap.sh` gave up waiting for it, and no answer names none. `./start-local.sh --sample` then loads the
sample (`../sample/`) into `niord_sample` with `psql` (`be/niord/scripts/load-sample.sh`).
