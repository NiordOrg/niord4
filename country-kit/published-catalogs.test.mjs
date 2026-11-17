/**
 * Tests for country-kit/lib/published-catalogs.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Maven is never run: a fake `run` reads the arguments the module hands lib/maven.mjs's runMaven
 * and does what dependency:copy and help:evaluate would — writes the registry's metadata into the
 * local repository as maven-metadata-<repository>.xml when LATEST is resolved, and a version's zip,
 * holding its catalog.json, into the output directory.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { FORMAT, serializeCatalog } from './catalog.mjs';
import {
  catalogVersions,
  latestCatalog,
  metadataVersions,
  publishedCatalogs,
  repositoryArguments,
} from './lib/published-catalogs.mjs';
import { writeZip } from './lib/zip.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const metadata = (versions) =>
  `<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>org.niord.web</groupId>
  <artifactId>niord-fe-src</artifactId>
  <versioning>
    <latest>${versions.at(-1)}</latest>
    <versions>
${versions.map((version) => `      <version>${version}</version>`).join('\n')}
    </versions>
    <lastUpdated>20261022120000</lastUpdated>
  </versioning>
</metadata>
`;

const catalogOf = (version, keys = { 'menu.close': {} }) => ({
  format: FORMAT,
  version,
  apps: { admin: keys, info: {} },
  backend: {},
  renamedFrom: { admin: {}, info: {}, backend: {} },
  rules: { datePatternTokens: [] },
});

/**
 * A workspace and a fake Maven: `remote` the versions the registry lists, `local` those an install
 * wrote, `catalogs` the catalog each version's zip holds (by default one naming the version).
 */
function fakeMaven({
  remote = ['4.0.80', '4.0.81'],
  local = [],
  catalogs = {},
  status = 0,
  repoArgs = true,
  tail = [],
} = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'catalogs-'));
  temporary.push(root);
  const fe = path.join(root, 'fe');
  const be = path.join(root, 'be');
  const repository = path.join(root, 'm2');
  const tailRepository = path.join(root, 'tail');
  fs.mkdirSync(fe, { recursive: true });
  fs.mkdirSync(be, { recursive: true });
  const artifactPath = ['org', 'niord', 'web', 'niord-fe-src'];
  const folder = path.join(repository, ...artifactPath);
  if (local.length) {
    fs.mkdirSync(folder, { recursive: true });
    fs.writeFileSync(path.join(folder, 'maven-metadata-local.xml'), metadata(local));
  }
  // A tail's metadata, as a stale download or an install into ~/.m2 leaves it: -U never refreshes it.
  if (tail.length) {
    const tailFolder = path.join(tailRepository, ...artifactPath);
    fs.mkdirSync(tailFolder, { recursive: true });
    fs.writeFileSync(path.join(tailFolder, 'maven-metadata-github.xml'), metadata(tail));
    fs.writeFileSync(path.join(tailFolder, 'maven-metadata-local.xml'), metadata(tail));
  }
  const calls = [];
  const tailArg = tail.length ? ` -Dmaven.repo.local.tail=${tailRepository}` : '';
  const env = repoArgs
    ? { MAVEN_ARGS: `-Dmaven.repo.local=${repository}${tailArg} -ntp` }
    : tail.length
      ? { MAVEN_ARGS: tailArg.trim() }
      : {};
  const run = ({ be: dir, args, paths, env: given }) => {
    calls.push({ dir, args, paths, env: given });
    const value = (name) =>
      args.find((arg) => arg.startsWith(`-D${name}=`))?.slice(name.length + 3);
    if (args.includes('help:evaluate')) {
      fs.writeFileSync(value('output'), `\n${repository}\n`);
      return 0;
    }
    const [, , version] = value('artifact').split(':');
    const out = value('outputDirectory');
    if (version === 'LATEST') {
      if (remote.length) {
        fs.mkdirSync(folder, { recursive: true });
        fs.writeFileSync(path.join(folder, 'maven-metadata-github.xml'), metadata(remote));
      }
      return status;
    }
    if (![...remote, ...local].includes(version)) return 1;
    fs.mkdirSync(out, { recursive: true });
    const catalog = catalogs[version] ?? catalogOf(version);
    fs.writeFileSync(
      path.join(out, `niord-fe-src-${version}.zip`),
      writeZip([
        { name: 'catalog.json', data: Buffer.from(serializeCatalog(catalog)) },
        { name: 'country-kit/pack.mjs', data: Buffer.from('// not read') },
      ]),
    );
    return 0;
  };
  return { fe, be, run, env, calls, repository };
}

