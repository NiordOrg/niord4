/**
 * Tests for country-kit/lib/pack-archive.mjs, the canonical archive of a language pack.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a home pack into a temporary folder, breaks it the way an edit or a checkout
 * could, and expects the archive to be refused with the file named — or, for the cases that must
 * pass, the same bytes whatever the host did to the line endings.
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import {
  ARCHIVE_LAYOUT,
  buildPackArchive,
  inLayout,
  MAX_ARCHIVE_BYTES,
  MAX_ENTRY_BYTES,
  readPackArchive,
  stagePack,
} from './lib/pack-archive.mjs';
import { readZip, writeZip } from './lib/zip.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.from('flag'),
]);

/** A complete home pack of `lang`, as the files of its folder. */
function packFiles(lang = 'da', eol = '\n') {
  const text = (lines) => lines.join(eol) + eol;
  return {
    'pack.json': text([
      '{',
      `  "lang": "${lang}",`,
      '  "label": "Dansk",',
      '  "locale": "da",',
      `  "flag": "img/flags/${lang}.png"`,
      '}',
    ]),
    [`i18n/admin/${lang}.json`]: text(['{', '  "menu": { "close": "Luk" }', '}']),
    [`i18n/info/${lang}.json`]: text(['{', '  "BTN_CLOSE": "Luk"', '}']),
    'backend/web.properties': text(['lang.da = Dansk', 'msg.more = Hent {{from}} - {{to}}']),
    'backend/mail.properties': text(['mail.regards = Venlig hilsen,<br>{0}']),
    [`img/flags/${lang}.png`]: PNG,
    [`img/flags/${lang}.svg`]: text(['<svg xmlns="http://www.w3.org/2000/svg"/>']),
    'ATTRIBUTION.txt': text(['The flag: drawn by hand.', 'Rendered with resvg.']),
    'status/fingerprints.json': text(['{', '  "admin": { "menu.close": "0123456789abcdef" }', '}']),
    'status/unaccepted.json': text(['{ "note": "awaiting", "info": { "unaccepted": [] } }']),
  };
}

/** Writes a pack into `<root>/packs/<lang>/`; returns that folder. */
function packFolder(files = packFiles(), lang = 'da') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pack-archive-'));
  temporary.push(root);
  const dir = path.join(root, 'packs', lang);
  for (const [name, content] of Object.entries(files)) {
    if (content === undefined) continue;
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), content);
  }
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

