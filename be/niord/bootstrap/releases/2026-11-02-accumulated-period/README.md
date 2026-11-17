Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-11-02: the accumulated notices dated by their weeks' periods

The accumulated yearly Notices to Mariners print each week under its own heading. That heading's info line
used to date the week by its cut-off ("Cut-off: 14. January 2026"). It now dates it by the stretch the week's
notices were published in, from the start of the week's content period to its cut-off ("Published:
07. January 2026 - 14. January 2026"). A week whose source has no period start, an in-force edition, keeps
the cut-off line.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview, then the import of the ticked paths | the base product's templates and scripts at this release; the one that changed is `messages/nm-accumulated-report-pdf.ftl` |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release, with the new `pdf.accumulated.period` ("Published") |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes |

How a running deployment takes the change:

1. Upgrade to the base product version whose release notes name this folder. That version hands the template
   each week's period start; a template from before this release ignores it and keeps printing the cut-off.
2. Import `script-resources.json` on the Script resources page. In the preview, tick the overwrite of
   `messages/nm-accumulated-report-pdf.ftl` alone, after comparing it with the deployment's own copy: a
   deployment that edited that template keeps its edits unless it takes this one, and can carry the change into
   its own copy instead. Tick no other overwrite.
3. Nothing to import for the text: start-up adds `pdf.accumulated.period` in English where a deployment lacks
   it, and a country's language pack brings its translation. `texts.json` restores the key if a deployment
   deleted it.

Whether to import at all is the sysadmin's decision. Nothing applies these files on its own. A fresh
deployment needs none of this: the bootstrap brings the template.

The three generated files are written by `ReleaseFilesGenerator` (run by `ReleaseFilesCurrentTest`); this
folder never changes once it is merged into main.
