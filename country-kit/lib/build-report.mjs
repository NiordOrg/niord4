/**
 * `niord-build-report.json`: what a country's build was made from, written once per set-up into
 * `.niord/build/` and staged unchanged into the administration bundle (`assets/`), where the
 * sysadmin page can read it. It holds no secret: every value is a version, a hash, a path of the
 * country tree or a language code.
 *
 *   format        1
 *   kit           the version of the kit that wrote it (the base product version)
 *   base          { version, sha256, kit? } — the zip's hash, null when a checkout was used;
 *                 `kit: { sha256 }` the kit package zip's hash, present only when the stub
 *                 verified one
 *   provenance    `pinned: …` or `local: <path> @ <commit>`, followed by
 *                 `; org.niord:niord-country-kit:<version>:zip sha256:<hash>` when the kit
 *                 package's hash is known, so it names both packages; `local` first also when
 *                 any pack came from NIORD_PACK_<LANG> (`packs` says which)
 *   country       the manifest's code and name
 *   packs         per language: its source (`base`, `home`, `pinned`, or `local: <path>`), for a
 *                 pinned pack its `reference` (`ghcr.io/<owner>/niord-lang-<lang>:<tag>@sha256:…`,
 *                 the pin it was pulled by), and the applications it serves (admin, info, content)
 *   escapeHatch   the entries of missing-keys-accepted.json
 *   overrides     the guarded overrides: per entry its file, the country's copy, basedOn, the
 *                 reason, the day it was added and the base product spec run against the copy
 *                 (null when the file has none). A protected file never appears: set-up refuses
 *                 an override of one before it writes the report
 *   dependencies  the country's own npm packages (fe/country/package.json): per package its name,
 *                 version, the application it is for (admin, info or both), the reason, and the
 *                 reason for its install script (null when it has none); [] when there are none
 *   textOverrides per language, how many keys the country's text overrides set in each
 *                 application: { <lang>: { admin, info } }; {} when there are none
 *   auditAccepted how many npm audit findings audit-accepted.json accepts; 0 when the file is
 *                 absent. Only the count: the administration bundle serves the report without
 *                 sign-in, and a list of the advisories a deployment runs unfixed, with the reasons,
 *                 would tell any visitor where to look. The entries stay in the country's
 *                 audit-accepted.json and in the lines set-up and the audit print on every run
 *
 * The last three came with P3b2, a pinned pack's `reference` with P5, and base's `kit` with the
 * kit package; each is additive: the format stays 1.
 */
import { KIT_PACKAGE } from './where.mjs';

export const REPORT_FORMAT = 1;
/** The kit package's Maven coordinates, without the version. */
export const KIT_ARTIFACT = `org.niord:${KIT_PACKAGE}`;

/** The report as an object; `auditAccepted` is given as the entries and recorded as their count. */
export function buildReport({
  version,
  sha256,
  kitSha256 = null,
  provenance,
  manifest,
  packSources,
  packReferences = {},
  accepted,
  overrides = [],
  dependencies = [],
  textOverrides = {},
  auditAccepted = [],
}) {
  // The same shape the lock and the previous-base reader accept, so a recorded kit pin survives.
  if (kitSha256 !== null && !/^[0-9a-f]{64}$/.test(kitSha256))
    throw new Error(`"${kitSha256}" is not a SHA-256 of 64 lower-case hex digits`);
  const admin = manifest.apps.admin;
  const info = manifest.apps.info;
  const langs = [
    ...new Set([...admin.contentLanguages, ...admin.uiLanguages, ...info.uiLanguages]),
  ].sort();
  const packs = {};
  for (const lang of langs) {
    const apps = [];
    if (admin.uiLanguages.includes(lang)) apps.push('admin');
    if (info.uiLanguages.includes(lang)) apps.push('info');
    if (admin.contentLanguages.includes(lang) && !admin.uiLanguages.includes(lang))
      apps.push('content');
    packs[lang] = {
      source: packSources[lang],
      ...(packReferences[lang] ? { reference: packReferences[lang] } : {}),
      apps,
    };
  }
  const named = kitSha256
    ? `${provenance}; ${KIT_ARTIFACT}:${version}:zip sha256:${kitSha256}`
    : provenance;
  const local =
    provenance.startsWith('local:') ||
    Object.values(packSources).some((source) => source.startsWith('local:'));
  return {
    format: REPORT_FORMAT,
    kit: version,
    base: { version, sha256: sha256 ?? null, ...(kitSha256 ? { kit: { sha256: kitSha256 } } : {}) },
    provenance:
      local && !provenance.startsWith('local:')
        ? `local: packs from NIORD_PACK_* over ${named}`
        : named,
    country: { code: manifest.country, name: manifest.name },
    packs,
    escapeHatch: accepted,
    overrides: overrides.map(({ file, with: copy, basedOn, reason, added, baseSpec }) => ({
      file,
      with: copy,
      basedOn,
      reason,
      added,
      baseSpec: baseSpec ?? null,
    })),
    dependencies: dependencies.map(({ name, version, app, reason, installScript }) => ({
      name,
      version,
      app,
      reason,
      installScript: installScript ?? null,
    })),
    textOverrides,
    auditAccepted: auditAccepted.length,
  };
}

/** The report's bytes, as the build ships them. */
export const reportText = (report) => `${JSON.stringify(report, null, 2)}\n`;

/** Whether a report records a workspace staged from a local checkout or pack. */
export const isLocal = (report) =>
  typeof report?.provenance !== 'string' || report.provenance.startsWith('local:');
