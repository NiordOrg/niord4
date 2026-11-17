/**
 * Tests for country-kit/lib/dependencies.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * A small base product manifest and lockfile and a passing country manifest and lock are kept in
 * test-fixtures/dependencies/; each case breaks a copy the way an edit could and expects the check
 * to name it, one case per rule. The registry and npm are injected: no test touches the network.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  addedEntries,
  checkCountryDependencies,
  manifestProblems,
  mergeDependencies,
  publishTimes,
  readCountryDependencies,
  registryUrl,
  reportDependencies,
  updateCountryLock,
} from './lib/dependencies.mjs';

const FIXTURES = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'test-fixtures',
  'dependencies',
);
const fixture = (name) => JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
const BASE_PACKAGE = fixture('base-package.json');
const BASE_LOCK = fixture('base-lock.json');
const NOW = new Date('2026-10-22T12:00:00Z');
const OLD = '2025-09-21T10:00:00.000Z';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});
const scratch = (prefix) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  temporary.push(dir);
  return dir;
};

/** The passing country, edited by `edit(manifest, lock)`. */
function country(edit) {
  const manifest = fixture('country-package.json');
  const lock = fixture('country-lock.json');
  edit?.(manifest, lock);
  return { manifest, lock };
}

/** Every added entry published long ago. */
const publishedFor = (subject) =>
  new Map(addedEntries(subject, BASE_LOCK).map(({ name, version }) => [`${name}@${version}`, OLD]));

function check(edit, { published } = {}) {
  const subject = country(edit);
  return checkCountryDependencies({
    country: subject,
    basePackage: BASE_PACKAGE,
    baseLock: BASE_LOCK,
    published: published ?? publishedFor(subject),
    now: NOW,
  });
}

function assertNamed(lines, ...fragments) {
  for (const fragment of fragments)
    assert.ok(
      lines.some((line) => line.includes(fragment)),
      `expected a line naming "${fragment}", got:\n${lines.join('\n') || '(none)'}`,
    );
}

const HARBOUR = 'node_modules/ngx-harbour';

