// english-history.mjs: the message history snapshots of niord_guides made English, after english.sql. A snapshot is a
// message as JSON, gzip-compressed (MessageHistory.snapshot), which SQL cannot rewrite; the editor's History shows it
// (app-json-diff-pane: 'title:...', 'name:0:da:...'). Run by make-guides-db.sh after english.sql; running it again
// changes nothing.
//
// Each snapshot is read, and
//   - every Danish entry is dropped: an object with lang "da" in an array (the descs of the message, its parts,
//     references, attachments, areas, categories) and a feature property named "...:da" (a position's Danish name),
//     a position's name that has no English key taking one first, as english.sql gives the features;
//   - every English entry's texts get english.sql's rules: the whole values it set (guides.english_value), its phrase
//     rules (guides.english_phrase), Danish month names in dates and 'signal mast N ved'; a position's English name
//     the feature rules (guides.english_feature_rule) and the phrase rules; a NAVTEX text the NAVTEX rules
//     (guides.english_navtex);
//   - an audio broadcast text that looks Danish (english.sql's guides.looks_danish, ported; its word list is read from
//     the function in the database) is blanked;
//   - a person's e-mail address (scrub.sql's rule "A person's address in any text": the users' addresses and the
//     comment recipients', not the functional mailboxes, and the private mailbox, and an address of a person's form,
//     guides.scrub_shape, that is none of theirs) becomes the invented address scrub.sql gave it,
//     case-insensitively, wherever the whole address stands (an address of the form scrub.sql never saw, in a
//     snapshot only, a new duty.officer<N>@example.org);
//   - default-deny (2026-11-14, scrub.sql): any other address outside example.org that is neither on the functional
//     list nor made of scrub-lists.mjs's role words only becomes scrub.sql's contact<N>@example.org for it (one
//     scrub.sql never saw, in a snapshot only, a new contact<N>@example.org);
// and written back, gzip-compressed, when it changed. Reads and writes niord_guides, through psql in the database
// container (GUIDES_DB_CONTAINER, by default niord-db), and prints what it changed. It reads the persons' addresses
// from niord_test (GUIDES_SOURCE_DB), only reads, into memory: they are never written or printed; the invented address
// of each is guides.scrub_mail's row for the same source row (the user's id, the comment address's rank), which
// holds no real address.
//
// A second self-check (2026-11-14): no snapshot, as written back, may still hold one of those addresses, also after a
// dot or before a dot (stricter than the rule), an address of a person's form outside example.org, or an address
// default-deny does not keep (also with the at-sign as an entity or encoded); the script then exits 1 and names the
// history rows by id. GUIDES_HISTORY_PROOF_SQL (the proof that it fails; never set in a run) is SQL run first in the
// transaction the snapshots are read in, which is rolled back; the script then writes nothing back.
//
// The self-check (the fifth check, 2026-11-11), as english.sql's for the current texts: the editor's History page shows
// every snapshot of a live message, so after the rewrite no snapshot of a message now in DRAFT, VERIFIED or PUBLISHED
// may hold an English text or English position name that looks Danish (guides.looks_danish, or the colour words gul,
// grøn, rød, hvid, 'Tlf.', or an abbreviation with the Danish definite ending, "USV'en"), unless the message is in
// guides.names_only. The script then exits 1 and names the
// messages and texts, after writing what it rewrote, so that a rule is added to english.sql and the script run again.
//
//   node fe/guides-shots/data/english-history.mjs

import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { gunzipSync, gzipSync } from 'node:zlib';
import {
  ADDRESS_PATTERN,
  keptAddress,
  readPersons,
  scrubLists,
  shapePattern,
} from './scrub-lists.mjs';

const CONTAINER = process.env.GUIDES_DB_CONTAINER || 'niord-db';
const DB = 'niord_guides';
const SOURCE_DB = process.env.GUIDES_SOURCE_DB || 'niord_test';
const USER = process.env.GUIDES_DB_USER || 'niord';
// The proof that the self-checks fail (never set in a run): SQL run first in the transaction the snapshots are read
// in, which is rolled back; the script then writes nothing back.
const PROOF_SQL = process.env.GUIDES_HISTORY_PROOF_SQL || '';

