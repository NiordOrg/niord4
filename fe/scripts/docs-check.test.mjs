/**
 * Negative tests for scripts/docs-check.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case hands the guard a crafted document naming something that does not exist, or a recipe
 * that would fail, and expects the guard to name it; the matching control case expects silence.
 * A pattern that stops matching fails nothing by itself, which is why these exist.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import {
  checkDocument,
  checkDocumentation,
  documentationFiles,
  fileOf,
  readWorkspace,
} from './docs-check.mjs';

const workspace = readWorkspace();

/** The errors the guard reports for crafted text, as if it were the document at `rel`. */
function errorsIn(rel, text) {
  return checkDocument(rel, text, workspace).errors;
}

function assertRejectedIn(rel, text, fragment) {
  const errors = errorsIn(rel, text);
  assert.ok(
    errors.some((error) => error.includes(fragment)),
    `expected an error naming "${fragment}" in ${rel} for:\n  ${text}\ngot:\n${errors.join('\n') || '(none)'}`,
  );
}

function assertAcceptedIn(rel, text) {
  assert.deepEqual(errorsIn(rel, text), [], `expected no error in ${rel} for:\n  ${text}`);
}

function assertRejected(text, fragment) {
  assertRejectedIn('docs/crafted.md', text, fragment);
}

function assertAccepted(text) {
  assertAcceptedIn('docs/crafted.md', text);
}

// A guide of the country kit, which the kit package carries and a country reads in its
// .niord/kit/.
const GUIDE = 'country-kit/docs/crafted.md';

