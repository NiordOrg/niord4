/**
 * The pins of a country repository, as the kit reads and writes them: the base product version
 * is the `<parent>` version of `be/pom.xml`, and `fe/niord-pins.lock.json` pins that version's
 * source zip by its SHA-256 and each foreign pack by its full reference, tag and digest:
 *
 *   { "format": 1, "base": { "version": "4.0.183", "sha256": "<64 hex>" },
 *     "kit": { "sha256": "<64 hex>" },
 *     "packs": { "da": "ghcr.io/sifa-dk/niord-lang-da:12@sha256:<64 hex>" } }
 *
 * The version beside the hash turns a pom bump without a new pin into a clear error rather than
 * a bare hash mismatch. `kit` pins the kit package's zip by its SHA-256; it shares base.version,
 * and is written only when a kit pin is known. The format stays 1 with it, because a stub from
 * before the kit package ignores keys it does not know. A pack's entry is a full reference with
 * its numeric tag and its digest (parsePin), under the GHCR owner the manifest's entry names
 * (`owner`, by default sifa-dk).
 * A pin on a loopback registry is accepted wherever a lock holds one (the digest guards the bytes,
 * and the lock's change shows in review); only the tests and the example's harness write one.
 * `node setup.mjs --update-pins` writes the entries (setup-kit.mjs). The stub (templates/setup.mjs)
 * reads the same file with its own copy of the base rules, because it runs before the kit is
 * verified; it takes `packs` as any object, so the pack entries are the kit's to check. The stub's
 * reader also requires the kit pin, as it fetches the kit package by it; this readLock keeps it
 * optional, so a lock without it is read and written back without it.
 */
import fs from 'node:fs';
import { parseReference } from './oci.mjs';

export const LOCK_FORMAT = 1;
export const VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]*$/;
export const SHA256 = /^[0-9a-f]{64}$/;
export const LOCK = 'niord-pins.lock.json';
/** The GHCR owner of a pinned pack whose manifest entry names none. */
export const DEFAULT_OWNER = 'sifa-dk';

/** The `<parent><version>` of a pom's text, XML comments left out. */
export function parentVersion(pom) {
  const text = pom.replace(/<!--[\s\S]*?-->/g, '');
  const parent = text.match(/<parent>([\s\S]*?)<\/parent>/);
  const version = parent?.[1].match(/<version>\s*([^<]*?)\s*<\/version>/)?.[1];
  if (!version) throw new Error('be/pom.xml has no <parent> with a <version>');
  if (!VERSION.test(version))
    throw new Error(`be/pom.xml's parent version "${version}" is not a plain version`);
  return version;
}

/**
 * A pinned pack's entry: `{ reference, registry, owner, lang, repository, tag, digest }`. Throws
 * unless it is a full reference with a numeric tag and a digest.
 */
export function parsePin(text) {
  const pin = parseReference(text);
  if (!pin.tag || pin.tag === 'withdrawn' || !pin.digest)
    throw new Error(
      `"${text}" is not a pin: <registry>/<owner>/niord-lang-<lang>:<tag>@sha256:<64 hex>`,
    );
  return { reference: text, ...pin };
}

/** The owner a manifest's pinned entry names for its pack. */
export const ownerOf = (entry) => entry?.owner ?? DEFAULT_OWNER;

/**
 * What is wrong with a lock's pack entries against the manifest, one line each: an entry that is
 * not a pin, one for another language than its key, under another owner than the manifest's, a
 * pin for a pack the manifest does not pin, and a pinned pack without a pin — unless it is among
 * `local`, the languages a developer's NIORD_PACK_<LANG> names, which need no pin.
 */
export function pinProblems(lock, manifest, { local = [] } = {}) {
  const problems = [];
  const packs = lock?.packs ?? {};
  const pinned = Object.entries(manifest.packs ?? {}).filter(([, entry]) => entry.pinned);
  for (const [lang, text] of Object.entries(packs)) {
    const at = `${LOCK} packs.${lang}`;
    if (!manifest.packs?.[lang]?.pinned) {
      problems.push(`${at} pins a pack niord-country.json does not name as pinned: remove it`);
      continue;
    }
    let pin;
    try {
      pin = parsePin(typeof text === 'string' ? text : JSON.stringify(text));
    } catch (error) {
      problems.push(`${at}: ${error.message}`);
      continue;
    }
    if (pin.lang !== lang) problems.push(`${at} names the pack ${pin.lang}, not ${lang}`);
    const owner = ownerOf(manifest.packs[lang]);
    if (pin.owner !== owner)
      problems.push(
        `${at} is under the owner ${pin.owner}, but niord-country.json names ${owner} for it`,
      );
    // The registry needs no check here: parseReference (lib/oci.mjs) holds a pin to ghcr.io or a
    // loopback registry.
  }
  for (const [lang] of pinned)
    if (!Object.hasOwn(packs, lang) && !local.includes(lang))
      problems.push(
        `${lang} is a pinned pack with no pin in ${LOCK}: run node setup.mjs --update-pins`,
      );
  return problems;
}

