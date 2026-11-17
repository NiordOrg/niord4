import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { buildCatalog, markupOf } from '../catalog.mjs';
import { checkPacks } from '../pack.mjs';
import { where } from '../lib/where.mjs';
import { placeholders } from '../stage-languages.mjs';
import { accent, pseudo, pseudoLocalise, writeIndexTitle } from './pseudo-localise.mjs';

const EXAMPLE = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;

/** Pack zz in a temporary folder: its own files from the example, the rest generated. */
function generatedPack() {
  const dir = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'niord-zz-')), 'zz');
  fs.cpSync(path.join(EXAMPLE, 'fe', 'packs', 'zz'), dir, {
    recursive: true,
    filter: (source) => !/[\\/](i18n|backend|status)([\\/]|$)/.test(path.relative(EXAMPLE, source)),
  });
  pseudoLocalise({ fe: FE, out: dir });
  return dir;
}

describe("the example's pseudo-localisation", () => {
  it('accents letters and keeps what a program reads', () => {
    assert.equal(pseudo('Save area'), '[Šávé áŕéá]');
    const text = 'Hello {{ name }}, <a href="https://x.org/a" class="b">see &amp; read</a> %s';
    const result = pseudo(text);
    assert.deepEqual(placeholders(result), placeholders(text));
    assert.deepEqual(markupOf(result), markupOf(text));
    assert.match(result, /&amp;/);
    assert.match(result, / %s\]$/);
  });

  it('keeps MessageFormat arguments and quoted runs in a backend text', () => {
    assert.equal(
      accent("{0,number} files in '{1}' at {2}", { quotes: true }),
      "{0,number} ƒíłéš íñ '{1}' áţ {2}",
    );
  });

  it("keeps a date pattern, a word list's entries and an empty text", () => {
    assert.equal(
      pseudo("d MMMM yyyy 'at' HH:mm", { app: 'info', key: 'PRINT_DATE_FORMAT' }),
      "d MMMM yyyy 'at' HH:mm",
    );
    assert.equal(pseudo('Jan|Feb', { app: 'info', key: 'DATE_MONTHS' }), 'Jáñ|Féƀ');
    assert.equal(pseudo(' ', { app: 'admin', key: 'x' }), ' ');
  });

  it("writes the zz site title into the public site's page, and the committed page holds it already", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-zz-index-'));
    const page = path.join(dir, 'index.html');
    const before =
      '<!doctype html>\n<html lang="zz">\n  <head>\n    <title>Old</title>\n  </head>\n</html>\n';
    fs.writeFileSync(page, before);
    writeIndexTitle(page, 'A & <B>');
    assert.equal(
      fs.readFileSync(page, 'utf8'),
      before.replace('<title>Old</title>', '<title>A &amp; &lt;B&gt;</title>'),
    );
    fs.writeFileSync(page, '<title>a</title><title>b</title>');
    assert.throws(() => writeIndexTitle(page, 'c'), /has 2 <title> elements, not one/);

    // The drift guard: an English SITE_TITLE change turns this red until the committed page is
    // written again.
    const committed = path.join(EXAMPLE, 'fe', 'info', 'app', 'index.html');
    const copy = path.join(dir, 'committed.html');
    fs.copyFileSync(committed, copy);
    pseudoLocalise({ fe: FE, out: path.join(dir, 'zz'), index: copy });
    assert.ok(
      fs.readFileSync(copy).equals(fs.readFileSync(committed)),
      `${committed} is not titled with the generated zz SITE_TITLE: ${fs.readFileSync(copy, 'utf8').match(/<title>.*<\/title>/)?.[0]}`,
    );
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('writes a pack the pack check passes for both applications and the backend', () => {
    const zz = generatedPack();
    const { errors, warnings } = checkPacks({
      catalog: buildCatalog(),
      packs: [path.join(FE, 'languages', 'en'), zz],
      uses: { admin: ['zz'], info: ['zz'], backend: { zz: 'ui' } },
      accepted: JSON.parse(
        fs.readFileSync(path.join(EXAMPLE, 'fe', 'missing-keys-accepted.json'), 'utf8'),
      ),
    });
    assert.deepEqual(errors, []);
    assert.ok(
      warnings.every((warning) => warning.startsWith('the escape hatch')),
      warnings.join('\n'),
    );
    assert.equal(fs.existsSync(path.join(zz, 'status', 'unaccepted.json')), false);
  });

  it("makes the backend half from a previous release's catalog: a removed, re-placeholdered or markup key passes", () => {
    // A previous release's catalog: one key this tree's be/ no longer has, one whose placeholders
    // changed since, one with markup this tree does not have, and one key this tree added.
    const current = buildCatalog();
    const previous = structuredClone(current);
    previous.backend.web['example.removed.key'] = {
      fp: '0123456789abcdef',
      placeholders: ['name', '{0}'],
    };
    const [replaced] = Object.keys(previous.backend.mail);
    previous.backend.mail[replaced] = { fp: 'fedcba9876543210', placeholders: ['{0}', '{1}'] };
    previous.backend.mail['example.markup.key'] = {
      fp: '00112233445566ff',
      placeholders: [],
      markup: ['<br>'],
    };
    const [added] = Object.keys(previous.backend.pdf);
    delete previous.backend.pdf[added];
    const uses = { admin: ['zz'], info: ['zz'], backend: { zz: 'ui' } };
    const english = path.join(FE, 'languages', 'en');

    // From this tree's be/ alone, as the upgrade test used to make it, the pack fails.
    const before = checkPacks({ catalog: previous, packs: [english, generatedPack()], uses });
    assert.ok(
      before.errors.some((error) => error.includes('example.removed.key')),
      before.errors.join('\n'),
    );

    const dir = generatedPack();
    const { skipped } = pseudoLocalise({ fe: FE, out: dir, catalog: previous });
    assert.deepEqual(skipped, [{ bundle: 'mail', key: 'example.markup.key' }]);
    const accepted = skipped.map(({ bundle, key }) => ({
      lang: 'zz',
      app: 'backend',
      bundle,
      key,
      reason: 'the previous English text is not in the zip',
      added: '2026-10-22',
    }));
    const { errors } = checkPacks({ catalog: previous, packs: [english, dir], uses, accepted });
    assert.deepEqual(errors, []);
    const web = fs.readFileSync(path.join(dir, 'backend', 'web.properties'), 'utf8');
    // The stand-in, Ẑẑẑ and the catalog's placeholders, as java.util.Properties stores it.
    assert.match(web, /^example\.removed\.key=\[\\u1e90\\u1e91\\u1e91 \{\{name\}\} \{0\}\]$/m);
    const pdf = fs.readFileSync(path.join(dir, 'backend', 'pdf.properties'), 'utf8');
    assert.doesNotMatch(pdf, new RegExp(`^${added.replaceAll('.', '\\.')}=`, 'm'));
    const fingerprints = JSON.parse(
      fs.readFileSync(path.join(dir, 'status', 'fingerprints.json'), 'utf8'),
    );
    assert.equal(fingerprints.backend.mail[replaced], 'fedcba9876543210');
  });
});
