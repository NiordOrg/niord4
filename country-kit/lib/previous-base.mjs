/**
 * The base product version a country's last set-up built against, which the upgrade report and
 * `--accept-override`'s diff since the recorded fingerprint compare with (fe-country-split-plan
 * §4.12).
 *
 *   readRecord     the last set-up record: the build report set-up writes on success,
 *                  .niord/build/niord-build-report.json ({ base: { version, sha256, kit? },
 *                  provenance }, kit the kit zip's { sha256 } when the stub verified one). A
 *                  set-up that fails leaves it as it was, so a red bump keeps comparing with the
 *                  last good version
 *   previousBase   that version's files, from the zips the stub fetched for it, which no later
 *                  set-up removes: the base product's .niord/niord-fe-src-<version>.zip and, for a
 *                  version whose record names a kit zip, the kit's
 *                  <root>/.niord/niord-country-kit-<version>.zip
 *
 * The kit is its own zip, its entries named relative to the kit, and the record holds its hash;
 * its files are given under country-kit/, the key the report reads a kit file by. Every
 * country-kit/ entry the niord-fe-src zip carries is deleted, as it is not the kit's: releases from
 * the kit package's first one (4.0.109, 4.0.111) still carry two. A record without a kit hash
 * gives the base product's files and `kitUnavailable`. Only a zip whose sha256 is the record's is
 * read, then through lib/zip.mjs's checks: a kit zip the record names no hash of is not read.
 *
 * Nothing is fetched: no network, no Maven. Without a zip — a fresh workspace, a record made from a
 * local checkout, a zip that is not the one recorded — each section that needs it says why.
 * Node built-ins only: set-up runs before `npm ci`.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { readZip } from './zip.mjs';

export const RECORD = '.niord/build/niord-build-report.json';
const VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]*$/;
const SHA256 = /^[0-9a-f]{64}$/;
/** The prefix the result gives the kit's files, and that a niord-fe-src zip's stale entries carry. */
const KIT_PREFIX = 'country-kit/';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const sha256Of = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

/** The zip the stub fetches a version into, relative to the country's fe/. */
export const zipOf = (version) => `.niord/niord-fe-src-${version}.zip`;

/**
 * The kit zip the stub fetches a version into, relative to the country's root (the folder holding
 * fe/ and be/).
 */
export const kitZipOf = (version) => `.niord/niord-country-kit-${version}.zip`;

/**
 * The last set-up record, `{ base: { version, sha256, kit? }, provenance }` as the build report
 * holds them, or null when there is none, it is no JSON or it names no plain base product version.
 * `kit` is there only when the report holds the kit zip's `{ sha256 }`.
 */
export function readRecord(fe) {
  let report;
  try {
    report = JSON.parse(fs.readFileSync(path.join(fe, RECORD), 'utf8'));
  } catch {
    return null;
  }
  if (!isObject(report?.base) || !VERSION.test(report.base.version ?? '')) return null;
  return {
    base: {
      version: report.base.version,
      sha256: SHA256.test(report.base.sha256 ?? '') ? report.base.sha256 : null,
      ...(SHA256.test(report.base.kit?.sha256 ?? '')
        ? { kit: { sha256: report.base.kit.sha256 } }
        : {}),
    },
    provenance: typeof report.provenance === 'string' ? report.provenance : '',
  };
}

/**
 * The files of the base product version `record` names: `{ version, files }`, `files` a Map of
 * each file's path (relative to fe/, the kit's under country-kit/) to its bytes, or `{ version,
 * unavailable }` saying why they are not at hand. `kitUnavailable`, beside `files`, says why the
 * kit's files are not among them. `root` is the country's root, by default fe/'s parent.
 */
export function previousBase({ fe, record, root = path.dirname(path.resolve(fe)) }) {
  const version = record.base.version;
  if (!record.base.sha256)
    return {
      version,
      unavailable: `the last set-up (${version}) was made from a local checkout, NIORD_BASE, not from a zip`,
    };
  const zip = zipOf(version);
  let bytes;
  try {
    bytes = fs.readFileSync(path.join(fe, zip));
  } catch {
    return { version, unavailable: `${zip}, where the stub's fetch leaves it, is not there` };
  }
  if (sha256Of(bytes) !== record.base.sha256)
    return {
      version,
      unavailable: `${zip} is not the zip the last set-up verified: its sha256 is not the record's`,
    };
  let files;
  try {
    files = new Map(readZip(bytes).map(({ name, data }) => [name, data]));
  } catch (error) {
    return { version, unavailable: `${zip} cannot be read (${error.message})` };
  }

  for (const name of [...files.keys()]) if (name.startsWith(KIT_PREFIX)) files.delete(name);
  if (!record.base.kit)
    return { version, files, kitUnavailable: 'the last set-up recorded no kit zip' };
  const kitZip = `../${kitZipOf(version)}`;
  let kitBytes;
  try {
    kitBytes = fs.readFileSync(path.join(root, kitZipOf(version)));
  } catch {
    return {
      version,
      files,
      kitUnavailable: `${kitZip}, where the stub's fetch leaves it, is not there`,
    };
  }
  if (sha256Of(kitBytes) !== record.base.kit.sha256)
    return {
      version,
      files,
      kitUnavailable: `${kitZip} is not the kit zip the last set-up verified: its sha256 is not the record's`,
    };
  let entries;
  try {
    entries = readZip(kitBytes);
  } catch (error) {
    return { version, files, kitUnavailable: `${kitZip} cannot be read (${error.message})` };
  }
  for (const { name, data } of entries) files.set(`${KIT_PREFIX}${name}`, data);
  return { version, files };
}