function psqlOn(db, ...commands) {
  return execFileSync(
    'docker',
    [
      'exec',
      CONTAINER,
      'psql',
      '-X',
      '-At',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      USER,
      '-d',
      db,
      ...commands.flatMap((c) => ['-c', c]),
    ],
    { encoding: 'utf8', maxBuffer: 1 << 28, windowsHide: true },
  );
}
const psql = (...commands) => psqlOn(DB, ...commands);

// The persons' addresses (scrub.sql's g_person_mail, the same rule and the same source rows): from niord_test, with
// the source row that names each without the address; a user's address first, then a comment recipient's, then the
// private mailbox's, then an address of a person's form that is none of theirs (scrub.sql's guides.scrub_shape, by
// its rank). The functional mailboxes are left out. scrub-lists.mjs reads scrub.sql's lists (g_functional_login,
// g_private_mail, the form), so they are written once.
const sourcePersons = readPersons({ container: CONTAINER, db: SOURCE_DB, user: USER });
// An address of the person's form that scrub.sql never saw (in a snapshot only) gets an invented address after the
// ones guides.scrub_mail holds, one per distinct address, in the order the snapshots are read (by id).
const SHAPE = new RegExp(shapePattern(scrubLists().shape), 'gi');
const shapeExtra = new Map();
const inventedBySource = new Map(
  JSON.parse(
    psql(
      `SELECT coalesce(json_agg(json_build_array(src || ':' || key, repl)), '[]') FROM guides.scrub_mail`,
    ),
  ),
);
const escapeRegex = (s) => s.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&');
const persons = new Map();
for (const [addr, src, key] of sourcePersons.sort(
  (a, b) => a[3] - b[3] || a[1].localeCompare(b[1]),
)) {
  if (persons.has(addr)) continue;
  const invented = inventedBySource.get(`${src}:${key}`);
  if (!invented)
    throw new Error(
      `A person's address of ${SOURCE_DB} (${src} ${key}) has no row in guides.scrub_mail: run make-guides-db.sh.`,
    );
  persons.set(addr, {
    addr,
    src,
    invented,
    // scrub.sql's rule: the whole address, not inside a longer one
    rule: new RegExp(
      `(?<![A-Za-z0-9._%+-])${escapeRegex(addr)}(?![A-Za-z0-9-]|\\.[A-Za-z0-9])`,
      'gi',
    ),
    // the self-check: also after a dot and before a dot
    check: new RegExp(`(?<![a-z0-9_%+-])${escapeRegex(addr)}(?![a-z0-9-])`),
  });
}
if (persons.size === 0) throw new Error(`No person's address was read from ${SOURCE_DB}.`);
const personList = [...persons.values()].sort((a, b) => b.addr.length - a.addr.length);
const shapeKeys = [...inventedBySource.keys()].filter((k) => k.startsWith('shape:'));
// The ranks name the same addresses on both sides only when both saw the same set
if (shapeKeys.length !== sourcePersons.filter((p) => p[1] === 'shape').length)
  throw new Error(
    `guides.scrub_mail holds ${shapeKeys.length} addresses of a person's form, ${SOURCE_DB} gives ` +
      `${sourcePersons.filter((p) => p[1] === 'shape').length}: run make-guides-db.sh again.`,
  );
const denyKeys = [...inventedBySource.keys()].filter((k) => k.startsWith('deny:'));
if (denyKeys.length !== sourcePersons.filter((p) => p[1] === 'deny').length)
  throw new Error(
    `guides.scrub_mail holds ${denyKeys.length} addresses default-deny replaces, ${SOURCE_DB} gives ` +
      `${sourcePersons.filter((p) => p[1] === 'deny').length}: run make-guides-db.sh again.`,
  );
