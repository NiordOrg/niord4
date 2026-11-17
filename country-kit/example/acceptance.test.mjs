import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { serializeCatalog } from '../catalog.mjs';
import { readApi } from '../lib/api.mjs';
import { readZip, writeZip } from '../lib/zip.mjs';
import { readAnswers } from '../lib/answers.mjs';
import { sha256, verifyKitZip, verifySourceZip } from '../source-zip.mjs';
import { fingerprint } from '../stage-languages.mjs';
import { STUB_CHANGED } from '../setup-kit.mjs';
import { KIT_BACKEND, kitBackendBytes, markerText, where } from '../lib/where.mjs';
import {
  addToHatch,
  BACKEND_BUILT,
  BACKEND_SKIPPED,
  bump,
  bumpParent,
  classifySetup,
  keptLines,
  sha256Sums,
  untouched,
  wrapperProblems,
  YY_ANSWERS,
  FIXTURE_KEY,
  FIXTURE_TEMPLATE,
  FIXTURE_TEXT,
  fixtureKitZip,
  fixtureZip,
  HATCH_REASON,
  dockerPort,
  pinnedManifest,
  REGISTRY_IMAGE,
  REGISTRY_PUBLISHED,
  REGISTRY_SKIPPED,
  registryName,
  REMOVED_IN,
  reportProblems,
  SKIPPED,
  stagedPackProblems,
  staleRegistries,
  stubSource,
  templateLine,
  upgradeSections,
  upgradeVerdict,
  writeStepSummary,
} from './acceptance.mjs';
import { manifestProblems } from '../lib/manifest.mjs';
import { packEntries } from '../lib/pack-archive.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const PUBLIC_API = 'ui-apps/admin/src/public-api.ts';
const OLD_CHANGES = [
  '# Country API changes',
  '',
  '## How an entry is written',
  '',
  '- **What a country must do**, concretely: the import to change.',
  '',
  '## Unreleased: first in the version PR A publishes',
  '',
  '**The country API is introduced.**',
  '',
  'What a country must do: import through the API, then run `node setup.mjs`.',
  '',
].join('\n');

/** A source zip of the files the fixture changes, at 4.0.0-example. */
function baseZip({ api } = {}) {
  const catalog = {
    format: 1,
    version: '4.0.0-example',
    apps: {
      admin: {
        'common.clear': { fp: fingerprint('Clear'), placeholders: [] },
        'common.retry': { fp: fingerprint('Retry'), placeholders: [] },
      },
      info: {},
    },
  };
  return writeZip([
    {
      name: 'languages/en/i18n/admin/en.json',
      data: Buffer.from(`${JSON.stringify({ common: { clear: 'Clear', retry: 'Retry' } })}\n`),
    },
    { name: 'catalog.json', data: Buffer.from(serializeCatalog(catalog)) },
    { name: FIXTURE_TEMPLATE, data: Buffer.from('<button type="button">{{ label }}</button>\n') },
    {
      name: PUBLIC_API,
      data: Buffer.from(
        api ??
          [
            '/**',
            ' * The administration app API.',
            ' */',
            '',
            '/**',
            ' * The guard every top-level country route carries.',
            ' */',
            "export { roleGuard } from './app/core/auth/guards/role.guard';",
            '',
          ].join('\r\n'),
      ),
    },
  ]);
}

/**
 * A kit package of 4.0.0-example: its API-CHANGES.md, the backend contract, its marker, and the
 * backend files the build adds (lib/where.mjs KIT_BACKEND), as placeholders with their line ends.
 */
function baseKitZip({ changes = OLD_CHANGES, marker = true } = {}) {
  const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return writeZip([
    { name: 'README.md', data: Buffer.from('# The country kit\n') },
    { name: 'API-CHANGES.md', data: Buffer.from(changes) },
    { name: 'docs/country-api.md', data: Buffer.from('# The backend contract\n') },
    ...(marker ? [{ name: 'kit.json', data: Buffer.from(markerText('4.0.0-example')) }] : []),
    ...Object.values(KIT_BACKEND).map(({ entry, eol }) => ({
      name: entry,
      data: eol === null ? PNG : Buffer.from(eol === 'crlf' ? 'x\r\n' : 'x\n'),
    })),
  ]);
}

const entriesOf = (zip) =>
  Object.fromEntries(readZip(zip).map(({ name, data }) => [name, data.toString('utf8')]));