describe('published catalogs', () => {
  it('reads the versions of every maven-metadata*.xml, plain semver versions only, lowest first', () => {
    const maven = fakeMaven({
      remote: ['4.0.80', '4.0.9', '4.0', 'not a version', '4.0.81'],
      local: ['4.0.0-example', '4.0.81'],
    });
    assert.deepEqual(catalogVersions(maven), ['4.0.0-example', '4.0.9', '4.0.80', '4.0.81']);
    // LATEST with -U, so Maven fetches the registry's metadata; never a range, which
    // dependency:copy does not resolve.
    assert.deepEqual(maven.calls[0].args.slice(0, 6), [
      '-B',
      '-q',
      '-N',
      '-U',
      'dependency:copy',
      '-Dartifact=org.niord.web:niord-fe-src:LATEST:zip',
    ]);
    assert.equal(maven.calls[0].dir, maven.be);
    assert.equal(maven.calls[0].env, maven.env);
    assert.deepEqual(maven.calls[0].paths, [maven.calls[0].args[6]]);
    assert.ok(!fs.existsSync(path.join(maven.fe, '.niord', 'catalogs', 'resolve')));
  });

  it('asks Maven for its local repository when MAVEN_ARGS names none', () => {
    const maven = fakeMaven({ repoArgs: false, remote: ['4.0.81'] });
    assert.deepEqual(catalogVersions(maven), ['4.0.81']);
    const evaluate = maven.calls.find(({ args }) => args.includes('help:evaluate'));
    assert.ok(evaluate.args.includes('-Dexpression=settings.localRepository'));
    assert.deepEqual(
      repositoryArguments('-ntp -Dmaven.repo.local=/a -Dmaven.repo.local.tail=/b,/c'),
      { local: '/a' },
    );
    assert.deepEqual(repositoryArguments(undefined), { local: undefined });
  });

  it("never reads a tail's metadata, which -U does not refresh", () => {
    // The tail lists a version the registry no longer does (retracted, or a stale install).
    const tail = ['4.0.80', '4.0.81', '4.0.99'];
    const named = fakeMaven({ remote: ['4.0.80', '4.0.81'], tail });
    assert.deepEqual(catalogVersions(named), ['4.0.80', '4.0.81']);
    assert.equal(latestCatalog(named).version, '4.0.81');
    assert.throws(
      () => publishedCatalogs({ ...named, from: '4.0.99' }),
      /4\.0\.99 is not a published version/,
    );
    // The same when the head is Maven's own and MAVEN_ARGS names only the tail.
    const evaluated = fakeMaven({ repoArgs: false, remote: ['4.0.81'], tail });
    assert.deepEqual(catalogVersions(evaluated), ['4.0.81']);
  });

  it('gives the newest version and its catalog, fetched as data', () => {
    const maven = fakeMaven({ local: ['4.0.0-example'] });
    const { version, catalog } = latestCatalog(maven);
    assert.equal(version, '4.0.81');
    assert.equal(catalog.version, '4.0.81');
    assert.ok(
      maven.calls.some(({ args }) =>
        args.includes('-Dartifact=org.niord.web:niord-fe-src:4.0.81:zip'),
      ),
    );
    assert.deepEqual(fs.readdirSync(path.join(maven.fe, '.niord', 'catalogs')), []);
  });

  it('gives the catalog of every version from --base up', () => {
    const maven = fakeMaven({ remote: ['4.0.79', '4.0.80', '4.0.81'] });
    assert.deepEqual(
      publishedCatalogs({ ...maven, from: '4.0.80' }).map(({ version, catalog }) => [
        version,
        catalog.version,
      ]),
      [
        ['4.0.80', '4.0.80'],
        ['4.0.81', '4.0.81'],
      ],
    );
  });

  it('says which version it reads, each before its fetch', () => {
    const maven = fakeMaven({ remote: ['4.0.79', '4.0.80', '4.0.81'] });
    const seen = [];
    const fetched = () =>
      maven.calls
        .filter(({ args }) => !args.includes('-U') && !args.includes('help:evaluate'))
        .map(({ args }) => args.find((arg) => arg.startsWith('-Dartifact=')).split(':')[2]);
    publishedCatalogs({
      ...maven,
      from: '4.0.80',
      onVersion: (version, index, count) => seen.push([version, index, count, fetched()]),
    });
    assert.deepEqual(seen, [
      ['4.0.80', 0, 2, []],
      ['4.0.81', 1, 2, ['4.0.80']],
    ]);
    assert.deepEqual(fetched(), ['4.0.80', '4.0.81']);
  });

  it('refuses a --base that is not published, or not a version', () => {
    assert.throws(
      () => publishedCatalogs({ ...fakeMaven(), from: '4.0.70' }),
      /4\.0\.70 is not a published version of org\.niord\.web:niord-fe-src \(published: 4\.0\.80, 4\.0\.81\)/,
    );
    assert.throws(
      () => publishedCatalogs({ ...fakeMaven(), from: '4.0' }),
      /--base "4\.0" is not a base product version/,
    );
  });

  it('refuses when Maven fails, finds no version, or a zip holds another catalog', () => {
    assert.throws(
      () => catalogVersions(fakeMaven({ status: 1 })),
      /Maven could not resolve the newest org\.niord\.web:niord-fe-src \(exit 1\)/,
    );
    assert.throws(
      () => catalogVersions(fakeMaven({ remote: [] })),
      /no published version of org\.niord\.web:niord-fe-src was found: Maven left no maven-metadata/,
    );
    assert.throws(
      () => latestCatalog(fakeMaven({ catalogs: { '4.0.81': catalogOf('4.0.80') } })),
      /the catalog in the zip of 4\.0\.81 names the version 4\.0\.80/,
    );
    assert.throws(
      () =>
        latestCatalog(fakeMaven({ catalogs: { '4.0.81': { ...catalogOf('4.0.81'), format: 2 } } })),
      /the catalog of 4\.0\.81 is not of format 1/,
    );
  });

  it('reads only the <versions> of a metadata file', () => {
    assert.deepEqual(
      metadataVersions(
        '<metadata><version>9.9.9</version><versioning><versions><version> 1.0.0 </version><version>1.0.1</version></versions></versioning></metadata>',
      ),
      ['1.0.0', '1.0.1'],
    );
    assert.deepEqual(metadataVersions('<metadata/>'), []);
  });
});
