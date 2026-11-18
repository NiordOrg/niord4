/**
 * Tests for country-kit/lib/branding.mjs: the branding rows of the pack check and the rules for
 * brandable files.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a country's branding folder, `<app>/assets/`, into a temporary folder — a
 * passing one, then one broken the way a country's edit could break it — and expects the check to
 * name the file and the rule. Branding ships to every anonymous reader and the applications render
 * some of it as HTML, which is why these exist.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  FORBIDDEN,
  PREFERENCES,
  brandingSchema,
  checkBranding,
  imageProblems,
  legalProblems,
  pngSize,
  readPreferences,
  referenceLanguageOf,
} from './lib/branding.mjs';
import { compileList, readIncludeList } from './lib/include.mjs';
import { where } from './lib/where.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
/** A PNG by its signature alone, for a file the schema gives no size. */
const PNG = Buffer.concat([SIGNATURE, Buffer.from('image')]);
/**
 * A PNG of the size given, as far as the check reads one: the signature, then the 13-byte IHDR
 * chunk with the width and height, 8-bit RGBA, and a zero CRC, since the check reads no CRC.
 */
function png(width, height) {
  const header = Buffer.alloc(25);
  header.writeUInt32BE(13, 0);
  header.write('IHDR', 4, 'latin1');
  header.writeUInt32BE(width, 8);
  header.writeUInt32BE(height, 12);
  header[16] = 8;
  header[17] = 6;
  return Buffer.concat([SIGNATURE, header]);
}
const json = (value) => `${JSON.stringify(value, null, 2)}\n`;
/** The names the public site stores, as preference-keys.json lists them. */
const STORED = ['niord-info.language', 'niord-info.rootAreaId', 'niord-info.NW', 'niord-info.NM'];
/** A cookie statement naming `names`. */
const cookies = (names) =>
  `<p>Thë sïtë rëmëmbërs:</p>\n<ul>\n${names.map((name) => `  <li><code>${name}</code></li>\n`).join('')}</ul>\n`;

/** The administration app's branding of a country whose UI languages are en and zz. */
function adminFiles() {
  return {
    'branding/en.json': json({
      'footer.copyright': '© 2026 Example Authority',
      'footer.link': 'https://authority.example/en/',
      'footer.disclaimer': 'Disclaimer',
      'main.footer.links.center.imageSrc': 'assets/branding/company-logo-en.png',
      'main.footer.links.center.imageAlt': 'The Example Authority',
      'main.footer.dialogs.disclaimer.title': 'Content disclaimer',
      'country.motto': 'Safe <b>seas</b>',
    }),
    'branding/zz.json': json({
      'footer.copyright': '© 2026 Ëxämplë Äüthörïty',
      'footer.link': 'https://authority.example/zz/',
      'footer.disclaimer': 'Dïsclääïmër',
      'main.footer.links.center.imageSrc': 'assets/branding/company-logo-zz.png',
      'main.footer.links.center.imageAlt': 'Thë Ëxämplë Äüthörïty',
      'main.footer.dialogs.disclaimer.title': 'Cöntënt dïsclääïmër',
      'country.motto': 'Sääfë <b>sëääs</b>',
    }),
    'branding/company-logo-en.png': PNG,
    'branding/company-logo-zz.png': PNG,
    'legal/disclaimer.en.html':
      '<h3>Disclaimer</h3>\n<p>See <a href="https://authority.example/en/" target="_blank" rel="noopener">the authority</a>.</p>\n',
    'legal/disclaimer.zz.html':
      '<h3>Dïsclääïmër</h3>\n<p>Sëë <a href="https://authority.example/zz/">thë äüthörïty</a>.</p>\n',
  };
}

/** The public site's branding of a country whose only UI language is zz. */
function infoFiles() {
  return {
    'branding/zz.json': json({
      MAP_COPYRIGHT:
        '&copy; <a href="https://authority.example/zz/" target="_blank">Äüthörïty</a>.',
      MAP_ACCESSIBILITY: '<a href="{{url}}" target="_blank">Äccëssïbïlïty</a>.',
      FOOTER_COPYRIGHT: '&copy; {{year}} Äüthörïty',
      FOOTER_DISCLAIMER: 'Dïsclääïmër',
      FOOTER_COOKIES: 'Cöökïës',
      FOOTER_LOGO: 'assets/branding/logo-gray.png',
      FOOTER_LOGO_ALT: 'Thë Äüthörïty',
      LEGAL_TITLE_DISCLAIMER: 'Dïsclääïmër',
      LEGAL_TITLE_COOKIES: 'Cöökïës',
    }),
    'branding/favicon.png': png(32, 32),
    'branding/apple-touch-icon.png': png(152, 152),
    'branding/logo-gray.png': png(220, 44),
    'legal/disclaimer.zz.html': '<p>Ä dïsclääïmër.</p>\n',
    'legal/cookies.zz.html': cookies(STORED),
  };
}

