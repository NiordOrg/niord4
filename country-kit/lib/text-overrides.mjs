/**
 * A country's text overrides: its own wording of ordinary base product keys, the third of the
 * translation layers (English, the language's pack, the text overrides, then branding at runtime).
 * A term two countries that share a language say differently is overridden here, without changing
 * the shared pack and without asking the base product for a brandable key.
 *
 * One file per language, `fe/text-overrides/<lang>.json`:
 *
 *   {
 *     "admin": { "<catalog key>": { "text": "<the country's text>", "fp": "<16 hex digits>" } },
 *     "info": { … }
 *   }
 *
 * `fp` is the fingerprint of the English text the override was written against, as the catalog
 * records it (stage-languages.mjs fingerprint). An override is checked like a translation:
 *
 *   language       the section's application has the file's language as a UI language
 *   catalog        the key is in the application's catalog (a key that left it fails, named with
 *                  its new name when the base product renamed it)
 *   branding       a key the branding schema names, or a `country.` key, is branding's, never an
 *                  override's
 *   shape          an entry has exactly a text and a fingerprint of 16 hex digits
 *   empty          a text is empty only where English's is
 *   placeholders   the placeholders are English's
 *   markup         the markup, a date pattern's tokens and a word list's entries hold as for a
 *                  pack's text (pack.mjs checkText)
 *   fingerprint    an override written against another English text than today's is a warning,
 *                  one per language, listing the overrides to look at with the fingerprint to
 *                  record once they are
 *   redundant      an override equal to the pack's text (English's, for `en`) is a warning
 *   difference     an override of a text the pack has is listed as a deliberate difference
 *
 * A file is read only as a regular file inside the folder, named `<lang>.json`; a link, another
 * file, a field other than `admin` and `info`, or a key named `__proto__`, `prototype` or
 * `constructor` fails. An absent folder means no overrides.
 */
import fs from 'node:fs';
import path from 'node:path';
import { checkText } from '../pack.mjs';
import { APPS, flatten, isBlank, packFile, placeholders } from '../stage-languages.mjs';

const FOLDER = 'text-overrides';
const ENGLISH = 'en';
const FILE_NAME = /^([a-z]{2,3})\.json$/;
const FINGERPRINT = /^[0-9a-f]{16}$/;
const ENTRY_FIELDS = ['fp', 'text'];
const COUNTRY_NAMESPACE = 'country.';
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const same = (a, b) => a.length === b.length && a.every((item, i) => item === b[i]);
const shown = (items) => (items.length ? items.join(' ') : 'none');

/**
 * The text-override files under `<fe>/text-overrides/`: `{ files: { <lang>: { admin?, info? } },
 * errors }`, each section the file's keys and their entries as written. A file with a problem is
 * left out, its problem in `errors`.
 */
export function readTextOverrides(fe) {
  const dir = path.join(fe, FOLDER);
  const files = {};
  const errors = [];
  let stat;
  try {
    stat = fs.lstatSync(dir);
  } catch (error) {
    if (error.code === 'ENOENT') return { files, errors };
    throw error;
  }
  if (stat.isSymbolicLink()) return { files, errors: [`${FOLDER}/ is a link`] };
  if (!stat.isDirectory()) return { files, errors: [`${FOLDER} is not a folder`] };
  const entries = fs
    .readdirSync(dir, { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name, 'en'));
  for (const entry of entries) {
    const label = `${FOLDER}/${entry.name}`;
    if (entry.isSymbolicLink()) {
      errors.push(`${label} is a link`);
      continue;
    }
    if (entry.isDirectory()) {
      errors.push(`${label} is a folder; ${FOLDER}/ holds one <lang>.json per language`);
      continue;
    }
    const match = FILE_NAME.exec(entry.name);
    if (!match) {
      errors.push(`${label} is not named <lang>.json, the language two or three a-z`);
      continue;
    }
    let content;
    try {
      const file = packFile(dir, entry.name);
      if (!file) continue;
      content = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
      errors.push(`${label}: ${error.message}`);
      continue;
    }
    if (!isObject(content)) {
      errors.push(`${label} is not an object`);
      continue;
    }
    const problems = [];
    const sections = {};
    for (const [field, section] of Object.entries(content)) {
      if (!APPS.includes(field)) {
        problems.push(`${label} has "${field}"; its sections are ${APPS.join(' and ')}`);
        continue;
      }
      if (!isObject(section)) {
        problems.push(`${label}: ${field} is not an object of key → { text, fp }`);
        continue;
      }
      const forbidden = Object.keys(section).filter((key) =>
        key.split('.').some((segment) => FORBIDDEN_KEYS.has(segment)),
      );
      for (const key of forbidden) problems.push(`${label}: ${field} has the key "${key}"`);
      if (!forbidden.length) sections[field] = section;
    }
    if (problems.length) errors.push(...problems);
    else files[match[1]] = sections;
  }
  return { files, errors };
}