let nextOfficer = Math.max(0, ...shapeKeys.map((k) => Number(k.slice(6)))) + 1;
// Default-deny (scrub.sql): any other address that is neither on the functional list nor made of role words only.
// One that scrub.sql never saw (in a snapshot only) gets an invented address after the ones guides.scrub_mail holds,
// one per distinct address, in the order the snapshots are read (by id).
const ADDRESS = new RegExp(ADDRESS_PATTERN, 'gi');
const functional = scrubLists().functional;
const kept = (address) => keptAddress(address, functional);
const denyExtra = new Map();
let nextContact = Math.max(0, ...denyKeys.map((k) => Number(k.slice(5)))) + 1;
/** The invented address of an address default-deny does not keep (a listed one's, else a new one). */
function inventedForDeny(addr) {
  const lower = addr.toLowerCase();
  const known = persons.get(lower)?.invented ?? denyExtra.get(lower);
  if (known) return known;
  const invented = `contact${nextContact++}@example.org`;
  denyExtra.set(lower, invented);
  return invented;
}
/** Whether a text still holds an address default-deny does not keep (also with the at-sign as an entity or encoded). */
const holdsDenied = (text) =>
  [
    ...text
      .toLowerCase()
      .replace(/&#0*64;|&#x0*40;|&commat;|%40|\\u0040/g, '@')
      .matchAll(ADDRESS),
  ].some((m) => !kept(m[1]));
/** Every string of a parsed snapshot. */
function strings(node, out = []) {
  if (typeof node === 'string') out.push(node);
  else if (Array.isArray(node)) node.forEach((item) => strings(item, out));
  else if (node && typeof node === 'object') Object.values(node).forEach((v) => strings(v, out));
  return out;
}
/** A parsed snapshot with every address default-deny does not keep replaced in its strings. */
function denyStrings(node, stats) {
  if (typeof node === 'string') {
    if (!node.includes('@')) return node;
    return node.replace(ADDRESS, (match) => {
      if (kept(match)) return match;
      stats.deny++;
      return inventedForDeny(match);
    });
  }
  if (Array.isArray(node)) return node.map((item) => denyStrings(item, stats));
  if (node && typeof node === 'object')
    return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, denyStrings(v, stats)]));
  return node;
}
/** The invented address of an address of the person's form (a listed one's, else a new one). */
function inventedForShape(addr) {
  const lower = addr.toLowerCase();
  if (lower.endsWith('@example.org')) return addr;
  const known = persons.get(lower)?.invented ?? shapeExtra.get(lower);
  if (known) return known;
  const invented = `duty.officer${nextOfficer++}@example.org`;
  shapeExtra.set(lower, invented);
  return invented;
}
/** Whether a text still holds an address of the person's form outside example.org. */
const holdsShape = (text) =>
  [...text.toLowerCase().matchAll(SHAPE)].some((m) => !m[1].endsWith('@example.org'));

// A PostgreSQL ARE (the patterns english.sql applies) as a JavaScript one: \m and \M are word boundaries there.
const WORD = '[\\p{L}\\p{N}_]';
function jsRegex(pattern, flags = 'gu') {
  return new RegExp(
    pattern.replaceAll('\\m', `(?<!${WORD})`).replaceAll('\\M', `(?!${WORD})`),
    flags,
  );
}
const jsReplacement = (repl) => repl.replace(/\\(\d)/g, '$$$1');

const phrases = JSON.parse(
  psql(
    `SELECT coalesce(json_agg(json_build_array(pattern, repl) ORDER BY n), '[]') FROM guides.english_phrase`,
  ),
).map(([pattern, repl]) => [jsRegex(pattern), jsReplacement(repl)]);
const values = new Map(
  JSON.parse(
    psql(
      `SELECT coalesce(json_agg(json_build_array(old_value, new_value)), '[]') FROM guides.english_value`,
    ),
  ),
);
// The feature rules replace once (no 'g', as english.sql applies them); the NAVTEX rules everywhere.
const featureRules = JSON.parse(
  psql(
    `SELECT coalesce(json_agg(json_build_array(pattern, repl) ORDER BY n), '[]') FROM guides.english_feature_rule`,
  ),
).map(([pattern, repl]) => [jsRegex(pattern, 'u'), jsReplacement(repl)]);
const navtexRules = JSON.parse(
  psql(
    `SELECT coalesce(json_agg(json_build_array(pattern, repl) ORDER BY n), '[]') FROM guides.english_navtex`,
  ),
).map(([pattern, repl]) => [jsRegex(pattern), jsReplacement(repl)]);

