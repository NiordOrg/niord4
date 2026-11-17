#!/usr/bin/env node
/**
 * The last step of a country workspace's `npm run build`: the checks on every country build
 * (fe-country-split-plan §4.8, §4.11 rules 4 and 5) over dist/niord-app/browser and
 * dist/niord-info/browser, as lib/build-checks.mjs runs them:
 *
 *   1. each application ships exactly the UI bundles of niord-country.json's `uiLanguages`;
 *   2. its flags are exactly `<lang>.png` of the languages it names (the administration app's UI and
 *      content languages, the public site's UI languages);
 *   3. nothing from a pack's `status/`, an `ATTRIBUTION.txt`, a flag's drawing, `node_modules`,
 *      another tree's assets (`assets/default/` or a two-letter folder under `assets/`) or
 *      `.niord/`;
 *   4. no external font or style URL, and no absolute URL outside the base product's
 *      scripts/build-urls-allowed.json (build-urls-check.mjs --absolute) and the country's own
 *      fe/build-urls-allowed.json, the same format with each entry one literal https: URL and its
 *      reason; every country entry is named on every build, and one that admits nothing is a
 *      warning;
 *   5. every country-only utility set-up recorded (.niord/build/country-utilities.json, §4.7) in its
 *      application's built CSS;
 *   6. no secret in either output (lib/secrets.mjs with the base product's secret-scan.json);
 *   7. the public site's separation check, with this workspace's prefixes
 *      (scripts/info-separation-check.mjs --root .niord/base/ --country <cc>);
 *   8. the public site's bundle report against the country's info/bundle-baseline.json, the same
 *      command as `npm run bundle:info`; a country without a baseline yet gets a warning;
 *   9. one source per output file (R9): no output path that two asset inputs of the generated
 *      angular.json write, as the workspace's installed @angular/build resolves them
 *      (lib/asset-outputs.mjs) — a country file staged where a base product file lands too;
 *  10. the public site's built index.html in the default language of the public site's manifest
 *      entry, its html tag's lang, and titled with that language's built bundle's SITE_TITLE; the
 *      fix is the country's info/app/index.html (the index seam).
 *
 *   node ../.niord/kit/check-build.mjs      # run from the workspace, fe/
 *
 * Exit codes: 0 clean, 1 when a check found something (every finding is printed), 2 when the
 * build's output, the public site's stats or the generated angular.json are missing.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkBuild, missingOutputs, oneSourcePerOutput } from './lib/build-checks.mjs';
import { readManifest } from './lib/manifest.mjs';

/**
 * Runs the checks in `fe`; answers the exit code. `run` and `resolve` as lib/build-checks.mjs
 * takes them.
 */
export async function main({
  fe = process.cwd(),
  log = console.log,
  warn = console.error,
  run,
  resolve,
} = {}) {
  const missing = missingOutputs(fe);
  if (missing.length) {
    warn(`check-build: ${missing.join(', ')} missing: run \`npm run build\``);
    return 2;
  }
  if (!fs.existsSync(path.join(fe, 'angular.json'))) {
    warn('check-build: angular.json missing: run `node setup.mjs`');
    return 2;
  }
  let manifest;
  try {
    manifest = readManifest(path.join(fe, 'niord-country.json'));
  } catch (error) {
    warn(`check-build: ${error.message}`);
    return 2;
  }
  const { errors, warnings, lines } = checkBuild({ fe, manifest, ...(run ? { run } : {}) });
  try {
    const oneSource = await oneSourcePerOutput({ fe, ...(resolve ? { resolve } : {}) });
    errors.push(...oneSource.errors);
    lines.push(...oneSource.lines);
  } catch (error) {
    warn(`check-build: ${error.message}`);
    return 2;
  }
  for (const line of lines) log(`check-build: ${line}`);
  for (const line of warnings) warn(`check-build: warning: ${line}`);
  for (const line of errors) warn(`check-build: error: ${line}`);
  if (errors.length) {
    warn(`check-build: ${errors.length} finding(s) in the build`);
    return 1;
  }
  log('check-build: the build passes every check of a country build');
  return 0;
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  if (process.argv.length > 2) {
    console.error('check-build: takes no argument; run it from the workspace, fe/');
    process.exit(2);
  }
  process.exit(await main());
}