describe('the canonical archive', () => {
  it('holds exactly the layout, sorted, unaccepted.json left out', () => {
    const { zip, sha256: hash, entries } = buildPackArchive(packFolder(), 'da');
    assert.equal(hash, sha256(zip));
    assert.deepEqual(
      entries.map(({ name }) => name),
      [
        'ATTRIBUTION.txt',
        'backend/mail.properties',
        'backend/web.properties',
        'i18n/admin/da.json',
        'i18n/info/da.json',
        'img/flags/da.png',
        'img/flags/da.svg',
        'pack.json',
        'status/fingerprints.json',
      ],
    );
    assert.deepEqual(
      readZip(zip).map(({ name }) => name),
      entries.map(({ name }) => name),
    );
    assert.ok(!entries.some(({ name }) => name === 'status/unaccepted.json'));
  });

  it('gives the same bytes for CRLF and LF copies, and on a second build', () => {
    const lf = buildPackArchive(packFolder(packFiles('da', '\n')), 'da');
    const crlf = buildPackArchive(packFolder(packFiles('da', '\r\n')), 'da');
    const cr = buildPackArchive(packFolder(packFiles('da', '\r')), 'da');
    assert.equal(crlf.sha256, lf.sha256);
    assert.equal(cr.sha256, lf.sha256);
    assert.deepEqual(buildPackArchive(packFolder(), 'da').zip, lf.zip);
    // The flag's bytes are never touched, whatever they hold.
    const png = Buffer.concat([PNG, Buffer.from('\r\n')]);
    const { entries } = buildPackArchive(
      packFolder({ ...packFiles(), 'img/flags/da.png': png }),
      'da',
    );
    assert.deepEqual(entries.find(({ name }) => name === 'img/flags/da.png').data, png);
  });

  it('dates every entry 1980-01-01 00:00 and writes it as a regular file', () => {
    const { zip } = buildPackArchive(packFolder(), 'da');
    const end = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
    let at = zip.readUInt32LE(end + 16);
    for (let i = 0; i < zip.readUInt16LE(end + 10); i++) {
      assert.equal(zip.readUInt16LE(at + 12), 0, 'time 00:00');
      assert.equal(zip.readUInt16LE(at + 14), (0 << 9) | (1 << 5) | 1, 'date 1980-01-01');
      assert.equal(zip.readUInt32LE(at + 38) >>> 16, 0o100644);
      at += 46 + zip.readUInt16LE(at + 28);
    }
  });

  it('refuses a stray file, a renames.json and a file outside the layout, naming it', () => {
    for (const [name, pattern] of [
      ['notes.md', /packs\/da\/notes\.md is not part of a pack/],
      ['renames.json', /packs\/da\/renames\.json is not part of a pack/],
      ['i18n/admin/en.json', /packs\/da\/i18n\/admin\/en\.json is not part of a pack/],
      // Upper case: a bundle is named ^[a-z][a-z0-9]*$ (no twin here, for a case-blind disk).
      ['backend/Pdf.properties', /backend\/Pdf\.properties is not part of a pack/],
      ['status/notes.json', /status\/notes\.json is not part of a pack/],
    ])
      assert.throws(
        () => buildPackArchive(packFolder({ ...packFiles(), [name]: 'x' }), 'da'),
        pattern,
      );
  });

  it('refuses a missing flag, a pack.json of another language and a folder of another one', () => {
    assert.throws(
      () => buildPackArchive(packFolder({ ...packFiles(), 'img/flags/da.png': undefined }), 'da'),
      /packs\/da: has no img\/flags\/da\.png/,
    );
    const kl = packFiles('kl');
    assert.throws(
      () => buildPackArchive(packFolder({ ...packFiles(), 'pack.json': kl['pack.json'] }), 'da'),
      /pack\.json names the language "kl", not "da"/,
    );
    assert.throws(
      () => buildPackArchive(packFolder(), 'kl'),
      /is not the folder of the language kl/,
    );
  });

  it('refuses a folder, empty or not, that holds no entry of the layout', () => {
    const empty = packFolder();
    fs.mkdirSync(path.join(empty, 'extra'));
    assert.throws(
      () => buildPackArchive(empty, 'da'),
      /packs\/da\/extra is a folder that is not part of a pack/,
    );
    const nested = packFolder();
    fs.mkdirSync(path.join(nested, 'i18n', 'admin', 'old'));
    assert.throws(
      () => buildPackArchive(nested, 'da'),
      /packs\/da\/i18n\/admin\/old is a folder that is not part of a pack/,
    );
    // The layout's own folders pass empty: a pack without the public site's text has no file there.
    const layout = packFolder({ ...packFiles(), 'i18n/info/da.json': undefined });
    fs.mkdirSync(path.join(layout, 'i18n', 'info'));
    buildPackArchive(layout, 'da');
  });

  it('refuses a folder where the layout has a file', () => {
    const dir = packFolder({ ...packFiles(), 'ATTRIBUTION.txt': undefined });
    fs.mkdirSync(path.join(dir, 'ATTRIBUTION.txt'));
    fs.writeFileSync(path.join(dir, 'ATTRIBUTION.txt', 'x.txt'), 'x');
    assert.throws(
      () => buildPackArchive(dir, 'da'),
      /packs\/da\/ATTRIBUTION\.txt is a folder where the layout has a file/,
    );
  });

  it('refuses a linked folder', () => {
    // A junction needs no privilege on Windows; elsewhere it is a symbolic link to the folder.
    const linked = packFolder();
    const target = path.join(path.dirname(path.dirname(linked)), 'backend-elsewhere');
    fs.renameSync(path.join(linked, 'backend'), target);
    fs.symlinkSync(target, path.join(linked, 'backend'), 'junction');
    assert.throws(() => buildPackArchive(linked, 'da'), /packs\/da\/backend is a link/);
  });

  it('refuses a link to a file', (t) => {
    const dir = packFolder();
    const outside = path.join(path.dirname(path.dirname(dir)), 'outside.txt');
    fs.writeFileSync(outside, 'x');
    fs.rmSync(path.join(dir, 'ATTRIBUTION.txt'));
    try {
      fs.symlinkSync(outside, path.join(dir, 'ATTRIBUTION.txt'), 'file');
    } catch (error) {
      if (error.code === 'EPERM') return t.skip('creating a link needs a privilege here');
      throw error;
    }
    assert.throws(() => buildPackArchive(dir, 'da'), /packs\/da\/ATTRIBUTION\.txt is a link/);
  });

  it('refuses an entry over 2 MiB, a pack over 8 MiB, over 32 files and a flag over 64 KB', () => {
    const big = 'x'.repeat(MAX_ENTRY_BYTES + 1);
    assert.throws(
      () => buildPackArchive(packFolder({ ...packFiles(), 'ATTRIBUTION.txt': big }), 'da'),
      /ATTRIBUTION\.txt is 2097153 bytes, over the 2097152/,
    );
    const many = {};
    for (let i = 0; i < 5; i++)
      many[`backend/b${i}.properties`] = `k = ${'y'.repeat(MAX_ARCHIVE_BYTES / 5)}\n`;
    assert.throws(
      () => buildPackArchive(packFolder({ ...packFiles(), ...many }), 'da'),
      /packs\/da holds \d+ bytes, over the 8388608 a pack may hold/,
    );
    const files = { ...packFiles() };
    for (let i = 0; i < 30; i++) files[`backend/b${i}.properties`] = 'k = v\n';
    assert.throws(
      () => buildPackArchive(packFolder(files), 'da'),
      /over the 32 files a pack may have/,
    );
    const flag = Buffer.concat([PNG, Buffer.alloc(64 * 1024)]);
    assert.throws(
      () => buildPackArchive(packFolder({ ...packFiles(), 'img/flags/da.png': flag }), 'da'),
      /img\/flags\/da\.png is \d+ bytes, over the 65536 a flag may be/,
    );
  });

  it('names the layout for a language, a bundle being ^[a-z][a-z0-9]*$', () => {
    assert.equal(ARCHIVE_LAYOUT.length, 8);
    assert.ok(inLayout('i18n/info/kl.json', 'kl'));
    assert.ok(inLayout('backend/web2.properties', 'kl'));
    assert.ok(!inLayout('backend/2web.properties', 'kl'));
    assert.ok(!inLayout('backend/sub/web.properties', 'kl'));
    assert.ok(!inLayout('i18n/info/da.json', 'kl'));
    assert.ok(!inLayout('status/unaccepted.json', 'kl'));
  });
});

