/**
 * The country's backend settings file against what its frontend build can serve. The deployment
 * chooses its languages at runtime from the web settings, whose defaults are the `values` of
 * `be/src/main/resources/defaults/country/settings.json`; the build ships texts only for the
 * languages the manifest names. What the deployment does with a setting naming any other depends
 * on the setting: the interface language lists (`siteLanguages`, `infoSiteLanguages`) drop it; a
 * `modelLanguages` entry stays, and the editor gives it a field labelled with its code in upper
 * case unless the build names it as a UI language; the administration app keeps a
 * `defaultLanguage` while `modelLanguages` names it and otherwise takes the first model language;
 * the public site replaces an `infoDefaultLanguage` it does not serve by its first language. So:
 *
 *   - a language in `siteLanguages`, `modelLanguages`, `infoSiteLanguages`, `defaultLanguage` or
 *     `infoDefaultLanguage` that the manifest does not name for that use is a warning, which says
 *     what the deployment does with it;
 *   - a `modelLanguages` entry whose pack has no backend half is a warning: the deployment's
 *     default language serves its PDFs and mails until it has one;
 *   - a `defaultLanguage` that is a content language only, with no backend half, fails: the
 *     backend cannot start without its default language's texts.
 *
 * A country with no settings file has nothing to check.
 */
import fs from 'node:fs';
import path from 'node:path';

const ENGLISH = 'en';

/** Whether a pack folder has a backend half: at least one `backend/<bundle>.properties`. */
export function hasBackendHalf(packDir) {
  if (!packDir) return false;
  const dir = path.join(packDir, 'backend');
  try {
    return fs.readdirSync(dir).some((name) => name.endsWith('.properties'));
  } catch {
    return false;
  }
}

const asList = (value) =>
  Array.isArray(value)
    ? value.filter((item) => typeof item === 'string')
    : typeof value === 'string'
      ? [value]
      : [];

/**
 * The warnings and errors of a settings file's `values` against the manifest. `backendHalf(lang)`
 * says whether a language's pack has a backend half (English always has: niord-base carries it).
 */
export function checkSettingsValues({ values, manifest, backendHalf, label = 'settings.json' }) {
  const warnings = [];
  const errors = [];
  const admin = manifest.apps?.admin ?? { uiLanguages: [], contentLanguages: [] };
  const info = manifest.apps?.info ?? { uiLanguages: [] };
  const uiAnywhere = new Set([...(admin.uiLanguages ?? []), ...(info.uiLanguages ?? [])]);
  const named = new Set([...uiAnywhere, ...(admin.contentLanguages ?? [])]);
  const hasHalf = (lang) => lang === ENGLISH || backendHalf(lang);
  const drops = () => 'the deployment drops it';
  // The editor labels a model language by the build's language list, which holds every UI and
  // content language of the administration app; any other is shown as its code.
  const adminNamed = new Set([...(admin.uiLanguages ?? []), ...(admin.contentLanguages ?? [])]);
  // Each row: the key, the languages the build serves for it, what the manifest would name it,
  // and what the deployment does with a language outside them.
  const rows = [
    ['siteLanguages', admin.uiLanguages ?? [], 'a UI language of the administration app', drops],
    [
      'modelLanguages',
      admin.contentLanguages ?? [],
      'a content language of the administration app',
      (lang) =>
        adminNamed.has(lang)
          ? "the deployment keeps it: the editor gives it a field under its pack's label"
          : 'the deployment keeps it: the editor gives it a field labelled with its code in upper case',
    ],
    ['infoSiteLanguages', info.uiLanguages ?? [], 'a UI language of the public site', drops],
    [
      'defaultLanguage',
      [...named],
      'a language of the build',
      () =>
        'the administration app keeps it while modelLanguages names it, and otherwise takes the first model language',
    ],
    [
      'infoDefaultLanguage',
      info.uiLanguages ?? [],
      'a UI language of the public site',
      () => 'the public site takes its first language instead',
    ],
  ];
  for (const [key, allowed, what, outcome] of rows)
    for (const lang of asList(values?.[key]))
      if (!allowed.includes(lang))
        warnings.push(
          `${label}: ${key} names ${lang}, which niord-country.json does not name as ${what}; ${outcome(lang)}`,
        );
  for (const lang of asList(values?.modelLanguages))
    if (named.has(lang) && !hasHalf(lang))
      warnings.push(
        `${label}: modelLanguages names ${lang}, whose pack has no backend half yet; the default language serves its PDFs and mails`,
      );
  for (const lang of asList(values?.defaultLanguage))
    if (named.has(lang) && !uiAnywhere.has(lang) && !hasHalf(lang))
      errors.push(
        `${label}: defaultLanguage is ${lang}, a content language whose pack has no backend half; the backend cannot start without its default language's texts`,
      );
  return { warnings, errors };
}

/** The checks for the settings file `file`, if it exists. */
export function checkSettings({ file, manifest, backendHalf, label }) {
  if (!fs.existsSync(file)) return { warnings: [], errors: [] };
  let settings;
  try {
    settings = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    return { warnings: [], errors: [`${label}: ${error.message}`] };
  }
  if (!settings || typeof settings !== 'object' || Array.isArray(settings))
    return { warnings: [], errors: [`${label} is not an object with "values"`] };
  return checkSettingsValues({ values: settings.values ?? {}, manifest, backendHalf, label });
}