describe('docs-check', () => {
  it('passes the documentation in the tree', () => {
    assert.deepEqual(checkDocumentation(workspace).errors, []);
  });

  it('rejects a relative link to a file that does not exist', () => {
    assertRejected('See [the plan](./missing-plan.md).', 'broken link: ./missing-plan.md');
    assertAccepted('See [the backlog](./backlog.md) and [the web](https://example.org/x.md).');
  });

  it('rejects a backticked path into an application folder that does not exist', () => {
    assertRejected('`ui-apps/info/src/app/missing.ts`', 'path does not exist');
    assertAccepted('`ui-apps/info/AGENTS.md` and `ui-apps/admin/src/`.');
  });

  it('reads the AGENTS.md files and their CLAUDE.md importers', () => {
    const files = documentationFiles(workspace);
    for (const file of [
      'AGENTS.md',
      'ui-apps/admin/AGENTS.md',
      'ui-apps/info/AGENTS.md',
      'CLAUDE.md',
      'ui-apps/admin/CLAUDE.md',
      'ui-apps/info/CLAUDE.md',
    ])
      assert.ok(files.includes(file), `${file} is read:\n${files.join('\n')}`);
  });

  it('finds an application by its AGENTS.md or its CLAUDE.md', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-docs-check-'));
    try {
      for (const [relative, text] of Object.entries({
        'package.json': '{"scripts":{}}',
        'angular.json': '{"projects":{}}',
        'ui-apps/a/AGENTS.md': '# AGENTS.md\n',
        'ui-apps/b/CLAUDE.md': '# CLAUDE.md\n',
        'ui-apps/c/src/x.ts': 'export {};\n',
      })) {
        fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
        fs.writeFileSync(path.join(root, relative), text);
      }
      assert.deepEqual(readWorkspace(root, path.join(root, 'no-kit')).apps.sort(), [
        'ui-apps/a',
        'ui-apps/b',
      ]);
    } finally {
      fs.rmSync(root, { recursive: true, force: true });
    }
  });

  it('accepts a one-line importer CLAUDE.md', () => {
    for (const rel of ['CLAUDE.md', 'ui-apps/info/CLAUDE.md'])
      assert.deepEqual(checkDocument(rel, '@AGENTS.md\n', workspace), {
        errors: [],
        warnings: [],
      });
  });

  it('rejects a backticked path into a shared folder or the scripts that does not exist', () => {
    assertRejected('`styles/tokens.active.xx.css`', 'styles/tokens.active.xx.css');
    assertRejected('`shared-map/missing-layer.ts`', 'shared-map/missing-layer.ts');
    assertRejected('`fe/shared-html/missing.ts`', 'fe/shared-html/missing.ts');
    assertRejected('`scripts/missing-check.mjs`', 'scripts/missing-check.mjs');
    assertAccepted(
      '`styles/tokens.active.css`, `fe/shared-map/ol-map.ts`, `shared-html/`, `scripts/docs-check.mjs`.',
    );
  });

  it('rejects a backticked path into the language packs that does not exist', () => {
    assertRejected('`languages/xx/pack.json`', 'languages/xx/pack.json');
    assertRejected('`fe/languages/da/i18n/admin/en.json`', 'fe/languages/da/i18n/admin/en.json');
    assertAccepted(
      '`languages/en/pack.json`, `fe/languages/en/img/flags/en.png`, `languages/<lang>/`.',
    );
  });

  it('rejects a backticked path into the country kit that does not exist', () => {
    assertRejected('`country-kit/missing.mjs`', 'country-kit/missing.mjs');
    assertAccepted('`country-kit/pack.mjs`, `country-kit/lib/where.mjs`.');
  });

  it('rejects a backticked path into the kit written inside fe/, where it no longer is', () => {
    assertRejected('`fe/country-kit/lib/missing.mjs`', 'the kit is at country-kit/');
    assertRejected('`fe/country-kit/stage-languages.mjs`', 'the kit is at country-kit/');
    assertRejectedIn(GUIDE, '`fe/country-kit/README.md`', 'the kit is at country-kit/');
  });

  it('resolves a link from a document of fe/ to the kit beside fe/', () => {
    assertAcceptedIn('README.md', '[the kit](../country-kit/README.md)');
    assertRejectedIn('README.md', '[the kit](country-kit/README.md)', 'broken link');
  });

  it("reads the country kit's documents, named as the zip names them, but not its templates or its example country", () => {
    const files = documentationFiles(workspace);
    for (const file of ['country-kit/README.md', 'country-kit/docs/country-guide.md'])
      assert.ok(files.includes(file), `${file} is read:\n${files.join('\n')}`);
    assert.ok(
      !files.some((file) => /^country-kit\/(templates|example)\//.test(file)),
      files.join('\n'),
    );
  });

  it('rejects a link in a document the kit package carries that leaves the kit, though it resolves here', () => {
    assertRejectedIn(GUIDE, '[the backlog](../../docs/backlog.md)', 'leaves the kit package');
    assertRejectedIn(GUIDE, '[the list](../country-words.json)', 'leaves the kit package');
    assertRejectedIn(GUIDE, '[the pom](../../../be/pom.xml)', 'leaves the kit package');
    assertRejectedIn('country-kit/README.md', '[x](../docs/backlog.md)', 'leaves the kit package');
    assertRejectedIn(
      GUIDE,
      '[the tokens](../../styles/tokens.default.css)',
      'leaves the kit package',
    );
    assertRejectedIn(GUIDE, '[the tool](../source-zip.mjs)', 'leaves the kit package');
    assertAcceptedIn(
      GUIDE,
      '[the kit](../README.md#set-up), [the example](../example/), [the policy](../API-CHANGES.md#the-compatibility-policy), [the stub](../templates/setup.mjs)',
    );
    assertAccepted('[the backlog](./backlog.md)');
  });

  it('rejects a backticked path in a document the kit package carries that its package leaves out', () => {
    assertRejectedIn(GUIDE, '`country-kit/api.test.mjs`', 'not in the kit package');
    assertRejectedIn(GUIDE, '`ui-apps/admin/e2e/auth/README.md`', 'not in the source zip');
    assertRejectedIn(GUIDE, '`fe/.niord/base/docs/backlog.md`', 'not in the source zip');
    assertRejectedIn(GUIDE, '`.niord/base/country-kit/missing.md`', 'not in the source zip');
    assertRejectedIn(GUIDE, '`.niord/kit/missing.md`', 'not in the kit package');
    assertRejectedIn(GUIDE, '`.niord/kit/api.test.mjs`', 'not in the kit package');
    assertRejectedIn(GUIDE, '`../.niord/kit/source-zip.mjs`', 'not in the kit package');
    assertRejectedIn(GUIDE, '`.niord/kit/country-words.json`', 'not in the kit package');
    assertRejectedIn(GUIDE, '`fe/.niord/kit/README.md`', 'the kit unpacks at .niord/kit/');
    assertAcceptedIn(
      GUIDE,
      '`styles/tokens.base.css`, `.niord/kit/README.md`, `../.niord/kit/pack.mjs`, `.niord/kit/docs/country-api.md`, `.niord/kit/kit.json`, `.niord/kit/`, `.niord/kit/templates/<name>`, `.niord/base/catalog.json`, `.niord/base/`, `.niord/base/ui-apps/<app>/src/`, `fe/.niord/base/styles/tokens.default.css`, `.niord/kit/backend/img/company-logo.png`, `.niord/kit/backend/defaults/base/settings.json`',
    );
    // The backend files the build adds are kit package entries, not paths a country has.
    assertRejectedIn(GUIDE, '`.niord/kit/backend/missing.json`', 'not in the kit package');
    assertAccepted('`country-kit/api.test.mjs`');
  });

  it('reads the agent template for its .niord/kit/ and .niord/base/ paths only', () => {
    const template = 'country-kit/templates/AGENTS.md';
    assertRejectedIn(template, '`fe/.niord/base/docs/missing.md`', 'not in the source zip');
    assertRejectedIn(template, '`.niord/kit/docs/missing.md`', 'not in the kit package');
    assertAcceptedIn(
      template,
      'Run `npm run start:xx`, see [the README](./README.md), `fe/country/package.json`, `styles/missing.css`, `fe/.niord/base/styles/tokens.base.css`, `.niord/kit/README.md` and `.niord/kit/docs/country-api.md`.',
    );
    const files = checkDocumentation(workspace).files;
    assert.ok(files.includes(template), files.join('\n'));
  });

  it('rejects a link in a document the source zip carries that leads into the kit', () => {
    const rel = 'styles/crafted.md';
    assertRejectedIn(rel, '[the kit](../country-kit/README.md)', 'link into the kit package');
    assertRejectedIn(rel, '[out](../../docs/backlog.md)', 'leaves the source zip');
    assertAcceptedIn(rel, '[the sheet](./tokens.base.css)');
  });

  it('compiles the kit set from kit-include.json and the source zip set from zip-include.json', () => {
    const kit = workspace.kitPackage?.files ?? new Set();
    for (const name of [
      'docs/country-guide.md',
      'templates/setup.mjs',
      'docs/country-api.md',
      'kit.json',
      'backend/mvnw',
      'backend/mvnw.cmd',
      'backend/maven-wrapper.properties',
      'backend/defaults/base/settings.json',
      'backend/defaults/base/settings-catalog.json',
      'backend/img/company-logo.png',
    ])
      assert.ok(kit.has(name), `${name} is in the kit package`);
    for (const folder of ['backend', 'backend/img', 'backend/defaults/base'])
      assert.ok(workspace.kitPackage.folders.has(folder), `${folder} is a kit package folder`);
    const backend = [...workspace.zip.files].filter((name) => name.startsWith('backend/'));
    assert.deepEqual(backend, [], 'the source zip carries no backend/ entry');
    for (const name of ['source-zip.mjs', 'country-words.json', 'zip.test.mjs'])
      assert.ok(!kit.has(name), `${name} is not in the kit package`);
    for (const name of ['styles/tokens.base.css', 'catalog.json'])
      assert.ok(workspace.zip.files.has(name), `${name} is in the source zip`);
    // The source zip carries nothing of the kit.
    assert.ok(!workspace.zip.files.has('country-kit/setup-kit.mjs'));
    assert.ok(!workspace.zip.files.has('country-kit/templates/setup.mjs'));
    assert.ok(!workspace.zip.folders.has('country-kit'));
    assert.ok(!workspace.zip.files.has('country-kit/pack.mjs'), 'the kit is its own package');
  });

  it("reads a released section of the kit's API-CHANGES.md as a record of that version's paths", () => {
    const rel = 'country-kit/API-CHANGES.md';
    const old = '`.niord/base/country-kit/templates/pack.yml`';
    const text = (top) =>
      [
        '# API changes',
        '',
        top,
        '',
        '## Unreleased',
        '',
        '- Nothing yet.',
        '',
        '## 4.0.87: x',
        '',
        `- Copy ${old}.`,
        '',
        '## 4.0.86: y',
        '',
        `- ${old} again.`,
      ].join('\n');
    assertAcceptedIn(rel, text('The policy.'));
    // Above Unreleased, and in Unreleased, a path is today's.
    assertRejectedIn(rel, text(`${old} at the top.`), 'not in the source zip');
    assert.deepEqual(
      errorsIn(
        rel,
        ['## Unreleased', '', `- Copy ${old}.`, '', '## 4.0.87: x', '', old].join('\n'),
      ),
      [`${rel}:3  not in the source zip: .niord/base/country-kit/templates/pack.yml`],
    );
    // Links and the other rules still apply there, and no other document has released sections.
    const released = (line) => ['## Unreleased', '', '## 4.0.87: x', '', line].join('\n');
    assertRejectedIn(rel, released('[x](../../docs/backlog.md)'), 'leaves the kit package');
    assertRejectedIn(rel, released('`fe/country-kit/x.mjs`'), 'the kit is at country-kit/');
    assertRejectedIn(rel, released('`fe/.niord/kit/README.md`'), 'the kit unpacks at .niord/kit/');
    assertRejectedIn(GUIDE, released(old), 'not in the source zip');
  });

  it('leaves a glob, a placeholder and a list alone', () => {
    assertAccepted('`styles/tokens.active.<cc>.css`, `scripts/*.test.mjs`, `shared-map/{a,b}.ts`.');
  });

  it('rejects an npm script that package.json does not have', () => {
    assertRejected('Run `npm run start:xx`.', 'npm run start:xx');
    assertRejected('then npm run verify:all.', 'npm run verify:all');
    assertAccepted('Run `npm run start:local`, then npm run verify:info.');
    assertAccepted('A country adds `npm run start:<cc>`.');
  });

  it("accepts the country workspace's npm scripts in the kit's guides, and nowhere else", () => {
    const text =
      'Run `npm run start:admin`, `npm run start:info`, `npm run audit` and `npm run e2e`.';
    assertAcceptedIn(GUIDE, text);
    assertRejectedIn('docs/crafted.md', text, 'npm run start:admin');
    assertRejectedIn('country-kit/README.md', text, 'npm run start:admin');
    assertRejectedIn(GUIDE, '`npm run docs:check`', 'npm run docs:check');
    assertRejectedIn(GUIDE, '`npm run start:xx`', 'npm run start:xx');
  });

  it('rejects a build configuration that angular.json does not have', () => {
    assertRejected('`ng build niord-app --configuration test-gl`', 'test-gl');
    assertRejected('`ng serve niord-app -c production,xx`', ': xx');
    assertAccepted('`ng build niord-app --configuration=production`');
    assertAccepted('`ng serve niord-app --configuration=development,<cc>`');
    assertAccepted('replayed with `git -c merge.directoryRenames=true`');
  });

  it('rejects an ng recipe that does not name its project', () => {
    assertRejected("`ng test --include='**/x.spec.ts'`", '`ng test` without its project');
    assertRejected('`ng build --configuration production`', '`ng build` without its project');
    assertRejected('`npx ng serve -c local`', '`ng serve` without its project');
    assertRejected('```bash\nng lint --fix\n```', '`ng lint` without its project');
    assertAccepted("`ng test niord-info --include='**/x.spec.ts'` and `ng build niord-app`");
    assertAccepted('A bare `ng build` stops with "Cannot determine project".');
  });

  it('rejects the retired vocabulary', () => {
    assertRejected('The replatforming kept the routes.', 'rewrite-process wording');
    assertRejected('Tracked as MSG-123.', 'sub-feature tracking id');
  });

  describe("a link's #fragment", () => {
    // The fragment a link names, under a heading, accepted, and a near miss rejected.
    const assertSlug = (heading, slug, miss, rel = 'docs/crafted.md') => {
      assertAcceptedIn(rel, `## ${heading}\n\n[x](#${slug})`);
      assertRejectedIn(rel, `## ${heading}\n\n[x](#${miss})`, `broken anchor: #${miss}`);
    };

    it('rejects a fragment that names no heading of the document', () => {
      assertAccepted('## Set-up\n\n[x](#set-up)');
      assertRejected('## Set-up\n\n[x](#setup)', 'broken anchor: #setup');
    });

    it("slugs the repository's own heading shapes as GitHub does", () => {
      for (const [heading, slug, miss] of [
        [
          'The manifest — `fe/niord-country.json`',
          'the-manifest--feniord-countryjson',
          'the-manifest-feniord-countryjson',
        ],
        ['`<app-drawer>`', 'app-drawer', 'appdrawer'],
        ['`DialogHostService<TResult>`', 'dialoghostservicetresult', 'dialoghostservice'],
        // An HTML tag is dropped from a heading's text, but not from its code.
        [
          'Without Renovate (or before its install): push `base-bump/<version>`',
          'without-renovate-or-before-its-install-push-base-bumpversion',
          'without-renovate-or-before-its-install-push-base-bump',
        ],
        [
          'Transparent wrappers, and why `gap` beats `space-y-*`',
          'transparent-wrappers-and-why-gap-beats-space-y-',
          'transparent-wrappers-and-why-gap-beats-space-y',
        ],
        [
          '4.0.111: the kit as its own package, the Base bump, the guides and the launcher',
          '40111-the-kit-as-its-own-package-the-base-bump-the-guides-and-the-launcher',
          '4.0.111-the-kit-as-its-own-package-the-base-bump-the-guides-and-the-launcher',
        ],
        [
          '1. Keycloak: who may sign in, and the domain clients',
          '1-keycloak-who-may-sign-in-and-the-domain-clients',
          'keycloak-who-may-sign-in-and-the-domain-clients',
        ],
        [
          "The public site's time zone",
          'the-public-sites-time-zone',
          'the-public-site-s-time-zone',
        ],
        ["The designers' brief", 'the-designers-brief', 'the-designer-s-brief'],
        [
          'Data flow: API service → facade → component',
          'data-flow-api-service--facade--component',
          'data-flow-api-service-facade-component',
        ],
        ['Filter ↔ URL contract', 'filter--url-contract', 'filter-url-contract'],
        ['Affected areas (buffers)', 'affected-areas-buffers', 'affected-areas-buffers-'],
        [
          'The public-site image — image:info',
          'the-public-site-image--imageinfo',
          'the-public-site-image-imageinfo',
        ],
        ['`core/`', 'core', 'core-'],
        // A link whose text is code keeps the code's text, and only it.
        [
          '2. Tokens — country-locked ([`tokens.base.css`](../styles/tokens.base.css))',
          '2-tokens--country-locked-tokensbasecss',
          '2-tokens--country-locked-tokensbasecssstylestokensbasecss',
        ],
        [
          '4. The Tailwind `@theme` bridge ([`theme.css`](../styles/theme.css))',
          '4-the-tailwind-theme-bridge-themecss',
          '4-the-tailwind-theme-bridge-themecssstylesthemecss',
        ],
        [
          '5. Layer-3 classes ([`components-core.css`](../styles/components-core.css), [`components-admin.css`](../ui-apps/admin/src/styles/components-admin.css))',
          '5-layer-3-classes-components-corecss-components-admincss',
          '5-layer-3-classes-components-corecssstylescomponents-corecss-components-admincssui-appsadminsrcstylescomponents-admincss',
        ],
      ])
        assertSlug(heading, slug, miss);
    });

    it('numbers the second and later of equal headings', () => {
      const text = (fragment) =>
        `## How to see it worked\n\n## How to see it worked\n\n## How to see it worked\n\n[x](#${fragment})`;
      for (const fragment of [
        'how-to-see-it-worked',
        'how-to-see-it-worked-1',
        'how-to-see-it-worked-2',
      ])
        assertAccepted(text(fragment));
      assertRejected(text('how-to-see-it-worked-3'), 'broken anchor: #how-to-see-it-worked-3');
    });

    it('slugs the other shapes GitHub reads', () => {
      assertSlug('Café menu', 'café-menu', 'cafe-menu');
      assertAccepted('## Café menu\n\n[x](#caf%C3%A9-menu)');
      assertSlug('🚀 Deploy', '-deploy', 'deploy');
      assertSlug('The _quoted_ word', 'the-quoted-word', 'the-_quoted_-word');
      assertSlug('snake_case_name', 'snake_case_name', 'snakecasename');
      assertSlug('Closing #', 'closing', 'closing-');
      assertSlug('A &amp; B', 'a--b', 'a-amp-b');
      assertSlug('A &#8212; B &#x2014; C', 'a--b--c', 'a-8212-b-x2014-c');
      assertSlug('The `&amp;` sign', 'the-amp-sign', 'the--sign');
    });

    it('accepts an explicit HTML anchor', () => {
      assertAccepted(
        '<a id="custom-spot"></a>\n\n<a name="old-name"></a>\n\n[x](#custom-spot) and [y](#old-name)',
      );
      assertRejected(
        '<a id="custom-spot"></a>\n\n[x](#custom-spots)',
        'broken anchor: #custom-spots',
      );
      assertRejected(
        '<a data-id="spot" data-name="place"></a>\n\n[x](#spot)',
        'broken anchor: #spot',
      );
      assertRejected('<a data-name="place"></a>\n\n[x](#place)', 'broken anchor: #place');
    });

    it('reads no heading inside a code fence', () => {
      assertRejected(
        '```bash\n# not a heading\n```\n\n[x](#not-a-heading)',
        'broken anchor: #not-a-heading',
      );
    });

    it('rejects a fragment into another document that names none of its headings', () => {
      assertAccepted('[x](./backlog.md#2-maintenance-backlog)');
      assertRejected(
        '[x](./backlog.md#2-maintenance)',
        'broken anchor: ./backlog.md#2-maintenance',
      );
      assertAcceptedIn(
        GUIDE,
        '[x](../README.md#set-up) and [y](../API-CHANGES.md#the-compatibility-policy)',
      );
      assertRejectedIn(GUIDE, '[x](../README.md#setup)', 'broken anchor: ../README.md#setup');
      assertRejectedIn(GUIDE, '[x](../README.md#setup)', 'nearest:');
      assertRejectedIn(GUIDE, '[x](../README.md#setup)', '#set-up');
      assertRejectedIn(GUIDE, '## Step one\n\n[x](#step-1)', 'broken anchor: #step-1');
    });

    it('reads a link into the document itself from the text being checked', () => {
      assertAcceptedIn('docs/backlog.md', '## Only\n\n[x](./backlog.md#only)');
      assertRejectedIn(
        'docs/backlog.md',
        '## Only\n\n[x](./backlog.md#2-maintenance-backlog)',
        'broken anchor: ./backlog.md#2-maintenance-backlog',
      );
    });

    it('checks a fragment into every document the guard reads', () => {
      const { files } = checkDocumentation(workspace);
      const from = path.join(workspace.root, 'docs');
      for (const file of files) {
        const link = `${path.relative(from, fileOf(workspace, file)).replaceAll('\\', '/')}#no-such-heading-anywhere`;
        assertRejected(`[x](${link})`, `broken anchor: ${link}`);
      }
    });

    it('leaves an address, a file that is not markdown and a document the guard does not read alone', () => {
      assertAccepted(
        [
          '[a](https://example.org/x.md#nope)',
          '[b](../scripts/docs-check.mjs#L10)',
          '[c](./publications/DATA-MODEL.md#nope)',
          '[d](../../be/docs/country-api.md#nope)',
        ].join('\n'),
      );
      assertAcceptedIn('country-kit/templates/AGENTS.md', '[x](./README.md#nope)');
      const errors = errorsIn('docs/crafted.md', '[x](./missing.md#x)');
      assert.ok(
        errors.some((error) => error.includes('broken link: ./missing.md#x')),
        errors.join('\n'),
      );
      assert.ok(!errors.some((error) => error.includes('broken anchor')), errors.join('\n'));
    });

    it('reports a broken anchor as an error, never as a warning', () => {
      const { errors, warnings } = checkDocument('docs/crafted.md', '[x](#nowhere)', workspace);
      assert.ok(
        errors.some((error) => error.includes('broken anchor: #nowhere')),
        errors.join('\n'),
      );
      assert.ok(!warnings.some((warning) => warning.includes('nowhere')), warnings.join('\n'));
    });
  });
});