describe('country dependencies: the check', () => {
  it('passes the fixture country and lists its packages', () => {
    const result = check();
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.warnings, []);
    assert.deepEqual(
      result.added.map((entry) => entry.path),
      [
        'node_modules/berth-geometry',
        HARBOUR,
        'node_modules/ngx-harbour/node_modules/ms',
        'node_modules/pretty-bytes',
      ],
    );
    assert.deepEqual(result.lines, [
      "country dependencies: country/package.json lists 3 packages, and its lock adds 4 entries to the base product's tree:",
      "  ngx-harbour@3.1.0 for admin — The harbour page's berth map.",
      "  pretty-bytes@7.1.0 for both — Shows the notes' sizes.",
      "  rxjs@7.8.2 for admin — The harbour page's streams, at the base product's version. (the base product's own version)",
    ]);
  });

  it('none: no country gives no finding and says so', () => {
    const result = checkCountryDependencies({
      country: null,
      basePackage: BASE_PACKAGE,
      baseLock: BASE_LOCK,
    });
    assert.deepEqual(result, {
      errors: [],
      warnings: [],
      lines: ['country dependencies: none'],
      added: [],
      peers: [],
    });
  });

  it('shape: a manifest outside its shape fails, each problem named', () => {
    const { errors } = check((manifest) => {
      manifest.devDependencies = { vitest: '4.0.0' };
      manifest.scripts = { postinstall: 'node x.js' };
      manifest.overrides = {};
      manifest.private = false;
      manifest.dependencies['@niord/admin'] = '1.0.0';
      manifest.dependencies['Bad Name'] = '1.0.0';
      manifest.niord.dependencies['left-pad'] = { app: 'admin', reason: 'gone' };
      manifest.niord.dependencies['pretty-bytes'].app = 'backend';
      manifest.niord.dependencies.rxjs.reason = ' ';
      manifest.niord.extra = true;
    });
    assertNamed(
      errors,
      'country/package.json has "devDependencies"; it has name, private, description, dependencies and niord only',
      'country/package.json has "scripts"',
      'country/package.json has "overrides"',
      'country/package.json is not private: true',
      "country/package.json: @niord/admin is in the base product's scope",
      'country/package.json: "Bad Name" is not an npm name',
      'niord.dependencies.left-pad names a package dependencies does not list',
      'niord.dependencies.pretty-bytes.app is "backend", not admin, info, both',
      'niord.dependencies.rxjs gives no reason',
      'niord has "extra"; it has dependencies and installScripts',
      'country/package.json: @niord/admin has no niord.dependencies entry',
    );
    assertNamed(
      manifestProblems({ name: 'x', private: true, dependencies: {} }),
      'has no niord.dependencies',
    );
    assertNamed(manifestProblems([]), 'country/package.json is not an object');
  });

  it('shape: a lock outside its shape fails', () => {
    const { errors } = check((manifest, lock) => {
      lock.lockfileVersion = 2;
      lock.dependencies = {};
      lock.packages[''].dependencies = { 'pretty-bytes': '7.1.0' };
      lock.packages[''].devDependencies = {};
      lock.packages['../outside'] = {};
    });
    assertNamed(
      errors,
      'country/package-lock.json is of lockfileVersion 2, not 3',
      'country/package-lock.json has "dependencies"',
      'packages[""].dependencies are not country/package.json\'s',
      'packages[""] has "devDependencies"',
      'country/package-lock.json: ../outside is not a path under node_modules/',
    );
  });

  it('exact: a version that is not exact fails', () => {
    const { errors } = check((manifest, lock) => {
      manifest.dependencies['pretty-bytes'] = '^7.1.0';
      lock.packages[''].dependencies['pretty-bytes'] = '^7.1.0';
    });
    assertNamed(
      errors,
      'country/package.json: pretty-bytes is "^7.1.0", not an exact version x.y.z',
    );
  });

  it('base package: a base product package at another version fails; the same version passes', () => {
    const edit = (version) => (manifest, lock) => {
      manifest.dependencies.rxjs = version;
      lock.packages[''].dependencies.rxjs = version;
    };
    assertNamed(
      check(edit('7.8.1')).errors,
      "country/package.json lists rxjs 7.8.1, which the base product has at 7.8.2: a country takes the base product's version",
    );
    // A dev package of the base product counts too.
    const typescript = check((manifest, lock) => {
      manifest.dependencies.typescript = '5.9.3';
      lock.packages[''].dependencies.typescript = '5.9.3';
      manifest.niord.dependencies.typescript = { app: 'admin', reason: 'x' };
    });
    assertNamed(typescript.errors, 'lists typescript 5.9.3, which the base product has at 6.0.3');
    assert.deepEqual(check(edit('7.8.2')).errors, []);
  });

  it('base entry: a lock entry at a base product path with another version, URL or integrity fails', () => {
    const { errors } = check((manifest, lock) => {
      lock.packages['node_modules/tslib'] = {
        ...BASE_LOCK.packages['node_modules/tslib'],
        version: '2.8.0',
      };
      lock.packages['node_modules/ms'] = {
        ...BASE_LOCK.packages['node_modules/ms'],
        integrity: 'sha512-other',
      };
    });
    assertNamed(
      errors,
      "node_modules/tslib has another version than the base product's (2.8.1): a base product entry of the merged tree differs from the pinned lockfile",
      "node_modules/ms has another integrity than the base product's (2.1.3)",
    );
    assertNamed(
      check((manifest, lock) => {
        lock.packages['node_modules/rxjs'] = BASE_LOCK.packages['node_modules/rxjs'];
      }).errors,
      "node_modules/rxjs repeats the base product's entry; the country lock holds only the entries it adds",
    );
  });

  it('registry: a resolved URL off the registry, an integrity not sha512 or a link fails', () => {
    const { errors } = check((manifest, lock) => {
      lock.packages['node_modules/pretty-bytes'].resolved =
        'http://registry.npmjs.org/pretty-bytes/-/pretty-bytes-7.1.0.tgz';
      lock.packages['node_modules/berth-geometry'].resolved =
        'https://codeload.github.com/zz/berth-geometry/tar.gz/abc';
      lock.packages[HARBOUR].integrity = 'sha1-abc';
      lock.packages['node_modules/ngx-harbour/node_modules/ms'] = {
        resolved: '../ms',
        link: true,
      };
    });
    assertNamed(
      errors,
      'node_modules/pretty-bytes is resolved from http://registry.npmjs.org/pretty-bytes/-/pretty-bytes-7.1.0.tgz, not https://registry.npmjs.org/',
      'node_modules/berth-geometry is resolved from https://codeload.github.com/',
      'node_modules/ngx-harbour has the integrity sha1-abc, not a sha512 one',
      'node_modules/ngx-harbour/node_modules/ms is a link; a country package comes from the registry',
    );
  });

  it('install script: an entry with one fails without a reason, passes with one; a stale reason warns', () => {
    const scripted = (manifest, lock) => {
      lock.packages['node_modules/berth-geometry'].hasInstallScript = true;
    };
    assertNamed(
      check(scripted).errors,
      "node_modules/berth-geometry runs an install script, and country/package.json's niord.installScripts gives no reason for berth-geometry",
    );
    const reasoned = check((manifest, lock) => {
      scripted(manifest, lock);
      manifest.niord.installScripts = { 'berth-geometry': 'Builds its native index; vetted.' };
    });
    assert.deepEqual(reasoned.errors, []);
    const stale = check((manifest) => {
      manifest.niord.installScripts = { 'pretty-bytes': 'No longer.' };
    });
    assert.deepEqual(stale.errors, []);
    assertNamed(stale.warnings, 'niord.installScripts.pretty-bytes names no added entry');
    assertNamed(
      check((manifest) => {
        manifest.niord.installScripts = { 'pretty-bytes': '' };
      }).errors,
      'niord.installScripts.pretty-bytes gives no reason',
    );
  });

  it("peers: a range the base product's framework fails, an absent peer fails unless optional", () => {
    const peers = (peerDependencies, peerDependenciesMeta) => (manifest, lock) => {
      lock.packages[HARBOUR].peerDependencies = peerDependencies;
      if (peerDependenciesMeta) lock.packages[HARBOUR].peerDependenciesMeta = peerDependenciesMeta;
    };
    assertNamed(
      check(peers({ '@angular/core': '^21.0.0' })).errors,
      "node_modules/ngx-harbour needs the peer @angular/core ^21.0.0, and the merged tree has 22.2.2, the base product's",
    );
    // typescript-eslint's range against the base product's TypeScript 6 (fe/.npmrc).
    assertNamed(
      check(peers({ typescript: '>=4.8.4 <6.0.0' })).errors,
      'needs the peer typescript >=4.8.4 <6.0.0, and the merged tree has 6.0.3',
    );
    assertNamed(
      check(peers({ 'zone.js': '~0.16.0' })).errors,
      'node_modules/ngx-harbour needs the peer zone.js ~0.16.0, which the merged tree lacks',
    );
    assert.deepEqual(
      check(peers({ 'zone.js': '~0.16.0' }, { 'zone.js': { optional: true } })).errors,
      [],
    );
    assertNamed(
      check(peers({ rxjs: 'workspace:*' })).errors,
      "node_modules/ngx-harbour's peer range for rxjs cannot be read",
    );
  });

  it('gives every peer of an added entry structured, rejected or not, for the upgrade report', () => {
    const result = check((manifest, lock) => {
      lock.packages[HARBOUR].peerDependencies = {
        '@angular/core': '^21.0.0',
        typescript: '>=5.0.0',
        'zone.js': '~0.16.0',
      };
      lock.packages[HARBOUR].peerDependenciesMeta = { 'zone.js': { optional: true } };
    });
    const harbour = result.peers.filter((relation) => relation.from === HARBOUR);
    assert.deepEqual(harbour, [
      {
        from: HARBOUR,
        name: 'ngx-harbour',
        peer: '@angular/core',
        range: '^21.0.0',
        version: '22.2.2',
        rejected: true,
        error: `country/package-lock.json: ${HARBOUR} needs the peer @angular/core ^21.0.0, and the merged tree has 22.2.2, the base product's`,
      },
      {
        from: HARBOUR,
        name: 'ngx-harbour',
        peer: 'typescript',
        range: '>=5.0.0',
        version: '6.0.3',
        rejected: false,
      },
      {
        from: HARBOUR,
        name: 'ngx-harbour',
        peer: 'zone.js',
        range: '~0.16.0',
        version: null,
        rejected: false,
      },
    ]);
    // The rejected relation's line is exactly the one the errors hold.
    assert.ok(result.errors.includes(harbour[0].error), harbour[0].error);
    assert.deepEqual(
      check(undefined).peers.filter((relation) => relation.rejected),
      [],
    );
  });

  it('tree: a missing dependency, an unreached entry or another version than listed fails', () => {
    assertNamed(
      check((manifest, lock) => {
        delete lock.packages['node_modules/berth-geometry'];
      }).errors,
      'node_modules/ngx-harbour depends on berth-geometry ^1.2.0, which the merged tree lacks',
    );
    assertNamed(
      check((manifest, lock) => {
        lock.packages['node_modules/stray'] = {
          version: '1.0.0',
          resolved: 'https://registry.npmjs.org/stray/-/stray-1.0.0.tgz',
          integrity: 'sha512-stray',
        };
      }).errors,
      "node_modules/stray is not reached from country/package.json's dependencies",
    );
    assertNamed(
      check((manifest, lock) => {
        lock.packages['node_modules/pretty-bytes'].version = '7.0.1';
      }).errors,
      'node_modules/pretty-bytes is 7.0.1, country/package.json lists 7.1.0',
    );
    assertNamed(
      check((manifest, lock) => {
        lock.packages['node_modules/berth-geometry'].version = '2.0.0';
      }).errors,
      'node_modules/ngx-harbour depends on berth-geometry ^1.2.0, and the merged tree has 2.0.0',
    );
  });

  it('age: an added entry published less than 7 days ago fails, as does one with no known time', () => {
    const subject = country();
    const published = publishedFor(subject);
    published.set('pretty-bytes@7.1.0', '2026-10-20T08:00:00.000Z');
    published.delete('berth-geometry@1.2.3');
    const { errors } = check(undefined, { published });
    assertNamed(
      errors,
      'pretty-bytes@7.1.0 was published 2026-10-20T08:00:00.000Z, less than 7 days ago; a country takes a version from 2026-10-27T08:00:00.000Z',
      'no publish time is known for berth-geometry@1.2.3',
    );
    assert.equal(errors.length, 2, errors.join('\n'));
    // The nested entry is named by its package, not its path.
    assert.ok(published.has('ms@3.0.0'));
  });
});