describe('reading a pulled archive', () => {
  const good = () => buildPackArchive(packFolder(), 'da').entries;
  const archive = (entries) => writeZip(entries);

  it('reads an archive of the layout back as its entries', () => {
    const entries = good();
    assert.deepEqual(readPackArchive(archive(entries), 'da'), entries);
  });

  it('refuses a name outside the layout, another language and an unaccepted.json', () => {
    assert.throws(
      () =>
        readPackArchive(archive([...good(), { name: 'evil.js', data: Buffer.from('x') }]), 'da'),
      /niord-lang-da: the archive has evil\.js, which is not part of a da pack/,
    );
    assert.throws(
      () => readPackArchive(archive(good()), 'kl'),
      /niord-lang-kl: the archive has .*, which is not part of a kl pack/,
    );
    assert.throws(
      () =>
        readPackArchive(
          archive([...good(), { name: 'status/unaccepted.json', data: Buffer.from('{}') }]),
          'da',
        ),
      /the archive has status\/unaccepted\.json, which a published pack never has/,
    );
  });

  it('refuses a missing pack.json or flag, a pack.json of another language, a flag no PNG', () => {
    const without = (name) => good().filter((entry) => entry.name !== name);
    assert.throws(() => readPackArchive(archive(without('pack.json')), 'da'), /has no pack\.json/);
    assert.throws(
      () => readPackArchive(archive(without('img/flags/da.png')), 'da'),
      /has no img\/flags\/da\.png/,
    );
    const replace = (name, data) =>
      good().map((entry) => (entry.name === name ? { name, data: Buffer.from(data) } : entry));
    assert.throws(
      () => readPackArchive(archive(replace('pack.json', packFiles('kl')['pack.json'])), 'da'),
      /niord-lang-da: pack\.json names the language "kl", not "da"/,
    );
    assert.throws(
      () => readPackArchive(archive(replace('img/flags/da.png', '<svg/>')), 'da'),
      /img\/flags\/da\.png is not a PNG/,
    );
    assert.throws(
      () => readPackArchive(Buffer.from('not a zip'), 'da'),
      /the archive is not a ZIP/,
    );
  });

  it("holds pack.json to readPack's schema, naming the pack before anything is staged", () => {
    const replace = (data) =>
      good().map((entry) =>
        entry.name === 'pack.json' ? { name: 'pack.json', data: Buffer.from(data) } : entry,
      );
    const full = JSON.parse(packFiles()['pack.json']);
    for (const [manifest, pattern] of [
      [
        { lang: 'da' },
        /niord-lang-da: pack\.json has lang; it has exactly flag, label, lang, locale/,
      ],
      [[], /niord-lang-da: pack\.json is not an object/],
      [{ ...full, locale: 'de_DE' }, /niord-lang-da: pack\.json's locale "de_DE" is not/],
      [{ ...full, label: '<b>Dansk</b>' }, /niord-lang-da: pack\.json's label is not plain text/],
      [{ ...full, label: 'D'.repeat(41) }, /niord-lang-da: pack\.json's label has 41 characters/],
      [
        { ...full, flag: 'img/flags/da.svg' },
        /niord-lang-da: pack\.json's flag is "img\/flags\/da\.svg"/,
      ],
      [{ ...full, label: ' ' }, /niord-lang-da: pack\.json's label is not a non-empty text/],
    ])
      assert.throws(
        () => readPackArchive(archive(replace(JSON.stringify(manifest))), 'da'),
        pattern,
        JSON.stringify(manifest),
      );
  });

  it('refuses an entry over 2 MiB and an archive over 8 MiB or 32 entries', () => {
    const replace = (name, data) =>
      good().map((entry) => (entry.name === name ? { name, data } : entry));
    assert.throws(
      () =>
        readPackArchive(
          archive(replace('ATTRIBUTION.txt', Buffer.alloc(MAX_ENTRY_BYTES + 1, 'x'))),
          'da',
        ),
      /niord-lang-da\/ATTRIBUTION\.txt is 2097153 bytes, over the 2097152/,
    );
    const many = (count, size) =>
      Array.from({ length: count }, (_, i) => ({
        name: `backend/b${i}.properties`,
        data: Buffer.alloc(size, 'y'),
      }));
    assert.throws(
      () => readPackArchive(archive([...good(), ...many(5, MAX_ARCHIVE_BYTES / 5)]), 'da'),
      /over the 8388608 bytes allowed/,
    );
    assert.throws(
      () => readPackArchive(archive([...good(), ...many(30, 1)]), 'da'),
      /has 39 entries, over the 32 allowed/,
    );
  });
});

describe('staging a pack', () => {
  it('replaces the folder whole with exactly the entries', () => {
    const { entries } = buildPackArchive(packFolder(), 'da');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pack-stage-'));
    temporary.push(root);
    const target = path.join(root, '.niord', 'packs', 'da');
    fs.mkdirSync(path.join(target, 'status'), { recursive: true });
    fs.writeFileSync(path.join(target, 'status', 'unaccepted.json'), '{}');
    stagePack({ entries, target });
    const staged = [];
    const walk = (dir, trail = []) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true }))
        if (entry.isDirectory()) walk(path.join(dir, entry.name), [...trail, entry.name]);
        else staged.push([...trail, entry.name].join('/'));
    };
    walk(target);
    assert.deepEqual(staged.sort(), entries.map(({ name }) => name).sort());
    assert.deepEqual(fs.readdirSync(path.dirname(target)), ['da']);
    assert.throws(
      () => stagePack({ entries, target: path.join(root, '.niord', 'packs', 'kl') }),
      /is not part of a kl pack, and nothing was staged/,
    );
  });

  it('keeps the staged folder, and leaves no temporary, when a write fails half-way', () => {
    const { entries } = buildPackArchive(packFolder(), 'da');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pack-stage-'));
    temporary.push(root);
    const target = path.join(root, '.niord', 'packs', 'da');
    stagePack({ entries, target });
    const before = fs.readFileSync(path.join(target, 'pack.json'));
    // A name of the layout given twice: the second write of the fresh folder fails (wx), after
    // the first wrote, so the failure is inside the write loop, not the layout check before it.
    assert.throws(
      () => stagePack({ entries: [...entries, entries[0]], target }),
      (error) => error.code === 'EEXIST',
    );
    assert.deepEqual(fs.readFileSync(path.join(target, 'pack.json')), before);
    const files = fs
      .readdirSync(target, { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile());
    assert.equal(files.length, entries.length);
    assert.deepEqual(fs.readdirSync(path.dirname(target)), ['da']);
  });
});