describe('the fixture upgrade zip', () => {
  const before = baseZip();
  const next = fixtureZip(before);
  const files = entriesOf(next.zip);

  it('changes one English text and recomputes its catalog fingerprint, the version set', () => {
    assert.equal(JSON.parse(files['languages/en/i18n/admin/en.json']).common.retry, FIXTURE_TEXT);
    const catalog = JSON.parse(files['catalog.json']);
    assert.equal(catalog.version, '4.0.1-example');
    assert.equal(catalog.apps.admin[FIXTURE_KEY].fp, fingerprint(FIXTURE_TEXT));
    assert.equal(catalog.apps.admin['common.clear'].fp, fingerprint('Clear'));
  });

  it('appends a line to the overridden template', () => {
    assert.equal(
      files[FIXTURE_TEMPLATE],
      `<button type="button">{{ label }}</button>\n${templateLine('4.0.1-example')}\n`,
    );
  });

  it("deprecates roleGuard with its removal version, the file's line endings kept", () => {
    assert.match(
      files[PUBLIC_API],
      /\r\n \*\r\n \* Deprecated since 4\.0\.1-example, removed in 4\.0\.2-example: [^\r\n]+\r\n \*\/\r\nexport \{ roleGuard \}/,
    );
    const [roleGuard] = readApi(PUBLIC_API, files[PUBLIC_API]);
    assert.deepEqual(
      { name: roleGuard.name, since: roleGuard.deprecated?.since },
      { name: 'roleGuard', since: '4.0.1-example' },
    );
    assert.equal(roleGuard.deprecated.removedIn, REMOVED_IN);
  });

  it('is a source zip, and the same input gives the same bytes', () => {
    assert.deepEqual(verifySourceZip(next.zip), []);
    assert.deepEqual(fixtureZip(before), next);
    assert.deepEqual(
      Object.keys(files).sort(),
      Object.keys(entriesOf(before)).sort(),
      'no entry added or dropped',
    );
  });

  it('refuses a zip in which roleGuard is not where it should be', () => {
    assert.throws(
      () => fixtureZip(baseZip({ api: "export { roleGuard } from './x';\n" })),
      /has no roleGuard statement/,
    );
  });
});

describe('the fixture upgrade kit package', () => {
  const before = baseKitZip();
  const next = fixtureKitZip(before);
  const files = entriesOf(next.zip);

  it("names the fixture's version in its marker", () => {
    assert.equal(files['kit.json'], markerText('4.0.1-example'));
  });

  it('adds an API-CHANGES.md section of the version, above Unreleased, that asks nothing of a country', () => {
    const added = upgradeSections(files['API-CHANGES.md'], OLD_CHANGES, '4.0.0-example');
    assert.deepEqual(
      added.map(({ heading, action }) => ({ heading, action })),
      [{ heading: '4.0.1-example', action: false }],
    );
    assert.deepEqual(
      added[0].entries.map(({ lead }) => lead),
      ['`roleGuard` from `@niord/admin/api` is deprecated'],
    );
    assert.deepEqual(added[0].musts, []);
    assert.ok(
      files['API-CHANGES.md'].indexOf('## 4.0.1-example') <
        files['API-CHANGES.md'].indexOf('## Unreleased'),
      'the section stands above Unreleased',
    );
  });

  it('is a kit package, and the same input gives the same bytes', () => {
    assert.deepEqual(verifyKitZip(next.zip), []);
    assert.deepEqual(fixtureKitZip(before), next);
    assert.deepEqual(
      Object.keys(files),
      Object.keys(entriesOf(before)),
      'no entry added or dropped',
    );
    assert.equal(files['docs/country-api.md'], '# The backend contract\n');
  });

  it('refuses a kit package without its marker or without an Unreleased section', () => {
    assert.throws(() => fixtureKitZip(baseKitZip({ marker: false })), /has no kit\.json/);
    assert.throws(
      () => fixtureKitZip(baseKitZip({ changes: '# Country API changes\n' })),
      /API-CHANGES\.md has no Unreleased section/,
    );
  });
});

