/**
 * Tests for country-kit/lib/country-words.mjs and its list, country-words.json.
 *
 *   npm run test:guards      # from fe/
 *
 * The guard keeps country words out of the base product's fe/, the kit beside it and the source
 * zip. Each refusal is tested on crafted entries and lists, in both modes; the last cases run the
 * real list over every file of fe/ and of the kit that git lists, named as the zip names them, so
 * a new country word anywhere in either fails here before it reaches the example harness or a
 * publish, and an allow-list entry nothing needs any more fails as well.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import {
  COUNTRY_WORDS_ENTRY,
  COUNTRY_WORDS_FILE,
  countryWordFindings,
  countryWordProblems,
  readCountryWords,
  treeEntries,
} from './lib/country-words.mjs';
import { compileList, readIncludeList } from './lib/include.mjs';
import { where } from './lib/where.mjs';
import { writeZip } from './lib/zip.mjs';
import { guardSourceZip, KIT_INCLUDE_FILE, treeFiles } from './source-zip.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

// Crafted words, so this file's own cases depend on no entry of the real list.
const LIST = {
  format: 1,
  words: ['Fjordland', 'harbour.example', 'Skærgård'],
  allowed: [],
};
const entry = (name, text) => ({ name, data: Buffer.from(text, 'utf8') });
const problems = (entries, list = LIST, mode = 'zip') =>
  countryWordProblems(countryWordFindings(entries, list), mode);

/** A list written to a temporary file, for the reader's refusals. */
function written(list) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'country-words-'));
  temporary.push(dir);
  const file = path.join(dir, 'country-words.json');
  fs.writeFileSync(file, JSON.stringify(list));
  return file;
}

describe('countryWordFindings', () => {
  it('finds a word in a file, naming the file and line, never the text', () => {
    const found = problems([entry('ui-apps/a.ts', 'one\nconst authority = "Fjordland";\n')]);
    assert.deepEqual(found, [
      'ui-apps/a.ts:2: names "Fjordland" — allow it in country-kit/country-words.json with a reason, or keep the file out of the zip',
    ]);
    assert.ok(!found[0].includes('authority'));
  });

  it("finds a word in an entry's path", () => {
    assert.deepEqual(problems([entry('assets/fjordland/logo.svg', '<svg/>')]), [
      'assets/fjordland/logo.svg (its path): names "Fjordland" — allow it in country-kit/country-words.json with a reason, or keep the file out of the zip',
    ]);
  });

  it('matches case-insensitively, inside a longer word and in either Unicode form', () => {
    // å decomposes (a and a combining ring), so the decomposed text must still match; ø and æ
    // have no decomposition. d.ts differs by one letter and must not match.
    const decomposed = 'Skærgård'.normalize('NFD');
    assert.notEqual(decomposed, 'Skærgård');
    const { hits } = countryWordFindings(
      [
        entry('a.ts', 'FJORDLANDS coast\n'),
        entry('b.ts', 'see https://www.HARBOUR.example/x\n'),
        entry('c.ts', `// ${decomposed}\n`),
        entry('d.ts', '// Skärgård\n'),
      ],
      LIST,
    );
    assert.deepEqual(
      hits.map(({ name, word }) => `${name} ${word}`),
      ['a.ts Fjordland', 'b.ts harbour.example', 'c.ts Skærgård'],
    );
  });

  it('passes a word an entry allows in that path, and fails it in another path', () => {
    const list = {
      ...LIST,
      allowed: [{ word: 'Fjordland', path: 'ui-apps/**/*.spec.ts', reason: 'sample data' }],
    };
    const { hits, unused } = countryWordFindings(
      [
        entry('ui-apps/admin/x.spec.ts', 'Fjordland\n'),
        entry('ui-apps/admin/x.ts', 'Fjordland\n'),
        entry('ui-apps/admin/y.spec.ts', 'harbour.example\n'),
      ],
      list,
    );
    assert.deepEqual(hits, [
      { name: 'ui-apps/admin/x.ts', line: 1, word: 'Fjordland' },
      { name: 'ui-apps/admin/y.spec.ts', line: 1, word: 'harbour.example' },
    ]);
    assert.deepEqual(unused, []);
  });

  it('in tree mode, finds a word in any file of fe/, a document included', () => {
    assert.deepEqual(
      problems([entry('docs/x.md', '# Notes\nThe Fjordland authority\n')], LIST, 'tree'),
      ['docs/x.md:2: names "Fjordland" — allow it in country-kit/country-words.json with a reason'],
    );
  });

  it('fails an allow-list entry that permits nothing in tree mode, never in zip mode', () => {
    const list = {
      ...LIST,
      allowed: [
        { word: 'Fjordland', path: 'LICENSE', reason: 'the holder' },
        { word: 'Skærgård', path: 'LICENSE', reason: 'gone', until: 'P7' },
      ],
    };
    const entries = [entry('LICENSE', 'Copyright Fjordland\n')];
    assert.deepEqual(problems(entries, list, 'tree'), [
      'allow-list entry Skærgård LICENSE matches nothing in fe/ or country-kit/: remove it',
    ]);
    assert.deepEqual(problems(entries, list, 'zip'), []);
    assert.deepEqual(countryWordProblems(countryWordFindings(entries, list)), []);
  });

  it('in zip mode, passes an entry for a file outside the zip', () => {
    // An entry for a document permits nothing in the zip, which never carries one.
    const list = {
      ...LIST,
      allowed: [{ word: 'Fjordland', path: 'docs/x.md', reason: 'a dated record' }],
    };
    const zip = writeZip([entry('ui-apps/a.ts', 'export {};\n')]);
    assert.deepEqual(guardSourceZip(zip, list), []);
  });

  it('refuses a mode it does not know', () => {
    assert.throws(() => problems([], LIST, 'all'), /"all" is not a mode/);
  });

  it('passes entries that name no word', () => {
    assert.deepEqual(problems([entry('a.ts', 'export {};\n'), entry('b.png', '\x89PNG')]), []);
  });
});

