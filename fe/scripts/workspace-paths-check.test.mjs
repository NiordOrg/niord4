/**
 * Negative tests for scripts/workspace-paths-check.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case takes the real angular.json, breaks it the way an edit could, and expects the check
 * to name the break. Every one of these breaks builds green, which is why the check exists.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { checkWorkspacePaths, onDisk, readAngularJson } from './workspace-paths-check.mjs';

/** The real angular.json with one edit applied. */
function edited(edit) {
  const config = readAngularJson();
  edit(config.projects);
  return config;
}

function assertRejected(config, ...fragments) {
  const { errors } = checkWorkspacePaths(config);
  for (const fragment of fragments)
    assert.ok(
      errors.some((error) => error.includes(fragment)),
      `expected an error naming "${fragment}", got:\n${errors.join('\n') || '(none)'}`,
    );
}

const infoBuild = (projects) => projects['niord-info'].architect.build;
const adminBuild = (projects) => projects['niord-app'].architect.build;

describe('workspace-paths-check', () => {
  it('passes the angular.json in the tree', () => {
    assert.deepEqual(checkWorkspacePaths(readAngularJson()).errors, []);
  });

  it('rejects a path left at the workspace root', () => {
    assertRejected(
      edited((projects) => (infoBuild(projects).options.browser = 'src/main.ts')),
      '"src/main.ts" still points at the workspace root',
    );
  });

  it('rejects a file that does not exist', () => {
    assertRejected(
      edited((projects) => (infoBuild(projects).options.index = 'ui-apps/info/src/missing.html')),
      '"ui-apps/info/src/missing.html" does not exist',
    );
  });

  it("rejects an asset input from the other application's folder", () => {
    assertRejected(
      edited((projects) =>
        infoBuild(projects).options.assets.push({
          glob: '**/*',
          input: 'ui-apps/admin/public',
          output: 'assets',
        }),
      ),
      '"ui-apps/admin/public" is neither in this project\'s folder',
    );
    assertRejected(
      edited((projects) => adminBuild(projects).options.styles.push('ui-apps/info/src/styles.css')),
      '"ui-apps/info/src/styles.css" is neither in this project\'s folder',
    );
  });

  it('passes its own staged language folder whether or not it has been staged', () => {
    // As on a fresh checkout, where nothing has been staged yet.
    const exists = (relative) => !relative.startsWith('.niord/') && onDisk.exists(relative);
    const config = edited((projects) =>
      infoBuild(projects).options.assets.push({
        glob: '**/*',
        input: '.niord/build/info/',
        output: 'assets',
      }),
    );
    assert.deepEqual(
      checkWorkspacePaths(config, { ...onDisk, exists }).errors.filter((error) =>
        error.includes('.niord/'),
      ),
      [],
    );
  });

  it("rejects the other application's staged language folder", () => {
    assertRejected(
      edited((projects) =>
        infoBuild(projects).options.assets.push({
          glob: '**/*',
          input: '.niord/build/admin',
          output: 'assets',
        }),
      ),
      '".niord/build/admin" is the staged language folder of ui-apps/admin/, not of this project (ui-apps/info/)',
    );
  });

  it('rejects a language pack read as an asset input', () => {
    assertRejected(
      edited((projects) =>
        adminBuild(projects).options.assets.push({
          glob: '**/*',
          input: 'languages/en',
          output: 'assets',
        }),
      ),
      '"languages/en" reads the language packs, which reach a build only through its staged folder',
    );
  });

  it("rejects a lint pattern that reaches into the other application's folder", () => {
    assertRejected(
      edited((projects) =>
        projects['niord-info'].architect.lint.options.lintFilePatterns.push(
          'ui-apps/admin/src/**/*.ts',
        ),
      ),
      '"ui-apps/admin/src/**/*.ts" is neither in this project\'s folder',
    );
  });
});