describe("the upgrade test's API-CHANGES.md sections", () => {
  const UNRELEASED = 'Unreleased: first in the version PR A publishes';

  it("reads each version section's answers, never a policy section; 'Nothing' asks for no action", () => {
    const sections = upgradeSections(
      `${OLD_CHANGES}\n## 4.0.2\n\nAn addition.\n\nWhat a country must do: Nothing.\n`,
      undefined,
      '4.0.1',
    );
    assert.deepEqual(
      sections.map(({ heading, action }) => [heading, action]),
      [
        [UNRELEASED, true],
        ['4.0.2', false],
      ],
    );
  });

  it('does not count a reworded policy section as new', () => {
    const reworded = OLD_CHANGES.replace(
      'concretely: the import to change.',
      'concretely: the import to change, the file to edit.',
    );
    assert.notEqual(reworded, OLD_CHANGES);
    assert.deepEqual(upgradeSections(reworded, OLD_CHANGES, '4.0.1'), []);
  });

  it('fails an upgrade whose only new section is a reworded policy section', () => {
    const reworded = OLD_CHANGES.replace(
      'concretely: the import to change.',
      'concretely: the import to change, the file to edit.',
    );
    const verdict = upgradeVerdict({
      failure: 'up: npm run build failed',
      sections: upgradeSections(reworded, OLD_CHANGES, '4.0.1'),
    });
    assert.equal(verdict.pass, false);
  });

  it('asks to read a section or an entry with no answer, which is no action', () => {
    const [section] = upgradeSections('## 4.0.3\n\nA note.\n', undefined, '4.0.1');
    assert.deepEqual(section.musts, ['the section names no action: read it']);
    assert.equal(section.action, false);
    const [entry] = upgradeSections(
      OLD_CHANGES.replace(`## ${UNRELEASED}\n`, `## ${UNRELEASED}\n\n**A note.**\n`),
      OLD_CHANGES,
      '4.0.1',
    );
    assert.deepEqual(entry.musts, ['the entry “A note.” names no action: read it']);
    assert.equal(entry.action, false);
  });

  it('counts a new heading and a changed Unreleased entry as new, an unchanged copy not', () => {
    const changed = OLD_CHANGES.replace('then run', 'then rerun');
    const sections = upgradeSections(
      `${changed}\n## 4.0.2\n\nWhat a country must do: nothing.\n`,
      OLD_CHANGES,
      '4.0.1',
    );
    assert.deepEqual(
      sections.map(({ heading }) => heading),
      [UNRELEASED, '4.0.2'],
    );
    assert.deepEqual(
      sections[0].entries.map(({ lead }) => lead),
      ['The country API is introduced.'],
    );
    assert.deepEqual(upgradeSections(OLD_CHANGES, OLD_CHANGES, '4.0.1'), []);
  });

  it('does not list a section of the previous version or an older one, with or without its copy', () => {
    const current = [
      OLD_CHANGES,
      '## 4.0.2',
      '',
      '**A new one.**',
      '',
      'What a country must do: run a.',
      '',
      '## 4.0.1',
      '',
      '**An old one.**',
      '',
      'What a country must do: run b.',
      '',
    ].join('\n');
    assert.deepEqual(
      upgradeSections(current, undefined, '4.0.1').map(({ heading }) => heading),
      [UNRELEASED, '4.0.2'],
    );
    assert.deepEqual(
      upgradeSections(current, OLD_CHANGES, '4.0.1').map(({ heading, musts }) => [heading, musts]),
      [['4.0.2', ['run a.']]],
    );
  });

  it('lists nothing of an Unreleased renamed to its version, a text reworded, and excuses no failure by its heading', () => {
    const renamed = OLD_CHANGES.replace(
      `## ${UNRELEASED}`,
      '## Unreleased\n\n## 4.0.2: the country API',
    ).replace('then run', 'then rerun');
    assert.notEqual(renamed, OLD_CHANGES);
    const sections = upgradeSections(renamed, OLD_CHANGES, '4.0.1');
    assert.deepEqual(sections, []);
    assert.equal(upgradeVerdict({ failure: 'up: npm run build failed', sections }).pass, false);
    // Without the previous copy the version rule alone lists it whole, as the report does.
    assert.deepEqual(
      upgradeSections(renamed, undefined, '4.0.1').map(({ heading, action }) => [heading, action]),
      [['4.0.2: the country API', true]],
    );
  });

  it('counts every version section new without a previous file', () => {
    assert.deepEqual(
      upgradeSections(OLD_CHANGES, undefined, '4.0.1').map(({ heading }) => heading),
      [UNRELEASED],
    );
  });
});

describe('the classification of a set-up after a bump', () => {
  const override = 'ui-apps/admin/src/app/x/x.ts';
  const changed = `setup: error: override ${override}: the base product changed ${override}, ui-apps/admin/src/app/x/x.html since the copy was based on it: compare admin/overrides/x/x.ts with the base product's, then run \`node setup.mjs --accept-override ${override}\``;

  it('reads an override whose base files changed as routine', () => {
    assert.deepEqual(classifySetup({ status: 1, output: `setup: ok\n${changed}\r\n` }), {
      errors: [changed.slice('setup: error: '.length)],
      routine: [override],
      stub: false,
      other: [],
    });
  });

  it("reads the stub the base product changed as routine: the routine action copies the kit's", () => {
    const output = [changed, `setup: error: ${STUB_CHANGED}`].join('\n');
    assert.deepEqual(classifySetup({ status: 1, output }), {
      errors: [changed.slice('setup: error: '.length), STUB_CHANGED],
      routine: [override],
      stub: true,
      other: [],
    });
  });

  it("reads a SECURITY line for the fixture's own template as routine, any other as not", () => {
    const own = `setup: error: SECURITY: 4.0.3 fixed ${FIXTURE_TEMPLATE} (escaped), which override x copies: merge the fix`;
    assert.deepEqual(classifySetup({ status: 1, output: own }).other, []);
    const another = own.replace(FIXTURE_TEMPLATE, 'ui-apps/admin/src/app/x/x.html');
    assert.equal(classifySetup({ status: 1, output: another }).other.length, 1);
  });

  it('reads every other error as not routine', () => {
    const lines = [
      `setup: error: override ${override}: the base product changed a.ts and removed b.html since the copy was based on it: …`,
      `setup: error: override ${override}: joined the protected list: replace it with an extension point`,
      'setup: error: SECURITY: 4.0.2 fixed a.ts (x), which override a.ts copies: merge the fix',
      'setup: error: pack check: zz: admin: 3 keys missing',
    ];
    const { routine, other } = classifySetup({ status: 1, output: lines.join('\n') });
    assert.deepEqual(routine, []);
    assert.equal(other.length, 4);
  });

  it('reads a set-up that failed before its checks as not routine', () => {
    assert.deepEqual(classifySetup({ status: 1, output: 'setup: Maven could not fetch' }).other, [
      'set-up failed before its checks (exit 1)',
    ]);
    assert.deepEqual(classifySetup({ status: 0, output: 'setup: done' }).other, []);
  });
});

