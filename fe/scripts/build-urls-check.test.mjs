/**
 * Negative tests for scripts/build-urls-check.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a small built output into a temporary folder with one external reference in
 * it and expects the check to name the file. Each of these builds and runs green.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import {
  ALLOW_FILE,
  checkAbsoluteUrls,
  checkDistUrls,
  compileCountryUrlAllowList,
  compileUrlAllowList,
  literalUrl,
} from './build-urls-check.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** A clean output: its own fonts and images, an inline image, links on its own origin. */
const CLEAN = {
  'index.html':
    '<!doctype html><html><head><link rel="stylesheet" href="styles-ABC.css"><link rel="icon" href="/favicon.ico"></head><body><a href="https://docs.niord.org">help</a></body></html>',
  'styles-ABC.css':
    '@font-face{font-family:Lato;src:url(./media/lato.woff2) format("woff2")}.x{background:url("data:image/svg+xml;utf8,<svg/>")}@import "./more.css";',
  'main-ABC.js': 'const api="https://example.org/rest";',
  'media/lato.woff2': 'binary',
};

function dist(extra = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'build-urls-check-'));
  temporary.push(root);
  for (const [file, content] of Object.entries({ ...CLEAN, ...extra })) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), content);
  }
  return root;
}

function assertRejected(extra, fragment) {
  const { errors } = checkDistUrls(dist(extra));
  assert.ok(
    errors.some((error) => error.includes(fragment)),
    `expected an error naming "${fragment}", got:\n${errors.join('\n') || '(none)'}`,
  );
}

describe('build-urls-check', () => {
  it('passes an output that loads everything from its own origin', () => {
    const { errors, read } = checkDistUrls(dist());
    assert.deepEqual(errors, []);
    assert.equal(read, 3);
  });

  it('rejects a url() to another origin in a stylesheet', () => {
    assertRejected(
      { 'styles-ABC.css': '.x{background:url( "https://cdn.example.org/a.png")}' },
      'styles-ABC.css:1: a url() to another origin',
    );
    assertRejected(
      { 'styles-ABC.css': '\n@font-face{src:url(//cdn.example.org/f.woff2)}' },
      'styles-ABC.css:2: a url() to another origin',
    );
  });

  it('rejects an @import from another origin in a stylesheet', () => {
    assertRejected(
      { 'styles-ABC.css': '@import "http://cdn.example.org/x.css";' },
      'styles-ABC.css:1: an @import from another origin',
    );
    assertRejected(
      { 'chunk.css': "@import url('//cdn.example.org/x.css');" },
      'chunk.css:1: an @import from another origin',
    );
  });

  it('rejects a <link href> to another origin in a page', () => {
    assertRejected(
      {
        'index.html':
          '<html><head><link rel="preconnect" href="https://cdn.example.org"></head></html>',
      },
      'index.html:1: a <link href> to another origin',
    );
  });

  it("rejects an @import and a font's url() to another origin in a page's inline style", () => {
    assertRejected(
      {
        'index.html':
          '<html><head>\n<style media="all">\n@import url(https://fonts.example/x.css);\n</style></head></html>',
      },
      'index.html:3: an @import from another origin: @import url(https://fonts.example/x.css)',
    );
    assertRejected(
      {
        'index.html':
          '<html><head><style>@font-face{font-family:X;src:url("//cdn.example/x.woff2")}</style></head></html>',
      },
      'index.html:1: a url() to another origin',
    );
  });

  it("reads a page's text outside its inline styles only for <link href>", () => {
    const { errors } = checkDistUrls(
      dist({
        'index.html':
          '<html><head><style>.x{background:url(./a.png)}</style></head><body><p>url(https://example.org/) @import url(https://example.org/x.css)</p></body></html>',
      }),
    );
    assert.deepEqual(errors, []);
  });

  it('reads no absolute URL without --absolute: a script may name its API and a page link out', () => {
    const { errors } = checkDistUrls(
      dist({ 'main-ABC.js': 'fetch("https://cdn.example.org/tracker.js")' }),
    );
    assert.deepEqual(errors, []);
  });

  it("rejects Google Fonts' address in any text file", () => {
    assertRejected(
      { 'chunk-XYZ.js': 'const s="@import url(https://fonts.googleapis.com/css2?family=Lato)";' },
      "chunk-XYZ.js:1: Google Fonts' address",
    );
    assertRejected(
      { 'assets/i18n/en.json': '{"x":"https://fonts.gstatic.com/s/lato.woff2"}' },
      "assets/i18n/en.json:1: Google Fonts' address",
    );
  });
});

