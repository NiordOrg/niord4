#!/usr/bin/env node
/**
 * A country's set-up: fetches the two pinned packages of the base product, the kit and the source
 * zip, verifies them and hands over to the kit, which checks the country's packs and branding,
 * stages what the builds ship and generates the Angular workspace in this folder.
 *
 *   node setup.mjs                          # run from the country repository's fe/
 *   NIORD_BASE=../../niord-ng/fe node setup.mjs   # a base product checkout instead (never in CI)
 *
 * This file is written by the kit's `init` and is never edited: the kit compares it with its own
 * copy and refuses a changed one. A change to it in the base product is a breaking change,
 * recorded in the kit's API-CHANGES.md under its version with what a country must do: copy
 * ../.niord/kit/templates/setup.mjs over setup.mjs, in fe/.
 *
 * It runs before anything of the base product does, so it uses Node's built-ins only and trusts
 * nothing of the base product it has not hashed. What it does trust is Node itself, the Maven
 * distribution ../be/mvnw downloads and Maven's plugins (the accepted residual trust of
 * fe-country-split-plan §4.11 rule 2):
 *
 *   1. the base product version is the `<parent>` version of ../be/pom.xml, and
 *      niord-pins.lock.json pins that version's source zip by its SHA-256 and the kit package of
 *      the same version by its own (`kit.sha256`); a lock without the kit pin is refused;
 *   2. the root's ../.niord/.gitignore holds `*`, so the kit and its zip ignore themselves;
 *   3. ../be/mvnw fetches the kit package org.niord:niord-country-kit:<version>:zip into
 *      ../.niord/ (Maven's credentials and cache serve it as they serve the backend; MAVEN_ARGS
 *      passes through), the zip is hashed and compared with the pin before a single entry is
 *      read, its kit.json must name the same version, and it is unpacked into ../.niord/kit/ —
 *      refusing links, anything but regular files, absolute or `..` or backslash paths,
 *      duplicate names and an oversized archive. The zip is kept: the next upgrade report reads
 *      the previous kit from it. A kit unpacked from the pinned hash before (the marker
 *      ../.niord/kit.sha256) is used again, outside CI;
 *   4. the same for the source zip org.niord.web:niord-fe-src:<version>:zip: fetched into
 *      .niord/, verified, unpacked into .niord/base/ and marked by .niord/base.sha256;
 *   5. with NIORD_BASE, a checkout's fe/ is used instead and the kit beside it, its
 *      ../country-kit/, which copies itself into ../.niord/kit/ when it runs; refused when CI is
 *      set, and recorded in the build report as `local: <path> @ <commit>` (a production build
 *      refuses such a workspace);
 *   6. the kit's setup-kit.mjs does the rest.
 */
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import zlib from 'node:zlib';

