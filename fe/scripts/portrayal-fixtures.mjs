#!/usr/bin/env node
/**
 * Writes the expected draw lists of the portrayal's golden fixtures.
 *
 *   npm run portrayal:fixtures          # run from fe/
 *
 * `shared-map/portrayal/fixtures/cases.json` holds map views with message features; its sibling
 * `expected.json` holds what the web maps draw of each: every fill, stroke and symbol, in the
 * order OpenLayers draws them, in image pixels. The web maps are the reference, so the file is
 * made by the kit spec that checks it (`shared-map/portrayal-fixtures.spec.ts`), which draws each
 * case with `map-styles.ts` and OpenLayers' own canvas replay. The backend's map-image renderer is
 * checked against the same file (`MapImagePlannerTest`).
 *
 * Run it after a change to a case, to the definition's screen sizes, to `map-styles.ts` or to
 * OpenLayers, look at the diff of `expected.json`, and commit it with the change; the backend
 * test then tells whether the renderer still draws what the web maps draw.
 *
 * The spec cannot write the file itself — the kit may import nothing from Node — so in the mode
 * this script sets (`PORTRAYAL_FIXTURES=write`) it prints the draw lists between two markers,
 * and this script writes them, formatted as the repository formats JSON.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as prettier from 'prettier';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SPEC = '../../../shared-map/portrayal-fixtures.spec.ts';
const TARGET = path.join(ROOT, 'shared-map', 'portrayal', 'fixtures', 'expected.json');
const BEGIN = 'PORTRAYAL-FIXTURES-BEGIN';
const END = 'PORTRAYAL-FIXTURES-END';

const run = spawnSync(`npx ng test niord-app --watch=false --include=${SPEC}`, {
  cwd: ROOT,
  env: { ...process.env, PORTRAYAL_FIXTURES: 'write' },
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
  shell: true,
});
const output = `${run.stdout ?? ''}${run.stderr ?? ''}`;
const begin = output.indexOf(BEGIN);
const end = output.indexOf(END, begin);
if (run.status !== 0 || begin < 0 || end < 0) {
  process.stderr.write(output);
  process.stderr.write('\nThe spec did not print the draw lists; expected.json is unchanged.\n');
  process.exit(1);
}

const { cases } = JSON.parse(output.slice(begin + BEGIN.length, end));
const expected = {
  $comment:
    'Written by `npm run portrayal:fixtures` from what OpenLayers draws of each case of cases.json ' +
    'with map-styles.ts: every fill, stroke and symbol in drawing order, positions in image pixels. ' +
    'Do not edit by hand; see fe/docs/map-portrayal.md.',
  cases,
};
const formatted = await prettier.format(JSON.stringify(expected), {
  ...(await prettier.resolveConfig(TARGET)),
  filepath: TARGET,
});
fs.writeFileSync(TARGET, formatted);
process.stdout.write(
  `Wrote the draw lists of ${Object.keys(cases).length} cases to ${path.relative(ROOT, TARGET)}\n`,
);
