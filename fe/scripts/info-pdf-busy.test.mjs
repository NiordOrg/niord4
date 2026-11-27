/**
 * The pages a reader is given when a PDF cannot be made. The backend makes only a few PDFs at a
 * time for readers without an identity and answers every further request 503 at once; the public
 * site's server answers that 503 with `ui-apps/info/public/pdf-busy.html` in its place, and any
 * other failure of a PDF with `ui-apps/info/public/pdf-failed.html`. The pages' one script words
 * each in the language the PDF was asked in, from that language's bundle.
 *
 * The pages stand outside the application, so what holds them together is held here: their English
 * is the English bundle's words, their script picks the language and words the page as it should
 * and leaves it English when it cannot, their script comes from the site and nothing runs inline,
 * the server sends the busy page for a 503 on both PDF addresses and asks for a retry after as many
 * seconds as the backend does, and the failed page for the other failures, and the image may
 * package all three files.
 *
 *   npm run test:guards      # from fe/
 *
 * The container test (`npm run test:container:info`) asks a built image for both PDFs from a
 * backend that answers 503, 404 and 500 and checks what a reader receives.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PUBLIC = path.join(FE, 'ui-apps/info/public');
const SCRIPT = path.join(PUBLIC, 'pdf-busy.js');
const BUNDLE = path.join(FE, 'languages/en/i18n/info/en.json');
const NGINX = path.join(FE, 'ui-apps/info/nginx/default.conf.template');
const BACKEND = path.join(
  FE,
  '../be/niord/src/main/java/org/niord/web/MessageReportRestService.java',
);
const ALLOWED = path.join(FE, '../country-kit/info-image-allowed.json');

const { SENTENCES, SHARED_WORDS, requestedLanguage, wordPage, localise } = await import(
  pathToFileURL(SCRIPT).href
);
const english = JSON.parse(fs.readFileSync(BUNDLE, 'utf8'));

/** Each page, by its file, with the id of the sentence it holds. */
const PAGES = {
  'pdf-busy.html': 'pdf-busy-text',
  'pdf-failed.html': 'pdf-failed-text',
};

/** The text of the element with the given id in a page's markup, its white space collapsed. */
function pageText(file, id) {
  const page = fs.readFileSync(path.join(PUBLIC, file), 'utf8');
  const element = new RegExp(`<(\\w+)[^>]*\\bid="${id}"[^>]*>([^<]*)</\\1>`).exec(page);
  assert.ok(element, `${file} has no element with the id ${id}`);
  return element[2].replace(/\s+/g, ' ').trim();
}

/** A page as the script sees it: the worded elements, with the given sentence, and the root's language. */
function fakePage(sentenceId = 'pdf-busy-text') {
  const elements = Object.fromEntries(
    [sentenceId, ...Object.keys(SHARED_WORDS)].map((id) => [id, { textContent: id }]),
  );
  return {
    elements,
    documentElement: { lang: 'en' },
    getElementById: (id) => elements[id] ?? null,
  };
}

for (const [file, sentenceId] of Object.entries(PAGES)) {
  const page = fs.readFileSync(path.join(PUBLIC, file), 'utf8');

  describe(`the page ${file}`, () => {
    it("is written in the English bundle's words, which the script words it from", () => {
      const words = { [sentenceId]: SENTENCES[sentenceId], ...SHARED_WORDS };
      for (const [id, key] of Object.entries(words)) {
        assert.equal(typeof english[key], 'string', `the English bundle has no ${key}`);
        assert.equal(
          pageText(file, id),
          english[key],
          `the page's ${id} is not the bundle's ${key}`,
        );
      }
      for (const other of Object.keys(SENTENCES).filter((id) => id !== sentenceId)) {
        assert.doesNotMatch(page, new RegExp(`\\bid="${other}"`), `the page also holds ${other}`);
      }
      assert.match(page, /<html lang="en">/);
    });

    it('runs no script but its own file, from the site itself', () => {
      const scripts = page.match(/<script\b[^>]*>/g) ?? [];
      assert.deepEqual(scripts, ['<script type="module" src="/pdf-busy.js">']);
      assert.doesNotMatch(page, /<script[^>]*>[^<\s]/, 'an inline script');
      assert.doesNotMatch(page, /\son[a-z]+=/i, 'an inline event handler');
    });

    it('asks again at the same address', () => {
      assert.match(page, /<a id="pdf-retry" href="">/);
    });
  });
}