/**
 * An application's folder, `<app>/`, in a temporary folder, holding `files` in its `assets/` and,
 * when `unaccepted` is given, its branding-unaccepted.json; answers the `assets/` folder.
 */
function folder(files, unaccepted) {
  const app = fs.mkdtempSync(path.join(os.tmpdir(), 'branding-'));
  temporary.push(app);
  const dir = path.join(app, 'assets');
  fs.mkdirSync(dir);
  for (const [relative, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, relative)), { recursive: true });
    fs.writeFileSync(path.join(dir, relative), content);
  }
  if (unaccepted !== undefined)
    fs.writeFileSync(
      path.join(app, 'branding-unaccepted.json'),
      typeof unaccepted === 'string' ? unaccepted : json(unaccepted),
    );
  return dir;
}

const ADMIN = { app: 'admin', uiLanguages: ['en', 'zz'] };
const INFO = { app: 'info', uiLanguages: ['zz'], referenceLanguage: 'zz' };

/** The check of `base`'s files after `edit`, with `unaccepted` as the list beside them. */
function checkOf(base, edit, { unaccepted, ...options } = {}) {
  const files = base === ADMIN ? adminFiles() : infoFiles();
  edit?.(files);
  return checkBranding({ ...base, ...options, dir: folder(files, unaccepted) });
}

function assertRejected(base, edit, fragment, options) {
  const { errors } = checkOf(base, edit, options);
  assert.ok(
    errors.some((error) => error.includes(fragment)),
    `expected an error naming "${fragment}", got:\n${errors.join('\n') || '(none)'}`,
  );
}

/** A copy of the schema with one change, for a rule the real schema has no subject for yet. */
function schemaWith(edit) {
  const copy = structuredClone(brandingSchema());
  edit(copy);
  return copy;
}

const rewrite = (files, relative, change) => {
  files[relative] = json(change(JSON.parse(files[relative])));
};