describe("the upgrade test's escape hatch", () => {
  it('lists the backend keys the previous catalog has with markup and no English at hand', () => {
    const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-hatch-'));
    temporary.push(fe);
    const kept = { lang: 'zz', app: 'admin', key: 'k', reason: 'r', added: '2026-01-01' };
    fs.writeFileSync(path.join(fe, 'missing-keys-accepted.json'), JSON.stringify([kept]));
    const added = addToHatch(fe, [{ bundle: 'mail', key: 'mail.signature' }], '2026-10-22');
    const entry = {
      lang: 'zz',
      app: 'backend',
      bundle: 'mail',
      key: 'mail.signature',
      reason: HATCH_REASON,
      added: '2026-10-22',
    };
    assert.deepEqual(added, [entry]);
    assert.deepEqual(
      JSON.parse(fs.readFileSync(path.join(fe, 'missing-keys-accepted.json'), 'utf8')),
      [kept, entry],
    );
  });
});

describe('the verdict of the upgrade test', () => {
  const nothing = { heading: '4.0.2', musts: ['nothing.'], action: false };
  const acting = {
    heading: '4.0.3',
    musts: ['import roleGuard from @niord/admin/api'],
    action: true,
  };

  it('passes an upgrade with nothing left failing', () => {
    assert.deepEqual(upgradeVerdict({ sections: [] }), { pass: true, lines: [] });
  });

  it('fails a failure no new section asks a country to act on', () => {
    const verdict = upgradeVerdict({ failure: 'npm run build failed', sections: [nothing] });
    assert.equal(verdict.pass, false);
    assert.match(verdict.lines[0], /^npm run build failed, and no section of API-CHANGES\.md/);
  });

  it('passes a failure a new section asks a country to act on, naming it', () => {
    assert.deepEqual(upgradeVerdict({ failure: 'set-up failed', sections: [nothing, acting] }), {
      pass: true,
      lines: [
        'set-up failed, which API-CHANGES.md says a country must act on:',
        '  4.0.3: import roleGuard from @niord/admin/api',
      ],
    });
  });
});

describe('the upgrade report check', () => {
  const report = [
    '# Upgrade report: xx from 4.0.0-example to 4.0.1-example',
    '## Versions',
    '## To do',
    '## To look at',
    '## New for you',
    '## Dependency changes',
  ].join('\n');
  const versions = { country: 'xx', from: '4.0.0-example', to: '4.0.1-example' };

  it('passes the five headings in order under the title', () => {
    assert.deepEqual(reportProblems(report, versions), []);
  });

  it('fails a missing heading, one out of order and another title', () => {
    assert.deepEqual(reportProblems(report.replace('## New for you\n', ''), versions), [
      'it has no heading "## New for you"',
    ]);
    assert.deepEqual(
      reportProblems(
        report.replace('## To do\n## To look at', '## To look at\n## To do'),
        versions,
      ),
      ['"## To look at" is out of order'],
    );
    assert.match(reportProblems(report, { ...versions, from: '3.9' })[0], /has no line "# Upgrade/);
  });
});

describe("the upgrade test's stub copy and bump", () => {
  /** A country folder with the copies of the kit's stub named in `copies` (relative paths). */
  function country(copies = []) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-stub-'));
    temporary.push(root);
    for (const copy of copies) {
      fs.mkdirSync(path.dirname(path.join(root, copy)), { recursive: true });
      fs.writeFileSync(path.join(root, copy), `// ${copy}\n`);
    }
    return root;
  }
  const KIT_COPY = '.niord/kit/templates/setup.mjs';

  it('takes the stub from the unpacked kit package', () => {
    const root = country([KIT_COPY]);
    assert.equal(stubSource(root), path.join(root, ...KIT_COPY.split('/')));
  });

  it('throws naming the place when the kit package holds no stub', () => {
    const none = country();
    assert.throws(
      () => stubSource(none),
      (error) =>
        error.message === `the kit's stub is not at ${path.join(none, ...KIT_COPY.split('/'))}`,
    );
  });

  it('pins the kit package in the lock when its hash is given, and leaves it out when not', () => {
    const root = country();
    fs.mkdirSync(path.join(root, 'be'));
    fs.mkdirSync(path.join(root, 'fe'));
    fs.writeFileSync(
      path.join(root, 'be', 'pom.xml'),
      '<project><parent><version>4.0.97</version></parent></project>',
    );
    const lock = () =>
      JSON.parse(fs.readFileSync(path.join(root, 'fe', 'niord-pins.lock.json'), 'utf8'));
    bump(root, '4.0.97', 'a'.repeat(64));
    assert.deepEqual(lock(), {
      format: 1,
      base: { version: '4.0.97', sha256: 'a'.repeat(64) },
      packs: {},
    });
    bump(root, '4.0.98', 'b'.repeat(64), 'c'.repeat(64));
    assert.deepEqual(lock(), {
      format: 1,
      base: { version: '4.0.98', sha256: 'b'.repeat(64) },
      kit: { sha256: 'c'.repeat(64) },
      packs: {},
    });
    assert.match(fs.readFileSync(path.join(root, 'be', 'pom.xml'), 'utf8'), /4\.0\.98/);
  });
});

