import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import zlib from 'node:zlib';
import { mavenCommand as kitMavenCommand } from './lib/maven.mjs';
import { markerText } from './lib/where.mjs';
import { writeZip } from './lib/zip.mjs';
import {
  NIORD_GITIGNORE,
  handOver,
  mavenCommand,
  parentVersion,
  prepare,
  readLock,
  readZipEntries,
  unpack,
} from './templates/setup.mjs';

const temp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'niord-stub-'));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

const POM = (version) => `<project>
  <!-- <parent><version>9.9.9</version></parent> -->
  <parent>
    <groupId>org.niord</groupId>
    <artifactId>niord-parent</artifactId>
    <version>${version}</version>
  </parent>
  <version>1.0</version>
</project>
`;

/**
 * A zip of stored entries with every field under the test's control: `mode` the Unix mode in the
 * external attributes (`attributes` all of them), `name` written as given, and the general
 * purpose `flags`, the `method`, the `crc`, the unpacked `length` and the `compressed` size
 * the central directory states, and the local header's `signature`, each the correct one unless
 * given.
 */
function rawZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const {
    name,
    data = Buffer.from('x'),
    mode = 0o100644,
    attributes = (mode << 16) >>> 0,
    flags = 0,
    method = 0,
    crc = zlib.crc32(data),
    length = data.length,
    compressed = data.length,
    signature = 0x04034b50,
  } of entries) {
    const nameBytes = Buffer.from(name, 'utf8');
    const local = Buffer.alloc(30);
    local.writeUInt32LE(signature, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(flags, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed, 18);
    local.writeUInt32LE(length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE((3 << 8) | 20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(flags, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(compressed, 20);
    central.writeUInt32LE(length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(attributes, 38);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBytes, data);
    centrals.push(central, nameBytes);
    offset += local.length + nameBytes.length + data.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

/**
 * A country's fe/ and be/ in a temporary folder, its lock pinning `sha256` for `version` and the
 * kit package by `kit` (`kit: null` leaves the kit pin out). The root's .niord/ is the kit's.
 */
function country({
  version = '4.0.7',
  sha256 = 'a'.repeat(64),
  lockVersion = version,
  kit = { sha256: 'b'.repeat(64) },
} = {}) {
  const root = temp();
  const fe = path.join(root, 'fe');
  fs.mkdirSync(path.join(root, 'be'), { recursive: true });
  fs.mkdirSync(fe);
  fs.writeFileSync(path.join(root, 'be', 'pom.xml'), POM(version));
  fs.writeFileSync(
    path.join(fe, 'niord-pins.lock.json'),
    JSON.stringify({
      format: 1,
      base: { version: lockVersion, sha256 },
      ...(kit ? { kit } : {}),
      packs: {},
    }),
  );
  return { root, fe };
}

/** The coordinates a Maven run was asked for: the `-Dartifact=` argument's value. */
const artifactOf = (call) =>
  call.find((arg) => arg.startsWith('-Dartifact='))?.slice('-Dartifact='.length);

/**
 * A `run` that stands in for Maven: writes, by the artifact asked for, `feSrc` or `kit` where
 * dependency:copy would, the source zip into fe/.niord/ and the kit package into the root's .niord/.
 */
const fakeMaven =
  (root, version, { feSrc, kit }, calls = []) =>
  (command, args) => {
    calls.push([command, ...args]);
    const artifact = artifactOf(args);
    const [folder, name, bytes] =
      artifact === `org.niord.web:niord-fe-src:${version}:zip`
        ? [path.join(root, 'fe', '.niord'), `niord-fe-src-${version}.zip`, feSrc]
        : artifact === `org.niord:niord-country-kit:${version}:zip`
          ? [path.join(root, '.niord'), `niord-country-kit-${version}.zip`, kit]
          : assert.fail(`Maven was asked for ${artifact}`);
    fs.mkdirSync(folder, { recursive: true });
    fs.writeFileSync(path.join(folder, name), bytes);
    return { status: 0 };
  };

/** A kit package zip: entries at its top, the marker `kit.json` naming `version`. */
const kitZip = (version = '4.0.7', marker = markerText(version)) =>
  writeZip([
    ...(marker === null ? [] : [{ name: 'kit.json', data: Buffer.from(marker) }]),
    { name: 'setup-kit.mjs', data: Buffer.from('export async function setup() {}\n') },
    { name: 'templates/setup.mjs', data: Buffer.from('// the stub\n') },
  ]);

/** A source zip, without the kit since the kit move. */
const feSrcZip = () => writeZip([{ name: 'package.json', data: Buffer.from('{}\n') }]);

/** A country whose lock pins `feSrc` and `kit` by their hashes, and the Maven that serves them. */
function pinnedCountry({ feSrc = feSrcZip(), kit = kitZip() } = {}) {
  const { root, fe } = country({ sha256: sha(feSrc), kit: { sha256: sha(kit) } });
  const calls = [];
  const options = (env = {}) => ({
    fe,
    env,
    platform: 'linux',
    run: fakeMaven(root, '4.0.7', { feSrc, kit }, calls),
    log: () => {},
  });
  return { root, fe, feSrc, kit, calls, options };
}

/** A base product checkout beside the country: `checkout/fe/` and the kit `checkout/country-kit/`. */
function checkout(root, { kit = true } = {}) {
  const folder = path.join(root, 'checkout');
  fs.mkdirSync(path.join(folder, 'fe'), { recursive: true });
  fs.mkdirSync(path.join(folder, 'country-kit'));
  if (kit) fs.writeFileSync(path.join(folder, 'country-kit', 'setup-kit.mjs'), 'export {}\n');
  return folder;
}

const exists = (...parts) => fs.existsSync(path.join(...parts));
const text = (...parts) => fs.readFileSync(path.join(...parts), 'utf8');

describe('the set-up stub', () => {
  it('reads the parent version of the pom, comments left out', () => {
    assert.equal(parentVersion(POM('4.0.183')), '4.0.183');
  });

  it('refuses a parent version that is not a plain version', () => {
    assert.throws(() => parentVersion(POM('4.0.${run}')), /not a plain version/);
    assert.throws(() => parentVersion('<project></project>'), /no <parent>/);
  });

  it('refuses a lock file that pins another version than the pom', () => {
    const text = JSON.stringify({
      format: 1,
      base: { version: '4.0.1', sha256: 'b'.repeat(64) },
      kit: { sha256: 'c'.repeat(64) },
      packs: {},
    });
    assert.equal(readLock(text, '4.0.1').base.version, '4.0.1');
    assert.throws(
      () => readLock(text, '4.0.2'),
      /pins the base product 4\.0\.1, but .* is 4\.0\.2/,
    );
    assert.throws(
      () => readLock('{"format":1,"base":{"version":"4.0.1","sha256":"x"},"packs":{}}', '4.0.1'),
      /64 lower-case hex/,
    );
  });

  it("refuses a lock without a kit pin, or with a malformed one, naming the release notes' line", () => {
    const lock = (kit) =>
      JSON.stringify({
        format: 1,
        base: { version: '4.0.7', sha256: 'b'.repeat(64) },
        ...(kit === undefined ? {} : { kit }),
        packs: {},
      });
    for (const kit of [
      undefined,
      null,
      [],
      {},
      'c'.repeat(64),
      { sha256: 'C'.repeat(64) },
      { sha256: 'c'.repeat(63) },
      { sha256: 64 },
    ])
      assert.throws(
        () => readLock(lock(kit), '4.0.7'),
        /has no kit \{ sha256 \} \(64 lower-case hex digits\): pin the kit package by its line '<sha256> {2}niord-country-kit-4\.0\.7\.zip' in the release notes$/,
        JSON.stringify(kit),
      );
    assert.deepEqual(readLock(lock({ sha256: 'c'.repeat(64) }), '4.0.7').kit, {
      sha256: 'c'.repeat(64),
    });
  });

  it('keeps the cause of a lock that is not JSON', () => {
    assert.throws(
      () => readLock('{"format":', '4.0.7'),
      (error) =>
        /^niord-pins\.lock\.json: /.test(error.message) && error.cause instanceof SyntaxError,
    );
  });

  it('runs mvnw.cmd through cmd.exe on Windows with a command line it built, and sh mvnw elsewhere', () => {
    const windows = mavenCommand({
      platform: 'win32',
      be: 'C:\\c\\be',
      version: '4.0.7',
      outDir: 'C:\\c\\fe\\.niord',
    });
    assert.equal(windows.command, 'cmd.exe');
    assert.deepEqual(windows.args.slice(0, 3), ['/d', '/s', '/c']);
    assert.match(
      windows.args[3],
      /^""C:\\c\\be\\mvnw\.cmd" -B -q -f C:\\c\\be\\pom\.xml dependency:copy -Dartifact=org\.niord\.web:niord-fe-src:4\.0\.7:zip /,
    );
    assert.equal(windows.verbatim, true);
    const linux = mavenCommand({
      platform: 'linux',
      be: '/c/be',
      version: '4.0.7',
      outDir: '/c/fe/.niord',
    });
    assert.equal(linux.command, 'sh');
    assert.equal(linux.args[0], '/c/be/mvnw');
  });

  it('builds the same command for the kit package when asked for its coordinates', () => {
    const artifact = 'org.niord:niord-country-kit';
    const windows = mavenCommand({
      platform: 'win32',
      be: 'C:\\c\\be',
      artifact,
      version: '4.0.7',
      outDir: 'C:\\c\\.niord',
    });
    assert.equal(
      windows.args[3],
      '""C:\\c\\be\\mvnw.cmd" -B -q -f C:\\c\\be\\pom.xml dependency:copy ' +
        '-Dartifact=org.niord:niord-country-kit:4.0.7:zip -DoutputDirectory=C:\\c\\.niord"',
    );
    const linux = mavenCommand({
      platform: 'linux',
      be: '/c/be',
      artifact,
      version: '4.0.7',
      outDir: '/c/.niord',
    });
    assert.deepEqual(linux.args, [
      '/c/be/mvnw',
      '-B',
      '-q',
      '-f',
      '/c/be/pom.xml',
      'dependency:copy',
      '-Dartifact=org.niord:niord-country-kit:4.0.7:zip',
      '-DoutputDirectory=/c/.niord',
    ]);
    assert.equal(
      artifactOf(
        mavenCommand({ platform: 'linux', be: '/c/be', version: '4.0.7', outDir: '/o' }).args,
      ),
      'org.niord.web:niord-fe-src:4.0.7:zip',
    );
  });

  it('quotes a checkout path with a space, a letter outside ASCII or parentheses on Windows', () => {
    const root = 'C:\\Users\\Søren Hansen\\niord (2)';
    const windows = mavenCommand({
      platform: 'win32',
      be: `${root}\\be`,
      version: '4.0.7',
      outDir: `${root}\\fe\\.niord`,
    });
    assert.equal(
      windows.args[3],
      `""${root}\\be\\mvnw.cmd" -B -q -f "${root}\\be\\pom.xml" dependency:copy ` +
        `-Dartifact=org.niord.web:niord-fe-src:4.0.7:zip "-DoutputDirectory=${root}\\fe\\.niord""`,
    );
    const plain = mavenCommand({
      platform: 'win32',
      be: 'C:\\Users\\Søren\\be',
      version: '4.0.7',
      outDir: 'C:\\Users\\Søren\\fe\\.niord',
    });
    assert.match(plain.args[3], / "-DoutputDirectory=C:\\Users\\Søren\\fe\\\.niord""$/);
  });

  it('refuses a path cmd.exe would read as an operator', () => {
    assert.throws(
      () =>
        mavenCommand({ platform: 'win32', be: 'C:\\a&b\\be', version: '4.0.7', outDir: 'C:\\o' }),
      /a character Maven is not run with/,
    );
    assert.throws(
      () =>
        mavenCommand({ platform: 'win32', be: 'C:\\c\\be', version: '4.0.7', outDir: 'C:\\o"x' }),
      /a character Maven is not run with/,
    );
  });

  it("quotes the kit's own Maven runs the same way, and refuses the same paths", () => {
    const root = 'C:\\Users\\Søren Hansen\\niord (2)';
    const command = kitMavenCommand({
      platform: 'win32',
      be: `${root}\\be`,
      args: ['-B', `-Dmaven.repo.local=${root}\\m2`, 'install'],
      paths: [`-Dmaven.repo.local=${root}\\m2`],
    });
    assert.equal(
      command.args[3],
      `""${root}\\be\\mvnw.cmd" -B "-Dmaven.repo.local=${root}\\m2" install"`,
    );
    assert.throws(
      () => kitMavenCommand({ platform: 'win32', be: 'C:\\a|b', args: ['install'] }),
      /a character Maven is not run with/,
    );
    assert.throws(
      () => kitMavenCommand({ platform: 'win32', be: 'C:\\c\\be', args: ['-Dx=a b'] }),
      /the argument -Dx=a b has a character/,
    );
  });

  it('reads back what the kit writes', () => {
    const entries = [
      { name: 'a/b.txt', data: Buffer.from('hello') },
      { name: 'c.json', data: Buffer.from('{}'.repeat(500)) },
    ];
    const read = readZipEntries(writeZip(entries));
    assert.deepEqual(
      read.map(({ name, data }) => [name, data.toString()]),
      entries.map(({ name, data }) => [name, data.toString()]),
    );
  });

  for (const [what, entries, message] of [
    ['a link', [{ name: 'link', mode: 0o120777 }], /not a regular file/],
    ['a path out of the folder', [{ name: 'a/../../evil' }], /not a relative path/],
    ['an absolute path', [{ name: '/etc/passwd' }], /not a relative path/],
    ['a drive letter', [{ name: 'C:/evil' }], /not a relative path/],
    ['a backslash', [{ name: 'a\\b' }], /not a relative path/],
    ['a name twice, in another case', [{ name: 'a.txt' }, { name: 'A.txt' }], /twice/],
    ['an encrypted entry', [{ name: 'a', flags: 0x1 }], /entry a is encrypted/],
    ['a method other than stored or deflate', [{ name: 'a', method: 12 }], /compression method 12/],
    ['a folder by its mode', [{ name: 'd', mode: 0o040755 }], /not a regular file/],
    ['a device', [{ name: 'd', mode: 0o020644 }], /not a regular file/],
    [
      'a folder by its attribute bit',
      [{ name: 'd', attributes: ((0o100644 << 16) | 0x10) >>> 0 }],
      /entry d is a folder/,
    ],
    ['an empty name', [{ name: '' }], /not a relative path/],
    ['an empty segment', [{ name: 'a//b' }], /not a relative path/],
    ['a `.` segment', [{ name: 'a/./b' }], /not a relative path/],
    ['a control character in a name', [{ name: 'a\nb' }], /not a relative path/],
    ['a broken local header', [{ name: 'a', signature: 0x12345678 }], /has no local header for a/],
    [
      'an entry longer than it says',
      [{ name: 'a', length: 2 }],
      /entry a does not match its checksum/,
    ],
    [
      'an entry whose checksum is not its own',
      [{ name: 'a', crc: 1 }],
      /does not match its checksum/,
    ],
    ['an entry that runs past the archive', [{ name: 'a', compressed: 4096 }], /runs past the end/],
  ])
    it(`refuses ${what}`, () => {
      assert.throws(() => readZipEntries(rawZip(entries)), message);
    });

  it('refuses an archive larger than it may unpack to', () => {
    const zip = rawZip([{ name: 'big', data: Buffer.alloc(2048) }]);
    assert.throws(() => readZipEntries(zip, { maxBytes: 1024 }), /more than the 1024 bytes/);
    assert.throws(
      () => readZipEntries(rawZip([{ name: 'a' }, { name: 'b' }]), { maxEntries: 1 }),
      /2 entries/,
    );
  });

  it('unpacks into a fresh folder and puts it in place', () => {
    const dir = temp();
    const target = path.join(dir, 'base');
    fs.mkdirSync(target);
    fs.writeFileSync(path.join(target, 'stale'), 'old');
    unpack([{ name: 'x/y.txt', data: Buffer.from('y') }], target);
    assert.equal(fs.readFileSync(path.join(target, 'x', 'y.txt'), 'utf8'), 'y');
    assert.equal(fs.existsSync(path.join(target, 'stale')), false);
  });

  it('verifies the fetched source zip against the pin before it reads a single entry', () => {
    const bytes = rawZip([{ name: '../outside' }]);
    const kit = kitZip();
    const { root, fe } = country({ sha256: 'c'.repeat(64), kit: { sha256: sha(kit) } });
    assert.throws(
      () =>
        prepare({
          fe,
          env: {},
          platform: 'linux',
          run: fakeMaven(root, '4.0.7', { feSrc: bytes, kit }),
          log: () => {},
        }),
      /niord-fe-src-4\.0\.7\.zip has the SHA-256 [0-9a-f]{64}, not the pinned c{64}: nothing was unpacked/,
    );
    assert.equal(exists(fe, '.niord', 'base'), false);
    assert.equal(exists(fe, '.niord', 'base.sha256'), false);
  });

  it('verifies the fetched kit zip against its pin before it reads a single entry', () => {
    const { root, fe } = country({ kit: { sha256: 'c'.repeat(64) } });
    const calls = [];
    assert.throws(
      () =>
        prepare({
          fe,
          env: {},
          platform: 'linux',
          run: fakeMaven(
            root,
            '4.0.7',
            { feSrc: feSrcZip(), kit: rawZip([{ name: '../outside' }]) },
            calls,
          ),
          log: () => {},
        }),
      /niord-country-kit-4\.0\.7\.zip has the SHA-256 [0-9a-f]{64}, not the pinned c{64}: nothing was unpacked/,
    );
    assert.deepEqual(calls.map(artifactOf), ['org.niord:niord-country-kit:4.0.7:zip']);
    assert.equal(exists(root, '.niord', 'kit'), false);
    assert.equal(exists(root, '.niord', 'kit.sha256'), false);
  });

  it('fetches the kit and then the source zip on a cold start, unpacks and marks both, and keeps the kit zip', () => {
    const { root, fe, feSrc, kit, calls, options } = pinnedCountry();
    const lines = [];
    const handover = prepare({ ...options(), log: (line) => lines.push(line) });
    assert.deepEqual(calls.map(artifactOf), [
      'org.niord:niord-country-kit:4.0.7:zip',
      'org.niord.web:niord-fe-src:4.0.7:zip',
    ]);
    assert.ok(calls[0].includes(`-DoutputDirectory=${path.join(root, '.niord')}`));
    assert.ok(calls[1].includes(`-DoutputDirectory=${path.join(fe, '.niord')}`));
    assert.deepEqual(handover, {
      source: path.join(fe, '.niord', 'base'),
      kit: path.join(root, '.niord', 'kit'),
      version: '4.0.7',
      sha256: sha(feSrc),
      kitSha256: sha(kit),
      provenance: `pinned: org.niord.web:niord-fe-src:4.0.7:zip sha256:${sha(feSrc)}`,
    });
    assert.equal(text(root, '.niord', 'kit', 'kit.json'), markerText('4.0.7'));
    assert.ok(exists(root, '.niord', 'kit', 'setup-kit.mjs'));
    assert.ok(exists(root, '.niord', 'kit', 'templates', 'setup.mjs'));
    assert.equal(text(root, '.niord', 'kit.sha256'), `${sha(kit)}\n`);
    assert.ok(
      fs.readFileSync(path.join(root, '.niord', 'niord-country-kit-4.0.7.zip')).equals(kit),
    );
    assert.ok(exists(fe, '.niord', 'base', 'package.json'));
    assert.equal(text(fe, '.niord', 'base.sha256'), `${sha(feSrc)}\n`);
    assert.deepEqual(lines, [
      'setup: fetching org.niord:niord-country-kit:4.0.7:zip through ../be/mvnw',
      `setup: the kit 4.0.7 verified (sha256 ${sha(kit).slice(0, 12)}…) and unpacked into ../.niord/kit/`,
      'setup: fetching org.niord.web:niord-fe-src:4.0.7:zip through ../be/mvnw',
      `setup: the base product 4.0.7 verified (sha256 ${sha(feSrc).slice(0, 12)}…) and unpacked into .niord/base/`,
    ]);
  });

  it('uses both unpacked packages again outside CI, and fetches both again under CI', () => {
    const { root, fe, feSrc, kit, calls, options } = pinnedCountry();
    prepare(options());
    assert.equal(calls.length, 2);
    const lines = [];
    const again = prepare({ ...options(), log: (line) => lines.push(line) });
    assert.equal(calls.length, 2);
    assert.equal(again.kitSha256, sha(kit));
    assert.equal(again.kit, path.join(root, '.niord', 'kit'));
    assert.deepEqual(lines, [
      `setup: the kit 4.0.7 is unpacked already (sha256 ${sha(kit).slice(0, 12)}…)`,
      `setup: the base product 4.0.7 is unpacked already (sha256 ${sha(feSrc).slice(0, 12)}…)`,
    ]);
    prepare(options({ CI: 'true' }));
    assert.deepEqual(calls.slice(2).map(artifactOf), [
      'org.niord:niord-country-kit:4.0.7:zip',
      'org.niord.web:niord-fe-src:4.0.7:zip',
    ]);
    // A source zip whose marker is gone is fetched again alone; the kit's still matches.
    fs.rmSync(path.join(fe, '.niord', 'base.sha256'));
    prepare(options());
    assert.deepEqual(calls.slice(4).map(artifactOf), ['org.niord.web:niord-fe-src:4.0.7:zip']);
  });

  for (const [what, kit, message] of [
    [
      'without kit.json',
      kitZip('4.0.7', null),
      /niord-country-kit-4\.0\.7\.zip has no kit\.json: it is not the kit package, and nothing was unpacked/,
    ],
    [
      'whose kit.json names another version',
      kitZip('4.0.7', markerText('4.0.8')),
      /niord-country-kit-4\.0\.7\.zip's kit\.json names niord-country-kit 4\.0\.8, not niord-country-kit 4\.0\.7: nothing was unpacked/,
    ],
    [
      'whose kit.json names another package',
      kitZip('4.0.7', JSON.stringify({ package: 'niord-fe-src', version: '4.0.7' })),
      /kit\.json names niord-fe-src 4\.0\.7, not niord-country-kit 4\.0\.7: nothing was unpacked/,
    ],
    [
      'whose kit.json is not a JSON object',
      kitZip('4.0.7', '["niord-country-kit"'),
      /kit\.json names no package and version, not niord-country-kit 4\.0\.7: nothing was unpacked/,
    ],
  ])
    it(`refuses a kit zip ${what} before anything is unpacked`, () => {
      const { root, fe } = country({ kit: { sha256: sha(kit) } });
      const calls = [];
      assert.throws(
        () =>
          prepare({
            fe,
            env: {},
            platform: 'linux',
            run: fakeMaven(root, '4.0.7', { feSrc: feSrcZip(), kit }, calls),
            log: () => {},
          }),
        message,
      );
      assert.equal(calls.length, 1);
      assert.equal(exists(root, '.niord', 'kit'), false);
      assert.equal(exists(root, '.niord', 'kit.sha256'), false);
    });

  it("writes the root's .niord/.gitignore holding * in both modes, over another content", () => {
    assert.equal(NIORD_GITIGNORE, '*\n');
    const pinned = pinnedCountry();
    fs.mkdirSync(path.join(pinned.root, '.niord'));
    fs.writeFileSync(path.join(pinned.root, '.niord', '.gitignore'), 'kit/\n');
    prepare(pinned.options());
    assert.equal(text(pinned.root, '.niord', '.gitignore'), '*\n');

    const { root, fe } = country();
    checkout(root);
    prepare({ fe, env: { NIORD_BASE: '../checkout/fe' }, run: () => assert.fail('no Maven') });
    assert.equal(text(root, '.niord', '.gitignore'), '*\n');
  });

  it('refuses NIORD_BASE when CI is set', () => {
    const { fe } = country();
    assert.throws(
      () =>
        prepare({
          fe,
          env: { NIORD_BASE: '../base', CI: '1' },
          run: () => assert.fail('no Maven'),
          log: () => {},
        }),
      /NIORD_BASE is refused when CI is set/,
    );
  });

  it("hands a NIORD_BASE checkout's fe/ and the kit beside it over, local provenance, Maven never run", () => {
    const { root, fe } = country();
    const folder = checkout(root);
    fs.mkdirSync(path.join(fe, '.niord'));
    fs.writeFileSync(path.join(fe, '.niord', 'base.sha256'), `${'a'.repeat(64)}\n`);
    fs.mkdirSync(path.join(root, '.niord'));
    fs.writeFileSync(path.join(root, '.niord', 'kit.sha256'), `${'b'.repeat(64)}\n`);
    const { provenance, ...handover } = prepare({
      fe,
      env: { NIORD_BASE: '../checkout/fe' },
      run: () => assert.fail('no Maven'),
      log: () => {},
    });
    assert.deepEqual(handover, {
      source: path.join(folder, 'fe'),
      kit: path.join(folder, 'country-kit'),
      version: '4.0.7',
      sha256: null,
      kitSha256: null,
    });
    assert.match(provenance, /^local: .*checkout\/fe @ /);
    assert.equal(exists(fe, '.niord', 'base.sha256'), false);
    assert.equal(exists(root, '.niord', 'kit.sha256'), false);
  });

  it('refuses a NIORD_BASE without the kit beside it, and a lock without the kit pin in that mode too', () => {
    const { root, fe } = country();
    checkout(root, { kit: false });
    // The layout from before the kit move: the kit inside the checkout's fe/.
    fs.mkdirSync(path.join(root, 'checkout', 'fe', 'country-kit'));
    fs.writeFileSync(path.join(root, 'checkout', 'fe', 'country-kit', 'setup-kit.mjs'), '');
    assert.throws(
      () =>
        prepare({ fe, env: { NIORD_BASE: '../checkout/fe' }, run: () => assert.fail('no Maven') }),
      /^Error: NIORD_BASE=\.\.\/checkout\/fe is not a base product checkout's fe\/ \(no \.\.\/country-kit\/setup-kit\.mjs beside it\)$/,
    );
    const unpinned = country({ kit: null });
    checkout(unpinned.root);
    assert.throws(
      () =>
        prepare({
          fe: unpinned.fe,
          env: { NIORD_BASE: '../checkout/fe' },
          run: () => assert.fail('no Maven'),
        }),
      /has no kit \{ sha256 \}/,
    );
  });

  it('refuses a lock without the kit pin before Maven is run', () => {
    const { fe } = country({ kit: null });
    assert.throws(
      () => prepare({ fe, env: {}, run: () => assert.fail('no Maven'), log: () => {} }),
      /has no kit \{ sha256 \}.*niord-country-kit-4\.0\.7\.zip/,
    );
  });

  it("hands over to the kit's setup-kit.mjs with exactly the set-up's arguments", async () => {
    const pinned = pinnedCountry();
    const { root, fe } = country();
    checkout(root);
    for (const [where, handover] of [
      [pinned.fe, prepare(pinned.options())],
      [fe, prepare({ fe, env: { NIORD_BASE: '../checkout/fe' }, run: () => assert.fail() })],
    ]) {
      const loaded = [];
      const given = [];
      const load = async (file) => {
        loaded.push(file);
        return { setup: async (args) => given.push(args) };
      };
      const stub = path.join(where, 'setup.mjs');
      await handOver({ fe: where, stub, handover, load });
      assert.deepEqual(loaded, [path.join(handover.kit, 'setup-kit.mjs')]);
      const { source, version, sha256, kitSha256, provenance } = handover;
      assert.deepEqual(given, [
        { fe: where, stub, source, version, sha256, kitSha256, provenance },
      ]);
    }
  });

  it('imports the hand-over file by its URL unless told otherwise, and passes its failure on', async () => {
    const kit = temp();
    const out = path.join(kit, 'given.json');
    fs.writeFileSync(
      path.join(kit, 'setup-kit.mjs'),
      `import fs from 'node:fs';
export async function setup(args) {
  if (args.version === 'fail') throw new Error('refused');
  fs.writeFileSync(${JSON.stringify(out)}, JSON.stringify(args));
}
`,
    );
    const handover = {
      source: 's',
      kit,
      version: '4.0.7',
      sha256: null,
      kitSha256: null,
      provenance: 'p',
    };
    await handOver({ fe: 'f', stub: 'f/setup.mjs', handover });
    assert.deepEqual(JSON.parse(fs.readFileSync(out, 'utf8')), {
      fe: 'f',
      stub: 'f/setup.mjs',
      source: 's',
      version: '4.0.7',
      sha256: null,
      kitSha256: null,
      provenance: 'p',
    });
    await assert.rejects(
      handOver({ fe: 'f', stub: 'f/setup.mjs', handover: { ...handover, version: 'fail' } }),
      /refused/,
    );
  });

  it('fails clearly when the lock pins another version than the pom', () => {
    const { fe } = country({ version: '4.0.8', lockVersion: '4.0.7' });
    assert.throws(
      () => prepare({ fe, env: {}, run: () => assert.fail('no Maven'), log: () => {} }),
      /pin the new version's zip/,
    );
  });
});
