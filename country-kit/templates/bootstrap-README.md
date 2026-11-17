# be/bootstrap/

Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import
(releases), or only into the demo database (sample). A running deployment's data may differ from
these files and is the truth; nothing here ever changes it.

This folder holds the country's bootstrap: its domains, categories, message series, mailing lists
with their scripts, reports, promulgation types and data dictionaries, applied after the base
product's bootstrap, in the same run, to a fresh database only. Never in `src/main/resources`, a
jar or an image.

`releases/` holds one folder per release of this country, `releases/<yyyy-mm-dd>-<topic>/` (the
base product's first is `2026-10-21-templates-from-database/`): the import files of a template,
script or report the release adds or changes, which a sysadmin decides to import into each
deployment, and that folder's own `README.md`. Nothing here arrives by itself. `releases/` itself
holds folders only: the bootstrap archive accepts `releases/<folder>/*.json`, `*.md` and `*.sql`
and nothing else under it.