describe('the job summary and the pom bump', () => {
  it('appends the skipped upgrade test to $GITHUB_STEP_SUMMARY, and nothing without it', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-summary-'));
    temporary.push(dir);
    const file = path.join(dir, 'summary.md');
    fs.writeFileSync(file, '## Frontend CI\n');
    assert.equal(
      writeStepSummary(['example: fixture upgrade ok', SKIPPED], { GITHUB_STEP_SUMMARY: file }),
      true,
    );
    assert.equal(
      fs.readFileSync(file, 'utf8'),
      `## Frontend CI\n\nexample: fixture upgrade ok\n\n${SKIPPED}\n`,
    );
    assert.match(
      SKIPPED,
      /^example: upgrade test: skipped — no previous niord-fe-src was given \(--upgrade-from; Frontend CI takes the latest release's\)/,
    );
    assert.equal(writeStepSummary([SKIPPED], {}), false);
  });

  it("moves the parent's version and refuses a pom without a parent", () => {
    const pom =
      '<project><!-- 1.0 --><parent><groupId>org.niord</groupId><version>4.0.0-example</version></parent><version>9</version></project>';
    assert.equal(bumpParent(pom, '4.0.1-example'), pom.replace('4.0.0-example', '4.0.1-example'));
    assert.throws(() => bumpParent('<project><version>1</version></project>', '2'), /no <parent>/);
  });
});

describe('publishing and pinning zz in the harness', () => {
  const EXAMPLE = path.resolve(import.meta.dirname);

  it("reads the registry's 127.0.0.1 port from docker port, and refuses any other binding", () => {
    assert.equal(dockerPort('127.0.0.1:49153\n'), 49153);
    assert.equal(dockerPort('127.0.0.1:5001\r\n'), 5001);
    for (const output of ['0.0.0.0:49153\n', '', '127.0.0.1:1\n127.0.0.1:2\n', '[::]:49153\n'])
      assert.throws(() => dockerPort(output), /no one 127\.0\.0\.1 binding/, output);
  });

  it('names the registry container by the run and says why the step is skipped without Docker', () => {
    assert.equal(registryName(1234), 'niord-example-registry-1234');
    assert.match(REGISTRY_SKIPPED, /^example: publishing and pinning zz: skipped — .*\(--image\)/);
  });

  it("says what the step ran in a line verify-local's row tells from the skip line", () => {
    // verify-local: grep -E '^example: (zz published|publishing and pinning zz: skipped)'
    const row = /^example: (zz published|publishing and pinning zz: skipped)/;
    assert.match(REGISTRY_PUBLISHED, row);
    assert.match(REGISTRY_SKIPPED, row);
    assert.equal(row.exec(REGISTRY_PUBLISHED)[1], 'zz published');
    assert.equal(row.exec(REGISTRY_SKIPPED)[1], 'publishing and pinning zz: skipped');
    assert.match(
      REGISTRY_PUBLISHED,
      /^example: zz published to a local registry:2\.8\.3 with the two commands pack\.yml's token jobs run .*, anonymously,/,
    );
    assert.doesNotMatch(REGISTRY_PUBLISHED, /as pack\.yml publishes/);
  });

  it('takes the registry image registry:2.8.3', () => {
    // Docker Hub's name: a public mirror's anonymous limit is per address too, and the runners share addresses.
    assert.equal(REGISTRY_IMAGE, 'registry:2.8.3');
  });

  it('removes only the registries of runs no longer going on, never its own or a live run’s', () => {
    const alive = new Set([200, 300]);
    const names = [
      'niord-example-registry-100',
      'niord-example-registry-200',
      'niord-example-registry-300',
      'niord-example-registry-400',
      'niord-example-registry-12x',
      'other-niord-example-registry-500',
      'niord-db',
    ];
    assert.deepEqual(
      staleRegistries(names, (pid) => alive.has(pid), 300),
      ['niord-example-registry-100', 'niord-example-registry-400'],
    );
    // Its own run's, even if the probe says otherwise.
    assert.deepEqual(
      staleRegistries(['niord-example-registry-7'], () => false, 7),
      [],
    );
    assert.deepEqual(
      staleRegistries([registryName()], () => false),
      [],
    );
  });

  it("pins zz under the example's owner as an administration language of yy, a manifest the kit reads", () => {
    const yy = {
      country: 'yy',
      name: 'YY',
      apps: {
        admin: { uiLanguages: ['en'], contentLanguages: ['en'], defaultLanguage: 'en' },
        info: { uiLanguages: ['en'], defaultLanguage: 'en' },
      },
      packs: {},
      locales: {},
      overrides: [],
    };
    const pinned = pinnedManifest(yy);
    assert.deepEqual(pinned.apps.admin, {
      uiLanguages: ['en', 'zz'],
      contentLanguages: ['en', 'zz'],
      defaultLanguage: 'en',
    });
    assert.deepEqual(pinned.apps.info, yy.apps.info);
    assert.deepEqual(pinned.packs, { zz: { pinned: true, owner: 'example' } });
    assert.deepEqual(manifestProblems(pinned), []);
    assert.deepEqual(yy.packs, {}, 'the manifest given is left as it was');
  });

  it("checks that .niord/packs/zz/ holds exactly zz's archive: a file more, missing or changed", () => {
    const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-staged-'));
    temporary.push(fe);
    fs.cpSync(path.join(EXAMPLE, 'fe', 'packs', 'zz'), path.join(fe, 'packs', 'zz'), {
      recursive: true,
    });
    fs.mkdirSync(path.join(fe, 'packs', 'zz', 'status'));
    fs.writeFileSync(path.join(fe, 'packs', 'zz', 'status', 'unaccepted.json'), '{}\n');
    const staged = path.join(fe, '.niord', 'packs', 'zz');
    for (const { name, data } of packEntries(path.join(fe, 'packs', 'zz'), 'zz')) {
      fs.mkdirSync(path.dirname(path.join(staged, name)), { recursive: true });
      fs.writeFileSync(path.join(staged, name), data);
    }
    assert.deepEqual(stagedPackProblems(fe, 'zz'), []);
    fs.mkdirSync(path.join(staged, 'status'), { recursive: true });
    fs.writeFileSync(path.join(staged, 'status', 'unaccepted.json'), '{}\n');
    fs.appendFileSync(path.join(staged, 'pack.json'), ' ');
    fs.rmSync(path.join(staged, 'ATTRIBUTION.txt'));
    assert.deepEqual(stagedPackProblems(fe, 'zz').sort(), [
      '.niord/packs/zz/ATTRIBUTION.txt is missing',
      ".niord/packs/zz/pack.json differs from the archive's",
      '.niord/packs/zz/status/unaccepted.json is not in the archive',
    ]);
    fs.rmSync(staged, { recursive: true });
    assert.deepEqual(stagedPackProblems(fe, 'zz'), ['.niord/packs/zz/ does not exist']);
  });
});

