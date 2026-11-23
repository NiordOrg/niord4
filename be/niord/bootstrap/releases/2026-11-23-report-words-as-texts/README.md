Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-11-23: the reports' English words become texts

The PDF reports printed a few words in English whatever the report's language: the label of the edition ("Version
2") on the first page of six reports, and on the message list the word "Generated" in its page header and the
front page's "Generated", "Criteria", "Result" and "messages" (the count of the list). They are now texts of the
`pdf` dictionary, so a report in another language prints them in that language once the dictionary has a value
for it. A deployment that wanted them in its language had to hold a whole-file language variant of the report
(`x_<ll>.ftl`) for these words alone; it no longer needs one. An English report prints byte for byte what it
printed before. This folder carries the changed templates and the texts. Seven paths changed since the release
`2026-11-23-description-default-language`; every other template is as it was.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview, then the import of the ticked paths | the base product's templates and scripts at this release; the seven that changed are listed below |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release. Five are new, all in the `pdf` dictionary; see below |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes |

## The new texts

| Key | English | Printed by |
|---|---|---|
| `pdf.edition` | `Version {0}` (`{0}` is the edition) | the six reports below that print an edition after the first |
| `pdf.list.generated` | `Generated` | the message list's page header and front page |
| `pdf.list.criteria` | `Criteria` | the message list's front page |
| `pdf.list.result` | `Result` | the message list's front page |
| `pdf.list.result_count` | `{0} messages` (`{0}` is the number of messages) | the message list's front page |

The colon after a front-page label stays in the template. A language with no value of its own prints the
deployment's default language's value, as every text does (the language's value, else the default language's, else
the first value the key has). A deployment whose dictionary lacks one of these keys altogether (one that imported
these templates before the backend that adds the texts, or deleted a key) prints the English word, as before, and
the report renders.

## The changed paths, and what changes on paper

| Path | What changes | Shadowed by |
|---|---|---|
| `templates/messages/message-list-pdf.ftl` | The page header's "Generated" and the front page's "Generated", "Criteria", "Result" and "N messages" are the texts `pdf.list.*` in the report's language. The standard, draft and public reports print with this template. | GL (language variants `_da` and `_kl`) |
| `templates/messages/fa-list-pdf.ftl` | The edition's label is the text `pdf.edition`. | GL (language variants `_da` and `_kl`) |
| `templates/messages/nm-almanac-report-pdf.ftl` | The same. | GL (language variants `_da` and `_kl`) |
| `templates/messages/nm-annex-report-pdf.ftl` | The same. | GL (language variants `_da` and `_kl`) |
| `templates/messages/nm-report-pdf.ftl` | The same. | none |
| `templates/messages/nm-tp-report-pdf.ftl` | The same. | none |
| `templates/messages/nm-accumulated-report-pdf.ftl` | The same. | GL (its own copy) |

## How a running deployment takes the changes

1. Upgrade to the base product version whose release notes name this folder. Its start-up adds the five texts to
   the `pdf` dictionary: in English, and in every language the deployment's own texts or its language pack give a
   value for. Start-up never changes a stored text and adds nothing a sysadmin deleted.
2. A deployment that prints in a language with no value for these texts yet prints the default language's words
   there. To print them in that language, write the values on the Dictionaries page (dictionary `pdf`), before or
   after the import.
3. Import `script-resources.json` on the Script resources page. In the preview, compare the deployment's stored
   copies of the seven paths above with this folder's. If the deployment never edited one, tick its overwrite. If
   it did, carry the change into its own copy instead, by editing the template on the Script resources page: an
   overwrite replaces the stored copy whole, and the deployment's own differences are lost. Tick no other
   overwrite. A deployment that has not imported `2026-11-23-description-default-language` or
   `2026-11-20-report-template-fixes` yet reads those folders' READMEs first; this folder repeats their changes.
4. A language variant of one of these reports (`x_<ll>.ftl` beside `x.ftl` on the Script resources page) is still
   what a print in that language reads; the import does not touch it. A deployment whose variant differs from the
   base product's report only by these words may delete the variant's row once the language's texts hold the words:
   prints in that language then read the base product's report and its later corrections. Whether to do so is the
   sysadmin's decision.

Prints already made keep what they printed until they are made again.

## The country deployments (DK, GL)

Never apply this import on a DK or GL deployment to a path that country's own bootstrap shadows: it would replace
the country's template, its own-language parts included, with the base product's. The "Shadowed by" column was read
from the main branches of the country repositories on 2026-11-23: a path is shadowed where the country's
`be/bootstrap/templates/` holds it or a language variant of it. DK holds none of the seven paths and no language
variant of them, and takes the import as any deployment does. GL holds `_da` and `_kl` language variants of four of
them and its own copy of `nm-accumulated-report-pdf.ftl`. A country that held a language variant of a report for
these words alone may remove it from its bootstrap once its language pack holds the words, so that a fresh
deployment takes the base product's file and its later corrections; that is the country's work, in its own
repository. GL's copy of `nm-accumulated-report-pdf.ftl` keeps printing the edition's label in English until GL
carries this change into it. A running GL deployment keeps its stored rows whether or not GL does so.

Whether to import at all is the sysadmin's decision. Nothing applies these files on its own. A fresh deployment
needs none of this: the bootstrap brings the changed templates and the texts.

The three generated files are written by `ReleaseFilesGenerator` (run by `ReleaseFilesCurrentTest`); this folder
never changes once it is merged into main.