export const VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]*$/;
const SHA256 = /^[0-9a-f]{64}$/;
const LOCK_FORMAT = 1;
const ARTIFACT = 'org.niord.web:niord-fe-src';
const KIT_ARTIFACT = 'org.niord:niord-country-kit';
// The kit package's marker at its top: { package, version }.
const KIT_MARKER = 'kit.json';
const KIT_PACKAGE = 'niord-country-kit';
/** The root's .niord/.gitignore: the folder (the kit and its zip) ignores itself. */
export const NIORD_GITIGNORE = '*\n';
const MAX_ENTRIES = 20_000;
const MAX_BYTES = 512 * 1024 * 1024;
// What may appear in an argument handed to Maven, and in a path handed to cmd.exe on Windows.
const SAFE_ARGUMENT = /^[0-9A-Za-z@:._=/\\+,-]+$/;
const UNSAFE_PATH = /["%^&|<>!\r\n]/;

/** The `<parent><version>` of a pom, comments left out; throws unless it is a plain version. */
export function parentVersion(pom) {
  const text = pom.replace(/<!--[\s\S]*?-->/g, '');
  const parent = text.match(/<parent>([\s\S]*?)<\/parent>/);
  const version = parent?.[1].match(/<version>\s*([^<]*?)\s*<\/version>/)?.[1];
  if (!version) throw new Error('../be/pom.xml has no <parent> with a <version>');
  if (!VERSION.test(version))
    throw new Error(`../be/pom.xml's parent version "${version}" is not a plain version`);
  return version;
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

/** The pins, checked for their shape and against the pom's version; the kit pin is required. */
export function readLock(text, version) {
  let lock;
  try {
    lock = JSON.parse(text);
  } catch (error) {
    throw new Error(`niord-pins.lock.json: ${error.message}`, { cause: error });
  }
  if (!isObject(lock) || lock.format !== LOCK_FORMAT)
    throw new Error(`niord-pins.lock.json is not a lock file of format ${LOCK_FORMAT}`);
  if (
    !isObject(lock.base) ||
    !VERSION.test(lock.base.version ?? '') ||
    !SHA256.test(lock.base.sha256 ?? '')
  )
    throw new Error(
      'niord-pins.lock.json has no base { version, sha256 } (64 lower-case hex digits)',
    );
  if (!isObject(lock.packs)) throw new Error('niord-pins.lock.json has no packs object');
  if (lock.base.version !== version)
    throw new Error(
      `niord-pins.lock.json pins the base product ${lock.base.version}, but ../be/pom.xml's parent is ${version}: pin the new version's zip with its SHA-256`,
    );
  if (!isObject(lock.kit) || !SHA256.test(lock.kit.sha256 ?? ''))
    throw new Error(
      `niord-pins.lock.json has no kit { sha256 } (64 lower-case hex digits): pin the kit package by its line '<sha256>  ${KIT_PACKAGE}-${version}.zip' in the release notes`,
    );
  return lock;
}

/** The SHA-256 of some bytes, as 64 lower-case hex digits. */
export const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

/**
 * The command that fetches the zip of `artifact` (the source zip unless told otherwise) into
 * `outDir` through the country's Maven wrapper: `sh mvnw`, or on Windows `cmd.exe` running
 * `mvnw.cmd` with a command line built here, every part checked first so that nothing in it is
 * read by cmd.exe as an operator. The two paths may hold spaces, letters outside ASCII and
 * parentheses (a checkout under `C:\Users\Søren Hansen\`); the other arguments are this file's
 * own and the checked version.
 */
export function mavenCommand({ platform, be, artifact = ARTIFACT, version, outDir }) {
  // Joined for the platform the command is for, whatever this runs on (a test builds both).
  const join = (...parts) => (platform === 'win32' ? path.win32 : path.posix).join(...parts);
  const goal = ['dependency:copy', `-Dartifact=${artifact}:${version}:zip`];
  const args = ['-B', '-q', '-f', join(be, 'pom.xml'), ...goal, `-DoutputDirectory=${outDir}`];
  if (!VERSION.test(version)) throw new Error(`"${version}" is not a plain version`);
  for (const value of [be, outDir])
    if (UNSAFE_PATH.test(value))
      throw new Error(`the path ${value} has a character Maven is not run with`);
  if (platform !== 'win32')
    return { command: 'sh', args: [join(be, 'mvnw'), ...args], verbatim: false };
  for (const arg of goal)
    if (!SAFE_ARGUMENT.test(arg))
      throw new Error(`the argument ${arg} has a character Maven is not run with`);
  // A path argument is quoted unless it is plain: cmd.exe and the wrapper's batch files then read
  // its spaces and parentheses as text. It holds no quote of its own (UNSAFE_PATH).
  const quote = (value) => (SAFE_ARGUMENT.test(value) ? value : `"${value}"`);
  const line = [`"${join(be, 'mvnw.cmd')}"`, ...args.map(quote)].join(' ');
  return { command: 'cmd.exe', args: ['/d', '/s', '/c', `"${line}"`], verbatim: true };
}

/**
 * The entries of a zip, read only after its hash matched: `{ name, data }` per regular file.
 * The minimal reader: country-kit/lib/zip.mjs is the full one, which the kit uses once set-up
 * has handed over. This one is a smaller copy by design: the stub must be Node's built-ins in
 * one file, and it reads nothing but an archive whose hash the pin names, made by the base
 * product's own zip writer. Throws on anything a regular file tree cannot hold or this reader
 * does not read: an encrypted entry, a method other than stored or deflate, a link, a folder, a
 * device, an absolute path, a drive letter, a backslash, an empty, `.` or `..` segment, a name
 * given twice (in any case), an entry without its local header or whose size or checksum does
 * not match, and an archive over `maxEntries` entries or `maxBytes` bytes unpacked.
 */
export function readZipEntries(buffer, { maxEntries = MAX_ENTRIES, maxBytes = MAX_BYTES } = {}) {
  const fail = (message) => {
    throw new Error(`the zip ${message}`);
  };
  let end = -1;
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 0xffff); i--)
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      end = i;
      break;
    }
  if (end < 0) fail('has no end of central directory');
  const count = buffer.readUInt16LE(end + 10);
  const size = buffer.readUInt32LE(end + 12);
  let offset = buffer.readUInt32LE(end + 16);
  if (count === 0xffff || offset === 0xffffffff)
    fail('is a zip64 archive, which this reader does not read');
  if (count > maxEntries) fail(`has ${count} entries, over the ${maxEntries} it may have`);
  if (offset + size > end) fail('has a central directory outside the archive');
  const entries = [];
  const seen = new Set();
  let total = 0;
  for (let n = 0; n < count; n++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50)
      fail(`has a broken central directory at entry ${n + 1}`);
    const madeBy = buffer.readUInt16LE(offset + 4) >> 8;
    const flags = buffer.readUInt16LE(offset + 8);
    const method = buffer.readUInt16LE(offset + 10);
    const crc = buffer.readUInt32LE(offset + 16);
    const compressed = buffer.readUInt32LE(offset + 20);
    const length = buffer.readUInt32LE(offset + 24);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const attributes = buffer.readUInt32LE(offset + 38);
    const local = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);
    offset += 46 + nameLength + extraLength + commentLength;

    if (flags & 0x1) fail(`entry ${name} is encrypted`);
    if (method !== 0 && method !== 8) fail(`entry ${name} uses the compression method ${method}`);
    const type = (attributes >>> 16) & 0o170000;
    if (madeBy === 3 && type !== 0o100000 && type !== 0)
      fail(`entry ${name} is not a regular file`);
    if (attributes & 0x10) fail(`entry ${name} is a folder`);
    if (
      !name ||
      name.startsWith('/') ||
      /^[A-Za-z]:/.test(name) ||
      name.includes('\\') ||
      Array.from(name).some((character) => character.charCodeAt(0) < 0x20) ||
      name.split('/').some((segment) => segment === '' || segment === '.' || segment === '..')
    )
      fail(`entry "${name}" is not a relative path inside the archive`);
    const key = name.toLowerCase();
    if (seen.has(key)) fail(`names ${name} twice`);
    seen.add(key);
    total += length;
    if (total > maxBytes) fail(`unpacks to more than the ${maxBytes} bytes it may`);

    if (buffer.readUInt32LE(local) !== 0x04034b50) fail(`has no local header for ${name}`);
    const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
    const raw = buffer.subarray(start, start + compressed);
    if (raw.length !== compressed) fail(`entry ${name} runs past the end of the archive`);
    const data =
      method === 0
        ? Buffer.from(raw)
        : zlib.inflateRawSync(raw, { maxOutputLength: Math.max(length, 1) });
    if (data.length !== length || zlib.crc32(data) !== crc)
      fail(`entry ${name} does not match its checksum`);
    entries.push({ name, data });
  }
  return entries;
}