// english.sql's pg_temp.english_dates: a day before a Danish month name ('7. juni'), a year after it ('januar 2025').
const MONTHS = [
  ['januar', 'January'],
  ['februar', 'February'],
  ['marts', 'March'],
  ['april', 'April'],
  ['maj', 'May'],
  ['juni', 'June'],
  ['juli', 'July'],
  ['august', 'August'],
  ['september', 'September'],
  ['oktober', 'October'],
  ['november', 'November'],
  ['december', 'December'],
].map(([da, en]) => [
  jsRegex(`\\m(\\d{1,2})\\.?((?:\\s|&nbsp;)+)${da}\\M`, 'giu'),
  jsRegex(`\\m${da}((?:\\s|&nbsp;)+\\d{4})\\M`, 'giu'),
  en,
]);
const SIGNAL_MAST = /(signal ?mast \d+) ved /giu;

function english(text) {
  let t = values.get(text) ?? text;
  for (const [day, year, en] of MONTHS) t = t.replace(day, `$1$2${en}`).replace(year, `${en}$1`);
  t = t.replace(SIGNAL_MAST, '$1 at ');
  for (const [regex, repl] of phrases) t = t.replace(regex, repl);
  return t;
}

// english.sql's pg_temp.english_feature and pg_temp.english_navtex.
function englishFeature(text) {
  let t = text;
  for (const [regex, repl] of featureRules) t = t.replace(regex, repl);
  for (const [regex, repl] of phrases) t = t.replace(regex, repl);
  return t;
}
function englishNavtex(text) {
  let t = text;
  for (const [regex, repl] of navtexRules) t = t.replace(regex, repl);
  return t;
}

// A position's name: 'name:<lang>' (the feature's) or 'name:<n>:<lang>' (a point's); the slot is the part before the
// language.
const NAME_KEY = /^(name(?::\d+)?):[A-Za-z]+$/;

/**
 * A feature's properties with an English key for every name that has none (the feature's own language's value, else
 * the first key's, as english.sql's pg_temp.english_properties) and the English keys given the feature rules.
 */
function englishNames(node, stats) {
  const out = { ...node };
  const slots = new Set();
  for (const key of Object.keys(node)) {
    const match = NAME_KEY.exec(key);
    if (match) slots.add(match[1]);
  }
  for (const slot of slots) {
    const enKey = `${slot}:en`;
    let value = node[enKey];
    if (typeof value !== 'string') {
      const own = node[`${slot}:${node.language ?? ''}`];
      const first = Object.keys(node)
        .filter((key) => NAME_KEY.exec(key)?.[1] === slot)
        .sort()
        .map((key) => node[key])[0];
      value = typeof own === 'string' && own !== '' ? own : first;
      if (typeof value !== 'string' || value === '') continue;
    }
    const changed = englishFeature(value);
    if (changed !== node[enKey]) {
      stats.english++;
      out[enKey] = changed;
    }
  }
  return out;
}