describe('country dependencies: reading', () => {
  it('reads both files, neither as none, and refuses one without the other', () => {
    const fe = scratch('niord-country-deps-');
    assert.equal(readCountryDependencies(fe), null);
    fs.mkdirSync(path.join(fe, 'country'));
    assert.equal(readCountryDependencies(fe), null);
    fs.copyFileSync(
      path.join(FIXTURES, 'country-package.json'),
      path.join(fe, 'country', 'package.json'),
    );
    assert.throws(
      () => readCountryDependencies(fe),
      /country\/package\.json has no package-lock\.json beside it/,
    );
    fs.copyFileSync(
      path.join(FIXTURES, 'country-lock.json'),
      path.join(fe, 'country', 'package-lock.json'),
    );
    assert.deepEqual(readCountryDependencies(fe), country());
    fs.rmSync(path.join(fe, 'country', 'package.json'));
    assert.throws(() => readCountryDependencies(fe), /package-lock\.json has no package\.json/);
    fs.writeFileSync(path.join(fe, 'country', 'package.json'), '{ "name": ');
    assert.throws(() => readCountryDependencies(fe), /^Error: country\/package\.json: /);
  });

  it('refuses a linked folder', () => {
    const fe = scratch('niord-country-deps-link-');
    const target = path.join(fe, 'elsewhere');
    fs.mkdirSync(target);
    const link = path.join(fe, 'country');
    fs.symlinkSync(target, link, 'junction');
    try {
      assert.throws(() => readCountryDependencies(fe), /country\/ is a link/);
    } finally {
      fs.unlinkSync(link);
    }
  });
});