/** Writes the entries into a fresh folder beside `target` and then puts it in its place. */
export function unpack(entries, target) {
  const temporary = `${target}.${process.pid}.tmp`;
  fs.rmSync(temporary, { recursive: true, force: true });
  fs.mkdirSync(temporary, { recursive: true });
  for (const { name, data } of entries) {
    const file = path.join(temporary, ...name.split('/'));
    const inside = path.relative(temporary, file);
    if (inside.startsWith('..') || path.isAbsolute(inside))
      throw new Error(`the zip entry ${name} leads out of the folder`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, data, { flag: 'wx', mode: 0o644 });
  }
  fs.rmSync(target, { recursive: true, force: true });
  fs.renameSync(temporary, target);
}

/** The commit a checkout is at, or 'no git'. */
function commitOf(dir) {
  const result = spawnSync('git', ['-C', dir, 'rev-parse', 'HEAD'], { encoding: 'utf8' });
  return result.status === 0 ? result.stdout.trim() : 'no git';
}

/** True outside CI when `folder` was unpacked from the zip of the hash `pin`, by its `marker`. */
function unpackedAlready({ env, folder, marker, pin }) {
  return (
    !env.CI &&
    fs.existsSync(folder) &&
    fs.existsSync(marker) &&
    fs.readFileSync(marker, 'utf8').trim() === pin
  );
}