describe('the language it is worded in', () => {
  it("is the PDF address's lang, when that is a language code", () => {
    assert.equal(requestedLanguage('?lang=kl&report=public'), 'kl');
    assert.equal(requestedLanguage('?report=public&lang=da'), 'da');
    for (const search of [
      '',
      '?report=public',
      '?lang=',
      '?lang=../en',
      '?lang=EN',
      '?lang=dansk',
    ]) {
      assert.equal(requestedLanguage(search), null, search);
    }
  });

  it("words the busy page from that language's bundle and says it is in that language", async () => {
    const document = fakePage();
    const asked = [];
    const bundle = { PDF_BUSY: 'Travlt.', MENU_PRINT: 'Udskriv', SITE_TITLE: 'Efterretninger' };
    const worded = await localise(document, '?lang=da&report=public', async (url) => {
      asked.push(url);
      return { ok: true, json: async () => bundle };
    });

    assert.equal(worded, true);
    assert.deepEqual(asked, ['/assets/i18n/da.json']);
    assert.equal(document.elements['pdf-busy-text'].textContent, 'Travlt.');
    assert.equal(document.elements['pdf-retry'].textContent, 'Udskriv');
    assert.equal(document.elements['pdf-title'].textContent, 'Efterretninger');
    assert.equal(document.documentElement.lang, 'da');
  });

  it('words the failed page with its own sentence', async () => {
    const document = fakePage('pdf-failed-text');
    const bundle = {
      PDF_BUSY: 'Travlt.',
      DOCUMENT_LOAD_FAILED: 'Dokumentet kunne ikke indlæses',
      MENU_PRINT: 'Udskriv',
      SITE_TITLE: 'Efterretninger',
    };
    const worded = await localise(document, '?lang=da', async () => ({
      ok: true,
      json: async () => bundle,
    }));

    assert.equal(worded, true);
    assert.equal(
      document.elements['pdf-failed-text'].textContent,
      'Dokumentet kunne ikke indlæses',
    );
    assert.equal(document.elements['pdf-retry'].textContent, 'Udskriv');
    assert.equal(document.elements['pdf-title'].textContent, 'Efterretninger');
    assert.equal(document.documentElement.lang, 'da');
  });

  it("leaves a page English when the bundle lacks that page's sentence", () => {
    const document = fakePage('pdf-failed-text');
    assert.equal(wordPage(document, 'da', { PDF_BUSY: 'Travlt.', MENU_PRINT: 'Udskriv' }), false);
    assert.equal(document.elements['pdf-failed-text'].textContent, 'pdf-failed-text');
    assert.equal(document.elements['pdf-retry'].textContent, 'pdf-retry');
    assert.equal(document.documentElement.lang, 'en');
  });

  it('leaves the page English when the bundle is missing, fails or lacks the sentence', async () => {
    const answers = [
      async () => ({ ok: false, json: async () => ({}) }),
      async () => {
        throw new Error('offline');
      },
      async () => ({ ok: true, json: async () => ({ MENU_PRINT: 'Udskriv' }) }),
    ];
    for (const answer of answers) {
      const document = fakePage();
      assert.equal(await localise(document, '?lang=da', answer), false);
      assert.equal(document.documentElement.lang, 'en');
      assert.equal(document.elements['pdf-retry'].textContent, 'pdf-retry');
    }
  });

  it('asks for no bundle when the address names no language', async () => {
    let asked = false;
    const fetchBundle = async () => {
      asked = true;
      return { ok: true, json: async () => ({}) };
    };
    assert.equal(await localise(fakePage(), '?report=public', fetchBundle), false);
    assert.equal(asked, false);
  });

  it('keeps a word the bundle lacks in English', () => {
    const document = fakePage();
    assert.equal(wordPage(document, 'kl', { PDF_BUSY: 'Sentence.' }), true);
    assert.equal(document.elements['pdf-busy-text'].textContent, 'Sentence.');
    assert.equal(document.elements['pdf-retry'].textContent, 'pdf-retry');
    assert.equal(document.documentElement.lang, 'kl');
  });
});

describe('the server', () => {
  const nginx = fs.readFileSync(NGINX, 'utf8');

  /** The body of the location the given line opens. */
  function location(opening) {
    const start = nginx.indexOf(opening);
    assert.notEqual(start, -1, `no "${opening}" in the nginx configuration`);
    return nginx.slice(start, nginx.indexOf('\n    }', start));
  }

  it('answers a 503 on either PDF address with the busy page, and every other failure with the failed page', () => {
    for (const opening of [
      'location = /rest/message-reports/report.pdf {',
      'location ~ "^/rest/message-reports/message/.+\\.pdf$" {',
    ]) {
      const body = location(opening);
      assert.match(body, /\n\s+proxy_intercept_errors on;/, opening);
      assert.deepEqual(
        body.match(/error_page[^;]*;/g),
        ['error_page 503 /pdf-busy.html;', 'error_page 400 403 404 500 502 504 /pdf-failed.html;'],
        opening,
      );
    }
  });

  it("serves the busy page as an error page only, kept by nobody, with the backend's Retry-After", () => {
    const body = location('location = /pdf-busy.html {');
    assert.match(body, /\n\s+internal;/);
    assert.match(body, /add_header Cache-Control "no-store" always;/);
    assert.match(body, /include \/etc\/nginx\/niord-security-headers\.conf;/);
    const seconds = /add_header Retry-After "(\d+)" always;/.exec(body)?.[1];
    const backend = /ANONYMOUS_PDF_RETRY_AFTER_SECONDS = (\d+);/.exec(
      fs.readFileSync(BACKEND, 'utf8'),
    )?.[1];
    assert.ok(seconds && backend, 'a Retry-After in the page and in the backend');
    assert.equal(seconds, backend, 'the page and the backend ask for the same wait');
  });

  it('serves the failed page as an error page only, kept by nobody, naming no time to ask again', () => {
    const body = location('location = /pdf-failed.html {');
    assert.match(body, /\n\s+internal;/);
    assert.match(body, /add_header Cache-Control "no-store" always;/);
    assert.match(body, /include \/etc\/nginx\/niord-security-headers\.conf;/);
    assert.doesNotMatch(body, /Retry-After/);
  });

  it('may be packaged into the image, the pages and their script', () => {
    const globs = JSON.parse(fs.readFileSync(ALLOWED, 'utf8')).allow.map((entry) => entry.glob);
    assert.ok(globs.includes('pdf-busy.{html,js}'), globs.join(', '));
    assert.ok(globs.includes('pdf-failed.html'), globs.join(', '));
  });
});
