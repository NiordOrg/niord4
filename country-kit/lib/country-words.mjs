/**
 * The base product's country-word guard (fe-country-split-plan §4.8, §4.11 rule 6): no file of
 * fe/ or of the kit beside it may name a country — an authority, its hosts, a country locale or
 * place — unless the list allows that word in that file, with a reason.
 *
 * `country-words.json` beside the kit is `{ format, note, words, allowed }`: `words` the country
 * words, `allowed` entries `{ word, path, reason, until? }`, `path` a glob of lib/include.mjs over
 * a path as the zip names it (relative to fe/, the kit's files as country-kit/…) and `until` the
 * phase that removes the need. A word is matched as a literal substring, case-insensitively, of
 * the NFC form of each entry's path and of its text read as UTF-8 line by line, so a longer word
 * or a composed and a decomposed letter are caught alike. A hit is allowed when an entry with that
 * word matches the path.
 *
 * The guard covers fe/ and the kit in two modes. The tree mode, run by test:guards, reads every
 * file git lists in fe/ and in the kit, named as the zip names them (source-zip.mjs treeFiles),
 * but the list itself (it names every word), and reports the hits and every entry that allows
 * nothing, so the list shrinks as the files it names lose the word and never keeps a stale
 * permission. The zip mode, run over the source zip by `source-zip.mjs build` and
 * `guard`, the example harness and the publish workflow, reports the hits only, because an entry
 * for a file outside the zip permits nothing there. It also runs over the kit package
 * (`source-zip.mjs build` and `guard` with `--package kit`), whose entries are named relative to
 * the kit's folder and are matched here as `country-kit/<entry>`, the name the allow-list gives a
 * kit file. The list stays out of both zips, and a country never runs the guard; the list is read
 * only when a guard runs, never on import, so this module loads where the list is not.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { compileList } from './include.mjs';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const COUNTRY_WORDS_FILE = path.join(KIT, 'country-words.json');
/** The list's name as the zip names paths, the one file the tree mode skips. */
export const COUNTRY_WORDS_ENTRY = 'country-kit/country-words.json';
export const COUNTRY_WORDS_FORMAT = 1;
const MODES = ['zip', 'tree'];

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const LIST_FIELDS = ['allowed', 'format', 'note', 'words'];
const ENTRY_FIELDS = ['path', 'reason', 'until', 'word'];
const nonEmpty = (value) => typeof value === 'string' && value.trim() !== '';
/** The form a word and a text are compared in. */
const folded = (text) => text.normalize('NFC').toLowerCase();

/**
 * Reads and checks the country-word list: its fields, its format, non-empty unique words, and
 * per allow-list entry a word of the list, a glob lib/include.mjs reads and a reason.
 */
export function readCountryWords(file = COUNTRY_WORDS_FILE) {
  const label = path.basename(file);
  let list;
  try {
    list = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
  if (!isObject(list)) throw new Error(`${label} is not an object`);
  const unknown = Object.keys(list).filter((key) => !LIST_FIELDS.includes(key));
  if (unknown.length) throw new Error(`${label} has the unknown field(s) ${unknown.join(', ')}`);
  if (list.format !== COUNTRY_WORDS_FORMAT)
    throw new Error(`${label} is not of format ${COUNTRY_WORDS_FORMAT}`);
  if (list.note !== undefined && typeof list.note !== 'string')
    throw new Error(`${label}: note is not a text`);
  if (!Array.isArray(list.words) || !list.words.length || !list.words.every(nonEmpty))
    throw new Error(`${label}: words is not a list of words`);
  const seen = new Set();
  for (const word of list.words) {
    if (seen.has(folded(word))) throw new Error(`${label}: the word "${word}" is listed twice`);
    seen.add(folded(word));
  }
  if (!Array.isArray(list.allowed)) throw new Error(`${label}: allowed is not a list`);
  const pairs = new Set();
  list.allowed.forEach((entry, i) => {
    const where = `${label}: allowed[${i}]`;
    if (!isObject(entry)) throw new Error(`${where} is not an object`);
    const extra = Object.keys(entry).filter((key) => !ENTRY_FIELDS.includes(key));
    if (extra.length) throw new Error(`${where} has the unknown field(s) ${extra.join(', ')}`);
    if (!list.words.includes(entry.word))
      throw new Error(`${where}: "${entry.word}" is not one of the words`);
    if (!nonEmpty(entry.path)) throw new Error(`${where} names no path`);
    try {
      compileList({ include: [entry.path] });
    } catch (error) {
      throw new Error(`${where}: ${error.message}`, { cause: error });
    }
    if (!nonEmpty(entry.reason)) throw new Error(`${where} gives no reason`);
    if (entry.until !== undefined && !nonEmpty(entry.until))
      throw new Error(`${where}: until is not a phase`);
    const pair = `${entry.word}\0${entry.path}`;
    if (pairs.has(pair)) throw new Error(`${where} repeats ${entry.word} ${entry.path}`);
    pairs.add(pair);
  });
  return list;
}

/**
 * The country words in a zip's or the tree's entries (`{ name, data }`, data a Buffer):
 * `{ hits, unused }`, `hits` every word no allow-list entry permits, `{ name, line, word }` with
 * line 0 for the entry's path and from 1 for its text, and `unused` the allow-list entries that
 * permitted none.
 */
export function countryWordFindings(entries, list) {
  const words = list.words.map((word) => ({ word, key: folded(word) }));
  const allowed = list.allowed.map((entry) => ({
    entry,
    matches: compileList({ include: [entry.path] }),
    used: false,
  }));
  const hits = [];
  const found = (name, line, text) => {
    const haystack = folded(text);
    for (const { word, key } of words) {
      if (!haystack.includes(key)) continue;
      const permits = allowed.filter((a) => a.entry.word === word && a.matches(name));
      if (permits.length) for (const a of permits) a.used = true;
      else hits.push({ name, line, word });
    }
  };
  for (const { name, data } of entries) {
    found(name, 0, name);
    data
      .toString('utf8')
      .split('\n')
      .forEach((text, i) => found(name, i + 1, text));
  }
  return { hits, unused: allowed.filter((a) => !a.used).map((a) => a.entry) };
}

/**
 * The entries of the tree mode: every file given (`{ name, file }`, the name as the zip names it,
 * as source-zip.mjs treeFiles lists them) read as `{ name, data }`, but the list itself, which
 * names every word.
 */
export const treeEntries = (files) =>
  files
    .filter(({ name }) => name !== COUNTRY_WORDS_ENTRY)
    .map(({ name, file }) => ({ name, data: fs.readFileSync(file) }));

/**
 * The guard's findings as lines, never quoting the text a word was found in: one per hit and, in
 * the tree mode only, then one per unused allow-list entry. An empty list when the files pass.
 * `mode` is `zip` (a source zip's entries) or `tree` (every file of fe/ and of the kit).
 */
export function countryWordProblems({ hits, unused }, mode = 'zip') {
  if (!MODES.includes(mode)) throw new Error(`"${mode}" is not a mode (${MODES.join(', ')})`);
  const cure = mode === 'zip' ? ', or keep the file out of the zip' : '';
  return [
    ...hits.map(
      ({ name, line, word }) =>
        `${line ? `${name}:${line}` : `${name} (its path)`}: names "${word}" — allow it in country-kit/country-words.json with a reason${cure}`,
    ),
    ...(mode === 'tree'
      ? unused.map(
          ({ word, path: glob }) =>
            `allow-list entry ${word} ${glob} matches nothing in fe/ or country-kit/: remove it`,
        )
      : []),
  ];
}
