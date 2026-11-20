Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

# Release 2026-11-20: the report templates' fixes

The second round of the recorded faults of November 2026 corrected four things the base product's PDF reports
print: the weeks a weekly report is headed by, the date on the reports' first page, the time stamp of the message
list, and the image attachments of a message whose file name holds `%`, `;` or a space. This folder carries the
corrected templates. Eight paths changed since the release `2026-11-16-error-hunt-phase-2-templates`; every other
template is as it was.

| File | Import | What |
|---|---|---|
| `script-resources.json` | Script resources page, Import: a preview, then the import of the ticked paths | the base product's templates and scripts at this release; the eight that changed are listed below |
| `texts.json` | Dictionaries page, Import | the base product's English texts at this release; these fixes changed no text and add none. Since the previous folder it holds `pdf.list.index_incomplete`, which start-up adds to a deployment by itself, so there is nothing to import from it unless a deployment deleted a key |
| `missing-paths.sql` | none: run it read-only, `psql -d <database> -f missing-paths.sql` | the missing-path report as SQL, for the release notes |

## The changed paths, and what changes on paper

| Path | What the fix does | Shadowed by |
|---|---|---|
| `templates/messages/nm-report-pdf.ftl` | The Notices to Mariners report is headed, on its first page and in its page header, by the issue's weeks as its name writes them, by the week part of the series' name pattern in the report's language: "Week 36-38, 2026" for an issue of three weeks or more and "Week 36+37, 2026" for a pair under the pair form `${week}+${weekTo}` (it said "Week 36, 2026" for both), "Uge 36 og 37, 2026" under `Uge ${week} og ${weekTo}, ${year}`, "Week 36, 2026" under a pattern that names the first week alone, as the name does. A pattern that writes no week gives the pair form, and a week label an editor wrote is printed whole. Its first page is dated by the language's date text, `msg.time.date_format`: "20 November 2026" in English (it was "20. November 2026", the Danish form). | none |
| `templates/messages/nm-tp-report-pdf.ftl` | The same for the T&P report: its heading, its page header and the "NtM 36-38 2026" line of its first page; and its date. | none |
| `templates/messages/nm-accumulated-report-pdf.ftl` | A source issue's weeks are printed on its line as the source's own name writes them, by its own series' name pattern, or its week label whole: under the pair form a source of two weeks is "Week 36+37" (it was "Week 36 - 37", which reads as a range), one of three weeks or more "Week 36-38"; a source without a name is called by its weeks so written in the contents and its heading (it was called by its first week). The period and cut-off dates of each source are printed by `msg.time.date_format`. | GL |
| `templates/messages/nm-almanac-report-pdf.ftl` | The first page's date by `msg.time.date_format`. | GL in `da` and `kl` (see below) |
| `templates/messages/nm-annex-report-pdf.ftl` | The first page's date by `msg.time.date_format`. | GL in `da` and `kl` |
| `templates/messages/fa-list-pdf.ftl` | The first page's date by `msg.time.date_format`. | GL in `da` and `kl` |
| `templates/messages/message-list-pdf.ftl` | The time the list was made, on its first page and in its page header, by the language's date-and-time text, `msg.time.date_time_format`, in the zone the print is dated in: "20 November 2026, hours 11:28" in English, as the message PDFs print a time (it was "Nov 20, 2026, 11:28:28 AM"). The criteria line, made by the backend, now reads "Series IDs", "Area IDs" and "Category IDs" with a backend of this version, whichever template prints it. | GL in `da` and `kl` |
| `templates/messages/message-support.ftl` | A message's image attachment is fetched by its path encoded segment by segment, by the rule the admin app and the public site follow: a segment that reads as encoded (every `%` in it starts the escapes of a UTF-8 character) keeps its escapes, any other is a name as it was uploaded and is encoded whole. So an image whose file name holds `%`, `;` or a space is in the message PDF (it could be left out where the path was stored as the file was named, as an imported message's is). | GL |