describe('treeEntries', () => {
  it('reads every file given but the list itself, which names every word', () => {
    const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'country-words-tree-'));
    temporary.push(fe);
    const files = {
      [COUNTRY_WORDS_ENTRY]: '{ "words": ["Fjordland"] }\n',
      'docs/country-words.json': '{ "words": ["Fjordland"] }\n',
      'docs/x.md': '# Notes\n',
    };
    for (const [name, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(fe, name)), { recursive: true });
      fs.writeFileSync(path.join(fe, name), text);
    }
    const entries = treeEntries(
      Object.keys(files).map((name) => ({ name, file: path.join(fe, name) })),
    );
    assert.deepEqual(
      entries.map(({ name }) => name),
      ['docs/country-words.json', 'docs/x.md'],
    );
    // Only the list's own path is skipped: a copy elsewhere is read like any file.
    assert.deepEqual(problems(entries, LIST, 'tree'), [
      'docs/country-words.json:1: names "Fjordland" — allow it in country-kit/country-words.json with a reason',
    ]);
  });
});

describe('readCountryWords', () => {
  const good = () => ({
    format: 1,
    note: 'n',
    words: ['Fjordland'],
    allowed: [{ word: 'Fjordland', path: 'LICENSE', reason: 'the holder', until: 'P7' }],
  });

  it('reads a list in its format', () => {
    assert.deepEqual(readCountryWords(written(good())), good());
  });

  it('refuses an unknown field, in the list and in an entry', () => {
    assert.throws(
      () => readCountryWords(written({ ...good(), extra: 1 })),
      /unknown field\(s\) extra/,
    );
    const list = good();
    list.allowed[0].owner = 'x';
    assert.throws(() => readCountryWords(written(list)), /allowed\[0\] has the unknown field/);
  });

  it('refuses another format, no words and a word given twice', () => {
    assert.throws(() => readCountryWords(written({ ...good(), format: 2 })), /not of format 1/);
    assert.throws(() => readCountryWords(written({ ...good(), words: [] })), /not a list of words/);
    assert.throws(
      () => readCountryWords(written({ ...good(), words: ['Fjordland', 'FJORDLAND'] })),
      /listed twice/,
    );
  });

  it('refuses an entry whose word is not one of the words', () => {
    const list = good();
    list.allowed[0].word = 'Fjord*';
    assert.throws(() => readCountryWords(written(list)), /"Fjord\*" is not one of the words/);
  });

  it('refuses an entry without a reason', () => {
    const list = good();
    list.allowed[0].reason = ' ';
    assert.throws(() => readCountryWords(written(list)), /gives no reason/);
  });

  it('refuses an entry whose path is not a glob of the include list', () => {
    for (const glob of ['/LICENSE', 'ui-apps/{a,b', 'ui-apps\\a.ts', 'a/b**/c']) {
      const list = good();
      list.allowed[0].path = glob;
      assert.throws(() => readCountryWords(written(list)), /allowed\[0\]/, glob);
    }
  });

  it('refuses the same word and path twice', () => {
    const list = good();
    list.allowed.push({ ...list.allowed[0] });
    assert.throws(() => readCountryWords(written(list)), /repeats Fjordland LICENSE/);
  });
});

describe('the real list over fe/ and the kit', () => {
  const list = readCountryWords();

  it('reads, and stays out of both packages itself', () => {
    const selects = compileList(readIncludeList());
    const kitSelects = compileList(readIncludeList(KIT_INCLUDE_FILE));
    assert.equal(path.relative(where().kit, COUNTRY_WORDS_FILE), 'country-words.json');
    assert.equal(COUNTRY_WORDS_ENTRY, 'country-kit/country-words.json');
    assert.ok(!selects(COUNTRY_WORDS_ENTRY));
    assert.ok(!kitSelects('country-words.json'));
    // The guard's code goes with the kit, in its own package, no longer in the source zip.
    assert.ok(kitSelects('lib/country-words.mjs'));
    assert.ok(!selects('country-kit/lib/country-words.mjs'));
    for (const { until } of list.allowed) if (until) assert.match(until, /^P\d+$/);
  });

  it('reads a file of the kit under its country-kit/ name', () => {
    const file = treeFiles().find(({ name }) => name === 'country-kit/lib/country-words.mjs');
    assert.ok(file, 'the tree names country-kit/lib/country-words.mjs');
    assert.equal(file.file, path.join(where().kit, 'lib', 'country-words.mjs'));
  });

  it('finds no country word the list does not allow in fe/ or the kit, and no entry is unused', () => {
    // Every file of fe/ and of the kit that git lists, tracked and untracked but never ignored, as
    // the zip build reads them; the list itself is left out, since it names every word.
    const entries = treeEntries(treeFiles());
    assert.ok(entries.length > 1000, `${entries.length} files`);
    assert.deepEqual(problems(entries, list, 'tree'), []);
  });
});