// english.sql's guides.clean and guides.looks_danish.
function clean(x) {
  return (x ?? '')
    .replaceAll('&aelig;', 'æ')
    .replaceAll('&oslash;', 'ø')
    .replaceAll('&aring;', 'å')
    .replaceAll('&AElig;', 'Æ')
    .replaceAll('&Oslash;', 'Ø')
    .replaceAll('&Aring;', 'Å')
    .replaceAll('&nbsp;', ' ')
    .replaceAll('&amp;', '&')
    .replaceAll('&deg;', '°')
    .replaceAll('&eacute;', 'é')
    .replaceAll('&shy;', '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&[a-zA-Z]+;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
// The word list is read from guides.looks_danish itself, so the two never differ.
const looksDanishSource = psql(
  `SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace ` +
    `WHERE n.nspname = 'guides' AND p.proname = 'looks_danish'`,
);
const wordList = /\\m\((og\|[^)]*)\)\\M/.exec(looksDanishSource)?.[1];
if (!wordList)
  throw new Error('The word list of guides.looks_danish was not found: run english.sql first.');
const DANISH_WORDS = jsRegex(`\\m(${wordList})\\M`.replaceAll('\\w', '[\\p{L}\\p{N}_]'), 'u');
const DANISH_LETTER_WORD = /(^|[^\p{L}])[a-zæøå]*[æøå][a-zæøå]*/u;
function visibleText(x) {
  return clean((x ?? '').replace(/href="[^"]*"/g, ''))
    .replace(/\S*(https?:\/\/|www\.|@)\S*/g, ' ')
    .trim();
}
function looksDanish(x) {
  const t = visibleText(x);
  return DANISH_WORDS.test(t.toLowerCase()) || DANISH_LETTER_WORD.test(t);
}
// The self-check's addition: a Danish colour word alone ('gul', 'Rød') or the Danish abbreviation of telephone.
const COLOUR_OR_PHONE = /(?<![\p{L}\p{N}_])(?:gul|grøn|rød|hvid|tlf\.)(?![\p{L}\p{N}_])/iu;
// And an abbreviation with the Danish definite ending ("USV'en": the USV).
const DANISH_DEFINITE = /(?<![\p{L}])[A-Z]{2,}'en(?![\p{L}])/u;
/** The Danish word of an English text with some words around it, or null (looks_danish, or a colour or 'Tlf.'). */
function danishInEnglish(x) {
  const t = visibleText(x);
  const match =
    DANISH_WORDS.exec(t.toLowerCase()) ??
    DANISH_LETTER_WORD.exec(t) ??
    COLOUR_OR_PHONE.exec(t) ??
    DANISH_DEFINITE.exec(t);
  if (!match) return null;
  const from = Math.max(0, match.index - 60);
  return `"${match[0].trim()}" in ...${t.slice(from, match.index + match[0].length + 60)}...`;
}

/** The snapshot made English; `stats` counts what changed. */
function rewrite(node, stats) {
  if (Array.isArray(node)) {
    const kept = node.filter(
      (item) => !(item && typeof item === 'object' && !Array.isArray(item) && item.lang === 'da'),
    );
    stats.danish += node.length - kept.length;
    return kept.map((item) => rewrite(item, stats));
  }
  if (node && typeof node === 'object') {
    const out = {};
    const source = Object.keys(node).some((key) => NAME_KEY.test(key))
      ? englishNames(node, stats)
      : node;
    for (const [key, value] of Object.entries(source)) {
      if (/:da$/.test(key)) {
        stats.danish++;
        continue;
      }
      if (typeof value === 'string' && node.lang === 'en' && key !== 'lang') {
        const changed = english(value);
        if (changed !== value) stats.english++;
        out[key] = changed;
      } else if (key === 'text' && typeof value === 'string' && node.type?.typeId === 'navtex') {
        const changed = englishNavtex(value);
        if (changed !== value) stats.english++;
        out[key] = changed;
      } else if (
        key === 'text' &&
        typeof value === 'string' &&
        node.type?.typeId === 'audio' &&
        looksDanish(value)
      ) {
        stats.audio++;
        out[key] = '';
      } else {
        out[key] = rewrite(value, stats);
      }
    }
    return out;
  }
  return node;
}

const dir = mkdtempSync(join(tmpdir(), 'guides-history-'));
const outFile = join(dir, 'history.tsv');
const out = createWriteStream(outFile, 'utf8');
const totals = {
  read: 0,
  changed: 0,
  danish: 0,
  english: 0,
  audio: 0,
  stillDanish: 0,
  mail: 0,
  mailSnapshots: 0,
  shape: 0,
  shapeSnapshots: 0,
  deny: 0,
  denySnapshots: 0,
};
// The second self-check's findings: history rows (ids) whose snapshot still holds a person's address.
const mailLeft = [];
const stillDanishSamples = [];
// The self-check's findings: a live message's snapshot (not in guides.names_only) with a Danish English text.
const liveDanish = new Map();

const copy = spawn(
  'docker',
  [
    'exec',
    CONTAINER,
    'psql',
    '-X',
    '-v',
    'ON_ERROR_STOP=1',
    '-U',
    USER,
    '-d',
    DB,
    ...(PROOF_SQL ? ['-q', '-c', 'BEGIN', '-c', PROOF_SQL] : []),
    '-c',
    `COPY (SELECT h.id, h.message_id, replace(encode(h.snapshot, 'base64'), E'\\n', ''), ` +
      `coalesce(m.status IN ('DRAFT', 'VERIFIED', 'PUBLISHED'), false) ` +
      `AND NOT EXISTS (SELECT 1 FROM guides.names_only n WHERE n.message_id = h.message_id) ` +
      `FROM "MessageHistory" h LEFT JOIN "Message" m ON m.id = h.message_id ` +
      `WHERE h.snapshot IS NOT NULL ORDER BY h.id) TO STDOUT`,
    ...(PROOF_SQL ? ['-c', 'ROLLBACK'] : []),
  ],
  { windowsHide: true },
);
let stderr = '';
copy.stderr.on('data', (chunk) => (stderr += chunk));
const closed = new Promise((resolve) => copy.on('close', resolve));
for await (const line of createInterface({ input: copy.stdout, crlfDelay: Infinity })) {
  if (!line) continue;
  const [id, messageId, b64, checked] = line.split('\t');
  if (!b64) continue;
  totals.read++;
  let json = gunzipSync(Buffer.from(b64, 'base64')).toString('utf8');
  const stats = { danish: 0, english: 0, audio: 0, mail: 0, shape: 0, deny: 0 };
  // A person's address: the invented one (an address holds no character JSON escapes)
  if (json.includes('@')) {
    const lower = json.toLowerCase();
    for (const person of personList) {
      if (!lower.includes(person.addr)) continue;
      json = json.replace(person.rule, () => {
        if (person.src === 'shape') stats.shape++;
        else if (person.src === 'deny') stats.deny++;
        else stats.mail++;
        return person.invented;
      });
    }
    // An address of the person's form that is no listed one (scrub.sql never saw it)
    json = json.replace(SHAPE, (match) => {
      const invented = inventedForShape(match);
      if (invented !== match) stats.shape++;
      return invented;
    });
  }
  // Default-deny: any other address that is neither on the functional list nor made of role words only, in the
  // snapshot's strings as decoded (in the JSON text, an escape such as \n would stand glued before the address)
  const parsed = json.includes('@') ? denyStrings(JSON.parse(json), stats) : JSON.parse(json);
  const snapshot = rewrite(parsed, stats);
  // What an English entry still holds that looks Danish, for the report (not rewritten: no rule exists for it).
  const englishTexts = [];
  const collect = (node) => {
    if (Array.isArray(node)) node.forEach(collect);
    else if (node && typeof node === 'object') {
      for (const [key, value] of Object.entries(node)) {
        if (
          typeof value === 'string' &&
          ((node.lang === 'en' && key !== 'lang') || /^name(?::\d+)?:en$/.test(key))
        )
          englishTexts.push(value);
        else collect(value);
      }
    }
  };
  collect(snapshot);
  const danishText = englishTexts.find(looksDanish);
  if (danishText) {
    totals.stillDanish++;
    if (stillDanishSamples.length < 15)
      stillDanishSamples.push(`${messageId}: ${clean(danishText).slice(0, 120)}`);
  }
  // The self-check, on the snapshot as written back.
  if (checked === 't') {
    for (const found of englishTexts.map(danishInEnglish).filter(Boolean)) {
      const texts = liveDanish.get(messageId) ?? new Set();
      texts.add(found);
      liveDanish.set(messageId, texts);
    }
  }
  const changed =
    stats.danish + stats.english + stats.audio + stats.mail + stats.shape + stats.deny > 0;
  const written = changed ? JSON.stringify(snapshot) : json;
  // The second self-check, on the snapshot as written back: a person's address, an address of the person's form
  // outside example.org, or (2026-11-14, default-deny) any address that is neither on the functional list nor made of
  // role words only, also with the at-sign as an entity or encoded.
  if (/@|&#0*64;|&#x0*40;|&commat;|%40|\\u0040/i.test(written)) {
    const lower = written.toLowerCase();
    if (
      personList.some((person) => lower.includes(person.addr) && person.check.test(lower)) ||
      holdsShape(written) ||
      strings(JSON.parse(written)).some(holdsDenied)
    )
      mailLeft.push(id);
  }
  if (!changed) continue;
  totals.changed++;
  totals.danish += stats.danish;
  totals.english += stats.english;
  totals.audio += stats.audio;
  totals.mail += stats.mail;
  totals.shape += stats.shape;
  totals.deny += stats.deny;
  if (stats.mail > 0) totals.mailSnapshots++;
  if (stats.shape > 0) totals.shapeSnapshots++;
  if (stats.deny > 0) totals.denySnapshots++;
  if (PROOF_SQL) continue;
  const compressed = gzipSync(Buffer.from(written, 'utf8')).toString('base64');
  if (!out.write(`${id}\t${compressed}\n`))
    await new Promise((resolve) => out.once('drain', resolve));
}
const code = await closed;
await new Promise((resolve) => out.end(resolve));
if (code !== 0) throw new Error(`Reading the snapshots failed (${code}): ${stderr}`);

if (PROOF_SQL) console.log('english-history: proof run: nothing written back');
if (totals.changed > 0 && !PROOF_SQL) {
  execFileSync('docker', ['cp', outFile, `${CONTAINER}:/tmp/guides-history.tsv`], {
    windowsHide: true,
  });
  const result = execFileSync(
    'docker',
    [
      'exec',
      CONTAINER,
      'psql',
      '-X',
      '-At',
      '-v',
      'ON_ERROR_STOP=1',
      '--single-transaction',
      '-U',
      USER,
      '-d',
      DB,
      '-c',
      'CREATE TEMP TABLE guides_history (id int PRIMARY KEY, b64 text NOT NULL)',
      '-c',
      `\\copy guides_history FROM '/tmp/guides-history.tsv'`,
      '-c',
      `UPDATE "MessageHistory" h SET snapshot = decode(g.b64, 'base64') FROM guides_history g WHERE h.id = g.id`,
    ],
    { encoding: 'utf8', windowsHide: true },
  );
  execFileSync('docker', ['exec', CONTAINER, 'rm', '-f', '/tmp/guides-history.tsv'], {
    windowsHide: true,
  });
  process.stdout.write(result);
}
rmSync(dir, { recursive: true, force: true });

console.log(
  `english-history: ${totals.read} snapshots read, ${totals.changed} rewritten: ${totals.danish} Danish entries ` +
    `dropped, ${totals.english} English texts corrected, ${totals.audio} Danish audio texts blanked`,
);
console.log(
  `english-history: ${totals.mail} persons' addresses replaced by the invented ones in ${totals.mailSnapshots} ` +
    `snapshots, ${totals.shape} addresses of a person's form in ${totals.shapeSnapshots} snapshots, ` +
    `${totals.deny} other addresses default-deny does not keep in ${totals.denySnapshots} snapshots ` +
    `(${persons.size} addresses read from ${SOURCE_DB}, ${shapeKeys.length} of them of the form, ` +
    `${denyKeys.length} others; ${shapeExtra.size} more of the form and ${denyExtra.size} other addresses in the ` +
    `snapshots only); snapshots still holding one: ${mailLeft.length}`,
);
if (mailLeft.length > 0) {
  console.error(
    `english-history: the self-check failed: history rows ${mailLeft.slice(0, 50).join(', ')} still hold a ` +
      `person's address of ${SOURCE_DB}, an address of a person's form or an address default-deny does not keep: ` +
      'make scrub.sql\'s rule "A person\'s address in any text" find it, and run make-guides-db.sh again.',
  );
  process.exitCode = 1;
}
console.log(
  `english-history: ${totals.stillDanish} snapshots still hold an English text that looks Danish (no rule for it)`,
);
for (const sample of stillDanishSamples) console.log(`  ${sample}`);

console.log(
  `english-history: live messages (draft, verified, published; not in guides.names_only) whose history still holds ` +
    `a Danish English text: ${liveDanish.size}`,
);
if (liveDanish.size > 0) {
  for (const [messageId, texts] of liveDanish) {
    for (const text of texts) console.error(`  message ${messageId}: ${text}`);
  }
  console.error(
    'english-history: the self-check failed: put these texts right by a rule in english.sql ' +
      '(guides.english_phrase, guides.english_feature_rule), apply it, and run this script again.',
  );
  process.exitCode = 1;
}