describe('build-urls-check --absolute', () => {
  const ALLOW = {
    allow: [
      { pattern: String.raw`https://docs\.niord\.org(?:/|$)`, reason: 'the help link' },
      { pattern: String.raw`https://example\.org/rest`, files: 'main-*.js', reason: 'the API' },
    ],
  };
  const absolute = (extra, allow = ALLOW) => checkAbsoluteUrls(dist(extra), allow).errors;

  it('passes an output whose absolute URLs are all allow-listed', () => {
    assert.deepEqual(absolute(), []);
  });

  it('rejects an absolute URL in a script, a stylesheet and a page', () => {
    assert.deepEqual(absolute({ 'chunk-X.js': 'a="x";\nload("https://cdn.example.org/t.js")' }), [
      'chunk-X.js:2: an absolute URL not in build-urls-allowed.json: https://cdn.example.org/t.js',
    ]);
    assert.deepEqual(
      absolute({ 'styles-ABC.css': '.x{background:url(//cdn.example.org/a.png)}' }),
      ['styles-ABC.css:1: an absolute URL not in build-urls-allowed.json: //cdn.example.org/a.png'],
    );
    assert.deepEqual(
      absolute({ 'teaser.html': '<a href="http://tracker.example.org/?x=1">x</a>' }),
      [
        'teaser.html:1: an absolute URL not in build-urls-allowed.json: http://tracker.example.org/?x=1',
      ],
    );
  });

  it("holds an entry to its files, and reads neither the legal documents nor a scheme's bare prefix", () => {
    assert.deepEqual(absolute({ 'chunk-Y.js': 'f("https://example.org/rest/x")' }), [
      'chunk-Y.js:1: an absolute URL not in build-urls-allowed.json: https://example.org/rest/x',
    ]);
    assert.deepEqual(
      absolute({
        'assets/legal/disclaimer.en.html': '<a href="https://www.example.org/terms">terms</a>',
        'main-ABC.js':
          'u.startsWith("https://")||u.startsWith("//");const api="https://example.org/rest";',
      }),
      [],
    );
  });

  it('fails an allow entry without a reason, with an unknown field or a pattern that is not one', () => {
    // An entry the list gets wrong allows nothing.
    assert.deepEqual(absolute({}, { allow: [{ pattern: 'https://docs\\.niord\\.org' }] }), [
      'build-urls-allowed.json: allow[0] has no reason',
      'index.html:1: an absolute URL not in build-urls-allowed.json: https://docs.niord.org',
      'main-ABC.js:1: an absolute URL not in build-urls-allowed.json: https://example.org/rest',
    ]);
    assert.match(
      absolute({}, { allow: [{ pattern: '(', reason: 'x' }] }).join('\n'),
      /allow\[0\]: the pattern "\(" is not one/,
    );
    assert.match(
      absolute({}, { allow: [{ pattern: 'x', reason: 'x', host: 'y' }] }).join('\n'),
      /allow\[0\] has the unknown field\(s\) host/,
    );
  });

  it("says the base product's list alone when no country list is handed in", () => {
    assert.deepEqual(checkAbsoluteUrls(dist(), ALLOW).matched, new Set());
  });

  it("allows exactly the base product's measured list, each entry with its reason", () => {
    const list = JSON.parse(fs.readFileSync(ALLOW_FILE, 'utf8'));
    assert.equal(list.format, 1);
    assert.ok(list.allow.length > 0);
    for (const entry of list.allow) assert.ok(entry.reason?.trim(), entry.pattern);
    assert.deepEqual(compileUrlAllowList(list).errors, []);
  });
});

