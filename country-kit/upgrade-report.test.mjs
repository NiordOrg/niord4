import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fingerprint } from './lib/overrides.mjs';
import { kitZipOf, previousBase, readRecord, RECORD, zipOf } from './lib/previous-base.mjs';
import {
  changeEntries,
  changeSections,
  countryActions,
  newChangeSections,
  REPORT,
  REPORT_DATA,
  requiredActions,
  sectionActions,
  STUB_TEMPLATE,
  upgradeReport,
  upgradeReportData,
} from './lib/upgrade-report.mjs';
import { markerText } from './lib/where.mjs';
import { readZip, writeZip } from './lib/zip.mjs';
import { parseArgs, upgradeFrom, writeUpgradeReport } from './setup-kit.mjs';

const temps = [];
const temp = () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-upgrade-'));
  temps.push(dir);
  return dir;
};
after(() => temps.forEach((dir) => fs.rmSync(dir, { recursive: true, force: true })));

/** A folder holding `files`, each relative path to its text. */
function tree(files) {
  const dir = temp();
  for (const [relative, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, relative)), { recursive: true });
    fs.writeFileSync(path.join(dir, relative), text);
  }
  return dir;
}

/** The zip of `files`, made and read back in memory as lib/previous-base.mjs gives it. */
const zipBytes = (files) =>
  writeZip(Object.entries(files).map(([name, text]) => ({ name, data: Buffer.from(text) })));
const zipped = (files) => new Map(readZip(zipBytes(files)).map(({ name, data }) => [name, data]));
const sha256Of = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

const KIT = 'country-kit/';
/** The kit's files of a files object, named relative to the kit, as the kit package holds them. */
const kitOf = (files) =>
  Object.fromEntries(
    Object.entries(files)
      .filter(([name]) => name.startsWith(KIT))
      .map(([name, text]) => [name.slice(KIT.length), text]),
  );
/** The rest: the base product's files without the kit. */
const withoutKit = (files) =>
  Object.fromEntries(Object.entries(files).filter(([name]) => !name.startsWith(KIT)));

const json = (value) => `${JSON.stringify(value, null, 2)}\n`;
const changes = (...sections) =>
  [
    '# Country API changes',
    '',
    '## The policy',
    '',
    'What a country must do: read this first.',
    '',
    ...sections,
  ].join('\n');
const section = (version, title, action) =>
  `## ${version}: ${title}\n\nThe change, and why.\n\nWhat a country must do: ${action}\n`;
const api = (...exports) =>
  [
    '/** The country API. */',
    '',
    ...exports.map(
      (name) => `/** ${name}, a contract. */\nexport { ${name} } from './app/${name}';\n`,
    ),
  ].join('\n');

// The override of the example's kind: a component whose template the base product changes.
const FOO = 'ui-apps/admin/src/app/features/foo/foo';
const WITH = 'admin/overrides/features/foo/foo.ts';
const TS = "@Component({ templateUrl: './foo.html' })\nexport class Foo {}\n";
const HTML_BEFORE = '<p>{{ text }}</p>\n<app-bar />\n';
const HTML_NOW = '<p>{{ text }}</p>\n<app-bar />\n<app-baz />\n';
const TOKEN_FILE = 'ui-apps/admin/src/app/features/foo/foo-renderer.token.ts';

const branding = (keys) => ({
  format: 1,
  apps: {
    admin: {
      texts: { path: 'branding/{lang}.json', output: 'assets/branding/{lang}.json' },
      keys,
      files: {},
      legal: { path: 'legal/{doc}.{lang}.html', documents: { disclaimer: {} } },
    },
  },
});

/** The base product's files at the previous version, 4.0.0. */
const PREVIOUS = {
  'country-kit/API-CHANGES.md': changes(section('4.0.0', 'the first API', 'nothing.')),
  'country-kit/security.json': json({ format: 1, fixes: [] }),
  'country-kit/seams.json': json({ format: 1, seams: [], hooks: [], slots: [], protected: [] }),
  'country-kit/branding.schema.json': json(
    branding({ 'footer.link': { kind: 'url', required: false } }),
  ),
  'tsconfig.json': json({
    compilerOptions: { paths: { '@niord/admin/api': ['./ui-apps/admin/src/public-api.ts'] } },
  }),
  'ui-apps/admin/src/public-api.ts': api('roleGuard'),
  'package.json': json({
    dependencies: { '@angular/common': '22.1.0', '@angular/core': '22.1.0', rxjs: '7.8.1' },
    devDependencies: { vitest: '4.0.0' },
  }),
  [`${FOO}.ts`]: TS,
  [`${FOO}.html`]: HTML_BEFORE,
};

/** The base product's files at the version set up now, 4.0.1, as .niord/base/ holds them. */
const CURRENT = {
  ...PREVIOUS,
  'country-kit/API-CHANGES.md': changes(
    section(
      '4.0.1',
      'roleGuard takes its roles from data',
      'replace `roleGuard(roles)` by `roleGuard`.',
    ),
    section('4.0.1', 'a renderer hook for foo', 'nothing; it is an addition.'),
    section('4.0.0', 'the first API', 'nothing.'),
  ),
  'country-kit/security.json': json({
    format: 1,
    fixes: [{ version: '4.0.1', files: [`${FOO}.html`], summary: 'escapes the bar' }],
  }),
  'country-kit/seams.json': json({
    format: 1,
    seams: [],
    hooks: [
      {
        id: 'admin.foo.renderers',
        app: 'admin',
        token: 'FOO_RENDERERS',
        file: `${FOO}.ts`,
        description: 'How foo is shown. Multi.',
      },
    ],
    slots: [],
    protected: [],
  }),
  'country-kit/branding.schema.json': json(
    branding({ 'footer.motto': { kind: 'text', required: true } }),
  ),
  'ui-apps/admin/src/public-api.ts': api('roleGuard', 'FOO_RENDERERS'),
  'package.json': json({
    dependencies: {
      '@angular/common': '22.2.0',
      '@angular/core': '22.2.0',
      rxjs: '7.8.2',
      'pretty-print': '1.0.0',
    },
    devDependencies: { vitest: '4.0.0' },
  }),
  [`${FOO}.html`]: HTML_NOW,
  [TOKEN_FILE]: 'export const FOO_RENDERERS = 1;\n',
};

const MANIFEST = {
  country: 'xx',
  apps: { admin: { uiLanguages: ['en', 'zz'], defaultLanguage: 'en' } },
  packs: { zz: { home: true, publish: false }, da: { pinned: true } },
  overrides: [
    {
      file: `${FOO}.ts`,
      with: WITH,
      basedOn: {
        [`${FOO}.ts`]: fingerprint(Buffer.from(TS)),
        [`${FOO}.html`]: fingerprint(Buffer.from(HTML_BEFORE)),
      },
    },
  ],
};

/** A country's fe/: the branding text it fills under the previous schema. */
const country = () =>
  tree({ 'admin/assets/branding/zz.json': json({ footer: { link: 'https://example.org' } }) });

/**
 * The report of an upgrade from 4.0.0 to 4.0.1 with `findings`, over the fixtures above; `folders`
 * the current version's base product and kit, each in a folder of its own.
 */
function report({
  findings = {},
  errors = [],
  previous = { version: '4.0.0', files: zipped(PREVIOUS) },
  current = CURRENT,
  folders = { base: tree(withoutKit(current)), kit: tree(kitOf(current)) },
  manifest = MANIFEST,
  fe = country(),
} = {}) {
  return upgradeReport({
    country: 'xx',
    manifest,
    fe,
    previous,
    current: { version: '4.0.1', ...folders },
    checks: { errors, findings: { packDirs: { zz: path.join(fe, 'packs', 'zz') }, ...findings } },
  });
}

/** report()'s input, with the version set up now as `version`: what the report's data is made from. */
function inputOf({
  findings = {},
  errors = [],
  previous = { version: '4.0.0', files: zipped(PREVIOUS) },
  current = CURRENT,
  folders = { base: tree(withoutKit(current)), kit: tree(kitOf(current)) },
  manifest = MANIFEST,
  fe = country(),
  version = '4.0.1',
} = {}) {
  return {
    country: 'xx',
    manifest,
    fe,
    previous,
    current: { version, ...folders },
    checks: { errors, findings: { packDirs: { zz: path.join(fe, 'packs', 'zz') }, ...findings } },
  };
}
const dataOf = (options) => upgradeReportData(inputOf(options));

/** The lines of one section of the report, its heading left out. */
const sectionOf = (text, heading) => {
  const start = text.indexOf(`\n## ${heading}\n`);
  assert.notEqual(start, -1, `no ${heading} section`);
  const end = text.indexOf('\n## ', start + 1);
  return text.slice(start + heading.length + 5, end === -1 ? undefined : end);
};

