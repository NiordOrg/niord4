Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-11-16: the error hunt's second template fixes

The second phase of the error hunt of November 2026 found a draft print that failed for a model language no
dictionary names, and a function in the base scripts that called a method no class has. It also found that no
release so far told a deployment that kept its old weekly Notices to Mariners report templates to take the new
ones. This folder carries the corrected templates. Two paths changed since the release
`2026-11-12-error-hunt-templates`; every other template is as it was.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview, then the import of the ticked paths | the base product's templates and scripts at this release; the two that changed are listed below |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release; these fixes changed no text, so there is nothing to import from it unless a deployment deleted a key |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes |

## The changed paths

| Path | What the fix does | Shadowed by |
|---|---|---|
| `templates/messages/message-support.ftl` | A draft print names each model language by its `lang.<code>` text, as before; a model language that no dictionary names (no `lang.<code>` key) is named by its code in upper case, as the administration app does, instead of failing the whole print. | GL |
| `templates/tmpl/common.js` | The function `updateNavtexTransmitters` is gone. It called a method no class has, and nothing in the base product called it. A deployment's own script that calls it failed at that call before, and fails at the missing function after the import. | none |

## The weekly report templates of before the publications redesign

`templates/messages/nm-report-pdf.ftl` and `templates/messages/nm-tp-report-pdf.ftl` are not changed by this
release; this folder carries them as they have been since the publications redesign. They are named here because
no earlier release told a deployment to take them: the README of `2026-10-21-templates-from-database` said to
tick no overwrite, so a deployment whose stored copies are older than the redesign still has the old ones. With
the old copies:

- an amended edition of a weekly notice prints no "Version n" on its first page (both templates print it now,
  from the edition the issue hands them);
- the volume on the first page of `nm-report-pdf.ftl` is counted from the year on the clock when the PDF is
  made, not from the year the issue is for, so a December issue amended or printed again in January shows the
  next year's volume (the current template counts it from the issue's year and uses the clock only where no
  issue is behind the print).

The notices' content is right either way; the first page can mislead a reader about which edition he holds.

To take them: in the import's preview, compare the deployment's stored copy of each of the two paths with this
folder's. If the deployment never edited them, tick the overwrite of both. If it did edit them (its own header,
its own ISSN line, its own layout), carry those differences into the new copy, by editing the template on the
Script resources page after the import or by editing the deployment's own copy to match the new one instead of
importing it: an overwrite replaces the stored copy whole, and the deployment's own differences are lost.

| Path | Why to compare and take it | Shadowed by |
|---|---|---|
| `templates/messages/nm-report-pdf.ftl` | "Version n" on an amended edition; the volume of the issue's year | none |
| `templates/messages/nm-tp-report-pdf.ftl` | "Version n" on an amended edition | none |

## How a running deployment takes the changes

1. Upgrade to the base product version whose release notes name this folder.
2. Import `script-resources.json` on the Script resources page. In the preview, tick the overwrite of the two
   changed paths above and, after the comparison the section above describes, of the two weekly report
   templates, each only after comparing it with the deployment's own copy: a deployment that edited a template
   keeps its edits unless it takes the new one, and can carry the change into its own copy instead. Tick no
   other overwrite. A deployment that has not imported `2026-11-12-error-hunt-templates` yet takes that folder
   first; this folder repeats its changes, but its README holds the notes on which paths go together.

Prints already made keep what they printed until they are made again.

## The country deployments (DK, GL)

Never apply this import on a DK or GL deployment to a path that country's own bootstrap shadows: it would
replace the country's template, its own-language parts included, with the base product's English-only one. The
"Shadowed by" column was read from the main branches of `SIFA-DK/niord-dk` and `SIFA-DK/niord-gl` on 2026-11-16
(both on base 4.0.162): a path is shadowed where the country's `be/bootstrap/templates/` holds it. DK shadows
none of the four paths. GL shadows `templates/messages/message-support.ftl`, and its copy carries the same fault
(the two `text('lang.' + lang)` lines of the draft print); carrying the fix into it is the country's work, shipped
in a release folder of the country's own repository. A country that shadows a path later checks again.

The two weekly report templates are shadowed by neither country's bootstrap, but a deployment's stored copies
came from its own database and may carry its own edits: the comparison above decides, not this column.

Whether to import at all is the sysadmin's decision. Nothing applies these files on its own. A fresh
deployment needs none of this: the bootstrap brings the corrected templates.

The three generated files are written by `ReleaseFilesGenerator` (run by `ReleaseFilesCurrentTest`); this
folder never changes once it is merged into main.