/**
 * Fetches the zip of `artifact` through ../be/mvnw into `folder` (`shown` as the messages name
 * it, from fe/) and compares its hash with the pin before a single entry is read. Returns its
 * bytes and their hash.
 */
function fetchZip({ be, platform, run, log, artifact, version, folder, shown, pin }) {
  const zip = path.join(folder, `${artifact.split(':')[1]}-${version}.zip`);
  fs.mkdirSync(folder, { recursive: true });
  fs.rmSync(zip, { force: true });
  log(`setup: fetching ${artifact}:${version}:zip through ../be/mvnw`);
  const { command, args, verbatim } = mavenCommand({
    platform,
    be,
    artifact,
    version,
    outDir: folder,
  });
  const result = run(command, args, { stdio: 'inherit', windowsVerbatimArguments: verbatim });
  if (result.error) throw new Error(`Maven could not be run: ${result.error.message}`);
  if (result.status !== 0)
    throw new Error(`Maven could not fetch ${artifact}:${version}:zip (exit ${result.status})`);
  if (!fs.existsSync(zip)) throw new Error(`Maven left no ${path.basename(zip)} in ${shown}`);
  const bytes = fs.readFileSync(zip);
  const actual = sha256(bytes);
  if (actual !== pin)
    throw new Error(
      `${path.basename(zip)} has the SHA-256 ${actual}, not the pinned ${pin}: nothing was unpacked`,
    );
  return { bytes, actual };
}

/** Throws unless the kit package's entries hold its marker naming `version`. */
function checkKitMarker(entries, zip, version) {
  const marker = entries.find((entry) => entry.name === KIT_MARKER);
  if (!marker)
    throw new Error(
      `${zip} has no ${KIT_MARKER}: it is not the kit package, and nothing was unpacked`,
    );
  let found;
  try {
    found = JSON.parse(marker.data.toString('utf8'));
  } catch {
    found = undefined;
  }
  if (isObject(found) && found.package === KIT_PACKAGE && found.version === version) return;
  const named =
    isObject(found) && typeof found.package === 'string' && typeof found.version === 'string'
      ? `${found.package} ${found.version}`
      : 'no package and version';
  throw new Error(
    `${zip}'s ${KIT_MARKER} names ${named}, not ${KIT_PACKAGE} ${version}: nothing was unpacked`,
  );
}

/**
 * Steps 1 to 5: the pins, the root's .niord/.gitignore, the verified kit and base product, and
 * where they came from. Returns what the kit is handed: the folder the base product's files are
 * in, the kit's folder, the version, the two zips' hashes and the provenance.
 */
