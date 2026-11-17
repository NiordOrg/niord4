/**
 * Both applications compile the shared folders' utilities — the map kit's components carry their
 * own, `dark:` ones among them — so a Tailwind setting that decides what such a utility means has
 * to be the same in both builds. The `dark:` variant is the one that already differed: bound to a
 * class in one entry stylesheet and left on the operating system's colour scheme in the other,
 * it turned the same layer switcher dark on a light page. This test reads every entry stylesheet
 * angular.json names and holds each to the shared theme that binds it.
 *
 *   npm run test:guards      # from fe/
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { readAngularJson } from './workspace-paths-check.mjs';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const THEME = 'styles/theme.css';
const DARK_VARIANT = '@custom-variant dark (&:where(.app-dark, .app-dark *));';

/** Every entry stylesheet a build of any project names: the first of each `styles` array. */
function entryStylesheets() {
  const entries = new Set();
  for (const project of Object.values(readAngularJson().projects)) {
    const build = project.architect?.build;
    if (!build) continue;
    for (const options of [build.options, ...Object.values(build.configurations ?? {})]) {
      const [entry] = options?.styles ?? [];
      if (typeof entry === 'string') entries.add(entry);
    }
  }
  return [...entries].sort();
}

/** The workspace-relative files a stylesheet pulls in with `@import '<relative path>'`. */
function imports(file) {
  const text = fs.readFileSync(path.join(FE, file), 'utf8');
  return [...text.matchAll(/@import\s+'(\.{1,2}\/[^']+)'/g)].map(([, relative]) =>
    path
      .relative(FE, path.resolve(path.dirname(path.join(FE, file)), relative))
      .replaceAll('\\', '/'),
  );
}

describe('the entry stylesheets', () => {
  const entries = entryStylesheets();

  it('are found: one per application', () => {
    assert.deepEqual(entries, ['ui-apps/admin/src/styles.css', 'ui-apps/info/src/styles.css']);
  });

  it(`all import ${THEME}`, () => {
    for (const entry of entries) assert.ok(imports(entry).includes(THEME), `${entry} does not`);
  });

  it(`take the dark variant from ${THEME} and bind none of their own`, () => {
    const theme = fs.readFileSync(path.join(FE, THEME), 'utf8');
    assert.ok(theme.includes(DARK_VARIANT), `${THEME} does not declare ${DARK_VARIANT}`);
    for (const entry of entries) {
      const text = fs.readFileSync(path.join(FE, entry), 'utf8');
      assert.doesNotMatch(text, /@custom-variant\s+dark\b/, `${entry} binds a dark variant itself`);
    }
  });
});