describe('the upgrade report: its frame', () => {
  it("has the title and the five sections in §4.12's order", () => {
    const text = report();
    assert.match(text, /^# Upgrade report: xx from 4\.0\.0 to 4\.0\.1\n/);
    const headings = [...text.matchAll(/^## (.+)$/gm)].map((match) => match[1]);
    assert.deepEqual(headings, [
      'Versions',
      'To do',
      'To look at',
      'New for you',
      'Dependency changes',
    ]);
    assert.match(text, /^Set-up of 4\.0\.1 passed\.$/m);
  });

  it('says when set-up failed, and that the last good version stays recorded', () => {
    assert.match(
      report({ errors: ['x', 'y'] }),
      /^Set-up of 4\.0\.1 failed: 2 checks .*stays recorded as 4\.0\.0 until it passes\.$/m,
    );
  });
});

describe('the upgrade report: Versions', () => {
  it('names the versions crossed, says the release notes are not in the zip, and lists the new sections and fixes', () => {
    const text = sectionOf(report(), 'Versions');
    assert.match(text, /The base product moves from 4\.0\.0 to 4\.0\.1\./);
    assert.match(text, /Release notes: not in the zip/);
    assert.match(
      text,
      /`\.niord\/kit\/API-CHANGES\.md`, sections new since 4\.0\.0: `4\.0\.1: roleGuard takes its roles from data`, `4\.0\.1: a renderer hook for foo`\./,
    );
    assert.match(text, /fixes new since 4\.0\.0: 4\.0\.1 \(escapes the bar\)\./);
    // The kit's files are named as a country has them, from its repository root, said once.
    assert.ok(
      text.includes(
        "- Release notes: not in the zip; the sections below come from `.niord/kit/API-CHANGES.md` and `.niord/kit/security.json`, the kit's files, from the repository root.",
      ),
      text,
    );
    assert.equal(text.split('from the repository root').length - 1, 1);
    assert.match(text, /^- `\.niord\/kit\/security\.json`, fixes new since 4\.0\.0: /m);
    assert.doesNotMatch(text, /country-kit\//);
  });

  it('without the previous zip, takes the sections and fixes newer than the previous version', () => {
    const text = sectionOf(
      report({ previous: { version: '4.0.0', unavailable: 'no zip' } }),
      'Versions',
    );
    assert.match(
      text,
      /sections new since 4\.0\.0 \(by version: the previous zip is not at hand\): `4\.0\.1: roleGuard/,
    );
    assert.doesNotMatch(text, /`4\.0\.0: the first API`/);
  });

  it('reads the version sections of API-CHANGES.md, and Nothing is no action', () => {
    const sections = changeSections(
      changes(section('Unreleased', 'next', 'Nothing.'), section('4.0.1', 'x', 'run y.')),
    );
    assert.deepEqual(
      sections.map((s) => s.version),
      ['Unreleased', '4.0.1'],
    );
    assert.deepEqual(requiredActions(sections[0]), []);
    assert.deepEqual(requiredActions(sections[1]), ['run y.']);
    assert.deepEqual(requiredActions({ body: 'An entry with no action.' }), [
      'the section names no action: read it',
    ]);
    // A lead-in that ends in a colon before a list gives one action per item, the lead-in first and
    // the item whole, so each reads on its own and none reads "at its bump:" and nothing else.
    const steps = ['at its bump: copy `image.yml` again;', 'at its bump: run set-up.'];
    assert.deepEqual(
      requiredActions({
        body: '**What a country must do at its bump:**\n\n1. copy `image.yml` again;\n2. run set-up.\n',
      }),
      steps,
    );
    // The list may follow on the very next line, and an item may wrap.
    assert.deepEqual(
      requiredActions({
        body: 'What a country must do at its bump:\n1. copy `image.yml` again;\n2. run set-up.\n',
      }),
      steps,
    );
    assert.deepEqual(
      requiredActions({
        body: 'What a country must do at its bump:\n\n1. copy\n   `image.yml` again;\n2. run\n   set-up.\n\nAn afterword.\n',
      }),
      steps,
    );
    // A deeper-indented list folds into its item.
    assert.deepEqual(
      countryActions('What a country must do:\n\n- run a:\n  - with b;\n  - with c.\n- run d.\n'),
      ['run a: - with b; - with c.', 'run d.'],
    );
    // A lead-in followed by no list stays one action; an answer not ending in a colon is one,
    // however long, and never cut.
    assert.deepEqual(countryActions('What a country must do at its bump:\n\nAn afterword.'), [
      'at its bump:',
    ]);
    const long = `run ${'a long step, '.repeat(40)}done.`;
    assert.deepEqual(countryActions(`What a country must do: ${long}\n\n1. not an item of it.\n`), [
      long,
    ]);
  });

  // Two entries as they stood in the kit's API-CHANGES.md, kept here as fixtures, each in a section
  // with a neighbour after it: the parser is held to entries of the real record's shape (a numbered
  // list after a lead-in, items that wrap over several lines, a "What a country should know" list
  // after the answer) whatever the record itself holds.
  const IMAGE_BACKEND_JOB = [
    "**The image template's backend job; set-up runs before Maven.** `templates/image-backend-job.yml`",
    'is new: the backend job, which runs set-up before Maven, then builds the jar, runs the override',
    'check over it, splits it and hands it on as `backend-dist.tar` with its SHA-256. `init` writes it',
    'into a new `image.yml` and prints it when `image.yml` exists. In `templates/image.yml` the push job',
    '`needs: [frontend, backend]`, downloads `backend-dist` and verifies `backend-dist.tar.sha256`',
    'beside the other two, and runs the new step `Check the version` before it pushes; the `env` gains',
    "`MODULE` (empty until the country sets it), `MVN_FLAGS` and `MAVEN_OPTS`. The README's",
    "[The backend build](README.md#the-backend-build) gives the pom's copies of a staged pack and the",
    'Maven check. No export is added or removed. Why: no backend job ran set-up, so',
    "`fe/.niord/packs/<lang>/backend/` did not exist when Maven ran, and a pinned pack's backend half",
    'reached no jar.',
    '',
    'What a country must do at its bump:',
    '',
    '1. Add the backend job to `image.yml` by hand, as `init` prints it, and set `MODULE` to its',
    "   deployable's `artifactId`. Where `image.yml` already has a backend job, add `setup-node` and",
    '   `Set up the workspace` (in `fe/`, the workflow token bound) before `Build the jar`. Either way,',
    "   take the push job's changes from `.niord/kit/templates/image.yml`: both jobs as its",
    '   needs, the `backend-dist` download and its `sha256sum -c`, and `Check the version`.',
    '2. Add `setup-node` and `Set up the workspace` before `./test-it` in the backend test job of its',
    '   `ci.yml`.',
    "3. Re-point the pom's pack copies from `fe/packs/<lang>/` to",
    '   `fe/.niord/packs/<lang>/backend` and `fe/.niord/packs/<lang>/img/flags` for every staged pack,',
    "   adding a pinned pack's copies where the pom has none.",
    "4. Add the Maven check, `maven-antrun-plugin`'s `require-staged-packs` at `validate`, over the paths",
    '   the copies read.',
    '5. Run `node fe/setup.mjs` before any local Maven build.',
  ].join('\n');
  const BASE_BUMP_APP = [
    "**The Niord base bump app opens the base bump; the pinned packs ride along.** The base product's",
    'publish run opens the bump pull request minutes after each release, in every country repository',
    'that installed the GitHub App Niord base bump (`https://github.com/apps/niord-base-bump`): it',
    'pushes `base-bump/<version>` from the default branch, whose one change is the `<parent>` of',
    '`be/pom.xml`. A newer release moves the open one on its branch; a branch in conflict with `main` is',
    'replaced by a fresh pull request, and the old one is closed with a comment.',
    "`templates/base-bump.yml` moves every pinned pack to its home's newest pack",
    '(`node setup.mjs --update-pins`) in its pin commit and names each moved pin, from and to, with the',
    "home's `pack-releases.md`, in its comment; keeps the tick of a box while what it is about is",
    'unchanged when it posts the comment again (its text, and for a box about a kit template the',
    "template's content, named by a hidden marker at the box's end); and accepts `base-bump/` branches",
    'only. `templates/renovate.json` is',
    'retired, with its `CODEOWNERS` line and its place among the templates the upgrade report compares;',
    'the comment of `templates/dependabot.yml` names the base bump instead of Renovate; `init` writes no',
    '`.github/renovate.json` and names the install link in its closing lines. No export is added or',
    'removed. Why: the bump arrives by itself within minutes, with no schedule to pause and no',
    'credential in the country.',
    '',
    'What a country must do: Nothing at its bump beyond the boxes the Base bump comment itself lists',
    '(the copy of `.niord/kit/templates/base-bump.yml` over `.github/workflows/base-bump.yml` and the',
    'merge of `.niord/kit/templates/AGENTS.md` into `AGENTS.md`).',
    '',
    'What a country should know:',
    '',
    '- Install the Niord base bump app once on this repository, with "Only select repositories"',
    '  ([Install the Niord base bump app](docs/responsibilities-and-upgrades.md#install-the-niord-base-bump-app)),',
    '  so each base version arrives as a pull request. Without it, push `base-bump/<version>` as before',
    '  ([Without the app](docs/responsibilities-and-upgrades.md#without-the-app-push-base-bumpversion)).',
    '- `.github/renovate.json` and its `CODEOWNERS` line are inert where no Renovate is installed:',
    '  delete them, or keep them.',
    '- The pack refresh and the kept ticks start at the bump after the copied `base-bump.yml` is on',
    '  `main`.',
    "- `.github/dependabot.yml` may take the template's new comment; its settings are unchanged.",
  ].join('\n');
  const neighbour = (lead) =>
    `**${lead}** The change, and why.\n\nWhat a country must do: nothing.`;
  const ENTRIES = changes(
    `## Unreleased\n\n${BASE_BUMP_APP}\n\n${neighbour('A later entry.')}\n`,
    `## 4.0.1: the image template\n\n${neighbour('An earlier entry.')}\n\n${IMAGE_BACKEND_JOB}\n\n${neighbour('The entry after it.')}\n`,
  );

  it("gives the image template's backend job entry as five whole actions", () => {
    const entry = changeSections(ENTRIES)
      .flatMap((s) => changeEntries(s.body))
      .find((e) => e.lead.startsWith("The image template's backend job"));
    assert.ok(entry, "no entry on the image template's backend job");
    const actions = countryActions(entry.text);
    assert.equal(actions.length, 5, actions.join('\n'));
    for (const action of actions) {
      assert.ok(action.startsWith('at its bump: '), action);
      assert.ok(!action.includes('…'), action);
    }
    assert.ok(actions[0].includes("set `MODULE` to its deployable's `artifactId`"), actions[0]);
    assert.ok(actions[0].endsWith('`Check the version`.'), actions[0]);
    assert.equal(actions[4], 'at its bump: Run `node fe/setup.mjs` before any local Maven build.');
  });

  it("gives the base bump app's entry one action, its Nothing, which asks for no box", () => {
    const lead = 'The Niord base bump app opens the base bump; the pinned packs ride along.';
    const entries = changeSections(ENTRIES).flatMap((s) => changeEntries(s.body));
    const same = entries.filter((e) => e.lead === lead);
    assert.equal(same.length, 1, `the lead "${lead}" is used ${same.length} times`);
    const [entry] = same;
    const actions = countryActions(entry.text);
    assert.equal(actions.length, 1, actions.join('\n'));
    assert.ok(actions[0].startsWith('Nothing at its bump'), actions[0]);
    assert.deepEqual(sectionActions({ entries: [entry] }), []);
  });
});

describe('the upgrade report: To do', () => {
  const todo = (options) => sectionOf(report(options), 'To do');

  it('a new key the home pack lacks: translate it in the pack file; a pinned pack waits for its home', () => {
    const text = todo({
      findings: {
        packs: {
          missing: [
            { lang: 'zz', app: 'admin', keys: ['harbour.title'] },
            { lang: 'da', app: 'admin', keys: ['harbour.title', 'harbour.notes'] },
          ],
        },
      },
    });
    assert.match(
      text,
      /^- `packs\/zz\/i18n\/admin\/zz\.json`: translate 1 new key: `harbour\.title`$/m,
    );
    assert.match(
      text,
      /^- `packs\/da\/i18n\/admin\/da\.json` lacks 2 new keys: `harbour\.title`, `harbour\.notes`: wait for the da home's pack/m,
    );
  });

  it('a translation whose markup no longer matches English', () => {
    assert.match(
      todo({
        findings: { packs: { markup: [{ lang: 'zz', app: 'admin', key: 'publish.summary' }] } },
      }),
      /^- `packs\/zz\/i18n\/admin\/zz\.json`: `publish\.summary`'s markup no longer matches English's: give the translation the English markup$/m,
    );
  });

  it("a changed overridden file, with the base product's diff since the recorded fingerprint", () => {
    const text = todo();
    assert.match(
      text,
      new RegExp(
        `^- \`${WITH}\` \\(the override of \`${FOO}\\.ts\`\\): the base product changed \`${FOO}\\.html\`: compare the copy with the base product's diff below \\(4\\.0\\.0 to 4\\.0\\.1; - before, \\+ now\\), adjust the copy, then run \`node setup\\.mjs --accept-override ${FOO}\\.ts\`\\n\\n {2}\`\`\`diff\\n {2}\\+3 <app-baz />\\n {2}\`\`\`$`,
        'm',
      ),
    );
    // The unchanged file is not named.
    assert.doesNotMatch(text, /changed `ui-apps\/admin\/src\/app\/features\/foo\/foo\.ts`/);
  });

  it('an override whose file joined the protected list', () => {
    const seams = JSON.parse(CURRENT['country-kit/seams.json']);
    const text = todo({
      current: {
        ...CURRENT,
        'country-kit/seams.json': json({
          ...seams,
          protected: ['ui-apps/admin/src/app/features/**'],
        }),
      },
    });
    assert.match(
      text,
      new RegExp(
        `^- \`${WITH}\` \\(the override of \`${FOO}\\.ts\`\\): its file joined the protected list`,
        'm',
      ),
    );
  });

  it('a branding key the country fills that the schema removed, and a required one it added', () => {
    const text = todo();
    assert.match(
      text,
      /^- `admin\/assets\/branding\/zz\.json`: delete `footer\.link`, which the branding schema no longer has$/m,
    );
    assert.match(
      text,
      /^- `admin\/assets\/branding\/zz\.json`: add `footer\.motto`, which the branding schema requires$/m,
    );
  });

  it('a text-override key that left the catalog, with its new name', () => {
    const text = todo({
      findings: {
        textOverrides: {
          leftCatalog: [
            { lang: 'en', app: 'admin', key: 'menu.shut', renamedTo: 'menu.close' },
            { lang: 'en', app: 'info', key: 'OLD' },
          ],
        },
      },
    });
    assert.match(
      text,
      /^- `text-overrides\/en\.json`: admin `menu\.shut` left the catalog: move the override to `menu\.close`, its new name$/m,
    );
    assert.match(
      text,
      /^- `text-overrides\/en\.json`: info `OLD` left the catalog: remove the override$/m,
    );
  });

  it('a country package whose peer rejects the new framework version', () => {
    const peers = [
      {
        name: 'ngx-harbour',
        peer: '@angular/core',
        range: '^22.1.0',
        version: '22.2.0',
        rejected: false,
      },
      {
        name: 'ngx-quay',
        peer: '@angular/core',
        range: '~22.1.0',
        version: '22.2.0',
        rejected: true,
      },
    ];
    const text = todo({ findings: { dependencies: { added: [{}], peers } } });
    assert.match(
      text,
      /^- `country\/package\.json`: `ngx-quay` needs the peer `@angular\/core ~22\.1\.0`, and the base product now has 22\.2\.0: update `ngx-quay`/m,
    );
    assert.doesNotMatch(text, /ngx-harbour/);
  });

  it('an API change requiring action is under To look at, as set-up does not check it; one whose action is nothing is not listed', () => {
    const text = report();
    assert.doesNotMatch(sectionOf(text, 'To do'), /API-CHANGES\.md`, `4\.0\.1/);
    const look = sectionOf(text, 'To look at');
    assert.match(
      look,
      /^What `\.niord\/kit\/API-CHANGES\.md` asks of a country \(set-up does not check these\):\n\n- `\.niord\/kit\/API-CHANGES\.md`, `4\.0\.1: roleGuard takes its roles from data`: replace `roleGuard\(roles\)` by `roleGuard`\.$/m,
    );
    assert.doesNotMatch(look, /a renderer hook for foo/);
    assert.doesNotMatch(look, /the first API/);
  });

  it("a stub the base product changed: copy the kit's template over setup.mjs", () => {
    assert.match(
      todo({ findings: { stub: true } }),
      /^Set-up or the build fails until each is done\.\n\n- `setup\.mjs`: the base product changed the stub: copy `\.\.\/\.niord\/kit\/templates\/setup\.mjs` over it/m,
    );
    assert.doesNotMatch(todo(), /changed the stub/);
  });

  it('a security fix that touches an override', () => {
    assert.match(
      todo(),
      new RegExp(
        `^- \`${WITH}\` .*: the security fix of 4\\.0\\.1 changed \`${FOO}\\.html\` \\(escapes the bar\\): merge it into the copy`,
        'm',
      ),
    );
  });

  it('a pinned pack its home withdrew: the pin, the reason and --update-pins; nothing without one', () => {
    const digest = `sha256:${'a'.repeat(64)}`;
    const reference = `ghcr.io/sifa-dk/niord-lang-da:12@${digest}`;
    const error = `pack da: ${reference} is withdrawn (2026-10-22): a broken date; move the pin with node setup.mjs --update-pins`;
    const withdrawn = [
      {
        lang: 'da',
        reference,
        digest,
        tag: '12',
        reason: 'a broken date',
        date: '2026-10-22',
        error,
      },
    ];
    const text = todo({ findings: { withdrawn }, errors: [error] });
    // The item accounts the check's line, so the line is not listed as well.
    assert.ok(!text.includes(error), text);
    assert.ok(
      text.includes(
        `- \`niord-pins.lock.json\`: the pinned pack da, \`${reference}\`, is withdrawn by its home (2026-10-22): a broken date; move the pin`,
      ),
      text,
    );
    assert.match(text, /move the pin .* with `node setup\.mjs --update-pins`/);
    assert.doesNotMatch(todo(), /withdrawn/i);
  });

  it('says what set-up does not check: the settings lists and the advisories', () => {
    const text = todo();
    assert.match(
      text,
      /settings keys that left the catalog: not available: the zip carries no backend settings list\./,
    );
    assert.doesNotMatch(text, /withdrawn pinned pack/);
    assert.match(
      text,
      /Dependency advisories not accepted: checked by `npm run build`'s audit after `npm ci`/,
    );
  });

  it('has nothing to do when nothing changed under the country', () => {
    const same = { ...PREVIOUS };
    const text = todo({ current: same, fe: tree({}) });
    assert.match(text, /^\n- Nothing\.\n\nNot checked by set-up:/);
  });
});

// The red runs of a bump: each with the lines its checks failed on (`errors`), the structured
// findings beside them, and the lines an item made from a finding accounts for. Every other line
// must be listed in To do as it is.
const PLACEHOLDERS =
  'pack check: packs/zz/i18n/admin/zz.json: secom.toast.reconciled has the placeholders cancelled, created, unchanged, updated, English cancelled, created, failed, unchanged, updated';
const PLACEHOLDERS_DA =
  'pack check: packs/da/i18n/admin/da.json: secom.toast.reconciled has the placeholders cancelled, created, unchanged, updated, English cancelled, created, failed, unchanged, updated';
const MISSING = 'pack check: packs/zz/i18n/admin/zz.json lacks harbour.title, which English has';
const SETTINGS =
  'be/src/main/resources/defaults/country/settings.json: modelLanguages names zz, which no pack supplies';
const CHANGED = `override ${FOO}.ts: the base product changed ${FOO}.html since the copy was based on it; compare the copy, then run node setup.mjs --accept-override ${FOO}.ts`;
const SEAM = `override ${FOO}.ts: it overrides a file the base product gives a seam for; use the seam`;
const SECURITY_FIX = `override ${FOO}.ts: the security fix of 4.0.1 changed ${FOO}.html; merge it into the copy`;
const BRANDING_KEY =
  'branding (admin): admin/assets/branding/zz.json lacks footer.motto, which the branding schema requires';
const WITHDRAWN = `pack da: ghcr.io/sifa-dk/niord-lang-da:12@sha256:${'a'.repeat(64)} is withdrawn (2026-10-22): a broken date; move the pin with node setup.mjs --update-pins`;
const STUB =
  "stub: fe/setup.mjs is not the kit's own; the base product changed it: in fe/, copy ../.niord/kit/templates/setup.mjs over setup.mjs";
const placeholder = (lang, error) => ({
  lang,
  app: 'admin',
  key: 'secom.toast.reconciled',
  error,
});
const NOTHING_ELSE = { current: { ...PREVIOUS }, fe: tree({}) };
const RED_RUNS = [
  {
    name: "a home pack's text whose placeholders are not English's",
    options: {
      ...NOTHING_ELSE,
      errors: [PLACEHOLDERS],
      findings: { packs: { placeholders: [placeholder('zz', PLACEHOLDERS)] } },
    },
    accounted: [PLACEHOLDERS],
  },
  {
    name: 'a mixed red run: a placeholder, a missing key and a settings line',
    options: {
      ...NOTHING_ELSE,
      errors: [PLACEHOLDERS, MISSING, SETTINGS],
      findings: {
        packs: {
          placeholders: [placeholder('zz', PLACEHOLDERS)],
          missing: [{ lang: 'zz', app: 'admin', keys: ['harbour.title'], error: MISSING }],
        },
      },
    },
    accounted: [PLACEHOLDERS, MISSING],
  },
  {
    name: 'a settings line alone',
    options: { ...NOTHING_ELSE, errors: [SETTINGS] },
    accounted: [],
  },
  {
    name: 'an override changed and overriding a seam, and a security fix',
    options: {
      errors: [CHANGED, SEAM, SECURITY_FIX],
      findings: {
        overrides: [
          { file: `${FOO}.ts`, kind: 'changed', error: CHANGED },
          { file: `${FOO}.ts`, kind: 'seam', error: SEAM },
          {
            file: `${FOO}.ts`,
            kind: 'security',
            version: '4.0.1',
            changed: `${FOO}.html`,
            error: SECURITY_FIX,
          },
        ],
      },
    },
    accounted: [CHANGED, SECURITY_FIX],
  },
  {
    name: 'a required branding key',
    options: {
      errors: [BRANDING_KEY],
      findings: {
        branding: {
          admin: [
            {
              kind: 'required-key',
              file: 'branding/zz.json',
              key: 'footer.motto',
              error: BRANDING_KEY,
            },
          ],
        },
      },
    },
    accounted: [BRANDING_KEY],
  },
  {
    name: 'a withdrawn pin and a changed stub',
    options: {
      ...NOTHING_ELSE,
      errors: [WITHDRAWN, STUB],
      findings: {
        withdrawn: [
          {
            lang: 'da',
            reference: `ghcr.io/sifa-dk/niord-lang-da:12@sha256:${'a'.repeat(64)}`,
            reason: 'a broken date',
            date: '2026-10-22',
            error: WITHDRAWN,
          },
        ],
        stub: { error: STUB },
      },
    },
    accounted: [WITHDRAWN, STUB],
  },
];

describe('the upgrade report: every failed check reaches To do', () => {
  const todoOf = (markdown) => sectionOf(markdown, 'To do');

  it("lists a home pack's text whose placeholders are not English's, and not the check's line", () => {
    const { markdown, data } = dataOf(RED_RUNS[0].options);
    assert.deepEqual(data.todo, [
      "`packs/zz/i18n/admin/zz.json`: `secom.toast.reconciled`'s placeholders no longer match English's: give the translation English's placeholders",
    ]);
    assert.doesNotMatch(todoOf(markdown), /^- Nothing\.$/m);
    const foreign = dataOf({
      ...NOTHING_ELSE,
      errors: [PLACEHOLDERS_DA],
      findings: { packs: { placeholders: [placeholder('da', PLACEHOLDERS_DA)] } },
    });
    assert.deepEqual(foreign.data.todo, [
      "`packs/da/i18n/admin/da.json`: `secom.toast.reconciled`'s placeholders no longer match English's: wait for the home's fixed pack",
    ]);
  });

  it('a mixed red run loses no line and doubles none', () => {
    const { markdown, data } = dataOf(RED_RUNS[1].options);
    assert.deepEqual(data.todo, [
      '`packs/zz/i18n/admin/zz.json`: translate 1 new key: `harbour.title`',
      "`packs/zz/i18n/admin/zz.json`: `secom.toast.reconciled`'s placeholders no longer match English's: give the translation English's placeholders",
      SETTINGS,
    ]);
    assert.equal(data.failed, 3);
    assert.ok(!markdown.includes(PLACEHOLDERS), markdown);
    assert.ok(!markdown.includes(MISSING), markdown);
    assert.ok(todoOf(markdown).includes(`\n- ${SETTINGS}\n`), markdown);
  });

  it('lists a line no item accounts for as set-up printed it, once', () => {
    const { markdown, data } = dataOf(RED_RUNS[2].options);
    assert.deepEqual(data.todo, [SETTINGS]);
    assert.doesNotMatch(todoOf(markdown), /^- Nothing\.$/m);
    assert.match(todoOf(markdown), /^Set-up or the build fails until each is done\.$/m);
    const twice = dataOf({ ...NOTHING_ELSE, errors: [SETTINGS, SETTINGS] });
    assert.deepEqual(twice.data.todo, [SETTINGS]);
    // A finding without its line accounts for nothing: the line is listed as well.
    const bare = dataOf({
      ...NOTHING_ELSE,
      errors: [MISSING],
      findings: { packs: { missing: [{ lang: 'zz', app: 'admin', keys: ['harbour.title'] }] } },
    });
    assert.deepEqual(bare.data.todo, [
      '`packs/zz/i18n/admin/zz.json`: translate 1 new key: `harbour.title`',
      MISSING,
    ]);
  });

  it("an override's diff item accounts its changed and security lines; a seam line is listed as it is", () => {
    const { data } = dataOf(RED_RUNS[3].options);
    assert.ok(
      data.todo.some((item) => item.includes(`the base product changed \`${FOO}.html\``)),
      data.todo.join('\n'),
    );
    assert.ok(data.todo.some((item) => item.includes('the security fix of 4.0.1 changed')));
    assert.ok(data.todo.includes(SEAM), data.todo.join('\n'));
    assert.ok(!data.todo.includes(CHANGED));
    assert.ok(!data.todo.includes(SECURITY_FIX));
    // A security line of another fix is no item's.
    const other = `override ${FOO}.ts: the security fix of 4.0.2 changed ${FOO}.html`;
    const unmatched = dataOf({
      errors: [other],
      findings: {
        overrides: [
          {
            file: `${FOO}.ts`,
            kind: 'security',
            version: '4.0.2',
            changed: `${FOO}.html`,
            error: other,
          },
        ],
      },
    });
    assert.ok(unmatched.data.todo.includes(other), unmatched.data.todo.join('\n'));
  });

  it("a branding item accounts the branding check's line", () => {
    const { data } = dataOf(RED_RUNS[4].options);
    assert.ok(
      data.todo.includes(
        '`admin/assets/branding/zz.json`: add `footer.motto`, which the branding schema requires',
      ),
    );
    assert.ok(!data.todo.includes(BRANDING_KEY));
  });

  it('the withdrawn and stub items account their lines', () => {
    const { data } = dataOf(RED_RUNS[5].options);
    assert.equal(data.todo.length, 2);
    assert.ok(data.todo[0].startsWith('`niord-pins.lock.json`: the pinned pack da, '));
    assert.ok(data.todo[1].startsWith('`setup.mjs`: the base product changed the stub: '));
    assert.equal(data.stubChanged, true);
  });
});

describe('the upgrade report: To look at', () => {
  const look = (findings) => sectionOf(report({ findings }), 'To look at');

  it('English changed under a translation', () => {
    assert.match(
      look({ packs: { changedEnglish: [{ lang: 'zz', app: 'admin', key: 'menu.close' }] } }),
      /^- `packs\/zz\/i18n\/admin\/zz\.json`: English changed under `menu\.close`: check the translation, then accept it/m,
    );
  });

  it('a redundant text override, one that differs, one written against another English text', () => {
    const text = look({
      textOverrides: {
        redundant: [{ lang: 'en', app: 'admin', key: 'menu.close' }],
        differing: [
          { lang: 'en', app: 'admin', key: 'a' },
          { lang: 'en', app: 'info', key: 'B' },
        ],
        stale: [{ lang: 'en', app: 'admin', key: 'c', fp: '0123456789abcdef' }],
      },
    });
    assert.match(
      text,
      /^- `text-overrides\/en\.json`: admin `menu\.close` equals the pack's text: remove the override$/m,
    );
    assert.match(
      text,
      /^- `text-overrides\/en\.json`: 2 overrides differ from the pack's text, `admin a`, `info B`/m,
    );
    assert.match(
      text,
      /^- `text-overrides\/en\.json`: admin `c` was written against another English text: .*0123456789abcdef$/m,
    );
  });

  it('a deprecated export the country uses, with its removal version or none scheduled', () => {
    const use = {
      file: 'admin/app/country.routes.ts',
      name: 'roleGuard',
      specifier: '@niord/admin/api',
      since: '4.0.1',
      instead: 'use roleGuard without arguments',
    };
    const text = look({
      deprecated: [
        { ...use, removedIn: '4.0.3' },
        { ...use, file: 'admin/app/x.ts' },
      ],
    });
    assert.match(
      text,
      /^- `admin\/app\/country\.routes\.ts` uses `roleGuard` from `@niord\/admin\/api`, deprecated since 4\.0\.1, removed in 4\.0\.3: use roleGuard without arguments$/m,
    );
    assert.match(
      text,
      /^- `admin\/app\/x\.ts` uses `roleGuard` .*, deprecated since 4\.0\.1, removal not scheduled: /m,
    );
  });

  it('an escape-hatch entry that can go', () => {
    assert.match(
      look({
        packs: { hatchStale: [{ lang: 'zz', app: 'info', key: 'BTN_CLOSE', why: 'translated' }] },
      }),
      /^- `missing-keys-accepted\.json`: zz info `BTN_CLOSE`: the pack now translates it; remove the entry$/m,
    );
  });

  it('has nothing to look at without findings and without a new API-CHANGES.md action', () => {
    const text = sectionOf(report({ current: { ...PREVIOUS }, fe: tree({}) }), 'To look at');
    assert.equal(text.trim(), '- Nothing.');
  });
});

describe('the upgrade report: New for you', () => {
  it('names an added hook and API export, and the override the hook may replace', () => {
    const text = sectionOf(report(), 'New for you');
    assert.match(
      text,
      /^- The hook `admin\.foo\.renderers` \(admin\), `FOO_RENDERERS` in `.*foo\.ts`: How foo is shown\./m,
    );
    assert.match(
      text,
      /^- `@niord\/admin\/api` exports `FOO_RENDERERS` \(`ui-apps\/admin\/src\/public-api\.ts`/m,
    );
    assert.match(
      text,
      new RegExp(
        `^- \`${WITH}\`: the new hook \`admin\\.foo\\.renderers\` is in \`${FOO}\\.ts\`, which the override is based on: if it covers the change, provide \`FOO_RENDERERS\` in \`admin/app/country\\.providers\\.ts\` and remove the override$`,
        'm',
      ),
    );
  });

  it('says when nothing was added', () => {
    assert.equal(
      sectionOf(report({ current: PREVIOUS }), 'New for you').trim(),
      '- No extension point and no API export was added.',
    );
  });
});

describe('the upgrade report: Dependency changes', () => {
  it('lists the framework first, then the other packages, and the country packages a move touches', () => {
    const text = sectionOf(
      report({
        findings: {
          dependencies: {
            added: [{}],
            peers: [
              {
                name: 'ngx-harbour',
                peer: '@angular/core',
                range: '^22.1.0',
                version: '22.2.0',
                rejected: false,
              },
            ],
          },
        },
      }),
      'Dependency changes',
    );
    const lines = text.trim().split('\n');
    assert.deepEqual(lines.slice(0, 3), [
      '- Framework: `@angular/common`, `@angular/core` 22.1.0 → 22.2.0.',
      '- `pretty-print` added at 1.0.0.',
      '- `rxjs` 7.8.1 → 7.8.2.',
    ]);
    assert.match(
      text,
      /^- `country\/package\.json`: `ngx-harbour` has the peer `@angular\/core \^22\.1\.0`, now 22\.2\.0: satisfied\.$/m,
    );
    assert.doesNotMatch(text, /vitest/);
  });

  it('says when the country has no packages', () => {
    assert.match(sectionOf(report(), 'Dependency changes'), /^- Country packages: none\.$/m);
  });
});

describe('the upgrade report: what is not available', () => {
  it("without the previous version's files, says why once per section that needs them", () => {
    const why = ".niord/niord-fe-src-4.0.0.zip, where the stub's fetch leaves it, is not there";
    const text = report({ previous: { version: '4.0.0', unavailable: why } });
    assert.match(
      sectionOf(text, 'To do'),
      /since the recorded fingerprint \(not available, below\)/,
    );
    assert.equal(
      sectionOf(text, 'To do').split(`not available: ${why}`).length - 1,
      1,
      'To do names the reason once',
    );
    assert.equal(sectionOf(text, 'New for you').trim(), `Not available: ${why}.`);
    assert.equal(sectionOf(text, 'Dependency changes').trim(), `Not available: ${why}.`);
  });

  it("names the reason a changed file's diff is missing when the previous file is not the recorded one", () => {
    const files = zipped({ ...PREVIOUS, [`${FOO}.html`]: '<p>another</p>\n' });
    assert.match(
      sectionOf(report({ previous: { version: '4.0.0', files } }), 'To do'),
      /since the recorded fingerprint \(not available: the recorded fingerprint is not the previous version's file \(4\.0\.0\)\)/,
    );
  });
});

describe('the upgrade report: the kit package', () => {
  /** The current version in the package layout: the kit at <root>/.niord/kit with its marker. */
  const packageLayout = () => {
    const kit = path.join(temp(), '.niord', 'kit');
    for (const [name, text] of Object.entries({
      ...kitOf(CURRENT),
      'kit.json': markerText('4.0.1'),
    })) {
      fs.mkdirSync(path.dirname(path.join(kit, name)), { recursive: true });
      fs.writeFileSync(path.join(kit, name), text);
    }
    return { base: tree(withoutKit(CURRENT)), kit };
  };

  it("reads the kit's files now from the kit, not from the base product", () => {
    const text = report({ folders: packageLayout() });
    assert.match(
      sectionOf(text, 'Versions'),
      /`\.niord\/kit\/API-CHANGES\.md`, sections new since 4\.0\.0: `4\.0\.1: roleGuard takes its roles from data`, `4\.0\.1: a renderer hook for foo`\./,
    );
    assert.match(
      sectionOf(text, 'Versions'),
      /fixes new since 4\.0\.0: 4\.0\.1 \(escapes the bar\)\./,
    );
    assert.match(
      sectionOf(text, 'To do'),
      /^- `admin\/assets\/branding\/zz\.json`: add `footer\.motto`, which the branding schema requires$/m,
    );
    assert.match(sectionOf(text, 'New for you'), /^- The hook `admin\.foo\.renderers` \(admin\)/m);
  });

  it("names the kit package's stub template when the stub changed", () => {
    const text = sectionOf(report({ folders: packageLayout(), findings: { stub: true } }), 'To do');
    assert.ok(
      text.includes(
        '- `setup.mjs`: the base product changed the stub: copy `../.niord/kit/templates/setup.mjs` over it (`cp ../.niord/kit/templates/setup.mjs setup.mjs`)',
      ),
      text,
    );
    assert.doesNotMatch(text, /\.niord\/base\/country-kit/);
  });

  it("gives the stub template relative to the country's fe/", () => {
    assert.equal(STUB_TEMPLATE, '../.niord/kit/templates/setup.mjs');
  });

  it('requires the folder the kit is read from', () => {
    assert.throws(
      () => upgradeReportData(inputOf({ folders: { base: tree(withoutKit(CURRENT)) } })),
      /current\.kit/,
    );
  });
});

/** The report data's keys, in their order: what a workflow reading the data relies on. */
const DATA_KEYS = [
  'format',
  'country',
  'from',
  'to',
  'failed',
  'sections',
  'fixes',
  'todo',
  'look',
  'stubChanged',
  'templatesChanged',
  'templatesUnavailable',
  'otherTemplatesChanged',
  'missingForeign',
  'notChecked',
];

describe('the upgrade report as data', () => {
  const FINDINGS = {
    packs: {
      missing: [
        { lang: 'zz', app: 'admin', keys: ['harbour.title'] },
        { lang: 'da', app: 'admin', keys: ['harbour.title', 'harbour.notes'] },
      ],
      changedEnglish: [{ lang: 'zz', app: 'admin', key: 'menu.close' }],
    },
    stub: true,
  };

  it('gives the JSON and the Markdown from the same findings', () => {
    const input = inputOf({ findings: FINDINGS });
    const { markdown, data } = upgradeReportData(input);
    assert.equal(markdown, upgradeReport(input));
    assert.deepEqual(Object.keys(data), DATA_KEYS);
    assert.equal(data.format, 1);
    assert.equal(data.country, 'xx');
    assert.equal(data.from, '4.0.0');
    assert.equal(data.to, '4.0.1');
    assert.equal(data.failed, 0);
    assert.equal(data.stubChanged, true);

    const todo = sectionOf(markdown, 'To do');
    assert.ok(data.todo.length > 0);
    for (const item of data.todo) assert.ok(todo.includes(`\n- ${item}\n`), item);
    assert.ok(data.todo.some((item) => item.startsWith('`setup.mjs`: the base product changed')));
    const look = sectionOf(markdown, 'To look at');
    assert.ok(data.look.length > 0);
    for (const item of data.look) assert.ok(look.includes(`\n- ${item}\n`), item);
    const versions = sectionOf(markdown, 'Versions');
    assert.deepEqual(data.sections, [
      {
        heading: '4.0.1: roleGuard takes its roles from data',
        entries: null,
        actions: ['replace `roleGuard(roles)` by `roleGuard`.'],
      },
      { heading: '4.0.1: a renderer hook for foo', entries: null, actions: [] },
    ]);
    const asked = look.slice(look.indexOf('asks of a country'));
    for (const section of data.sections) {
      assert.ok(versions.includes(`\`${section.heading}\``), section.heading);
      for (const action of section.actions) assert.ok(asked.includes(action), action);
    }
    assert.deepEqual(data.fixes, [{ version: '4.0.1', summary: 'escapes the bar' }]);
    for (const fix of data.fixes) assert.ok(versions.includes(`${fix.version} (${fix.summary})`));
    assert.equal(data.notChecked.length, 2);
    for (const line of data.notChecked) assert.ok(todo.includes(`\n- ${line}\n`), line);
    assert.deepEqual(data.missingForeign, [
      { lang: 'da', keys: ['harbour.title', 'harbour.notes'] },
    ]);
    assert.deepEqual(data.templatesChanged, []);
    assert.equal(data.templatesUnavailable, null);
    assert.deepEqual(data.otherTemplatesChanged, []);

    assert.equal(dataOf({ errors: ['x', 'y'] }).data.failed, 2);

    // Every failed check reaches To do: each line is an item's or an item itself, never both.
    for (const run of RED_RUNS) {
      const red = dataOf(run.options);
      assert.ok(red.data.todo.length > 0, run.name);
      assert.doesNotMatch(sectionOf(red.markdown, 'To do'), /^- Nothing\.$/m, run.name);
      for (const line of run.options.errors)
        assert.equal(
          red.data.todo.includes(line),
          !run.accounted.includes(line),
          `${run.name}: ${line}`,
        );
    }
  });

  it('gives an action in full, in the JSON and the Markdown', () => {
    const long = `run ${'a long step, '.repeat(40)}done.`;
    const current = {
      ...CURRENT,
      'country-kit/API-CHANGES.md': changes(section('4.0.1', 'x', long)),
    };
    const { markdown, data } = dataOf({ current });
    assert.deepEqual(data.sections[0].actions, [long]);
    assert.ok(markdown.includes(long));
    assert.ok(!markdown.includes('…'));
  });

  it("lists a foreign pack's missing keys under missingForeign, the home pack's left out", () => {
    const { data } = dataOf({
      findings: {
        packs: {
          missing: [
            { lang: 'zz', app: 'admin', keys: ['k1'] },
            { lang: 'da', app: 'admin', keys: ['k1', 'k2'] },
            { lang: 'da', app: 'backend', bundle: 'messages', keys: ['k2', 'k3'] },
          ],
        },
      },
    });
    assert.deepEqual(data.missingForeign, [{ lang: 'da', keys: ['k1', 'k2', 'k3'] }]);
    assert.deepEqual(dataOf().data.missingForeign, []);
  });

  it('writes both files on a version change, also when a check failed', () => {
    const fe = country();
    const input = inputOf({ errors: ['x'], fe });
    const result = writeUpgradeReport(input);
    assert.equal(REPORT, '.niord/niord-upgrade-report.md');
    assert.equal(REPORT_DATA, '.niord/niord-upgrade-report.json');
    assert.equal(fs.readFileSync(path.join(fe, REPORT), 'utf8'), result.markdown);
    const text = fs.readFileSync(path.join(fe, REPORT_DATA), 'utf8');
    assert.equal(text, `${JSON.stringify(result.data, null, 2)}\n`);
    assert.deepEqual(JSON.parse(text), result.data);
    assert.equal(result.data.failed, 1);
    assert.match(result.markdown, /^Set-up of 4\.0\.1 failed: 1 check /m);
  });
});

describe('the upgrade report: the templates that changed', () => {
  /**
   * The data of an upgrade from 4.0.0, set up from its niord-fe-src zip and kit zip, the kit zip
   * holding `before` beside the previous kit's files, to 4.0.1 with its kit holding `now`.
   */
  const upgradeOf = ({ before, now }) => {
    const root = temp();
    const fe = path.join(root, 'fe');
    fs.mkdirSync(path.join(fe, path.dirname(RECORD)), { recursive: true });
    fs.mkdirSync(path.join(root, '.niord'), { recursive: true });
    const zip = zipBytes(withoutKit(PREVIOUS));
    const kitZip = zipBytes({ ...kitOf(PREVIOUS), 'kit.json': markerText('4.0.0'), ...before });
    fs.writeFileSync(path.join(fe, zipOf('4.0.0')), zip);
    fs.writeFileSync(path.join(root, kitZipOf('4.0.0')), kitZip);
    const record = {
      base: { version: '4.0.0', sha256: sha256Of(zip), kit: { sha256: sha256Of(kitZip) } },
    };
    fs.writeFileSync(path.join(fe, RECORD), json(record));
    const previous = previousBase({ fe, record: readRecord(fe) });
    assert.equal(previous.unavailable, undefined);
    assert.equal(previous.kitUnavailable, undefined);
    const kit = path.join(temp(), '.niord', 'kit');
    for (const [name, text] of Object.entries({
      ...kitOf(CURRENT),
      'kit.json': markerText('4.0.1'),
      ...now,
    })) {
      fs.mkdirSync(path.dirname(path.join(kit, name)), { recursive: true });
      fs.writeFileSync(path.join(kit, name), text);
    }
    return dataOf({ previous, folders: { base: tree(withoutKit(CURRENT)), kit } });
  };

  it('lists a changed templates/AGENTS.md between two kit zips, with the merge it asks for', () => {
    const { markdown, data } = upgradeOf({
      before: { 'templates/AGENTS.md': 'old\n', 'templates/pack.yml': 'same\n' },
      now: { 'templates/AGENTS.md': 'new\n', 'templates/pack.yml': 'same\n' },
    });
    const line =
      'Templates changed since 4.0.0, from the repository root: `AGENTS.md` (merge `.niord/kit/templates/AGENTS.md` into `AGENTS.md`)';
    assert.deepEqual(data.templatesChanged, ['AGENTS.md']);
    assert.equal(data.templatesUnavailable, null);
    assert.equal(data.look.at(-1), line);
    assert.ok(sectionOf(markdown, 'To look at').includes(`\n- ${line}\n`), markdown);
  });

  it('lists a template the kit added and one it dropped, in a fixed order', () => {
    const { data } = upgradeOf({
      before: { 'templates/start-local.sh': 'x\n', 'templates/AGENTS.md': 'same\n' },
      now: { 'templates/base-bump.yml': 'y\n', 'templates/AGENTS.md': 'same\n' },
    });
    assert.deepEqual(data.templatesChanged, ['base-bump.yml', 'start-local.sh']);
    assert.equal(
      data.look.at(-1),
      'Templates changed since 4.0.0, from the repository root: `base-bump.yml` (copy `.niord/kit/templates/base-bump.yml` over `.github/workflows/base-bump.yml`), `start-local.sh` (the kit no longer has it: see `.niord/kit/API-CHANGES.md`)',
    );
  });

  it('names the retired templates/renovate.json only in the line of the other templates', () => {
    const { data } = upgradeOf({
      before: { 'templates/renovate.json': '{}\n', 'templates/AGENTS.md': 'same\n' },
      now: { 'templates/AGENTS.md': 'same\n' },
    });
    assert.deepEqual(data.templatesChanged, []);
    for (const item of data.look) assert.doesNotMatch(item, /renovate/i);
    assert.deepEqual(data.otherTemplatesChanged, ['renovate.json']);
  });

  it('names every other changed template in one line, not an item', () => {
    const { markdown, data } = upgradeOf({
      before: {
        'templates/AGENTS.md': 'old\n',
        'templates/CODEOWNERS': 'a\n',
        'templates/dependabot.yml': 'same\n',
        'templates/renovate.json': '{}\n',
        'templates/setup.mjs': 'old stub\n',
      },
      now: {
        'templates/AGENTS.md': 'new\n',
        'templates/CODEOWNERS': 'b\n',
        'templates/dependabot.yml': 'same\n',
        'templates/be/pom.xml.tmpl': '<project/>\n',
        'templates/setup.mjs': 'new stub\n',
      },
    });
    const line =
      'Other templates changed since 4.0.0, in `.niord/kit/templates/`: `CODEOWNERS`, `be/pom.xml.tmpl`, `renovate.json` (no action: a country that keeps its own copy compares it).';
    assert.deepEqual(data.templatesChanged, ['AGENTS.md']);
    // Changed, added (nested) and removed; the tracked one, the stub and the equal one left out.
    assert.deepEqual(data.otherTemplatesChanged, [
      'CODEOWNERS',
      'be/pom.xml.tmpl',
      'renovate.json',
    ]);
    assert.ok(!data.look.includes(line));
    for (const item of data.look) assert.doesNotMatch(item, /Other templates changed/);
    assert.match(data.look.at(-1), /^Templates changed since 4\.0\.0, from the repository root: /);
    const look = sectionOf(markdown, 'To look at');
    assert.ok(look.includes(`\n\n${line}\n`), markdown);
    assert.ok(!look.includes(`- ${line}`), markdown);
  });

  it('keys a template two folders deep by its path from templates/', () => {
    const { data } = upgradeOf({
      before: { 'templates/be/deeper/x.txt': '1\n' },
      now: { 'templates/be/deeper/x.txt': '2\n' },
    });
    assert.deepEqual(data.otherTemplatesChanged, ['be/deeper/x.txt']);
  });

  it('names none of the eight tracked templates in the line of the other templates', () => {
    const eight = [
      'AGENTS.md',
      'base-bump.yml',
      'ci.yml',
      'image.yml',
      'image-backend-job.yml',
      'image-frontend-job.yml',
      'pack.yml',
      'start-local.sh',
    ];
    const of = (text) => Object.fromEntries(eight.map((name) => [`templates/${name}`, text]));
    const { markdown, data } = upgradeOf({ before: of('old\n'), now: of('new\n') });
    assert.equal(data.templatesChanged.length, 8);
    assert.deepEqual(data.otherTemplatesChanged, []);
    assert.doesNotMatch(markdown, /Other templates changed/);
  });

  it('names the part of image.yml a changed job template is merged into', () => {
    const { data } = upgradeOf({
      before: {
        'templates/image-backend-job.yml': 'old\n',
        'templates/image-frontend-job.yml': 'a\n',
      },
      now: {
        'templates/image-backend-job.yml': 'new\n',
        'templates/image-frontend-job.yml': 'b\n',
      },
    });
    assert.deepEqual(data.templatesChanged, ['image-backend-job.yml', 'image-frontend-job.yml']);
    assert.equal(
      data.look.at(-1),
      'Templates changed since 4.0.0, from the repository root: `image-backend-job.yml` (merge `.niord/kit/templates/image-backend-job.yml` into the backend job of `.github/workflows/image.yml`), `image-frontend-job.yml` (merge `.niord/kit/templates/image-frontend-job.yml` into the frontend job of `.github/workflows/image.yml`)',
    );
  });

  const CI_LINE =
    'Templates changed since 4.0.0, from the repository root: `ci.yml` (merge `.niord/kit/templates/ci.yml` into `.github/workflows/ci.yml`)';

  it('lists a changed templates/ci.yml with the merge it asks for', () => {
    const { markdown, data } = upgradeOf({
      before: { 'templates/ci.yml': 'old\n' },
      now: { 'templates/ci.yml': 'new\n' },
    });
    assert.deepEqual(data.templatesChanged, ['ci.yml']);
    assert.equal(data.look.at(-1), CI_LINE);
    assert.ok(sectionOf(markdown, 'To look at').includes(`\n- ${CI_LINE}\n`), markdown);
  });

  it('counts a ci.yml template new to the kit as changed', () => {
    // The kit that brings the template asks every country once to merge it into its own ci.yml.
    const { data } = upgradeOf({ before: {}, now: { 'templates/ci.yml': 'new\n' } });
    assert.deepEqual(data.templatesChanged, ['ci.yml']);
    assert.equal(data.look.at(-1), CI_LINE);
  });

  it('lists ci.yml between base-bump.yml and image.yml', () => {
    const { data } = upgradeOf({
      before: {
        'templates/image.yml': 'old\n',
        'templates/ci.yml': 'old\n',
        'templates/base-bump.yml': 'old\n',
      },
      now: {
        'templates/image.yml': 'new\n',
        'templates/ci.yml': 'new\n',
        'templates/base-bump.yml': 'new\n',
      },
    });
    assert.deepEqual(data.templatesChanged, ['base-bump.yml', 'ci.yml', 'image.yml']);
  });

  it('names no template when both copies are equal', () => {
    const { markdown, data } = upgradeOf({
      before: { 'templates/AGENTS.md': 'same\n', 'templates/pack.yml': 'same\n' },
      now: { 'templates/AGENTS.md': 'same\n', 'templates/pack.yml': 'same\n' },
    });
    assert.deepEqual(data.templatesChanged, []);
    assert.deepEqual(data.otherTemplatesChanged, []);
    assert.doesNotMatch(markdown, /Templates changed/);
    assert.doesNotMatch(markdown, /Other templates changed/);
    const nothing = dataOf({ current: { ...PREVIOUS }, fe: tree({}) });
    assert.deepEqual(nothing.data.templatesChanged, []);
    assert.deepEqual(nothing.data.otherTemplatesChanged, []);
    assert.deepEqual(nothing.data.look, []);
    assert.equal(sectionOf(nothing.markdown, 'To look at').trim(), '- Nothing.');
  });

  it("says why when the previous kit's files are not at hand", () => {
    const { markdown, data } = dataOf({
      previous: { version: '4.0.0', files: zipped(withoutKit(PREVIOUS)), kitUnavailable: 'why' },
    });
    const line =
      "Templates changed since 4.0.0: not available: why; compare each file of `.niord/kit/templates/` with the country's copy";
    assert.equal(data.templatesChanged, null);
    assert.equal(data.templatesUnavailable, 'why');
    assert.equal(data.otherTemplatesChanged, null);
    assert.equal(data.look.at(-1), line);
    assert.ok(sectionOf(markdown, 'To look at').includes(`\n- ${line}\n`), markdown);
    // The item above already asks for every file of the folder to be compared: no second line.
    assert.doesNotMatch(markdown, /Other templates changed/);
    const unavailable = dataOf({ previous: { version: '4.0.0', unavailable: 'no zip' } });
    assert.equal(unavailable.data.templatesChanged, null);
    assert.equal(unavailable.data.templatesUnavailable, 'no zip');
    assert.equal(unavailable.data.otherTemplatesChanged, null);
    assert.doesNotMatch(unavailable.markdown, /Other templates changed/);
  });
});

describe('the upgrade report without a set-up record: --previous', () => {
  /** A country's fe/ inside its root, with a set-up record of `version` when given. */
  const feOf = (version) => {
    const fe = path.join(temp(), 'fe');
    fs.mkdirSync(path.join(fe, path.dirname(RECORD)), { recursive: true });
    if (version) fs.writeFileSync(path.join(fe, RECORD), json({ base: { version, sha256: null } }));
    return fe;
  };

  it('takes --previous <version>, alone', () => {
    assert.deepEqual(parseArgs(['--previous', '4.0.97']), { previous: '4.0.97' });
    for (const args of [
      ['--previous'],
      ['--previous', 'latest'],
      ['--previous', '4.0.97', '--accept-override', 'x'],
      ['--previous', '4.0.97', '--previous', '4.0.98'],
    ])
      assert.throws(() => parseArgs(args), /--previous/, args.join(' '));
  });

  it('compares by version with --previous 4.0.97 and no record, its files not at hand', () => {
    const lines = [];
    const result = upgradeFrom({
      fe: feOf(),
      record: null,
      previous: '4.0.97',
      version: '4.0.110',
      log: (line) => lines.push(line),
    });
    assert.equal(result.upgrade, true);
    assert.equal(result.from, '4.0.97');
    assert.equal(result.previous.version, '4.0.97');
    assert.equal(result.previous.option, true);
    assert.match(result.previous.unavailable, /no set-up record/);
    assert.match(result.previous.unavailable, /--previous 4\.0\.97/);
    assert.deepEqual(lines, [
      'setup: no previous set-up record (.niord/build/niord-build-report.json); the upgrade report compares with --previous 4.0.97, by version',
    ]);

    const current = {
      ...CURRENT,
      'country-kit/API-CHANGES.md': changes(
        section('Unreleased', 'next', 'run a.'),
        section('4.0.99', 'x', 'run b.'),
        section('4.0.97', 'y', 'run c.'),
      ),
    };
    const { markdown, data } = dataOf({
      previous: result.previous,
      current,
      version: '4.0.110',
      errors: ['x'],
    });
    assert.deepEqual(
      data.sections.map((s) => s.heading),
      ['Unreleased: next', '4.0.99: x'],
    );
    assert.deepEqual(
      data.sections.map((s) => s.entries),
      [null, null],
    );
    assert.equal(data.from, '4.0.97');
    assert.equal(data.to, '4.0.110');
    assert.equal(data.templatesChanged, null);
    assert.equal(data.templatesUnavailable, result.previous.unavailable);
    assert.equal(data.otherTemplatesChanged, null);
    assert.match(
      markdown,
      /^Set-up of 4\.0\.110 failed: 1 check \(.*\)\. There is no set-up record; set-up writes one when it passes\.$/m,
    );
    assert.doesNotMatch(markdown, /stays recorded/);
  });

  it("refuses a --previous that is not the record's version; the record wins when they agree", () => {
    const fe = feOf('4.0.97');
    const record = readRecord(fe);
    assert.throws(
      () => upgradeFrom({ fe, record, previous: '4.0.98', version: '4.0.110' }),
      /--previous 4\.0\.98.*names 4\.0\.97/,
    );
    const agreed = upgradeFrom({ fe, record, previous: '4.0.97', version: '4.0.110' });
    assert.equal(agreed.upgrade, true);
    assert.equal(agreed.from, '4.0.97');
    assert.deepEqual(agreed.previous, previousBase({ fe, record }));
    assert.equal(agreed.previous.option, undefined);
    assert.deepEqual(upgradeFrom({ fe, record, version: '4.0.110' }), agreed);
  });

  it('without a record and without --previous there is no upgrade, as before', () => {
    const lines = [];
    const none = upgradeFrom({
      fe: feOf(),
      record: null,
      version: '4.0.110',
      log: (line) => lines.push(line),
    });
    assert.equal(none.upgrade, false);
    assert.equal(none.from, undefined);
    assert.equal(
      none.previous.unavailable,
      'there is no previous set-up record (.niord/build/niord-build-report.json)',
    );
    assert.deepEqual(lines, [
      'setup: no previous set-up record (.niord/build/niord-build-report.json), so no upgrade report',
    ]);
    const fe = feOf('4.0.110');
    const same = upgradeFrom({ fe, record: readRecord(fe), version: '4.0.110' });
    assert.equal(same.upgrade, false);
    assert.match(same.previous.unavailable, /^the last set-up was of this version, 4\.0\.110/);
    const named = [];
    const itself = upgradeFrom({
      fe: feOf(),
      record: null,
      previous: '4.0.110',
      version: '4.0.110',
      log: (line) => named.push(line),
    });
    assert.equal(itself.upgrade, false);
    assert.match(itself.previous.unavailable, /^--previous names this version, 4\.0\.110/);
    assert.match(named[0], /--previous names this version, so no upgrade report$/);
  });
});

describe('the upgrade report: Unreleased compared per entry', () => {
  const entry = (lead, text, action) =>
    `**${lead}** ${text}\n${action === undefined ? '' : `\nWhat a country must do: ${action}\n`}`;
  const unreleased = (...entries) => `## Unreleased\n\n${entries.join('\n')}`;
  const FIRST = section('4.0.0', 'the first API', 'nothing.');
  const A = entry('A.', 'text a.', 'run a.');
  const B = entry('B.', 'text b.', 'run b.');
  /** The data and Markdown of an upgrade whose API-CHANGES.md was `before` and is `now`. */
  const between = (before, now) =>
    dataOf({
      previous: {
        version: '4.0.0',
        files: zipped({ ...PREVIOUS, 'country-kit/API-CHANGES.md': changes(before, FIRST) }),
      },
      current: { ...PREVIOUS, 'country-kit/API-CHANGES.md': changes(now, FIRST) },
    });

  it('reads the entries of a section: a bold lead each, its paragraphs with it', () => {
    assert.deepEqual(
      changeEntries(
        '**A.** text a.\n\nWhat a country must do: run a.\n\n**B.** text b.\n\nWhat a country must do: run b.',
      ),
      [
        { lead: 'A.', text: '**A.** text a.\n\nWhat a country must do: run a.' },
        { lead: 'B.', text: '**B.** text b.\n\nWhat a country must do: run b.' },
      ],
    );
    // A bold "What a country must do" is no lead; what stands before the first lead is an entry.
    assert.deepEqual(
      changeEntries('Intro.\r\n\r\n**A.** a.\r\n\r\n**What a country must do:** run a.\r\n'),
      [
        { lead: '', text: 'Intro.' },
        { lead: 'A.', text: '**A.** a.\n\n**What a country must do:** run a.' },
      ],
    );
  });

  it('lists only the entry new since the previous copy, with its one action', () => {
    const { markdown, data } = between(
      unreleased(A),
      unreleased(A, entry('C.', 'text c.', 'run c.')),
    );
    assert.deepEqual(data.sections, [
      { heading: 'Unreleased', entries: ['C.'], actions: ['run c.'] },
    ]);
    assert.ok(
      sectionOf(markdown, 'Versions').includes(
        "`Unreleased` (entries new or changed since 4.0.0's copy: “C.”)",
      ),
      markdown,
    );
    const look = sectionOf(markdown, 'To look at');
    assert.ok(look.includes('- `.niord/kit/API-CHANGES.md`, `Unreleased`: run c.'), look);
    assert.doesNotMatch(look, /run a\./);
  });

  it('lists a changed entry again, and a re-wrap is no change', () => {
    const changed = between(
      unreleased(A, B),
      unreleased(A, entry('B.', 'text b, now changed.', 'run b.')),
    );
    assert.deepEqual(changed.data.sections, [
      { heading: 'Unreleased', entries: ['B.'], actions: ['run b.'] },
    ]);
    const rewrapped = between(
      unreleased(A, B),
      unreleased(A, '**B.**\ntext\nb.\n\nWhat a country must do:\nrun b.\n'),
    );
    assert.deepEqual(rewrapped.data.sections, []);
    assert.match(sectionOf(rewrapped.markdown, 'Versions'), /sections new since 4\.0\.0: none\./);
  });

  it('says to read an entry that names no action; Nothing is none', () => {
    const { data } = between(
      unreleased(A),
      unreleased(A, entry('X.', 'text x.'), entry('D.', 'text d.', 'Nothing.')),
    );
    assert.deepEqual(data.sections, [
      {
        heading: 'Unreleased',
        entries: ['X.', 'D.'],
        actions: ['the entry “X.” names no action: read it'],
      },
    ]);
  });

  it('keeps the whole-section rule when the previous copy has no Unreleased', () => {
    const { data } = between('', unreleased(A));
    assert.deepEqual(data.sections, [
      { heading: 'Unreleased', entries: null, actions: ['run a.'] },
    ]);
  });

  // An entry of API-CHANGES.md is wrapped at 100 columns, so the phrase can break at any of its spaces.
  it('reads a "What a country must do" that wraps across lines', () => {
    const { data, markdown } = between(
      unreleased(A),
      unreleased(
        A,
        '**W.** text w. What\na country must do: nothing.\n',
        '**V.** text v. What a\ncountry must\ndo: run v.\n',
      ),
    );
    assert.deepEqual(data.sections, [
      { heading: 'Unreleased', entries: ['W.', 'V.'], actions: ['run v.'] },
    ]);
    assert.doesNotMatch(markdown, /names no action/);
    const whole = between('', unreleased('**A.** text a. What\na country must do: run a.\n'));
    assert.deepEqual(whole.data.sections, [
      { heading: 'Unreleased', entries: null, actions: ['run a.'] },
    ]);
    assert.deepEqual(requiredActions({ body: 'The change. What a\ncountry must do: run x.' }), [
      'run x.',
    ]);
  });

  /** between() for a country coming from `version`, set up now at `to`. */
  const betweenFrom = (version, to, before, now) =>
    dataOf({
      previous: {
        version,
        files: zipped({ ...PREVIOUS, 'country-kit/API-CHANGES.md': changes(before, FIRST) }),
      },
      current: { ...PREVIOUS, 'country-kit/API-CHANGES.md': changes(now, FIRST) },
      version: to,
    });
  const C = entry('C.', 'text c.', 'run c.');
  const released = (heading, ...entries) => `## ${heading}\n\n${entries.join('\n')}`;

  // A country on 4.0.97 saw the kit-move entry under Unreleased; the publish renamed the section
  // after the version that published it, 4.0.97, which the country already has.
  it('lists no section of a version the country already has, even with a reworded lead', () => {
    for (const now of [A, entry('A, reworded.', 'text a.', 'run a.')]) {
      const { markdown, data } = betweenFrom(
        '4.0.97',
        '4.0.98',
        unreleased(A),
        `${unreleased()}\n${released('4.0.97: the base product lets go', now)}`,
      );
      assert.deepEqual(data.sections, []);
      assert.match(sectionOf(markdown, 'Versions'), /sections new since 4\.0\.97: none\./);
    }
  });

  it('lists a released section newer than the previous version by lead: only the leads the previous Unreleased lacked', () => {
    for (const now of [unreleased(), '']) {
      const expected = [{ heading: '4.0.1: next', entries: ['C.'], actions: ['run c.'] }];
      const { data, markdown } = between(
        unreleased(A, B),
        `${now}\n${released('4.0.1: next', A, B, C)}`,
      );
      assert.deepEqual(data.sections, expected);
      assert.ok(
        sectionOf(markdown, 'Versions').includes(
          "`4.0.1: next` (entries new or changed since 4.0.0's copy: “C.”)",
        ),
        markdown,
      );
      // An entry whose text changed but whose lead the previous Unreleased had is not listed.
      const edited = between(
        unreleased(A, B),
        `${now}\n${released('4.0.1: next', A, entry('B.', 'text b, put in the past.', 'run b.'), C)}`,
      );
      assert.deepEqual(edited.data.sections, expected);
    }
    // Every lead known: the section is left out.
    assert.deepEqual(between(unreleased(A, B), released('4.0.1: next', A, B)).data.sections, []);
  });

  it('newChangeSections: the version rule first, then the per-entry rule', () => {
    const text = changes(
      unreleased(A),
      section('4.0.1', 'next', 'run n.'),
      section('4.0.0', 'the first API', 'nothing at all.'),
    );
    // Without the previous copy, by version, each section whole.
    assert.deepEqual(
      newChangeSections(text, undefined, '4.0.0').map(({ heading, entries }) => ({
        heading,
        entries,
      })),
      [
        { heading: 'Unreleased', entries: null },
        { heading: '4.0.1: next', entries: null },
      ],
    );
    assert.deepEqual(newChangeSections(undefined, undefined, '4.0.0'), []);
    // A section of the previous version is never new, its body changed or not.
    const previous = changes(unreleased(A), FIRST);
    assert.deepEqual(
      newChangeSections(text, previous, '4.0.1').map((s) => s.heading),
      [],
    );
    // Unreleased against the previous Unreleased still lists a changed entry.
    const changed = newChangeSections(
      changes(unreleased(entry('A.', 'text a, now changed.', 'run a.'))),
      previous,
      '4.0.1',
    );
    assert.deepEqual(
      changed.map((s) => [s.heading, s.version, s.entries.map((e) => e.lead)]),
      [['Unreleased', 'Unreleased', ['A.']]],
    );
    assert.deepEqual(sectionActions(changed[0]), ['run a.']);
  });
});

describe("the previous version's files", () => {
  const PINNED = 'pinned: org.niord.web:niord-fe-src:4.0.0:zip sha256:…';
  /**
   * A country's fe/ inside its root, with a set-up record and, when given, the niord-fe-src zip in
   * fe/.niord/ and the kit zip in <root>/.niord/.
   */
  const workspace = ({ record, zip, kitZip }) => {
    const root = temp();
    const fe = path.join(root, 'fe');
    fs.mkdirSync(path.join(fe, '.niord'), { recursive: true });
    if (record !== undefined) {
      fs.mkdirSync(path.join(fe, path.dirname(RECORD)), { recursive: true });
      fs.writeFileSync(path.join(fe, RECORD), typeof record === 'string' ? record : json(record));
    }
    if (zip) fs.writeFileSync(path.join(fe, zipOf('4.0.0')), zip);
    if (kitZip) {
      fs.mkdirSync(path.join(root, '.niord'), { recursive: true });
      fs.writeFileSync(path.join(root, kitZipOf('4.0.0')), kitZip);
    }
    return fe;
  };
  const bytes = zipBytes(PREVIOUS);
  const sha256 = sha256Of(bytes);

  // The kit is its own zip, named relative to the kit with its marker. A country-kit/ entry the
  // niord-fe-src zip still has (4.0.109 and 4.0.111 carry two) is never read: here an
  // API-CHANGES.md without the 4.0.0 section, which would list that section as new if it were read.
  const STALE = changes();
  const postMove = zipBytes({ ...withoutKit(PREVIOUS), 'country-kit/API-CHANGES.md': STALE });
  const postMoveSha256 = sha256Of(postMove);
  const kitZip = zipBytes({ ...kitOf(PREVIOUS), 'kit.json': markerText('4.0.0') });
  const kitSha256 = sha256Of(kitZip);
  const postMoveRecord = (kit = { sha256: kitSha256 }) => ({
    base: { version: '4.0.0', sha256: postMoveSha256, kit },
  });
  const previousOf = (options) => {
    const fe = workspace(options);
    return previousBase({ fe, record: readRecord(fe) });
  };

  /** What the report says when it read the previous version's kit: content, not just no error. */
  const assertKitRead = (previous) => {
    const text = report({ previous });
    const versions = sectionOf(text, 'Versions');
    assert.match(
      versions,
      /`\.niord\/kit\/API-CHANGES\.md`, sections new since 4\.0\.0: `4\.0\.1: roleGuard takes its roles from data`, `4\.0\.1: a renderer hook for foo`\./,
    );
    assert.doesNotMatch(versions, /\(by version/);
    assert.doesNotMatch(versions, /`4\.0\.0: the first API`/);
    assert.match(versions, /fixes new since 4\.0\.0: 4\.0\.1 \(escapes the bar\)\./);
    assert.match(
      sectionOf(text, 'New for you'),
      /^- The hook `admin\.foo\.renderers` \(admin\), `FOO_RENDERERS`/m,
    );
  };

  it('reads the last set-up record, and none from a missing or malformed report', () => {
    const record = { base: { version: '4.0.0', sha256 }, provenance: PINNED };
    assert.deepEqual(readRecord(workspace({ record })), record);
    assert.equal(readRecord(workspace({})), null);
    assert.equal(readRecord(workspace({ record: '{' })), null);
    assert.equal(readRecord(workspace({ record: { base: { version: '../4' } } })), null);
  });

  it("reads the kit zip's hash from the record, and drops a malformed one", () => {
    const record = {
      base: { version: '4.0.0', sha256, kit: { sha256: kitSha256 } },
      provenance: PINNED,
    };
    const read = readRecord(workspace({ record }));
    assert.deepEqual(read, record);
    assert.deepEqual(Object.keys(read.base), ['version', 'sha256', 'kit']);
    for (const kit of [{ sha256: 'x' }, { sha256: 'D'.repeat(64) }, 'd'.repeat(64), null, {}])
      assert.deepEqual(
        readRecord(workspace({ record: { base: { version: '4.0.0', sha256, kit } } })),
        {
          base: { version: '4.0.0', sha256 },
          provenance: '',
        },
      );
  });

  it("a record without a kit zip: the niord-fe-src zip's country-kit/ entries are left out and the kit's files are not available", () => {
    const previous = previousOf({ record: { base: { version: '4.0.0', sha256 } }, zip: bytes });
    const why = 'the last set-up recorded no kit zip';
    assert.equal(previous.kitUnavailable, why);
    assert.equal(previous.unavailable, undefined);
    assert.deepEqual(
      [...previous.files.keys()].filter((name) => name.startsWith(KIT)),
      [],
    );
    assert.equal(previous.files.get(`${FOO}.html`).toString(), HTML_BEFORE);
    const versions = sectionOf(report({ previous }), 'Versions');
    assert.ok(
      versions.includes(`sections new since 4.0.0 (by version: ${why}): \`4.0.1: roleGuard`),
      versions,
    );
  });

  it("a version with the kit package: the kit's files are the kit zip's, not the stale copy", () => {
    const previous = previousOf({ record: postMoveRecord(), zip: postMove, kitZip });
    assert.equal(
      previous.files.get('country-kit/API-CHANGES.md').toString(),
      PREVIOUS['country-kit/API-CHANGES.md'],
    );
    assert.notEqual(previous.files.get('country-kit/API-CHANGES.md').toString(), STALE);
    assert.equal(previous.files.get('country-kit/kit.json').toString(), markerText('4.0.0'));
    assert.equal(previous.files.get(`${FOO}.html`).toString(), HTML_BEFORE);
    assert.equal(previous.unavailable, undefined);
    assert.equal(previous.kitUnavailable, undefined);
    assertKitRead(previous);
  });

  it('says when the kit zip the record names is missing, not the one verified, or unreadable', () => {
    const missing =
      "../.niord/niord-country-kit-4.0.0.zip, where the stub's fetch leaves it, is not there";
    const previous = previousOf({ record: postMoveRecord(), zip: postMove });
    assert.equal(previous.kitUnavailable, missing);
    assert.equal(previous.unavailable, undefined);
    // The stale copy in the niord-fe-src zip is not taken instead.
    assert.deepEqual(
      [...previous.files.keys()].filter((name) => name.startsWith(KIT)),
      [],
    );
    assert.equal(previous.files.get(`${FOO}.html`).toString(), HTML_BEFORE);
    const text = report({ previous });
    const versions = sectionOf(text, 'Versions');
    assert.ok(
      versions.includes(`sections new since 4.0.0 (by version: ${missing}): \`4.0.1: roleGuard`),
      versions,
    );
    assert.ok(
      versions.includes(`fixes new since 4.0.0 (by version: ${missing}): 4.0.1 (escapes the bar)`),
      versions,
    );
    assert.doesNotMatch(versions, /`4\.0\.0: the first API`/);
    assert.match(
      sectionOf(text, 'New for you'),
      new RegExp(
        `^- Extension points: not available: ${missing.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\.$`,
        'm',
      ),
    );

    assert.equal(
      previousOf({ record: postMoveRecord({ sha256: 'b'.repeat(64) }), zip: postMove, kitZip })
        .kitUnavailable,
      "../.niord/niord-country-kit-4.0.0.zip is not the kit zip the last set-up verified: its sha256 is not the record's",
    );
    const broken = Buffer.from('not a zip');
    assert.match(
      previousOf({
        record: postMoveRecord({ sha256: sha256Of(broken) }),
        zip: postMove,
        kitZip: broken,
      }).kitUnavailable,
      /^\.\.\/\.niord\/niord-country-kit-4\.0\.0\.zip cannot be read \(.+\)$/,
    );
  });

  it('does not read a kit zip the record names no hash of', () => {
    const unverified = zipBytes({
      ...kitOf(PREVIOUS),
      'API-CHANGES.md': STALE,
      'kit.json': markerText('4.0.0'),
    });
    const previous = previousOf({
      record: { base: { version: '4.0.0', sha256 } },
      zip: bytes,
      kitZip: unverified,
    });
    assert.equal(previous.kitUnavailable, 'the last set-up recorded no kit zip');
    assert.equal(previous.files.has('country-kit/API-CHANGES.md'), false);
    assert.equal(previous.files.has('country-kit/kit.json'), false);
  });

  it("gives the files of the zip the record's sha256 names", () => {
    const fe = workspace({ record: { base: { version: '4.0.0', sha256 } }, zip: bytes });
    const previous = previousBase({ fe, record: readRecord(fe) });
    assert.equal(previous.version, '4.0.0');
    assert.equal(previous.files.get(`${FOO}.html`).toString(), HTML_BEFORE);
  });

  it('is not available without the zip, from a local checkout, or for another zip', () => {
    const unavailable = (options) => {
      const fe = workspace(options);
      return previousBase({ fe, record: readRecord(fe) }).unavailable;
    };
    assert.match(
      unavailable({ record: { base: { version: '4.0.0', sha256 } } }),
      /^\.niord\/niord-fe-src-4\.0\.0\.zip, where the stub's fetch leaves it, is not there$/,
    );
    assert.match(
      unavailable({ record: { base: { version: '4.0.0', sha256: null } }, zip: bytes }),
      /made from a local checkout, NIORD_BASE, not from a zip/,
    );
    assert.match(
      unavailable({ record: { base: { version: '4.0.0', sha256: 'b'.repeat(64) } }, zip: bytes }),
      /is not the zip the last set-up verified: its sha256 is not the record's/,
    );
  });
});
