import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { LOCK_FORMAT, lockText, readLock, writeLock } from './lib/pins.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary)
    fs.rmSync(folder, { recursive: true, force: true, maxRetries: 3 });
});
const temp = () => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-pins-'));
  temporary.push(folder);
  return folder;
};

const BASE = { version: '4.0.1', sha256: 'c'.repeat(64) };
const KIT = { sha256: 'd'.repeat(64) };
const PACKS = { da: `ghcr.io/sifa-dk/niord-lang-da:12@sha256:${'a'.repeat(64)}` };
const lock = (value) => JSON.stringify(value);

describe('the lock file', () => {
  it('is written without a kit pin as before: format, base and packs', () => {
    const text = lockText({ ...BASE, packs: PACKS });
    assert.deepEqual(Object.keys(JSON.parse(text)), ['format', 'base', 'packs']);
    assert.equal(
      text,
      '{\n' +
        '  "format": 1,\n' +
        '  "base": {\n' +
        '    "version": "4.0.1",\n' +
        `    "sha256": "${'c'.repeat(64)}"\n` +
        '  },\n' +
        '  "packs": {\n' +
        `    "da": "${PACKS.da}"\n` +
        '  }\n' +
        '}\n',
    );
  });

  it('carries a kit pin between base and packs, in format 1', () => {
    const parsed = JSON.parse(lockText({ ...BASE, kit: KIT, packs: PACKS }));
    assert.deepEqual(Object.keys(parsed), ['format', 'base', 'kit', 'packs']);
    assert.deepEqual(parsed.kit, KIT);
    assert.equal(parsed.format, LOCK_FORMAT);
    assert.equal(LOCK_FORMAT, 1);
  });

  for (const [what, kit] of [
    ['with a kit pin', KIT],
    ['without one', undefined],
  ])
    it(`reads back what it writes, ${what}`, () => {
      const text = lockText({ ...BASE, kit, packs: PACKS });
      const read = readLock(text);
      assert.deepEqual(read, JSON.parse(text));
      assert.equal(
        lockText({
          version: read.base.version,
          sha256: read.base.sha256,
          kit: read.kit,
          packs: read.packs,
        }),
        text,
      );
    });

  it('writes the file and returns its text', () => {
    const file = path.join(temp(), 'niord-pins.lock.json');
    const text = writeLock(file, { ...BASE, kit: KIT, packs: PACKS });
    assert.equal(text, lockText({ ...BASE, kit: KIT, packs: PACKS }));
    assert.equal(fs.readFileSync(file, 'utf8'), text);
  });

  for (const kit of [
    {},
    { sha256: 'x' },
    { sha256: 'D'.repeat(64) },
    { sha256: 'd'.repeat(64), extra: 1 },
    'd'.repeat(64),
    null,
  ])
    it(`refuses to write the kit pin ${JSON.stringify(kit)}`, () => {
      assert.throws(
        () => lockText({ ...BASE, kit, packs: PACKS }),
        /"kit" is not \{ sha256 \} of 64 lower-case hex digits/,
      );
    });
});

describe("the kit's lock reader", () => {
  const good = { format: 1, base: BASE, packs: PACKS };
  for (const [what, text, message] of [
    ['a text that is not JSON', '{ "format": 1,', /^niord-pins\.lock\.json: /],
    [
      'a lock of format 2',
      lock({ ...good, format: 2 }),
      /^niord-pins\.lock\.json is not a lock file of format 1$/,
    ],
    ['an array', lock([good]), /^niord-pins\.lock\.json is not a lock file of format 1$/],
    [
      'a base without a version',
      lock({ ...good, base: { sha256: BASE.sha256 } }),
      /^niord-pins\.lock\.json has no base \{ version, sha256 \}/,
    ],
    [
      'a base without a sha256',
      lock({ ...good, base: { version: BASE.version } }),
      /^niord-pins\.lock\.json has no base \{ version, sha256 \}/,
    ],
    [
      'a malformed kit pin',
      lock({ ...good, kit: { sha256: 'x' } }),
      /^niord-pins\.lock\.json: "kit" is not \{ sha256 \} of 64 lower-case hex digits$/,
    ],
    [
      'a kit pin with another key',
      lock({ ...good, kit: { ...KIT, version: '4.0.1' } }),
      /^niord-pins\.lock\.json: "kit" is not \{ sha256 \}/,
    ],
    [
      'packs that are not an object',
      lock({ ...good, packs: [] }),
      /^niord-pins\.lock\.json has no packs object$/,
    ],
  ])
    it(`refuses ${what}`, () => {
      assert.throws(() => readLock(text), { message });
    });

  it('reads a lock without packs as one with none', () => {
    assert.deepEqual(readLock(lock({ format: 1, base: BASE })), {
      format: 1,
      base: BASE,
      packs: {},
    });
  });
});