describe('country dependencies: publish times', () => {
  const entries = [
    { name: 'pretty-bytes', version: '7.1.0', integrity: 'sha512-a' },
    { name: '@zz/berths', version: '1.0.0', integrity: 'sha512-b' },
  ];

  it('asks the registry once per package, caches an answer older than 7 days, and reuses it', async () => {
    const cacheFile = path.join(scratch('niord-ages-'), '.niord', 'country-dependency-ages.json');
    const asked = [];
    const fetchJson = async (url) => {
      asked.push(url);
      if (url.endsWith('pretty-bytes')) return fixture('registry-pretty-bytes.json');
      return { time: { '1.0.0': '2026-10-20T00:00:00.000Z' } };
    };
    const first = await publishTimes({ entries, fetchJson, cacheFile, now: NOW });
    assert.deepEqual(first.errors, []);
    assert.deepEqual(asked, [
      'https://registry.npmjs.org/pretty-bytes',
      'https://registry.npmjs.org/@zz%2Fberths',
    ]);
    assert.equal(first.published.get('pretty-bytes@7.1.0'), '2025-09-21T10:00:00.000Z');
    assert.equal(first.published.get('@zz/berths@1.0.0'), '2026-10-20T00:00:00.000Z');
    // Only the old answer is cached: the young one must be asked again until it is old.
    assert.deepEqual(JSON.parse(fs.readFileSync(cacheFile, 'utf8')), {
      format: 1,
      published: { 'pretty-bytes@7.1.0+sha512-a': '2025-09-21T10:00:00.000Z' },
    });
    asked.length = 0;
    const second = await publishTimes({ entries, fetchJson, cacheFile, now: NOW });
    assert.deepEqual(asked, ['https://registry.npmjs.org/@zz%2Fberths']);
    assert.equal(second.published.get('pretty-bytes@7.1.0'), '2025-09-21T10:00:00.000Z');
    // Another integrity is another key: the cache never vouches for different bytes.
    asked.length = 0;
    await publishTimes({
      entries: [{ ...entries[0], integrity: 'sha512-c' }],
      fetchJson,
      cacheFile,
      now: NOW,
    });
    assert.deepEqual(asked, ['https://registry.npmjs.org/pretty-bytes']);
  });

  it('names the package when the registry gives no answer or no time', async () => {
    const { published, errors } = await publishTimes({
      entries,
      fetchJson: async (url) => {
        if (url.endsWith('pretty-bytes')) throw new Error('getaddrinfo ENOTFOUND');
        return { time: {} };
      },
    });
    assert.equal(published.size, 0);
    assertNamed(
      errors,
      'the registry gave no answer for pretty-bytes (getaddrinfo ENOTFOUND)',
      'the registry has no publish time for @zz/berths@1.0.0',
    );
  });

  it("builds the registry document's URL with the scope's @ kept", () => {
    assert.equal(registryUrl('@angular/core'), 'https://registry.npmjs.org/@angular%2Fcore');
    assert.equal(registryUrl('ms'), 'https://registry.npmjs.org/ms');
  });
});