/**
 * The pinned packs of a lock's text: `{ <lang>: pin }` (parsePin). Throws naming every problem.
 * Without a lock file (`text` undefined) the packs are none. `local` as for pinProblems.
 */
export function readPins(text, manifest, { local = [] } = {}) {
  let lock = { packs: {} };
  if (text !== undefined)
    try {
      lock = JSON.parse(text);
    } catch (error) {
      throw new Error(`${LOCK}: ${error.message}`, { cause: error });
    }
  const problems = pinProblems(lock, manifest, { local });
  if (problems.length) throw new Error(problems.join('\n  '));
  return Object.fromEntries(
    Object.entries(lock.packs ?? {}).map(([lang, reference]) => [lang, parsePin(reference)]),
  );
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

/** Throws unless `kit` is a kit pin: an object whose one key is a sha256. */
function checkKit(kit) {
  if (
    !isObject(kit) ||
    Object.keys(kit).length !== 1 ||
    typeof kit.sha256 !== 'string' ||
    !SHA256.test(kit.sha256)
  )
    throw new Error('"kit" is not { sha256 } of 64 lower-case hex digits');
}

/**
 * The lock file as written: `{ format, base: { version, sha256 }, kit?, packs }`, `packs` {} when
 * the file has none. Throws, naming the file, on a text of another shape. The base version is not
 * compared with the pom here: the stub does that before the kit runs.
 */
export function readLock(text) {
  let lock;
  try {
    lock = JSON.parse(text);
  } catch (error) {
    throw new Error(`${LOCK}: ${error.message}`, { cause: error });
  }
  if (!isObject(lock) || lock.format !== LOCK_FORMAT)
    throw new Error(`${LOCK} is not a lock file of format ${LOCK_FORMAT}`);
  const { version, sha256 } = isObject(lock.base) ? lock.base : {};
  if (
    typeof version !== 'string' ||
    !VERSION.test(version) ||
    typeof sha256 !== 'string' ||
    !SHA256.test(sha256)
  )
    throw new Error(`${LOCK} has no base { version, sha256 } (64 lower-case hex digits)`);
  if (lock.kit !== undefined)
    try {
      checkKit(lock.kit);
    } catch (error) {
      throw new Error(`${LOCK}: ${error.message}`, { cause: error });
    }
  if (lock.packs !== undefined && !isObject(lock.packs))
    throw new Error(`${LOCK} has no packs object`);
  return {
    format: lock.format,
    base: { version, sha256 },
    ...(lock.kit ? { kit: { sha256: lock.kit.sha256 } } : {}),
    packs: lock.packs ?? {},
  };
}

/**
 * The lock file's text for a base product version, its zip's hash, the kit pin when one is known
 * and the pack pins, sorted.
 */
export function lockText({ version, sha256, kit, packs = {} }) {
  if (!VERSION.test(version)) throw new Error(`"${version}" is not a plain version`);
  if (!SHA256.test(sha256))
    throw new Error(`"${sha256}" is not a SHA-256 of 64 lower-case hex digits`);
  if (kit !== undefined) checkKit(kit);
  const sorted = Object.fromEntries(
    Object.keys(packs)
      .sort()
      .map((lang) => [lang, parsePin(packs[lang]).reference]),
  );
  const lock = {
    format: LOCK_FORMAT,
    base: { version, sha256 },
    ...(kit ? { kit: { sha256: kit.sha256 } } : {}),
    packs: sorted,
  };
  return `${JSON.stringify(lock, null, 2)}\n`;
}

/** Writes the lock file; returns its text. */
export function writeLock(file, pins) {
  const text = lockText(pins);
  fs.writeFileSync(file, text);
  return text;
}
