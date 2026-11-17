# Base product defaults

**Defaults only: a running deployment may differ, and keeps its values.**

This folder holds the base product's defaults, the inert keys that Niord adds to a deployment's database when
the deployment lacks them (`docs/code-data-config-plan.md` §3.3, §4 and §5).

| Path | What |
|---|---|
| `texts/en/<bundle>.properties` | the base product's English backend texts, one file per dictionary: `web`, `message`, `pdf`, `mail`, `template` |
| `settings.json` | the base product's values for its settings, `{ "values": { … } }`: the file start-up reads. A country's `defaults/country/settings.json` replaces it whole; until niord-base's test tree moves its `niord.json` to `defaults/` (the plan's §5.2), a root-level `niord.json` on the class path is read as the country's file. `country` and `organisation` are required: this file gives the generic deployable neutral values (`XX`, `Niord`), and every country's file sets its own; start-up warns when the country's file lacks one |
| `settings-catalog.json` | every setting key the code declares, with its type, code default, the value of `settings.json`, scope (`operator` or `state`), `web`, `editable`, `required`, `cached` and `declaredIn` (the classes that declare it). Generated from the code, never edited by hand; the unit suite fails when it is stale (`SettingsCatalogCurrentTest`) |

After adding or changing a setting declaration (an `@Setting` field or a static `Setting` constant), regenerate the
catalog from `be/`:

```
./mvnw -pl niord test -Dtest=SettingsCatalogCurrentTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dniord.settingsCatalog.write=true -Dniord.settingsCatalog.introducedIn=<the newest release plus one>
```

`introducedIn` is needed only when the change adds a key; a key already in the catalog keeps its version. A change
that adds or removes a key also changes the key count `SettingsCatalogCurrentTest.assertTheCatalogsContent`
expects: the command writes the catalog first, then fails on the old count until it is updated.

A changed or added text in `texts/en/` also changes the base product texts' dictionary import file,
`be/niord/bootstrap/texts.json`, which a sysadmin imports to restore a key (`docs/code-data-config-plan.md` §4.6).
It is generated, and `ReleaseFilesCurrentTest` fails while it is stale; regenerate it from `be/`:

```
./mvnw -pl niord test -Dtest=ReleaseFilesCurrentTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dniord.releaseFiles.write=true
```

## How a deployment gets them

- At start, Niord adds each text key or translation the deployment's database lacks. It never changes a value
  the database already has, whoever wrote it.
- A changed value in this folder therefore reaches only deployments that never had the key. A deployment that
  has the key keeps its own value.
- A key an operator deleted is not added again, in any language: the `DefaultsLedger` table records what start-up
  added and what was deleted. A key cannot be deleted while a list parameter type holds it among its values (409,
  and the delete confirmation names the parameter types).
- A sysadmin restores a deleted key with the dictionary import, by ticking it in the preview. The restored key keeps
  its deleted mark (the ledger only ever adds rows): it gets no default in a language added later, and that language
  is completed by import.
- The import's preview shows what a ticked overwrite does: it writes each language the file carries whole, value and
  long value, so a long value the file lacks is cleared, and the preview shows it. A key changed between the preview
  and the import is kept. An entry with no translation is refused; an empty value is accepted, as the English
  `navwarn.date.time_interval_prefix` in `texts/en/template.properties` is one.
- Settings likewise: at start, Niord adds each operator setting the database lacks, with its value from the settings
  file (else from the catalog's base value, else the code default) and its type and flags from the catalog. It adds a
  key only on a node where the code that declares it runs, so a node with SECOM switched off adds no SECOM setting.
  A read never adds one: a setting the database lacks resolves in memory. A setting whose row was removed stays
  removed (the ledger has it). A `state` setting is written only by the system, never from a file.
- The server's own `${niord.home}/niord.json` gives values over the settings file, never types or flags; a key no
  catalog declares is skipped with a warning.

## Which text wins

For each dictionary, key and language, the first of these that has the key gives the default:

1. the country's texts, `defaults/country/texts/<lang>/<bundle>.properties` in the country's jar;
2. for `en` this folder; for any other language the language pack, `defaults/packs/<lang>/<bundle>.properties`.

The files are merged key by key, so a country's file never hides a key of this folder. No other file is read: a
root-level `<bundle>_<lang>.properties` is not (the transitional layer went in NG-B; the plan's §4.1), and the text
checks fail a build that has one.

## No other jar writes here

No other jar may supply a file at these paths. Start-up fails when a known file under `defaults/` is found in
more than one place (the texts, `settings.json` and `settings-catalog.json`, here and under `defaults/country/`),
when two jars supply `defaults/country/`, or when a `defaults/packs/en/` exists.

## Text checks

A text is checked before it can reach a deployment, where it is never changed again (`docs/code-data-config-plan.md`
§4.7). The base product's unit suite fails on a broken rule and names the file, the key and the rule
(`BaseProductTextsCheckTest`, with the rules in `DefaultTextsChecks`):

- the file is valid UTF-8;
- a value has at most 1,000 characters, and a key at most 255;
- no control characters other than tab and newline;
- placeholders are well-formed: `{{ name }}`, and `{0}` that `MessageFormat` reads (an apostrophe in a value with `{0}` placeholders is written `''`);
- a country's or a pack's text has the same placeholders and the same markup as the English value: the same tags
  and attributes, the same `href` and `src`, and no markup where English has none;
- every key a template names in `text('…')` is in this folder, every message status, type and part type has its
  `msg.status.`, `msg.type.` or `msg.field.` key, and the only language name here is `lang.en`;
- no root-level `<bundle>_<lang>.properties` of the five dictionaries in English, in a language with a folder under
  `defaults/country/texts/` or `defaults/packs/`, or in a model language of the settings file (the country's when it
  sets `modelLanguages`, else this folder's): start-up no longer reads one.

## Settings checks

A settings file is checked before it can reach a deployment (`docs/code-data-config-plan.md` §5.5). The base
product's unit suite runs the checks over `settings.json` (`BaseProductSettingsCheckTest`, with the rules in
`DefaultSettingsChecks`); a country's build runs them over its `defaults/country/settings.json` against both
catalogs. Each finding names the file, the key and the rule:

- format: the file is an object with a `values` object, and only a country's file has a `useBaseDefault` list;
  each file is on the class path once;
- undeclared key: every key is in a catalog;
- state key: no file sets a `state` key, which only the system writes;
- type: a value has its type's JSON form (a string for `String`, `Password` and `Path`; an integral number in `int`
  range for `Integer`; an integral number for `Long` and `Date`; a number for `Float` and `Double`; a boolean for
  `Boolean`; anything for `json`; `null` for any);
- secret: a `Password` setting is empty, and no value holds a private key, a token or a URL with a password; the
  real value is set on the Settings page or in the server's `${niord.home}/niord.json`;
- completeness: a country's file sets every key of this folder's `settings.json`, or lists it in `useBaseDefault`
  with a reason;
- use base default: an entry has a reason, is listed once, and is not a `required` key; the check writes the
  list to its test report (`target/surefire-reports/org.niord.core.defaults.BaseProductSettingsCheckTest-output.txt`,
  in CI the unit-test report artefact);
- required: a file sets every `required` key (today `country` and `organisation`, which have no code default);
- https: an `http://` URL to a host other than this machine gives a warning, not a failure.