/**
 * The flat bundles of the packs given, `{ <lang>: <pack folder> }`, as the check compares an
 * override with them: `{ <lang>: { admin?: { <key>: <text> }, info?: … } }`. English is read as
 * the other packs are. A bundle that cannot be read is left out: the pack check reports it.
 */
export function readPackBundles(packs) {
  const out = {};
  for (const [lang, dir] of Object.entries(packs)) {
    out[lang] = {};
    for (const app of APPS) {
      try {
        const file = packFile(dir, `i18n/${app}/${lang}.json`);
        if (file) out[lang][app] = flatten(JSON.parse(fs.readFileSync(file, 'utf8')));
      } catch {
        // The pack check names it.
      }
    }
  }
  return out;
}

/** A pack's text that staging would ship: present, not empty where English is not, English's placeholders. */
function usable(text, entry) {
  return (
    typeof text === 'string' &&
    (!isBlank(text) || entry.empty) &&
    same(placeholders(text), entry.placeholders)
  );
}

/**
 * Checks the text overrides (readTextOverrides's `files`) against the catalog, the country's
 * manifest, the packs' bundles (readPackBundles, English included) and the branding schema.
 * Returns the errors and warnings, one line each and naming their file; the printed lines, each
 * starting `text overrides:`; `covered`, per language and application the keys whose override
 * passed, for the pack check; and `counts`, per language the overrides of each application, for
 * the build report. Beside them, the findings the upgrade report lists, structured, each
 * `{ lang, app, key }`: `leftCatalog` (with `renamedTo` when the base product renamed the key),
 * `markup` (an override whose markup is not English's), `stale` (written against another English
 * text, with the English `fp` to record), `redundant` and `differing` (from the pack's text).
 * Each finding of a failed override, `leftCatalog` and `markup`, carries `error`, its line in
 * `errors`.
 */