export function prepare({
  fe,
  env = process.env,
  platform = process.platform,
  run = spawnSync,
  log = console.log,
}) {
  const root = path.resolve(fe, '..');
  const be = path.join(root, 'be');
  const version = parentVersion(fs.readFileSync(path.join(be, 'pom.xml'), 'utf8'));
  const lockFile = path.join(fe, 'niord-pins.lock.json');
  if (!fs.existsSync(lockFile))
    throw new Error(
      'niord-pins.lock.json does not exist: it pins the base product zip by its SHA-256',
    );
  const lock = readLock(fs.readFileSync(lockFile, 'utf8'), version);
  if (env.NIORD_BASE && env.CI)
    throw new Error(
      'NIORD_BASE is refused when CI is set: a pipeline builds from the pinned zip only',
    );
  const shared = path.join(root, '.niord');
  const niord = path.join(fe, '.niord');
  const marker = path.join(niord, 'base.sha256');
  const kitMarker = path.join(shared, 'kit.sha256');
  fs.mkdirSync(shared, { recursive: true });
  const ignore = path.join(shared, '.gitignore');
  if (!fs.existsSync(ignore) || fs.readFileSync(ignore, 'utf8') !== NIORD_GITIGNORE)
    fs.writeFileSync(ignore, NIORD_GITIGNORE);

  if (env.NIORD_BASE) {
    const source = path.resolve(fe, env.NIORD_BASE);
    const kit = path.resolve(source, '..', 'country-kit');
    if (!fs.existsSync(path.join(kit, 'setup-kit.mjs')))
      throw new Error(
        `NIORD_BASE=${env.NIORD_BASE} is not a base product checkout's fe/ (no ../country-kit/setup-kit.mjs beside it)`,
      );
    fs.rmSync(marker, { force: true });
    fs.rmSync(kitMarker, { force: true });
    return {
      source,
      kit,
      version,
      sha256: null,
      kitSha256: null,
      provenance: `local: ${source.replaceAll('\\', '/')} @ ${commitOf(source)}`,
    };
  }

  const maven = { be, platform, run, log, version };
  const kit = path.join(shared, 'kit');
  const kitSha256 = lock.kit.sha256;
  if (unpackedAlready({ env, folder: kit, marker: kitMarker, pin: kitSha256 })) {
    log(`setup: the kit ${version} is unpacked already (sha256 ${kitSha256.slice(0, 12)}…)`);
  } else {
    const { bytes, actual } = fetchZip({
      ...maven,
      artifact: KIT_ARTIFACT,
      folder: shared,
      shown: '../.niord/',
      pin: kitSha256,
    });
    const entries = readZipEntries(bytes);
    checkKitMarker(entries, `${KIT_PACKAGE}-${version}.zip`, version);
    fs.rmSync(kitMarker, { force: true });
    unpack(entries, kit);
    fs.writeFileSync(kitMarker, `${actual}\n`);
    log(
      `setup: the kit ${version} verified (sha256 ${actual.slice(0, 12)}…) and unpacked into ../.niord/kit/`,
    );
  }

  const base = path.join(niord, 'base');
  const provenance = `pinned: ${ARTIFACT}:${version}:zip sha256:${lock.base.sha256}`;
  if (unpackedAlready({ env, folder: base, marker, pin: lock.base.sha256 })) {
    log(
      `setup: the base product ${version} is unpacked already (sha256 ${lock.base.sha256.slice(0, 12)}…)`,
    );
  } else {
    const { bytes, actual } = fetchZip({
      ...maven,
      artifact: ARTIFACT,
      folder: niord,
      shown: '.niord/',
      pin: lock.base.sha256,
    });
    fs.rmSync(marker, { force: true });
    unpack(readZipEntries(bytes), base);
    fs.writeFileSync(marker, `${actual}\n`);
    log(
      `setup: the base product ${version} verified (sha256 ${actual.slice(0, 12)}…) and unpacked into .niord/base/`,
    );
  }
  return { source: base, kit, version, sha256: lock.base.sha256, kitSha256, provenance };
}

/**
 * Step 6: hands over to setup-kit.mjs in the kit folder `prepare` returned (../.niord/kit/, or
 * the kit beside a checkout's fe/), with what it verified.
 */
export async function handOver({
  fe,
  stub,
  handover,
  load = (file) => import(pathToFileURL(file).href),
}) {
  const { source, version, sha256, kitSha256, provenance } = handover;
  const kit = await load(path.join(handover.kit, 'setup-kit.mjs'));
  await kit.setup({ fe, stub, source, version, sha256, kitSha256, provenance });
}

// Run as a script rather than imported by a test.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const fe = path.dirname(fileURLToPath(import.meta.url));
  try {
    await handOver({ fe, stub: fileURLToPath(import.meta.url), handover: prepare({ fe }) });
  } catch (error) {
    console.error(`setup: ${error.message}`);
    process.exit(1);
  }
}