describe("build-urls-check --absolute with a country's own list", () => {
  const BASE_ALLOW = {
    allow: [{ pattern: String.raw`https://docs\.niord\.org$`, reason: 'the help link' }],
  };
  const OWN = String.raw`https://authority\.example/en/$`;
  const country = (allow, extra = {}) => compileCountryUrlAllowList({ format: 1, allow, ...extra });
  const entry = (pattern, more = {}) => ({ pattern, reason: "the authority's site", ...more });
  /** The country entry's problems, one line each. */
  const refused = (pattern, more) => country([entry(pattern, more)]).errors;

  it("admits a URL the country's list names, and the same URL fails without it", () => {
    const output = { 'main-ABC.js': 'const a="https://authority.example/en/";' };
    const own = country([entry(OWN)]);
    assert.deepEqual(own.errors, []);
    assert.deepEqual(own.entries[0].url, 'https://authority.example/en/');
    const admitted = checkAbsoluteUrls(dist(output), BASE_ALLOW, own);
    assert.deepEqual(admitted.errors, []);
    assert.deepEqual(admitted.matched, new Set([0]));
    assert.deepEqual(checkAbsoluteUrls(dist(output), BASE_ALLOW, country([])).errors, [
      'main-ABC.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: https://authority.example/en/',
    ]);
  });

  it('fails a URL outside both lists, naming both files, and admits nothing beyond the literal', () => {
    const own = country([entry(OWN)]);
    assert.deepEqual(
      checkAbsoluteUrls(
        dist({
          'main-ABC.js': 'f("https://authority.example/en/x");f("https://tracker.example/")',
        }),
        BASE_ALLOW,
        own,
      ).errors,
      [
        'main-ABC.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: https://authority.example/en/x',
        'main-ABC.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: https://tracker.example/',
      ],
    );
  });

  it('holds a country entry to its files', () => {
    const own = country([entry(OWN, { files: 'chunk-*.js' })]);
    assert.equal(
      checkAbsoluteUrls(dist({ 'main-ABC.js': '"https://authority.example/en/"' }), BASE_ALLOW, own)
        .errors.length,
      1,
    );
  });

  it('refuses an entry that is not https:, or no address of its own', () => {
    assert.match(
      refused(String.raw`http://authority\.example/$`).join('\n'),
      /is not an https: URL/,
    );
    assert.match(refused(String.raw`//authority\.example/$`).join('\n'), /is not an absolute URL/);
    assert.match(
      refused(String.raw`https://user:secret@authority\.example/$`).join('\n'),
      /carries credentials/,
    );
  });

  it('refuses a wildcard, a pattern or an IP address for a host, and a port', () => {
    for (const pattern of [
      String.raw`https://.*\.example/$`,
      String.raw`https://[a-z]+\.example/$`,
      String.raw`https://(?:www\.)?authority\.example/$`,
    ])
      assert.match(refused(pattern).join('\n'), /holds the regular-expression syntax/, pattern);
    assert.match(refused(String.raw`https://192\.0\.2\.1/$`).join('\n'), /no IP address/);
    assert.match(refused(String.raw`https://authority\.example:8443/$`).join('\n'), /no port/);
    assert.match(refused(String.raw`https://localhost/$`).join('\n'), /has the host localhost/);
  });

  it('refuses an unescaped dot, alternation, another escape and a pattern without $', () => {
    assert.deepEqual(refused('https://authority.example/$'), [
      'fe/build-urls-allowed.json: allow[0]: the pattern "https://authority.example/$" holds the regular-expression syntax ".": a country\'s entry is one literal https: URL, escaping only ., /, ? and -; an address that needs other syntax is a base product change',
    ]);
    assert.match(
      refused(String.raw`https://a\.example/|https://b\.example/$`).join('\n'),
      /syntax "\|"/,
    );
    assert.match(refused(String.raw`https://a\.example/\d$`).join('\n'), /syntax "\\\\d"/);
    assert.deepEqual(refused(String.raw`https://authority\.example/`), [
      'fe/build-urls-allowed.json: allow[0]: the pattern "https://authority\\\\.example/" does not end with $',
    ]);
  });

  it('refuses an entry without a reason, an unknown field at either level, and a list of another format', () => {
    assert.deepEqual(country([{ pattern: OWN }]).errors, [
      'fe/build-urls-allowed.json: allow[0] has no reason',
    ]);
    assert.deepEqual(refused(OWN, { host: 'authority.example' }), [
      'fe/build-urls-allowed.json: allow[0] has the unknown field(s) host',
    ]);
    assert.deepEqual(country([], { scope: 'all' }).errors, [
      'fe/build-urls-allowed.json has the unknown field(s) scope',
    ]);
    assert.deepEqual(compileCountryUrlAllowList({ allow: [], note: 1 }).errors, [
      'fe/build-urls-allowed.json: format is undefined, not 1',
      'fe/build-urls-allowed.json: note is not a string',
    ]);
    assert.deepEqual(compileCountryUrlAllowList([]).errors, [
      'fe/build-urls-allowed.json: is not an object',
    ]);
    // An entry the list gets wrong admits nothing.
    assert.deepEqual(country([entry('https://authority.example/$')]).entries, []);
  });

  it('refuses +, { and }, bare or escaped: such an address is a base product change', () => {
    for (const [pattern, syntax] of [
      [String.raw`https://authority\.example/a+b$`, '"+"'],
      [String.raw`https://authority\.example/a\+b$`, String.raw`"\\+"`],
      [String.raw`https://authority\.example/{z}$`, '"{"'],
      [String.raw`https://authority\.example/\{z\}$`, String.raw`"\\{"`],
      [String.raw`https://authority\.example/z}$`, '"}"'],
      [String.raw`https://authority\.example/z\}$`, String.raw`"\\}"`],
    ])
      assert.deepEqual(
        refused(pattern),
        [
          `fe/build-urls-allowed.json: allow[0]: the pattern ${JSON.stringify(pattern)} holds the regular-expression syntax ${syntax}: a country's entry is one literal https: URL, escaping only ., /, ? and -; an address that needs other syntax is a base product change`,
        ],
        pattern,
      );
  });

  it('compiles an entry scoped to one application, keeping its app', () => {
    const own = country([entry(OWN, { app: 'info' })]);
    assert.deepEqual(own.errors, []);
    assert.equal(own.entries[0].app, 'info');
    assert.equal(country([entry(OWN)]).entries[0].app, undefined);
  });

  it("admits a scoped entry's URL in its application's output only, and names it where it does not", () => {
    const output = () => dist({ 'main-ABC.js': '"https://authority.example/en/"' });
    const scoped = country([entry(OWN, { app: 'info' })]);
    const admin = checkAbsoluteUrls(output(), BASE_ALLOW, scoped, 'admin');
    assert.deepEqual(admin.errors, [
      "main-ABC.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: https://authority.example/en/ (fe/build-urls-allowed.json allow[0] admits it in the public site's build only)",
    ]);
    assert.deepEqual(admin.matched, new Set());
    const info = checkAbsoluteUrls(output(), BASE_ALLOW, scoped, 'info');
    assert.deepEqual(info.errors, []);
    assert.deepEqual(info.matched, new Set([0]));
    const toAdmin = country([entry(OWN, { app: 'admin' })]);
    assert.match(
      checkAbsoluteUrls(output(), BASE_ALLOW, toAdmin, 'info').errors.join('\n'),
      / \(fe\/build-urls-allowed\.json allow\[0\] admits it in the administration app's build only\)$/,
    );
  });

  it('admits an unscoped entry in either application, and every entry when no application is named', () => {
    const output = () => dist({ 'main-ABC.js': '"https://authority.example/en/"' });
    const unscoped = country([entry(OWN)]);
    for (const app of ['admin', 'info', undefined])
      assert.deepEqual(
        checkAbsoluteUrls(output(), BASE_ALLOW, unscoped, app).errors,
        [],
        String(app),
      );
    assert.deepEqual(
      checkAbsoluteUrls(output(), BASE_ALLOW, country([entry(OWN, { app: 'info' })])).errors,
      [],
    );
  });

  it('refuses an app that is neither admin nor info, and admits nothing by the entry', () => {
    assert.deepEqual(refused(OWN, { app: 'backend' }), [
      'fe/build-urls-allowed.json: allow[0]: app is "backend", not "admin" or "info"',
    ]);
    assert.deepEqual(refused(OWN, { app: '' }), [
      'fe/build-urls-allowed.json: allow[0]: app is "", not "admin" or "info"',
    ]);
    assert.deepEqual(refused(OWN, { app: 1 }), [
      'fe/build-urls-allowed.json: allow[0]: app is 1, not "admin" or "info"',
    ]);
    assert.deepEqual(country([entry(OWN, { app: 'backend' })]).entries, []);
  });

  it("keeps refusing app in the base product's own list, which applies to both applications", () => {
    assert.deepEqual(
      compileUrlAllowList({ allow: [{ pattern: 'x', reason: 'r', app: 'info' }] }).errors,
      ['build-urls-allowed.json: allow[0] has the unknown field(s) app'],
    );
  });

  it('reads an entry as the URL it spells, the four escapes a literal URL needs undone', () => {
    assert.deepEqual(literalUrl(String.raw`https://accessibility\.example\.org/a\-b\/c\?d$`), {
      url: 'https://accessibility.example.org/a-b/c?d',
    });
  });
});