describe('branding', () => {
  it('passes a country whose branding keeps the contract, and says what staging may copy', () => {
    const admin = checkOf(ADMIN);
    assert.deepEqual(admin.errors, []);
    assert.deepEqual(admin.warnings, []);
    assert.deepEqual(admin.brandingLanguages, ['en', 'zz']);
    assert.deepEqual(admin.files.map(({ output }) => output).sort(), [
      'assets/branding/company-logo-en.png',
      'assets/branding/company-logo-zz.png',
      'assets/branding/en.json',
      'assets/branding/zz.json',
      'assets/legal/disclaimer.en.html',
      'assets/legal/disclaimer.zz.html',
    ]);
    const info = checkOf(INFO);
    assert.deepEqual(info.errors, []);
    assert.deepEqual(info.warnings, []);
  });

  it('passes an application with no branding at all when nothing is required', () => {
    const dir = path.join(folder({}), 'absent');
    assert.deepEqual(checkBranding({ ...ADMIN, dir }).errors, []);
  });

  it('takes English as the reference branding, else the default language', () => {
    assert.equal(referenceLanguageOf(['zz', 'en'], 'zz'), 'en');
    assert.equal(referenceLanguageOf(['kl', 'da'], 'kl'), 'kl');
  });

  it('rejects a key the schema does not name', () => {
    assertRejected(
      ADMIN,
      (files) => rewrite(files, 'branding/zz.json', (b) => ({ ...b, 'footer.slogan': 'Hëj' })),
      'branding/zz.json footer.slogan is not a key of branding.schema.json',
    );
  });

  it('rejects a key whose placeholders are not the schema’s', () => {
    assertRejected(
      INFO,
      (files) =>
        rewrite(files, 'branding/zz.json', (b) => ({
          ...b,
          FOOTER_COPYRIGHT: '&copy; 2026 Äüthörïty',
        })),
      'FOOTER_COPYRIGHT has the placeholders none, not year',
    );
  });

  it('rejects a value that is not of its key’s kind', () => {
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(files, 'branding/en.json', (b) => ({
          ...b,
          'footer.link': 'http://authority.example/',
        })),
      'footer.link is not an https: URL',
    );
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(files, 'branding/en.json', (b) => ({
          ...b,
          'footer.accessibility': 'Accessibility statement',
          'footer.accessibilityLink': 'http://statement.example/',
        })),
      'footer.accessibilityLink is not an https: URL',
    );
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(files, 'branding/en.json', (b) => ({
          ...b,
          'main.footer.links.center.imageSrc': 'assets/img/niord.png',
        })),
      'names assets/img/niord.png, which is not a brandable file it ships',
    );
  });

  it('rejects a required key missing', () => {
    const contract = schemaWith((s) => (s.apps.admin.keys['footer.copyright'].required = true));
    assertRejected(
      ADMIN,
      (files) => rewrite(files, 'branding/zz.json', ({ 'footer.copyright': _, ...rest }) => rest),
      'branding/zz.json has no footer.copyright, which is required',
      { schema: contract },
    );
  });

  it('rejects a required file missing', () => {
    assertRejected(
      INFO,
      (files) => delete files['branding/favicon.png'],
      'branding/favicon.png does not exist; it is required',
    );
  });

  it('gives the errors the upgrade report makes an item of structured, each with its line', () => {
    const contract = schemaWith((s) => (s.apps.info.keys.FOOTER_COPYRIGHT.required = true));
    const result = checkOf(
      INFO,
      (files) => {
        files['branding/stray.png'] = PNG;
        rewrite(files, 'branding/zz.json', ({ FOOTER_COPYRIGHT: _, ...rest }) => ({
          ...rest,
          FOOTER_SLOGAN: 'Hëj',
        }));
        delete files['branding/favicon.png'];
      },
      { schema: contract },
    );
    assert.deepEqual(result.findings, [
      {
        kind: 'unnamed-file',
        file: 'branding/stray.png',
        error: 'info/assets/branding/stray.png: is a file branding.schema.json does not name',
      },
      {
        kind: 'unknown-key',
        file: 'branding/zz.json',
        key: 'FOOTER_SLOGAN',
        error:
          'info/assets/branding/zz.json FOOTER_SLOGAN is not a key of branding.schema.json (the country\'s own keys start with "country.")',
      },
      {
        kind: 'required-key',
        file: 'branding/zz.json',
        key: 'FOOTER_COPYRIGHT',
        error: 'info/assets/branding/zz.json has no FOOTER_COPYRIGHT, which is required',
      },
      {
        kind: 'required-file',
        file: 'branding/favicon.png',
        error: 'info/assets/branding/favicon.png does not exist; it is required',
      },
    ]);
    for (const finding of result.findings)
      assert.ok(result.errors.includes(finding.error), finding.error);
    assert.deepEqual(checkOf(INFO).findings, []);
  });

  it('rejects a UI language without branding once another language has some', () => {
    assertRejected(
      ADMIN,
      (files) => delete files['branding/zz.json'],
      'branding/zz.json does not exist, and zz is a UI language of admin',
    );
  });

  it('rejects branding for a language that is not a UI language', () => {
    assertRejected(
      INFO,
      (files) => (files['branding/en.json'] = json({})),
      'branding/en.json: is for "en", which is not a UI language of info',
    );
  });

  it('rejects a legal document whose language’s branding has no title for it', () => {
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(
          files,
          'branding/zz.json',
          ({ 'main.footer.dialogs.disclaimer.title': _, ...rest }) => rest,
        ),
      'legal/disclaimer.zz.html is shipped, but branding/zz.json has no main.footer.dialogs.disclaimer.title',
    );
  });

  it('rejects the footer logo without its alternative text, and its link without its accessible name', () => {
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(
          files,
          'branding/zz.json',
          ({ 'main.footer.links.center.imageAlt': _, ...rest }) => rest,
        ),
      'branding/zz.json sets main.footer.links.center.imageSrc and not main.footer.links.center.imageAlt, which it requires',
    );
    const linked = (b) => ({ ...b, 'main.footer.links.center.url': 'https://authority.example/' });
    assertRejected(
      ADMIN,
      (files) => rewrite(files, 'branding/en.json', linked),
      'branding/en.json sets main.footer.links.center.url and not main.footer.links.center.ariaLabel, which it requires',
    );
    const named = (b) => ({ ...linked(b), 'main.footer.links.center.ariaLabel': 'The authority' });
    const { errors } = checkOf(ADMIN, (files) => {
      rewrite(files, 'branding/en.json', named);
      rewrite(files, 'branding/zz.json', named);
    });
    assert.deepEqual(errors, []);
  });

  it('rejects the accessibility statement’s label without its address, and the reverse', () => {
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(files, 'branding/en.json', (b) => ({
          ...b,
          'footer.accessibility': 'Accessibility statement',
        })),
      'branding/en.json sets footer.accessibility and not footer.accessibilityLink, which it requires',
    );
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(files, 'branding/en.json', (b) => ({
          ...b,
          'footer.accessibilityLink': 'https://statement.example/',
        })),
      'branding/en.json sets footer.accessibilityLink and not footer.accessibility, which it requires',
    );
    const stated = (b) => ({
      ...b,
      'footer.accessibility': 'Accessibility statement',
      'footer.accessibilityLink': 'https://statement.example/',
    });
    const { errors } = checkOf(ADMIN, (files) => rewrite(files, 'branding/en.json', stated));
    assert.deepEqual(errors, []);
  });

  it('rejects a footer label whose document is shipped in no language, or that has no dialog title', () => {
    assertRejected(
      ADMIN,
      (files) => rewrite(files, 'branding/en.json', (b) => ({ ...b, 'footer.cookies': 'Cookies' })),
      'branding/en.json sets footer.cookies, so the footer offers the cookies, but no legal/cookies.{lang}.html is shipped for any branding language',
    );
    assertRejected(
      ADMIN,
      (files) => {
        files['legal/cookies.en.html'] = '<p>Cookies.</p>\n';
        rewrite(files, 'branding/en.json', (b) => ({ ...b, 'footer.cookies': 'Cookies' }));
      },
      'branding/en.json sets footer.cookies and not main.footer.dialogs.cookies.title, the title of the dialog it opens',
    );
  });

  // The public site's footer is a seam a country may replace, so its keys are held to the guide
  // with warnings: the build stays green, and set-up prints each.
  it("warns of the public site's footer logo without its alternative text, and does not fail it", () => {
    const { errors, warnings } = checkOf(INFO, (files) =>
      rewrite(files, 'branding/zz.json', ({ FOOTER_LOGO_ALT: _, ...rest }) => rest),
    );
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'info/assets/branding/zz.json sets FOOTER_LOGO and not FOOTER_LOGO_ALT, which it recommends',
    ]);
  });

  it('warns of a public-site footer label whose document is shipped in no language, and does not fail it', () => {
    const { errors, warnings } = checkOf(INFO, (files) => delete files['legal/cookies.zz.html']);
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'info/assets/branding/zz.json sets FOOTER_COOKIES, so the default footer offers the cookies, but no legal/cookies.{lang}.html is shipped for any branding language',
    ]);
  });

  it("warns of a public-site footer label without its dialog's title, and does not fail it", () => {
    // A second UI language whose branding labels the cookies but has no title; the document is
    // shipped in zz only, whose branding has its title.
    const { errors, warnings } = checkOf(
      INFO,
      (files) => (files['branding/en.json'] = json({ FOOTER_COOKIES: 'Cookies' })),
      { uiLanguages: ['en', 'zz'] },
    );
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'info/assets/branding/en.json sets FOOTER_COOKIES and not LEGAL_TITLE_COOKIES, the title of the dialog it opens',
    ]);
  });

  it('warns of a public-site document shipped without its footer label, naming the default footer', () => {
    const { errors, warnings } = checkOf(INFO, (files) =>
      rewrite(files, 'branding/zz.json', ({ FOOTER_DISCLAIMER: _, ...rest }) => rest),
    );
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'info/assets/legal/disclaimer.zz.html is shipped, but branding/zz.json has no FOOTER_DISCLAIMER, so the default footer never offers it',
    ]);
  });

  it('rejects markup that differs from the reference branding', () => {
    assertRejected(
      ADMIN,
      (files) =>
        rewrite(files, 'branding/zz.json', (b) => ({
          ...b,
          'country.motto': 'Sääfë <i>sëääs</i>',
        })),
      'country.motto has the markup',
    );
  });

  it('rejects an href on another host, or not https:, where the reference links', () => {
    const info = (href) => (files) =>
      rewrite(files, 'branding/zz.json', (b) => ({
        ...b,
        MAP_COPYRIGHT: `&copy; <a href="${href}" target="_blank">Äüthörïty</a>.`,
      }));
    // The reference is English, which the public site needs for this case.
    const withEnglish = (href) => (files) => {
      files['branding/en.json'] = files['branding/zz.json'];
      info(href)(files);
    };
    const options = { uiLanguages: ['en', 'zz'], referenceLanguage: 'en' };
    assertRejected(
      INFO,
      withEnglish('https://elsewhere.example/zz/'),
      'links elsewhere than the reference branding (en)',
      options,
    );
    assertRejected(
      INFO,
      withEnglish('http://authority.example/zz/'),
      'has a link that uses http:',
      options,
    );
    assert.deepEqual(
      checkOf(INFO, withEnglish('https://authority.example/other/path'), options).errors,
      [],
    );
  });

  it('rejects markup where the reference branding has none', () => {
    assertRejected(
      INFO,
      (files) => {
        files['branding/en.json'] = json({ FOOTER_COPYRIGHT: '&copy; {{year}} Authority' });
        rewrite(files, 'branding/zz.json', (b) => ({
          ...b,
          FOOTER_COPYRIGHT: '&copy; {{year}} <b>Äüthörïty</b>',
        }));
      },
      'has markup where the reference branding (en) has none',
      { uiLanguages: ['en', 'zz'], referenceLanguage: 'en' },
    );
  });

  it('rejects an image that is not one by its bytes', () => {
    assertRejected(
      INFO,
      (files) => (files['branding/favicon.png'] = '<html>not an image</html>'),
      'branding/favicon.png: is not PNG by its content',
    );
  });

  // Rule 6, the rules the base product's specs held on the copies it once carried (D23).
  it('rejects a favicon that is not 32×32 px', () => {
    assertRejected(
      INFO,
      (files) => (files['branding/favicon.png'] = png(16, 16)),
      'branding/favicon.png: is 16×16 px; branding.schema.json gives it a size of 32×32 px',
    );
  });

  it('rejects a touch icon that is not 152×152 px', () => {
    assertRejected(
      INFO,
      (files) => (files['branding/apple-touch-icon.png'] = png(180, 180)),
      'branding/apple-touch-icon.png: is 180×180 px; branding.schema.json gives it a size of 152×152 px',
    );
  });

  it('rejects a footer logo that is not 44 px high, whatever its width, in every language', () => {
    assertRejected(
      INFO,
      (files) => (files['branding/logo-gray.png'] = png(120, 24)),
      'branding/logo-gray.png: is 120×24 px; branding.schema.json gives it a height of 44 px',
    );
    assertRejected(
      INFO,
      (files) => (files['branding/logo-gray-zz.png'] = png(120, 24)),
      'branding/logo-gray-zz.png: is 120×24 px; branding.schema.json gives it a height of 44 px',
    );
    const wide = checkOf(INFO, (files) => (files['branding/logo-gray-zz.png'] = png(348, 44)));
    assert.deepEqual(wide.errors, []);
  });

  it('rejects a PNG whose size cannot be read, having no IHDR chunk first', () => {
    const bare = Buffer.concat([SIGNATURE, Buffer.from('imagexyz')]);
    assert.equal(bare.length, 16);
    assertRejected(
      INFO,
      (files) => (files['branding/favicon.png'] = bare),
      'branding/favicon.png: is a PNG without its header chunk (IHDR) first, so its size cannot be read',
    );
  });

  it('rejects a year written into FOOTER_COPYRIGHT, and takes no character reference for one', () => {
    const copyright = (value) => (files) =>
      rewrite(files, 'branding/zz.json', (b) => ({ ...b, FOOTER_COPYRIGHT: value }));
    assertRejected(
      INFO,
      copyright('&copy; 2026 {{year}} Äüthörïty'),
      "branding/zz.json FOOTER_COPYRIGHT has four digits standing alone, 2026, written in: a year goes to {{year}}, which the product fills with the deployment's own",
    );
    assert.deepEqual(checkOf(INFO, copyright('&#8212; {{year}} Äüthörïty')).errors, []);
  });

  it('rejects an address written into MAP_ACCESSIBILITY, and {{url}} anywhere but an href', () => {
    const accessibility = (value) => (files) =>
      rewrite(files, 'branding/zz.json', (b) => ({ ...b, MAP_ACCESSIBILITY: value }));
    const link = '<a href="{{url}}" target="_blank">Äccëssïbïlïty</a>';
    for (const [written, address] of [
      [`${link} https://x.example/a11y`, 'https://x.example/a11y'],
      [`${link} www.x.example`, 'www.x.example'],
      [`${link} a@b.example`, 'a@b.example'],
      [`${link} <a href="mailto:a&#64;b.example">Äüthörïty</a>`, 'mailto:a@b.example'],
    ])
      assertRejected(
        INFO,
        accessibility(written),
        `branding/zz.json MAP_ACCESSIBILITY has the address ${address} written in: the product fills {{url}} from info.config.ts`,
      );
    assertRejected(
      INFO,
      accessibility('Äccëssïbïlïty: {{url}}'),
      'branding/zz.json MAP_ACCESSIBILITY gives {{url}} outside an href: the product fills it with the address of the accessibility statement, which the link opens',
    );
    assert.deepEqual(
      checkOf(INFO, accessibility('<a href=\'{{url}}\' target="_blank">Äccëssïbïlïty</a>.')).errors,
      [],
    );
  });

  it('rejects a cookie statement that does not name each name the public site stores', () => {
    assertRejected(
      INFO,
      (files) => (files['legal/cookies.zz.html'] = cookies(STORED.slice(0, 3))),
      "info/assets/legal/cookies.zz.html: does not name niord-info.NM, which the public site stores in a reader's browser (ui-apps/info/src/app/core/services/preference-keys.json)",
    );
    // A name that only starts with a stored one is not that name.
    assertRejected(
      INFO,
      (files) =>
        (files['legal/cookies.zz.html'] = cookies([...STORED.slice(0, 3), 'niord-info.NMX'])),
      'legal/cookies.zz.html: does not name niord-info.NM,',
    );
  });

  it('rejects every cookie statement once the public site stores a further name', () => {
    const preferences = {
      prefix: 'niord-info.',
      keys: ['language', 'rootAreaId', 'NW', 'NM', 'extra'],
    };
    const { errors } = checkOf(
      INFO,
      (files) => {
        files['branding/en.json'] = files['branding/zz.json'];
        files['legal/cookies.en.html'] = cookies(STORED);
      },
      { uiLanguages: ['en', 'zz'], referenceLanguage: 'en', preferences },
    );
    assert.deepEqual(
      errors.filter((error) => error.includes('does not name')),
      ['en', 'zz'].map(
        (lang) =>
          `info/assets/legal/cookies.${lang}.html: does not name niord-info.extra, which the public site stores in a reader's browser (ui-apps/info/src/app/core/services/preference-keys.json)`,
      ),
    );
  });

  it("does not hold the administration app's cookie statement to the public site's names", () => {
    const { errors } = checkOf(ADMIN, (files) => {
      files['legal/cookies.en.html'] = '<p>Thë sïtë üsës ä sëssïön cöökïë.</p>\n';
      rewrite(files, 'branding/en.json', (b) => ({
        ...b,
        'main.footer.dialogs.cookies.title': 'Cookies',
      }));
    });
    assert.deepEqual(errors, []);
  });

  it("passes the example country's own public-site branding, its real PNGs read for their size", () => {
    const dir = path.join(KIT, 'example', 'fe', 'info', 'assets');
    const { errors, warnings } = checkBranding({ ...INFO, dir });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, []);
    assert.deepEqual(pngSize(fs.readFileSync(path.join(dir, 'branding', 'favicon.png'))), {
      width: 32,
      height: 32,
    });
  });

  it("reads the base product's list of stored names, and refuses one of another shape", () => {
    const list = JSON.parse(fs.readFileSync(path.join(where().base, PREFERENCES), 'utf8'));
    assert.deepEqual(readPreferences(), list);
    // The zip carries it, so a country's set-up reads it from .niord/base/.
    assert.ok(
      compileList(readIncludeList())(PREFERENCES),
      `zip-include.json leaves out ${PREFERENCES}`,
    );
    assert.deepEqual(
      list.keys.map((key) => list.prefix + key),
      STORED,
    );
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'branding-preferences-'));
    temporary.push(base);
    fs.mkdirSync(path.dirname(path.join(base, PREFERENCES)), { recursive: true });
    for (const shape of [{ prefix: '', keys: ['a'] }, { prefix: 'p.', keys: [] }, { keys: [1] }]) {
      fs.writeFileSync(path.join(base, PREFERENCES), json(shape));
      assert.throws(() => readPreferences(base), /is not \{ "prefix"/, JSON.stringify(shape));
    }
  });

  it('gives a size only to a PNG entry, and forbids only rules lib/branding.mjs knows', () => {
    for (const [app, section] of Object.entries(brandingSchema().apps)) {
      for (const [pattern, entry] of Object.entries(section.files ?? {}))
        if (entry.width !== undefined || entry.height !== undefined)
          assert.deepEqual(entry.types, ['png'], `${app} ${pattern}`);
      for (const [key, entry] of Object.entries(section.keys))
        for (const rule of entry.forbids ?? [])
          assert.ok(Object.hasOwn(FORBIDDEN, rule), `${app} ${key} forbids ${rule}`);
    }
    const contract = schemaWith((s) => (s.apps.info.keys.MAP_COPYRIGHT.forbids = ['colour']));
    assert.throws(
      () => checkOf(INFO, undefined, { schema: contract }),
      /info MAP_COPYRIGHT forbids "colour", which lib\/branding\.mjs does not know/,
    );
    const strict = schemaWith((s) => (s.apps.info.legal.labelCheck = 'error'));
    assert.throws(
      () => checkOf(INFO, undefined, { schema: strict }),
      /info legal labelCheck is "error", which lib\/branding\.mjs does not know/,
    );
  });

  it('rejects an SVG with a script, a foreignObject, an event attribute or an external reference', () => {
    const contract = schemaWith((s) => {
      s.apps.info.files['branding/logo.svg'] = {
        output: 'assets/branding/logo.svg',
        types: ['svg'],
        required: false,
      };
    });
    assertRejected(
      INFO,
      (files) =>
        (files['branding/logo.svg'] =
          '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'),
      'an SVG with a script',
      { schema: contract },
    );
    assert.deepEqual(
      imageProblems(
        Buffer.from(
          '<svg><foreignObject/><rect onclick="x()"/><image href="https://x.example/a.png"/></svg>',
        ),
        ['svg'],
      ),
      [
        'an SVG with a foreignObject',
        'an SVG with an event attribute',
        'an SVG with the external reference https://x.example/a.png',
      ],
    );
    assert.deepEqual(imageProblems(Buffer.from('<svg><use href="#a"/></svg>'), ['svg']), []);
  });

  it('rejects a legal document with an event attribute, or outside the dialog’s allow-list', () => {
    assertRejected(
      ADMIN,
      (files) => (files['legal/disclaimer.zz.html'] = '<p onclick="steal()">Dïsclääïmër</p>'),
      'legal/disclaimer.zz.html: holds the event attribute onclick',
    );
    assert.deepEqual(
      legalProblems(
        '<script>x()</script><p style="color:red">a</p><a href="javascript&#58;x()">b</a><iframe src="https://x.example/"></iframe>',
      ),
      [
        'the element <script>',
        'a style attribute',
        'an href that uses javascript:',
        'the element <iframe>',
        'the attribute src',
      ],
    );
  });

  it('rejects a file in the branding folder that the schema does not name', () => {
    assertRejected(
      ADMIN,
      (files) => (files['branding/logo.png'] = PNG),
      'branding/logo.png: is a file branding.schema.json does not name',
    );
    assertRejected(
      ADMIN,
      (files) => (files['legal/imprint.en.html'] = '<p>x</p>'),
      'legal/imprint.en.html: is a file branding.schema.json does not name',
    );
  });

  it('rejects a key named __proto__, prototype or constructor', () => {
    assertRejected(
      ADMIN,
      (files) => (files['branding/zz.json'] = '{ "__proto__": { "x": "y" } }'),
      'branding/zz.json: has the key "__proto__"',
    );
  });

  it('rejects a link in the branding folder', (t) => {
    const dir = folder(adminFiles());
    try {
      fs.symlinkSync(
        path.join(dir, 'branding', 'company-logo-en.png'),
        path.join(dir, 'branding', 'company-logo-xx.png'),
      );
    } catch {
      t.skip('this account may not create symbolic links');
      return;
    }
    const { errors } = checkBranding({ ...ADMIN, dir });
    assert.ok(
      errors.includes('admin/assets/branding/company-logo-xx.png is a link'),
      errors.join('\n'),
    );
  });
});