describe('the untouched init and its backend', () => {
  const VERSION = '4.0.0-example';
  const BE = where().be;
  const INIT = [
    process.execPath,
    '.niord/kit/init.mjs',
    '--answers',
    '.niord/init-answers.json',
    '--sha256-file',
    '.niord/SHA256SUMS',
  ];
  const WRAPPER = {
    mvnw: 'be/mvnw',
    mvnwCmd: 'be/mvnw.cmd',
    wrapperProperties: 'be/.mvn/wrapper/maven-wrapper.properties',
  };
  const GROUP_SCRIPT = 'be/scripts/check-country-build-group.sh';
  const zip = writeZip([{ name: 'catalog.json', data: Buffer.from('{}\n') }]);
  const kitZip = writeZip([
    { name: 'kit.json', data: Buffer.from(markerText(VERSION)) },
    { name: 'init.mjs', data: Buffer.from('// init\n') },
  ]);

  /** A temp folder, removed after the tests. */
  const scratch = (prefix) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
    temporary.push(dir);
    return dir;
  };

  /** Writes the files init writes that the harness reads: the wrapper, the group script, the ignore. */
  function writeInitOutput(root) {
    const write = (relative, data, mode = 0o644) => {
      const file = path.join(root, ...relative.split('/'));
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, data, { mode });
    };
    write('.niord/.gitignore', '*\n');
    for (const [key, relative] of Object.entries(WRAPPER))
      write(relative, kitBackendBytes(key), key === 'mvnw' ? 0o755 : 0o644);
    write(GROUP_SCRIPT, '#!/usr/bin/env bash\n', 0o755);
  }

  /**
   * A fake harness: `step` records each name and runs the action, `run` records each command and
   * answers as init, the group check and the frontend commands would, `maven` records each call.
   * `m2` holds the niord-base jars named in `jars`.
   */
  function fakeHarness({
    backend = true,
    skipFrontend = false,
    image = true,
    initOutput = 'init: wrote be/pom.xml\n',
    jars = [`niord-base-${VERSION}.jar`, `niord-base-${VERSION}-tests.jar`],
  } = {}) {
    const work = scratch('niord-untouched-');
    const m2 = scratch('niord-untouched-m2-');
    const folder = path.join(m2, 'org', 'niord', 'base', 'niord-base', VERSION);
    fs.mkdirSync(folder, { recursive: true });
    for (const jar of jars) fs.writeFileSync(path.join(folder, jar), 'jar');
    const record = { steps: [], runs: [], mavens: [], atInit: undefined };
    const h = {
      step: (name, action) => {
        record.steps.push(name);
        action();
      },
      run: (command, args, { cwd, env } = {}) => {
        record.runs.push({ command: [command, ...args], cwd, env });
        if (args[0] === '.niord/kit/init.mjs') {
          const niord = path.join(cwd, '.niord');
          record.atInit = {
            sums: fs.readFileSync(path.join(niord, 'SHA256SUMS'), 'utf8'),
            answers: JSON.parse(fs.readFileSync(path.join(niord, 'init-answers.json'), 'utf8')),
            zip: fs.readFileSync(path.join(niord, `niord-fe-src-${VERSION}.zip`)),
            kitZip: fs.readFileSync(path.join(niord, `niord-country-kit-${VERSION}.zip`)),
            kit: fs.readFileSync(path.join(niord, 'kit', 'init.mjs'), 'utf8'),
            be: fs.existsSync(path.join(cwd, 'be')),
            base: fs.existsSync(path.join(cwd, 'fe', '.niord')),
          };
          writeInitOutput(cwd);
          // What set-up unpacks and the baseline step copies.
          fs.mkdirSync(path.join(cwd, 'fe', '.niord', 'base', 'ui-apps', 'info'), {
            recursive: true,
          });
          fs.writeFileSync(
            path.join(cwd, 'fe', '.niord', 'base', 'ui-apps', 'info', 'bundle-baseline.json'),
            '{}\n',
          );
          fs.mkdirSync(path.join(cwd, 'fe', 'info'), { recursive: true });
          return { status: 0, output: initOutput };
        }
        if (args[0] === 'scripts/check-country-build-group.sh')
          return {
            status: 0,
            output: 'the country build group ran: 12 integration tests, 0 skipped\n',
          };
        const line = args.join(' ');
        if (command === 'npm' && line === 'run build')
          return { status: 0, output: 'warning: info/bundle-baseline.json does not exist\n' };
        if (command === 'npm' && line.startsWith('run image:info'))
          return {
            status: 0,
            output:
              'warning: x holds no legal document (--no-legal-doc): add info/assets/legal/disclaimer.en.html\n' +
              'warning: x holds no en.json (--no-branding-text): add info/assets/branding/en.json\n',
          };
        return { status: 0, output: '' };
      },
      maven: (be, args, { env } = {}) => record.mavens.push({ be, args, env }),
      env: { PATH: 'path' },
      backendEnv: { PATH: 'path', NIORD_HOME: 'C:/work/niord-home' },
      bash: 'the-bash',
      m2,
      backend,
      skipFrontend,
      image,
      removeImage: () => {},
      removeContainer: () => {},
    };
    const published = [];
    const publish = (options) => {
      published.push(options);
      options.h.step('pin', () => {});
      options.h.step('withdraw', () => {});
      return 'example: zz published';
    };
    const go = () =>
      untouched({
        work,
        zip,
        sha256: sha256(zip),
        kitZip,
        kitSha256: sha256(kitZip),
        version: VERSION,
        country: path.join(work, 'xx'),
        catalog: path.join(work, 'latest-catalog.json'),
        h,
        publish,
      });
    return { work, h, record, published, go };
  }

  /** The phase of each step, runs of one phase collapsed. */
  const phases = (steps) =>
    steps
      .map((name) => {
        if (name === 'pin' || name === 'withdraw') return name;
        if (/^yy: init from the two packages/.test(name)) return 'init';
        if (name === 'yy: node setup.mjs') return 'set-up';
        if (
          /test-jar installed|be\/mvnw verify|check-country-build-group|override check/.test(name)
        )
          return 'backend';
        return 'frontend';
      })
      .filter((phase, i, all) => phase !== all[i - 1]);

  it('runs init, set-up, the four backend steps, the frontend steps, then publish and pin', () => {
    const { record, published, go, work } = fakeHarness();
    assert.equal(go(), 'example: zz published');
    assert.deepEqual(phases(record.steps), [
      'init',
      'set-up',
      'backend',
      'frontend',
      'pin',
      'withdraw',
    ]);
    assert.equal(
      record.steps.filter((name) => phases([name])[0] === 'backend').length,
      4,
      'four backend steps',
    );
    assert.equal(published.length, 1);
    assert.equal(published[0].country, path.join(work, 'xx'));
  });

  it('with --skip-frontend runs init, set-up and the backend only; without --backend no Maven', () => {
    const skip = fakeHarness({ skipFrontend: true });
    assert.equal(skip.go(), null);
    assert.deepEqual(phases(skip.record.steps), ['init', 'set-up', 'backend']);
    assert.equal(skip.published.length, 0);
    assert.ok(!skip.record.runs.some(({ command }) => command[0] === 'npm'));

    const plain = fakeHarness({ backend: false, image: false });
    assert.equal(plain.go(), REGISTRY_SKIPPED);
    assert.deepEqual(phases(plain.record.steps), ['init', 'set-up', 'frontend']);
    assert.deepEqual(plain.record.mavens, []);
    assert.equal(plain.published.length, 0);
  });

  it('runs init as the Quick start does: --answers and --sha256-file in an empty repository', () => {
    const { record, go, work } = fakeHarness({ backend: false, image: false });
    go();
    const [first] = record.runs;
    assert.deepEqual(first.command, INIT);
    assert.equal(first.cwd, path.join(work, 'yy'));
    const sums = `${sha256(zip)}  niord-fe-src-${VERSION}.zip\n${sha256(kitZip)}  niord-country-kit-${VERSION}.zip\n`;
    assert.equal(record.atInit.sums, sums);
    assert.equal(
      sha256Sums({ version: VERSION, sha256: sha256(zip), kitSha256: sha256(kitZip) }),
      sums,
    );
    assert.deepEqual(record.atInit.answers, YY_ANSWERS);
    assert.ok(record.atInit.zip.equals(zip) && record.atInit.kitZip.equals(kitZip));
    assert.equal(record.atInit.kit, '// init\n', 'the kit package unzipped into .niord/kit/');
    assert.equal(record.atInit.be, false, 'no be/pom.xml or wrapper written before init');
    assert.equal(record.atInit.base, false, 'the source zip is not unpacked by hand');
    assert.equal(readAnswers(YY_ANSWERS).country, 'yy');
    assert.deepEqual(readAnswers(YY_ANSWERS).languages, []);
    assert.ok(Object.isFrozen(YY_ANSWERS) && Object.isFrozen(YY_ANSWERS.map));
  });

  it('fails the init step on a file init kept, naming it', () => {
    const { go, record } = fakeHarness({
      initOutput: 'init: wrote AGENTS.md\ninit: kept be/pom.xml, which exists\r\n',
    });
    assert.throws(go, /^Error: init kept be\/pom\.xml: the untouched init must write every file/);
    assert.equal(record.steps.length, 1, 'set-up never ran');
    assert.deepEqual(
      keptLines('init: kept a, which exists\ninit: wrote b\ninit: kept c, which exists'),
      ['init: kept a, which exists', 'init: kept c, which exists'],
    );
  });

  it("names a wrapper file whose bytes differ from the tree's be/ and, off Windows, one without exec bits", () => {
    const root = scratch('niord-wrapper-');
    writeInitOutput(root);
    const be = path.join(root, 'be');
    assert.deepEqual(wrapperProblems(be, 'win32'), []);
    if (process.platform !== 'win32') assert.deepEqual(wrapperProblems(be, 'linux'), []);
    fs.appendFileSync(path.join(be, 'mvnw.cmd'), 'rem changed\r\n');
    fs.chmodSync(path.join(be, 'mvnw'), 0o644);
    const changed = "be/mvnw.cmd differs from the Maven wrapper of the tree's be/";
    assert.deepEqual(wrapperProblems(be, 'win32'), [changed]);
    const linux = wrapperProblems(be, 'linux');
    assert.ok(linux.includes(changed));
    assert.ok(linux.includes('be/mvnw is not executable (mode 0755)'), linux.join('\n'));
    if (process.platform !== 'win32')
      assert.ok(!linux.includes(`${GROUP_SCRIPT} is not executable (mode 0755)`));
    fs.rmSync(path.join(be, '.mvn'), { recursive: true });
    assert.ok(
      wrapperProblems(be, 'win32').includes('be/.mvn/wrapper/maven-wrapper.properties is missing'),
    );
  });

  it("issues the backend's Maven calls, the group check and the override check as the country's ci.yml runs them", () => {
    const { record, go, h, work } = fakeHarness({ image: false });
    go();
    const yyBe = path.join(work, 'yy', 'be');
    assert.deepEqual(record.mavens, [
      {
        be: BE,
        args: ['-B', '-ntp', `-Drevision=${VERSION}`, '-pl', 'niord', 'install', '-DskipTests'],
        env: undefined,
      },
      { be: yyBe, args: ['-B', '-ntp', 'verify'], env: h.backendEnv },
    ]);
    const group = record.runs.find(
      ({ command }) => command[1] === 'scripts/check-country-build-group.sh',
    );
    assert.deepEqual(group.command, ['the-bash', 'scripts/check-country-build-group.sh']);
    assert.equal(group.cwd, yyBe);
    assert.equal(group.env, h.backendEnv);
    const override = record.runs.find(({ command }) =>
      /check-overrides\.sh$/.test(command[1] ?? ''),
    );
    assert.deepEqual(override.command, [
      'the-bash',
      `${BE.replaceAll('\\', '/')}/k8s/scripts/check-overrides.sh`,
      `target/niord-yy-${VERSION}.jar`,
      '.',
    ]);
    assert.ok(!override.command[1].includes('\\'), 'the script path has forward slashes');
    assert.equal(override.cwd, yyBe);
    assert.ok(fs.existsSync(path.join(BE, 'k8s', 'scripts', 'check-overrides.sh')));
    assert.match(BACKEND_BUILT, /^example: yy's backend built from init's scaffold: /);
    assert.match(
      BACKEND_SKIPPED,
      /^example: the generated country's backend: not built \(no --backend\)/,
    );
  });

  it('fails the install step when the scratch repository holds no niord-base test-jar', () => {
    const { go, record } = fakeHarness({ jars: [`niord-base-${VERSION}.jar`] });
    assert.throws(go, new RegExp(`niord-base-${VERSION.replaceAll('.', '\\.')}-tests\\.jar`));
    assert.equal(record.mavens.length, 1, 'yy is not verified after a failed install');
  });

  it('fails the group check when the group ran no test', () => {
    const { go, h } = fakeHarness({ image: false });
    const run = h.run;
    h.run = (command, args, options) =>
      args[0] === 'scripts/check-country-build-group.sh'
        ? { status: 0, output: 'the country build group ran: 0 integration tests, 0 skipped\n' }
        : run(command, args, options);
    assert.throws(go, /the country build group/);
  });
});
