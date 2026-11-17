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
// and written back, gzip-compressed, when it changed. Reads and writes niord_guides only, through psql in the database
// container (GUIDES_DB_CONTAINER, by default niord-db), and prints what it changed.
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

const CONTAINER = process.env.GUIDES_DB_CONTAINER || 'niord-db';
const DB = 'niord_guides';
const USER = process.env.GUIDES_DB_USER || 'niord';

function psql(...commands) {
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
      DB,
      ...commands.flatMap((c) => ['-c', c]),
    ],
    { encoding: 'utf8', maxBuffer: 1 << 28, windowsHide: true },
  );
}

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
const totals = { read: 0, changed: 0, danish: 0, english: 0, audio: 0, stillDanish: 0 };
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
    '-c',
    `COPY (SELECT h.id, h.message_id, replace(encode(h.snapshot, 'base64'), E'\\n', ''), ` +
      `coalesce(m.status IN ('DRAFT', 'VERIFIED', 'PUBLISHED'), false) ` +
      `AND NOT EXISTS (SELECT 1 FROM guides.names_only n WHERE n.message_id = h.message_id) ` +
      `FROM "MessageHistory" h LEFT JOIN "Message" m ON m.id = h.message_id ` +
      `WHERE h.snapshot IS NOT NULL ORDER BY h.id) TO STDOUT`,
  ],
  { windowsHide: true },
);
let stderr = '';
copy.stderr.on('data', (chunk) => (stderr += chunk));
const closed = new Promise((resolve) => copy.on('close', resolve));
for await (const line of createInterface({ input: copy.stdout, crlfDelay: Infinity })) {
  if (!line) continue;
  const [id, messageId, b64, checked] = line.split('\t');
  totals.read++;
  const json = gunzipSync(Buffer.from(b64, 'base64')).toString('utf8');
  const stats = { danish: 0, english: 0, audio: 0 };
  const snapshot = rewrite(JSON.parse(json), stats);
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
  if (stats.danish + stats.english + stats.audio === 0) continue;
  totals.changed++;
  totals.danish += stats.danish;
  totals.english += stats.english;
  totals.audio += stats.audio;
  const compressed = gzipSync(Buffer.from(JSON.stringify(snapshot), 'utf8')).toString('base64');
  if (!out.write(`${id}\t${compressed}\n`))
    await new Promise((resolve) => out.once('drain', resolve));
}
const code = await closed;
await new Promise((resolve) => out.end(resolve));
if (code !== 0) throw new Error(`Reading the snapshots failed (${code}): ${stderr}`);

if (totals.changed > 0) {
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
