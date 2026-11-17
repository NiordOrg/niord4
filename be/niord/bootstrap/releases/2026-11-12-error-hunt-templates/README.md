Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-11-12: the error hunt's template fixes

The error hunt of November 2026 found faults in what the base product's templates print: texts for mariners
with a depth nobody entered, a dangling "in pos..", a vessel named "???", wrong English, NAVTEX numbers with a
thousands comma and titles broken over two lines; station mails that failed; and a report that failed with a
server error. This folder carries the corrected templates. Nineteen paths changed since the release
`2026-11-02-accumulated-period`; every other template is as it was.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview, then the import of the ticked paths | the base product's templates and scripts at this release; the nineteen that changed are listed below |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release; these fixes changed no text, so there is nothing to import from it unless a deployment deleted a key |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes |

## The changed paths

| Path | What the fix does | Shadowed by |
|---|---|---|
| `templates/tmpl/common.ftl` | A part without a position prints no "in pos." (or "in position") phrase, so a text no longer reads "observed in pos. in the entrance" or "is observed in pos..". `renderAtonType` takes `capFirst` (default true, so every existing caller prints as before). | GL |
| `templates/tmpl/radio-navigation.ftl` | The AIS category prints AIS whether or not the optional AIS status was filled in (it printed RACON without one); the category is known by its template parameter of type or id `ais`, and a category without one is still decided by the status. The AtoN type is lower case mid-sentence: "AIS on the light". The subject is one line: "AIS unreliable.", several positions joined by a space, so the title no longer carries a line break. Needs the `common.ftl` of this release. | DK, GL |
| `templates/tmpl/reduced-depth.ftl` | Without a water depth it prints "Reduced water depths have been observed" (NAVTEX "REDUCED WATER DEPTHS OBSERVED") instead of a depth of 0 m. A depth prints without a thousands comma, and one sent as text prints as typed. The NAVTEX text ends "MARINERS ADVISED TO KEEP CLEAR." with its full stop. | DK, GL |
| `templates/tmpl/work-vessel.ftl` | A vessel with a call sign and no name is named by its call sign, not "???". The minimum distance prints without a thousands comma. | DK, GL |
| `templates/tmpl/obstruction-found.ftl` | An object reported in several positions takes a plural verb: "Mines have been reported", not "Mines has been reported" (the mine and torpedo templates use it). | DK, GL |
| `templates/tmpl/mine.ftl` | "The mines", not "The Mines", mid-sentence. | DK, GL |
| `templates/tmpl/torpedo.ftl` | "Torpedoes" and NAVTEX "TORPEDOES", not "Torpedos". | DK, GL |
| `templates/tmpl/object-adrift.ftl` | Plural objects (logs, fishing stakes) take "have been observed". The subject is one line: "Object adrift", "Logs adrift". | DK, GL |
| `templates/tmpl/obstruction-work.ftl` | The text starts with a capital when the warning has no event dates. | DK, GL |
| `templates/tmpl/salvage-operations.ftl` | "Salvage operations are carried out"; a capital start without event dates. | DK, GL |
| `templates/tmpl/exercise.ftl` | A capital start without event dates; an exercise without a type is "an exercise". | DK, GL |
| `templates/tmpl/uncharted-obstruction.ftl` | Without a type: "An uncharted obstruction ... The obstruction is unmarked". The depth prints without a thousands comma. | DK, GL |
| `templates/tmpl/markings.ftl` | "of the position" only after a distance over 0 or a bearing ("buoyed with a green buoy.", not "... of the position"). The distance prints without a thousands comma. | DK, GL |
| `templates/tmpl/wreck.ftl` | The depth above the wreck prints without a thousands comma. | DK, GL |
| `templates/aton/light-character.ftl` | "every 6 seconds" (not "every 6. seconds"), "every second", "1 meter", "1 nautical mile", and a decimal period or range as written ("every 2.5 seconds"). | none |
| `templates/mailinglist/publish-navtex.ftl` | The NAVTEX station mail prints the preamble, and the text, only when there is one; a message without a preamble no longer fails the mail. | none |
| `templates/mailinglist/publish-safetynet.ftl` | The SafetyNET station mail renders without a SafetyNET area (an empty area type) instead of failing. | none |
| `templates/mailinglist/publish-navwarn-common.ftl` | A cancellation warning's mail prints its title, which names the warning it cancels. | none |
| `templates/messages/nm-almanac-report-pdf.ftl` | The table of contents lists a notice without a short id (a draft, a verified notice) by its title instead of failing with a server error. | none |

## How a running deployment takes the changes

1. Upgrade to the base product version whose release notes name this folder. Its Java fixes need nothing more:
   a decimal light period is read with its point, an empty English text is kept, a NAVTEX text is held to the
   NAVTEX alphabet, and the NAVTEX station mail no longer fails without a preamble even with the old template.
2. Import `script-resources.json` on the Script resources page. In the preview, tick the overwrite of the changed
   paths above only, after comparing each with the deployment's own copy: a deployment that edited a template
   keeps its edits unless it takes the new one, and can carry the change into its own copy instead. Tick no
   other overwrite. Take `templates/tmpl/common.ftl` whenever you take `radio-navigation.ftl`: the new
   `radio-navigation.ftl` passes `capFirst` to `renderAtonType`, which an older `common.ftl` does not take, and
   the AIS and RACON templates would fail. Take `obstruction-found.ftl` with `mine.ftl` and `torpedo.ftl`,
   which include it, and `markings.ftl` with `wreck.ftl` and `uncharted-obstruction.ftl`, which include it.
3. If the deployment has the base product's category "light destroyed", rename it to "Light destroyed" on the
   Categories page, so a title made from it ends "Light destroyed.". A category is data too; no import is shipped
   for one name.

Messages already saved keep their texts and titles until an editor runs the template again.

## The country deployments (DK, GL)

Never apply this import on a DK or GL deployment to a path that country's own bootstrap shadows (the column
"Shadowed by" above, read from the country repositories in October 2026; check the current ones): it would
replace the country's template, its own-language parts included, with the base product's English-only one. The
base import does reach both for `templates/aton/light-character.ftl`, the three mailing-list templates and
`templates/messages/nm-almanac-report-pdf.ftl`, and DK also for `templates/tmpl/common.ftl`. For the light
character and the almanac report that corrects the English rendering only: a text in the country's own
language is made by that language's variant (`light-character_da.ftl`, `light-character_kl.ftl`,
`nm-almanac-report-pdf_da.ftl`, `nm-almanac-report-pdf_kl.ftl`), which carries the same faults and which this
import does not touch. Their own copies of the other templates carry the same faults too; carrying the fixes
into all of them is the country's work, shipped in a release folder of the country's own repository.

Whether to import at all is the sysadmin's decision. Nothing applies these files on its own. A fresh
deployment needs none of this: the bootstrap brings the corrected templates and the category's new name.

The three generated files are written by `ReleaseFilesGenerator` (run by `ReleaseFilesCurrentTest`); this
folder never changes once it is merged into main.