describe('country dependencies: the merged tree', () => {
  it("adds the country's packages and entries to the base product's, which no country leaves as it is", () => {
    const same = mergeDependencies({
      basePackage: BASE_PACKAGE,
      baseLock: BASE_LOCK,
      country: null,
    });
    assert.deepEqual(same.dependencies, BASE_PACKAGE.dependencies);
    assert.deepEqual(same.lock, BASE_LOCK);

    const { dependencies, lock } = mergeDependencies({
      basePackage: BASE_PACKAGE,
      baseLock: BASE_LOCK,
      country: country(),
    });
    // rxjs stays at the base product's range: the country lists the base product's version.
    assert.deepEqual(dependencies, {
      '@angular/core': '^22.2.2',
      'ngx-harbour': '3.1.0',
      'pretty-bytes': '7.1.0',
      rxjs: '~7.8.0',
      tslib: '^2.3.0',
    });
    assert.deepEqual(lock.packages[''].dependencies, dependencies);
    assert.deepEqual(lock.packages[''].devDependencies, BASE_PACKAGE.devDependencies);
    assert.deepEqual(Object.keys(lock.packages), [
      '',
      'node_modules/@angular/core',
      'node_modules/berth-geometry',
      'node_modules/ms',
      HARBOUR,
      'node_modules/ngx-harbour/node_modules/ms',
      'node_modules/pretty-bytes',
      'node_modules/rxjs',
      'node_modules/tslib',
      'node_modules/typescript',
    ]);
    assert.deepEqual(BASE_LOCK, fixture('base-lock.json'), 'the base lock is not changed');
  });

  it('reports each package for the build report', () => {
    assert.deepEqual(reportDependencies(null), []);
    assert.deepEqual(
      reportDependencies(
        country((manifest) => {
          manifest.niord.installScripts = { 'ngx-harbour': 'Vetted.' };
        }),
      )[0],
      {
        name: 'ngx-harbour',
        version: '3.1.0',
        app: 'admin',
        reason: "The harbour page's berth map.",
        installScript: 'Vetted.',
      },
    );
  });
});