The weeks need the backend of the version whose release notes name this folder: it hands the reports the weeks as
the issue's name writes them (`weeks`, and `group.weeks` for each source of the accumulated report). On an older backend
the new templates still render, but the weeks are short: the weekly reports print the first week alone, as the
old ones did, and the accumulated report names a source of two weeks or more by its first week alone, where the
old one printed "Week 36 - 37". So upgrade first, as step 1 below says. The dates read the texts
`msg.time.date_format` and `msg.time.date_time_format`, which every deployment holds (the message PDFs read them
already); a deployment that changed them changes its reports' dates with them. In Danish, with the Danish text
`d. MMMM yyyy`, a report's date loses its leading zero: "2. november 2026" where it printed "02. november 2026".

## How a running deployment takes the changes

1. Upgrade to the base product version whose release notes name this folder.
2. Import `script-resources.json` on the Script resources page. In the preview, compare the deployment's stored
   copy of each of the eight changed paths with this folder's. If the deployment never edited a path, tick its
   overwrite. If it did (its own header, its own layout), carry the change into its own copy instead, by editing
   the template on the Script resources page: an overwrite replaces the stored copy whole, and the deployment's
   own differences are lost. Tick no other overwrite. A deployment that has not imported
   `2026-11-16-error-hunt-phase-2-templates` yet reads that folder's README first; this folder repeats its
   changes.

Prints already made keep what they printed until they are made again. A weekly or accumulated edition already
released keeps its PDF; an amendment or a new release of it is printed with the new templates.

## The country deployments (DK, GL)

Never apply this import on a DK or GL deployment to a path that country's own bootstrap shadows: it would replace
the country's template, its own-language parts included, with the base product's English-only one. The "Shadowed
by" column was read from the main branches of `SIFA-DK/niord-dk` and `SIFA-DK/niord-gl` on 2026-11-20: a path is
shadowed where the country's `be/bootstrap/templates/` holds it. DK shadows none of the eight paths. GL holds its
own `message-support.ftl` and `nm-accumulated-report-pdf.ftl`, and `da` and `kl` variants of four more
(`fa-list-pdf_da.ftl` and `_kl`, `message-list-pdf_da.ftl` and `_kl`, `nm-almanac-report-pdf_da.ftl` and `_kl`,
`nm-annex-report-pdf_da.ftl` and `_kl`), which a print in that language reads in place of the base product's
file, while a print in English reads the base product's. GL's copies carry the same faults. Carrying the fixes
into them is the country's work, shipped in a release folder of the country's own repository:

- `message-support.ftl`: the `repoPathUrl` function of this folder's copy, and the attachment's
  `<img src="/rest/repo/file/${att.path}" ...>` written `${repoPathUrl(att.path)}`;
- `nm-accumulated-report-pdf.ftl`: `${group.week?c}` in the macro `groupTitle` written `${group.weeks!(group.week?c)}`;
  the source's line `${group.week?c}<#if group.weekTo?? ...> - ${group.weekTo?c}</#if>` written
  `${group.weeks!(group.week?c)}`; and `?string["dd. MMMM yyyy"]` written `?string(text("msg.time.date_format"))`;
- the `_da` and `_kl` variants of the almanac, the annex and the firing-area list: `${.now?string['dd. MMMM yyyy']}`
  written `${.now?string(text('msg.time.date_format'))}`, if the country wants its texts to decide the pattern
  (its Danish pattern already prints the Danish form);
- `message-list-pdf_da.ftl` and `_kl`: the two `${.now?datetime}` written by `msg.time.date_time_format`, as this
  folder's copy does.

The weekly report templates are shadowed by neither country's bootstrap, but a deployment's stored copies came
from its own database and may carry its own edits: the comparison above decides, not this column.

Whether to import at all is the sysadmin's decision. Nothing applies these files on its own. A fresh deployment
needs none of this: the bootstrap brings the corrected templates.

The three generated files are written by `ReleaseFilesGenerator` (run by `ReleaseFilesCurrentTest`); this folder
never changes once it is merged into main.