describe("the country's branding-unaccepted.json", () => {
  // The base product's format, as a country's public site keeps it beside its branding folder.
  const NOTE =
    'Country branding values and legal documents of the public site awaiting acceptance, per language. Go-live requires every list empty.';
  const LISTED = {
    note: NOTE,
    zz: ['assets/branding/zz.json#MAP_ACCESSIBILITY', 'assets/legal/disclaimer.zz.html'],
  };
  /** The public site's errors with `unaccepted` beside its branding. */
  const errorsOf = (unaccepted, base = INFO) => checkOf(base, undefined, { unaccepted }).errors;
  const assertListRejected = (unaccepted, fragment, base) => {
    const errors = errorsOf(unaccepted, base);
    assert.ok(
      errors.some((error) => error.includes(fragment)),
      `expected an error naming "${fragment}", got:\n${errors.join('\n') || '(none)'}`,
    );
  };

  it('passes a list of values and documents the country ships, with one warning while it is not empty', () => {
    const { errors, warnings, files } = checkOf(INFO, undefined, { unaccepted: LISTED });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      '2 branding value(s) and legal document(s) await acceptance (zz 2): info/branding-unaccepted.json; go-live requires every list empty',
    ]);
    // The list is never staged: staging copies only what the schema names.
    assert.ok(!files.some(({ source }) => source.includes('unaccepted')), JSON.stringify(files));
    const admin = checkOf(ADMIN, undefined, {
      unaccepted: {
        note: NOTE,
        en: ['assets/branding/en.json#country.motto'],
        zz: ['assets/legal/disclaimer.zz.html'],
      },
    });
    assert.deepEqual(admin.errors, []);
    assert.deepEqual(admin.warnings, [
      '2 branding value(s) and legal document(s) await acceptance (en 1, zz 1): admin/branding-unaccepted.json; go-live requires every list empty',
    ]);
  });

  it('is silent when the list is absent or every list in it is empty', () => {
    const absent = checkOf(INFO);
    assert.deepEqual([absent.errors, absent.warnings], [[], []]);
    const empty = checkOf(INFO, undefined, { unaccepted: { note: NOTE, zz: [] } });
    assert.deepEqual([empty.errors, empty.warnings], [[], []]);
  });

  it('rejects a list that is not an object, a note that is not a string, and a field that is not a language', () => {
    assert.deepEqual(errorsOf([]), [
      'info/branding-unaccepted.json: is not an object of a note and a list per language',
    ]);
    assert.deepEqual(errorsOf('{'), [
      `info/branding-unaccepted.json: ${(() => {
        try {
          JSON.parse('{');
        } catch (error) {
          return error.message;
        }
      })()}`,
    ]);
    assert.deepEqual(errorsOf({ note: 1, zz: [] }), [
      'info/branding-unaccepted.json: note is not a string',
    ]);
    assert.deepEqual(errorsOf({ notes: NOTE, zz: [] }), [
      'info/branding-unaccepted.json: notes is neither the note nor a language',
    ]);
    assert.deepEqual(errorsOf({ zz: 'assets/legal/disclaimer.zz.html' }), [
      'info/branding-unaccepted.json: zz is not a list',
    ]);
  });

  it("rejects a language that is not one of the application's UI languages", () => {
    // Another country's list, copied along: the public site's UI language is zz alone.
    assertListRejected(
      { note: NOTE, zz: [], kl: ['assets/legal/cookies.kl.html'] },
      'info/branding-unaccepted.json: kl is not a UI language of info',
    );
  });

  it('rejects an entry of neither shape, or for another language than its list', () => {
    for (const entry of [
      'assets/branding/zz.json',
      'assets/branding/zz.json#',
      'assets/branding/zz.json#A#B',
      'branding/zz.json#MAP_ACCESSIBILITY',
      'assets/legal/imprint.zz.html',
      'assets/branding/logo-gray.png',
    ])
      assertListRejected(
        { zz: [entry] },
        `info/branding-unaccepted.json: zz: ${JSON.stringify(entry)} is neither assets/branding/zz.json#<key> nor assets/legal/{doc}.zz.html`,
      );
    assertListRejected({ zz: [7] }, "zz: 7 is not a file's path");
    assertListRejected(
      { en: ['assets/legal/disclaimer.zz.html'] },
      'admin/branding-unaccepted.json: en: "assets/legal/disclaimer.zz.html" is for "zz", not en, the language of its list',
      ADMIN,
    );
  });

  it('rejects a file the country does not ship, and a key its branding or the schema lacks', () => {
    assertRejected(
      INFO,
      (files) => delete files['legal/cookies.zz.html'],
      'zz: "assets/legal/cookies.zz.html" names assets/legal/cookies.zz.html, which the country\'s branding does not ship',
      { unaccepted: { zz: ['assets/legal/cookies.zz.html'] } },
    );
    assertListRejected(
      { zz: ['assets/branding/zz.json#FOOTER_ACCESSIBILITY'] },
      'zz: "assets/branding/zz.json#FOOTER_ACCESSIBILITY" names the key FOOTER_ACCESSIBILITY, which assets/branding/zz.json does not set',
    );
    // A key the branding sets and the schema does not name is rule 1's error too.
    const errors = checkOf(
      INFO,
      (files) => rewrite(files, 'branding/zz.json', (b) => ({ ...b, FOOTER_SLOGAN: 'Hëj' })),
      { unaccepted: { zz: ['assets/branding/zz.json#FOOTER_SLOGAN'] } },
    ).errors;
    assert.ok(
      errors.includes(
        'info/branding-unaccepted.json: zz: "assets/branding/zz.json#FOOTER_SLOGAN" names the key FOOTER_SLOGAN, which branding.schema.json does not name',
      ),
      errors.join('\n'),
    );
  });

  it('rejects an entry listed twice', () => {
    assertListRejected(
      { zz: ['assets/legal/disclaimer.zz.html', 'assets/legal/disclaimer.zz.html'] },
      'zz: "assets/legal/disclaimer.zz.html" is listed twice',
    );
  });

  it('rejects a list inside the branding folder, which staging would otherwise meet', () => {
    assertRejected(
      INFO,
      (files) => (files['branding-unaccepted.json'] = json(LISTED)),
      'info/assets/branding-unaccepted.json: is a file branding.schema.json does not name',
    );
  });
});