describe('country dependencies: making the lock', () => {
  /** A country fe/ and a base folder from the fixtures. */
  function workspace() {
    const root = scratch('niord-country-lock-');
    const fe = path.join(root, 'fe');
    const base = path.join(fe, '.niord', 'base');
    fs.mkdirSync(path.join(fe, 'country'), { recursive: true });
    fs.mkdirSync(base, { recursive: true });
    fs.copyFileSync(
      path.join(FIXTURES, 'country-package.json'),
      path.join(fe, 'country', 'package.json'),
    );
    fs.copyFileSync(path.join(FIXTURES, 'base-package.json'), path.join(base, 'package.json'));
    fs.copyFileSync(path.join(FIXTURES, 'base-lock.json'), path.join(base, 'package-lock.json'));
    fs.writeFileSync(path.join(base, '.npmrc'), 'legacy-peer-deps=true\n');
    return { fe, base };
  }

  /** npm as a test plays it: the base lock plus the fixture country's entries, or `change`d. */
  const npm =
    (calls, change) =>
    (args, { cwd }) => {
      calls.push({
        args,
        cwd,
        package: JSON.parse(fs.readFileSync(path.join(cwd, 'package.json'), 'utf8')),
        npmrc: fs.readFileSync(path.join(cwd, '.npmrc'), 'utf8'),
      });
      const lock = JSON.parse(fs.readFileSync(path.join(cwd, 'package-lock.json'), 'utf8'));
      for (const [lockPath, entry] of Object.entries(fixture('country-lock.json').packages))
        if (lockPath !== '') lock.packages[lockPath] = entry;
      change?.(lock);
      fs.writeFileSync(path.join(cwd, 'package-lock.json'), JSON.stringify(lock));
    };

  it("writes exactly the entries npm adds, with npm's constant arguments in a scratch folder", () => {
    const { fe, base } = workspace();
    const calls = [];
    const lines = updateCountryLock({ fe, base, run: npm(calls) });
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0].args, [
      'install',
      '--package-lock-only',
      '--ignore-scripts',
      '--no-audit',
      '--no-fund',
    ]);
    assert.equal(calls[0].cwd, path.join(fe, '.niord', 'country-lock'));
    assert.equal(calls[0].package.scripts, undefined);
    assert.equal(calls[0].package.dependencies['pretty-bytes'], '7.1.0');
    assert.equal(calls[0].npmrc, 'legacy-peer-deps=true\n');
    const written = fs.readFileSync(path.join(fe, 'country', 'package-lock.json'), 'utf8');
    assert.deepEqual(JSON.parse(written), fixture('country-lock.json'));
    assert.match(lines[0], /country\/package-lock\.json written, 4 entries the merged tree adds/);
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'country-lock')), false);
    // The result passes the check, and a second run writes nothing.
    const again = updateCountryLock({ fe, base, run: npm([]) });
    assert.match(again[0], /unchanged/);
  });

  it('refuses a result that changes a base product entry, and a manifest outside its shape', () => {
    const { fe, base } = workspace();
    assert.throws(
      () =>
        updateCountryLock({
          fe,
          base,
          run: npm([], (lock) => {
            lock.packages['node_modules/tslib'].version = '2.9.0';
          }),
        }),
      /npm would change 1 base product entry to fit the country's packages \(node_modules\/tslib\)/,
    );
    assert.equal(fs.existsSync(path.join(fe, 'country', 'package-lock.json')), false);
    const manifest = fixture('country-package.json');
    manifest.dependencies['pretty-bytes'] = 'latest';
    fs.writeFileSync(path.join(fe, 'country', 'package.json'), JSON.stringify(manifest));
    const calls = [];
    assert.throws(() => updateCountryLock({ fe, base, run: npm(calls) }), /not an exact version/);
    assert.equal(calls.length, 0);
  });
});