export function checkTextOverrides({ catalog, overrides, manifest, packBundles, brandingSchema }) {
  const errors = [];
  const warnings = [];
  const lines = [];
  const covered = {};
  const counts = {};
  const found = { leftCatalog: [], markup: [], stale: [], redundant: [], differing: [] };
  for (const lang of Object.keys(overrides).sort()) {
    const label = `${FOLDER}/${lang}.json`;
    const stale = [];
    const differences = [];
    const tally = { admin: 0, info: 0 };
    for (const app of APPS) {
      const section = overrides[lang][app];
      if (!section || !Object.keys(section).length) continue;
      const uiLanguages = manifest.apps?.[app]?.uiLanguages ?? [];
      if (!uiLanguages.includes(lang)) {
        errors.push(
          `${label} overrides ${app} texts, but ${lang} is not a UI language of ${app} (${uiLanguages.join(', ') || 'none'})`,
        );
        continue;
      }
      const entries = catalog.apps?.[app] ?? {};
      const brandingKeys = brandingSchema.apps?.[app]?.keys ?? {};
      const packText = packBundles[lang]?.[app] ?? {};
      for (const [key, override] of Object.entries(section)) {
        const where = `${label}: ${app} ${key}`;
        if (Object.hasOwn(brandingKeys, key) || key.startsWith(COUNTRY_NAMESPACE)) {
          errors.push(
            `${where} is branding's (${key.startsWith(COUNTRY_NAMESPACE) ? 'the country.* keys' : 'branding.schema.json'}): set it in ${app}/assets/branding/${lang}.json, not as a text override`,
          );
          continue;
        }
        const entry = Object.hasOwn(entries, key) ? entries[key] : undefined;
        if (!entry) {
          const renamedTo = Object.entries(catalog.renamedFrom?.[app] ?? {}).find(
            ([, oldKey]) => oldKey === key,
          )?.[0];
          const error = `${where} left the catalog${renamedTo ? `: the base product renamed it to ${renamedTo}` : ''}; ${renamedTo ? 'move' : 'remove'} the override`;
          errors.push(error);
          found.leftCatalog.push({ lang, app, key, ...(renamedTo ? { renamedTo } : {}), error });
          continue;
        }
        const before = errors.length;
        if (!isObject(override)) {
          errors.push(`${where} is not an object with a text and its English fingerprint, fp`);
          continue;
        }
        const fields = Object.keys(override).sort();
        if (fields.join() !== ENTRY_FIELDS.join())
          errors.push(`${where} has ${fields.join(', ') || 'no fields'}; it has exactly fp, text`);
        if (typeof override.fp !== 'string' || !FINGERPRINT.test(override.fp))
          errors.push(
            `${where} has no fingerprint of 16 hex digits, fp; the English text's is ${entry.fp}`,
          );
        const { text } = override;
        if (typeof text !== 'string') {
          errors.push(`${where} has no text`);
          continue;
        }
        if (isBlank(text) && !entry.empty) {
          errors.push(`${where} is empty, and English's text is not`);
          continue;
        }
        const own = placeholders(text);
        if (!same(own, entry.placeholders)) {
          errors.push(
            `${where} has the placeholders ${shown(own)}, English ${shown(entry.placeholders)}`,
          );
          continue;
        }
        const markupError = checkText(`${label} ${app}`, key, text, entry, catalog, errors);
        if (markupError) found.markup.push({ lang, app, key, error: markupError });
        if (errors.length > before) continue;

        ((covered[lang] ??= {})[app] ??= []).push(key);
        tally[app]++;
        if (override.fp !== entry.fp) {
          stale.push(`${app} ${key} (fp ${entry.fp})`);
          found.stale.push({ lang, app, key, fp: entry.fp });
        }
        const reference = packText[key];
        if (text === reference) {
          warnings.push(
            `${where} is redundant: the ${lang === ENGLISH ? 'English text' : `${lang} pack`} has the same text; remove the override`,
          );
          found.redundant.push({ lang, app, key });
        } else if (usable(reference, entry)) {
          differences.push(`${app} ${key}`);
          found.differing.push({ lang, app, key });
        }
      }
    }
    if (stale.length)
      warnings.push(
        `${label}: ${stale.length} override(s) were written against another English text; look at each, then record the English fingerprint shown: ${stale.join(', ')}`,
      );
    for (const difference of differences)
      lines.push(
        `text overrides: ${lang} ${difference} differs from the ${lang === ENGLISH ? 'English text' : `${lang} pack's text`}, a deliberate difference`,
      );
    if (tally.admin + tally.info) counts[lang] = tally;
  }
  const languages = Object.keys(counts);
  if (!languages.length) lines.push('text overrides: none');
  for (const lang of languages) {
    const { admin, info } = counts[lang];
    lines.push(`text overrides: ${lang} ${admin + info} (admin ${admin}, info ${info})`);
  }
  return { errors, warnings, lines, covered, counts, ...found };
}

/** The overridden texts as staging takes them: `{ admin: { <lang>: { <key>: <text> } }, info: … }`. */
export function textOverridesFor(overrides) {
  const out = Object.fromEntries(APPS.map((app) => [app, {}]));
  for (const [lang, sections] of Object.entries(overrides))
    for (const app of APPS) {
      const section = sections[app];
      if (!section || !Object.keys(section).length) continue;
      out[app][lang] = Object.fromEntries(
        Object.entries(section).map(([key, override]) => [key, override.text]),
      );
    }
  return out;
}
