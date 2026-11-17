Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-10-21: templates from the database

The first release import files, made with the code, data and configuration plan's C4
(`docs/code-data-config-plan.md` §6.4, §6.6). From this release on, Niord reads every template and script from
the database only: nothing copies a template from the jar on first use any more, and no jar holds one. A path
a running deployment never rendered before therefore has no row there, and resolves to nothing until a
sysadmin imports it.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview (`POST /rest/script-resources/import-preview`), then the import of the ticked paths (`POST /rest/script-resources/upload-script-resources`) | all 80 templates and scripts of the base product as they were in the niord-base jar; the five placeholders (the four report introductions and `mailinglist/safetynet-intro.ftl`) are carried as the one-line FreeMarker comment `<#-- introduction: this deployment's text goes here -->` instead of an empty file, so the reports and SafetyNet mails that include them render |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release (the five dictionaries `web`, `message`, `pdf`, `mail` and `template`), to restore a key a deployment deleted; the preview offers a deleted key unticked, and the import adds the ticked missing keys and overwrites only the keys the sysadmin ticks (§4.6) |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes: every template or script path that the deployment's data or the code names and that has no row, and every built-in report (`standard`, `draft`, `public`) that is missing |

How a running deployment uses them:

1. Before or after the upgrade, read the missing-path report: run `missing-paths.sql`, or after the upgrade
   look at the start-up log or `GET /rest/script-resources/missing-paths` (SYSADMIN).
2. Import `script-resources.json` on the Script resources page. Its preview marks the paths the report lists:
   add those (the preview unticks the others in one go), and tick no overwrite. A path the deployment has keeps
   its row: the deployment's template is the truth, whoever edited it. Every row the import adds is recorded in
   its history with the sysadmin and the source `import`.
3. Whether to import at all is the sysadmin's decision. Nothing applies these files on its own.

A fresh deployment needs none of this: the bootstrap brings every template.

The three files are generated (`ReleaseFilesGenerator`, run by `ReleaseFilesCurrentTest`); this folder never
changes once it is merged into main. A later release that ships a new or changed template does so in a new
folder of its own, `releases/<yyyy-mm-dd>-<topic>/`, which its release notes name.
