Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-11-23: a message's text in the default language

A message print or mail in a language the message has no text in now takes the text in the deployment's default
language, and only where that is missing too the first text the message has, as before; so does the text a category
template writes into a message. It also corrects one English text, the heading of the administration app's "Remove
from..." tag menu. This folder carries the corrected templates and texts. Two paths changed since the release
`2026-11-20-report-template-fixes`; every other template is as it was.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview, then the import of the ticked paths | the base product's templates and scripts at this release; the two that changed are listed below |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release. One changed: `menu.action.tag.remove_named` (web) is `Remove from...` (it was `Remove from "{{tag}}"`); see below |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes |

## The changed paths, and what changes on paper

| Path | What the fix does | Shadowed by |
|---|---|---|
| `templates/messages/message-support.ftl` | Its function `descForLang`, which every message print, report and mail that includes the file reads a message's, a part's and a reference's text through, picks the text in the language asked for, else the text in the deployment's default language (the setting `defaultLanguage`), else the first text the message has. It went from the first step straight to the last, so a message with no text in the language of a print was printed in whichever language its first text had. A deployment whose messages have a text in every language it prints prints as before. | GL |
| `templates/tmpl/common.ftl` | Its function `descForLang`, which every category template includes and reads an area's, a message's, a part's, a template's and a list value's text through, takes the same three steps: the language asked for, else the deployment's default language, else the first text. It went from the first step straight to the last, so a category template run for a language the area or list value has no text in wrote whichever language its first text had into the message. A deployment whose areas, categories and lists have a text in every language it uses writes as before. | GL |

## How a running deployment takes the changes

1. Upgrade to the base product version whose release notes name this folder. The templates need nothing new of
   the backend, which already hands every template `defaultLanguage`, a category template's included.
2. Import `script-resources.json` on the Script resources page. In the preview, compare the deployment's stored
   copies of `templates/messages/message-support.ftl` and `templates/tmpl/common.ftl` with this folder's. If the
   deployment never edited one, tick its overwrite. If it did, carry the change into its own copy instead, by editing the template on the Script
   resources page: an overwrite replaces the stored copy whole, and the deployment's own differences are lost. Tick
   no other overwrite. A deployment that has not imported `2026-11-20-report-template-fixes` yet reads that
   folder's README first; this folder repeats its changes.
3. The heading of the "Remove from..." tag menu: the administration app shows its own text there, "Remove from...",
   and takes a Dictionary value over it only when the value has no placeholder, since it passes the heading none.
   The old default named one tag with `{{tag}}`, so the app never showed it. Nothing on screen changes with the new
   default, and start-up never changes a stored text, so a deployment keeps its `menu.action.tag.remove_named`. To
   have the Dictionary read the corrected value, edit the English value on the Dictionaries page (dictionary `web`)
   to `Remove from...`, or import `texts.json` on the Dictionaries page and tick that key under *Would overwrite*
   (no overwrite is ticked by itself). A deployment that wrote its own wording keeps it; if that wording holds a
   `{{tag}}`, the menu shows the app's text until the `{{tag}}` is taken out.

Prints already made keep what they printed until they are made again, and a message keeps the text a category
template wrote into it until the template is run on it again.

## The country deployments (DK, GL)

Never apply this import on a DK or GL deployment to a path that country's own bootstrap shadows: it would replace
the country's template, its own-language parts included, with the base product's. The "Shadowed by" column was read
from the main branches of the country repositories on 2026-11-23: a path is shadowed where the country's
`be/bootstrap/templates/` holds it. DK shadows no path of this release: it holds neither `message-support.ftl` nor
`tmpl/common.ftl`. GL holds its own copy of both, and each copy's one difference from the base product's file was this
fallback: its copies and this folder's now pick the same text. GL may therefore remove both copies from its bootstrap,
so that a fresh GL deployment takes the base product's files and their later corrections; that is the country's
work, in its own repository. A running GL deployment keeps its stored copies whether or not GL does so, and prints
and writes the same either way.

Whether to import at all is the sysadmin's decision. Nothing applies these files on its own. A fresh deployment
needs none of this: the bootstrap brings the corrected template and texts.

The three generated files are written by `ReleaseFilesGenerator` (run by `ReleaseFilesCurrentTest`); this folder
never changes once it is merged into main.
