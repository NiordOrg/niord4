// repo-files.mjs: keeps a person's address out of the repository files of the guides home and of the copy.
//
//   node repo-files.mjs leave-out <home>/repo   replaces with a placeholder (a PDF) or deletes (any other kind) every
//                                               file of the guides home that holds an address default-deny does not
//                                               keep (make-guides-home.sh, after the copy; the source is never touched)
//   node repo-files.mjs check <home>/repo       exits 1 when a file of the guides home still holds one
//                                               (make-guides-home.sh, after leave-out)
//   node repo-files.mjs check-db                exits 1 when a RepoFile row of niord_guides still holds one, or when
//                                               the table is empty (seed-examples.mjs runs it first and stops on it)
//
// An address default-deny does not keep (the decision of 2026-11-14 under the owner's "remove GDPR
// information"): every address outside example.org that is neither on scrub.sql's functional list nor made of
// scrub-lists.mjs's role words only (ROLE_WORDS: info, post, havn, ...). That covers scrub.sql's persons (the users'
// addresses, the comment recipients', the private mailbox) and the addresses of a person's form (guides.scrub_shape),
// which are also looked for by name (the persons from niord_test, only read, into memory, never written or printed),
// and every outside person whose mailbox is a first name or initials, which no form tells from a role.
//
// A file is read as its bytes and as what it holds inflated: the streams of a PDF (an image stream is skipped), the
// parts of a zip file (docx, xlsx, odt, zip; nested ones too) and gzip; each text also with PDF string escapes and hex
// strings decoded, URL-encoding and the HTML and XML entities for the at-sign and the dot decoded, NUL bytes dropped
// (UTF-16), markup taken out and the pieces of one TJ array joined. A PDF's content streams are also read the way a
// reader sees the text: every string piece of the stream (shown by Tj, TJ, ' and ") joined in order, once with
// nothing and once with a space between the pieces, so an address drawn as separate pieces is found. When pdftotext
// (poppler) is on the PATH or in Git for Windows' folder, its text of every PDF is read too; the run does not depend
// on it (GUIDES_PDFTOTEXT names the program; "none" turns it off). Text that a PDF draws through a font's own glyph
// codes is read only through pdftotext, and an image's pixels are not read. A stream named ASCII85Decode or
// ASCIIHexDecode is decoded first. Noise is kept out of default-deny's rule (the persons' addresses and the form are
// looked for everywhere, as before): a PDF's own bytes count with their streams' data taken out (encoded data such
// as ASCII85 holds '@'), a font program is not read for it, and in bytes that are not text (an image, a compressed
// stream that does not inflate) an address counts only when it stands between printable ASCII characters and its
// local part and domain names have two characters or more. A label the joined pieces glue to the front of an
// address ("E-mail" "info@...") does not count as part of it, an address cut at a piece's edge is excused when the
// unspaced join holds a kept address ending with it, and one with a piece glued before it when the spaced join holds
// a kept address it ends with.
//
// The placeholder (leave-out): a PDF keeps its name and becomes a valid one-page PDF this script writes, with the
// line "Example document. The original is left out of the guides' copy."; a file of any other kind is deleted.
// Prints counts only: files read, files with a hit per top folder, per kind and per type, never a path or an address.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, extname, join, relative, resolve, sep } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { constants, gunzipSync, inflateRawSync, inflateSync } from 'node:zlib';
import {
  ADDRESS_PATTERN,
  keptAddress,
  readPersons,
  scrubLists,
  shapePattern,
} from './scrub-lists.mjs';

const CONTAINER = process.env.GUIDES_DB_CONTAINER || 'niord-db';
const SOURCE_DB = process.env.GUIDES_SOURCE_DB || 'niord_test';
const GUIDES_DB = 'niord_guides';
const SYNC = { finishFlush: constants.Z_SYNC_FLUSH };
const PDFTOTEXT_PARALLEL = 6;

const escapeRegex = (s) => s.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&');

/** The matchers: each person's address (its check pattern), the person's form, any address, and what is kept. */
export function loadMatchers() {
  const seen = new Set();
  const lists = scrubLists();
  // one entry per address (a user's, a comment recipient's and the private mailbox's can be the same address); the
  // other addresses default-deny replaces in the database need no entry: the rule below finds every one
  const persons = readPersons({ container: CONTAINER, db: SOURCE_DB })
    .filter(([, src]) => src !== 'deny')
    .sort((a, b) => a[3] - b[3])
    .filter(([addr]) => !seen.has(addr) && seen.add(addr))
    .map(([addr, src]) => ({
      addr,
      src,
      check: new RegExp(`(?<![a-z0-9_%+-])${escapeRegex(addr)}(?![a-z0-9-])`),
    }));
  if (persons.filter((p) => p.src !== 'shape').length === 0)
    throw new Error(`repo-files: no person's address was read from ${SOURCE_DB}.`);
  return {
    persons,
    shape: new RegExp(shapePattern(lists.shape), 'g'),
    address: new RegExp(ADDRESS_PATTERN, 'g'),
    kept: (address) => keptAddress(address, lists.functional),
  };
}

// The parts of a zip file, by its central directory (sizes are right there even when a part has a data descriptor)
function zipParts(buf) {
  const parts = [];
  const eocd = buf.lastIndexOf(Buffer.from('PK\x05\x06', 'latin1'));
  if (eocd < 0 || eocd + 22 > buf.length) return parts;
  let p = buf.readUInt32LE(eocd + 16);
  const count = buf.readUInt16LE(eocd + 10);
  for (let i = 0; i < count && p + 46 <= buf.length && buf.readUInt32LE(p) === 0x02014b50; i++) {
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const local = buf.readUInt32LE(p + 42);
    const next =
      p + 46 + buf.readUInt16LE(p + 28) + buf.readUInt16LE(p + 30) + buf.readUInt16LE(p + 32);
    if (local + 30 <= buf.length && buf.readUInt32LE(local) === 0x04034b50) {
      const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
      const data = buf.subarray(start, start + csize);
      try {
        if (method === 0) parts.push(data);
        else if (method === 8) parts.push(inflateRawSync(data, SYNC));
      } catch {
        // a part that does not inflate is read as its bytes only
      }
    }
    p = next;
  }
  return parts;
}

// ASCII85 (the PDF filter ASCII85Decode): the bytes, or null when the data is not ASCII85
function ascii85(data) {
  const s = data.toString('latin1').replace(/\s+/g, '');
  const end = s.indexOf('~>');
  const body = (end >= 0 ? s.slice(0, end) : s).replace(/^<~/, '');
  if (!/^[!-uz]*$/.test(body)) return null;
  const out = [];
  let group = [];
  for (const ch of body) {
    if (ch === 'z' && group.length === 0) {
      out.push(0, 0, 0, 0);
      continue;
    }
    group.push(ch.charCodeAt(0) - 33);
    if (group.length === 5) {
      let n = 0;
      for (const d of group) n = n * 85 + d;
      out.push((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);
      group = [];
    }
  }
  if (group.length > 1) {
    const size = group.length;
    while (group.length < 5) group.push(84);
    let n = 0;
    for (const d of group) n = n * 85 + d;
    out.push(...[(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].slice(0, size - 1));
  }
  return Buffer.from(out);
}

// ASCIIHex (the PDF filter ASCIIHexDecode): the bytes, or null
function asciiHex(data) {
  const s = data.toString('latin1').replace(/\s+/g, '').replace(/>.*$/, '');
  if (!/^[0-9A-Fa-f]*$/.test(s)) return null;
  return Buffer.from(s.length % 2 ? `${s}0` : s, 'hex');
}

// The streams of a PDF: decoded from ASCII85 or ASCIIHex when the dictionary names it, then inflated (zlib, or raw
// deflate), or as they stand when they have no filter (an image stream skipped: pixels, no text)
function pdfStreams(buf, s) {
  const out = [];
  let i = 0;
  while ((i = s.indexOf('stream', i)) >= 0) {
    if (s.startsWith('endstream', i - 3)) {
      i += 6;
      continue;
    }
    let start = i + 6;
    if (s[start] === '\r') start++;
    if (s[start] === '\n') start++;
    const end = s.indexOf('endstream', start);
    if (end < 0) break;
    // the stream's dictionary: from the object's start (or the end of the one before) to the keyword
    const dict = s.slice(Math.max(s.lastIndexOf('obj', i), i - 4000, 0), i);
    if (!/\/Subtype\s*\/Image/.test(dict)) {
      let data = buf.subarray(start, end);
      if (/\/ASCII85Decode|\/A85\b/.test(dict)) data = ascii85(data) ?? data;
      else if (/\/ASCIIHexDecode|\/AHx\b/.test(dict)) data = asciiHex(data) ?? data;
      // raw deflate only where the dictionary says Flate: raw inflate of other bytes can give noise
      const inflates = /\/FlateDecode|\/Fl\b/.test(dict)
        ? [inflateSync, inflateRawSync]
        : [inflateSync];
      let inflated = null;
      for (const inflate of inflates) {
        try {
          inflated = inflate(data, SYNC);
          break;
        } catch {
          // not this kind of deflate stream
        }
      }
      // a font program: read for the persons' addresses and the form only (its glyph data gives noise)
      const font = /\/Length[123]\b|\/Subtype\s*\/(?:Type1C|CIDFontType0C|OpenType)\b/.test(dict);
      if (inflated) out.push({ data: inflated, font });
      else if (
        !/\/Filter/.test(dict) ||
        !/\/(?:Flate|Fl\b|LZW|RunLength|DCT|JPX|JBIG2|CCITTFax)/.test(dict)
      )
        out.push({ data, font });
    }
    i = end + 9;
  }
  return out;
}

/**
 * The string pieces of a PDF content stream, in order: every literal string (escapes decoded) and hex string, which
 * is what Tj, TJ, ' and " show. Joined, they are the text as a reader sees it, an address drawn piece by piece too.
 */
export function pdfPieces(s) {
  const pieces = [];
  const n = s.length;
  const named = { n: '\n', r: '\r', t: '\t', b: '\b', f: '\f' };
  let i = 0;
  while (i < n) {
    const c = s[i];
    if (c === '%') {
      // a comment, to the end of the line
      while (i < n && s[i] !== '\n' && s[i] !== '\r') i++;
    } else if (c === '(') {
      let depth = 1;
      let piece = '';
      i++;
      while (i < n && depth > 0) {
        const ch = s[i];
        if (ch === '\\') {
          const next = s[i + 1];
          if (next >= '0' && next <= '7') {
            let j = i + 1;
            let octal = '';
            while (j < n && octal.length < 3 && s[j] >= '0' && s[j] <= '7') octal += s[j++];
            piece += String.fromCharCode(parseInt(octal, 8) & 0xff);
            i = j;
          } else if (next === '\r' || next === '\n') {
            i += next === '\r' && s[i + 2] === '\n' ? 3 : 2;
          } else {
            piece += named[next] ?? next ?? '';
            i += 2;
          }
          continue;
        }
        if (ch === '(') depth++;
        else if (ch === ')' && --depth === 0) {
          i++;
          break;
        }
        piece += ch;
        i++;
      }
      pieces.push(piece);
    } else if (c === '<' && s[i + 1] === '<') {
      i += 2;
    } else if (c === '<') {
      const end = s.indexOf('>', i);
      if (end < 0) break;
      const hex = s.slice(i + 1, end).replace(/\s+/g, '');
      if (/^[0-9A-Fa-f]*$/.test(hex))
        pieces.push(Buffer.from(hex.length % 2 ? `${hex}0` : hex, 'hex').toString('latin1'));
      i = end + 1;
    } else {
      i++;
    }
  }
  return pieces;
}

// A text is printable when control characters (but whitespace) and NUL are rare in it (UTF-16 text has a NUL in
// every other byte: it counts as text)
function isText(s) {
  const sample = s.length > 65536 ? s.slice(0, 65536) : s;
  if (sample.length === 0) return true;
  let control = 0;
  let nul = 0;
  for (let i = 0; i < sample.length; i++) {
    const code = sample.charCodeAt(i);
    if (code === 0) nul++;
    else if ((code < 9 || (code > 13 && code < 32) || code === 127) && code !== 27) control++;
  }
  if (nul > sample.length * 0.3 && control < sample.length * 0.02) return true;
  return control + nul < sample.length * 0.02;
}

/** Every text a file holds (its bytes, and what it holds inflated), as latin1 strings, each marked text or not. */
function texts(buf, depth = 0, out = [], stream = null) {
  const s = buf.toString('latin1');
  if (s.startsWith('%PDF-') || (depth === 0 && s.slice(0, 1024).includes('%PDF-'))) {
    // A PDF's own bytes: its streams are read decoded below; for any other address than the persons' and the form,
    // its objects are read with the streams' data taken out (the encoded data would give noise: ASCII85 holds '@')
    out.push({ s, text: false, persons: true });
    const objects = s.replace(/stream\r?\n[\s\S]*?endstream/g, 'stream endstream');
    out.push({ s: objects, text: isText(objects), other: true });
  } else {
    out.push({ s, text: isText(s), persons: Boolean(stream?.font) });
  }
  if (stream && !stream.font && /T[Jj*'"]/.test(s) && s.includes('BT')) {
    const pieces = pdfPieces(s);
    if (pieces.length > 1) {
      // with nothing between the pieces, and with a space (scan() excuses what a join cuts or glues at an edge)
      out.push({ s: pieces.join(''), text: true, joined: 'unspaced' });
      out.push({ s: pieces.join(' '), text: true, joined: 'spaced' });
    }
  }
  if (depth > 3) return out;
  if (buf[0] === 0x1f && buf[1] === 0x8b) {
    try {
      texts(gunzipSync(buf, SYNC), depth + 1, out);
    } catch {
      // not gzip after all
    }
  }
  if (s.includes('PK\x03\x04')) for (const part of zipParts(buf)) texts(part, depth + 1, out);
  if (s.includes('stream'))
    for (const one of pdfStreams(buf, s)) texts(one.data, depth + 1, out, one);
  return out;
}

/** A text normalised for the search: decoded, lower case, and its variants with markup out and PDF text joined. */
function normalised(s) {
  let t = s
    .replace(/\\([0-7]{1,3})/g, (_, o) => String.fromCharCode(parseInt(o, 8) & 0xff))
    .replace(/\\([()\\])/g, '$1');
  // a PDF's hex strings, decoded (UTF-16 too, by the NUL bytes dropped below)
  const hex = [...t.matchAll(/<([0-9A-Fa-f\s]{8,})>/g)]
    .map((m) => Buffer.from(m[1].replace(/\s+/g, ''), 'hex').toString('latin1'))
    .join('\n');
  t = `${t}\n${hex}`
    .replace(/\0/g, '')
    .replace(/%([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/&#0*64;|&#x0*40;|&commat;/gi, '@')
    .replace(/&#0*46;|&#x0*2e;|&period;/gi, '.')
    .replace(/&amp;/gi, '&')
    .replace(/­/g, '')
    .toLowerCase();
  if (!t.includes('@')) return '';
  return [t, t.replace(/<[^>]*>/g, ''), t.replace(/\)\s*-?[\d.]*\s*\(/g, '')].join('\n');
}

// In bytes that are not text: an address that stands between printable ASCII characters, with a local part and a
// domain name of two characters or more (not noise of binary data)
const PRINTABLE = /[\t\n\r\x20-\x7e]/;
function standsInText(t, index, address) {
  const [local, domain] = address.split('@');
  if (local.length < 2 || domain.split('.').some((label) => label.length < 2)) return false;
  for (const at of [
    index - 3,
    index - 2,
    index - 1,
    index + address.length,
    index + address.length + 1,
  ]) {
    if (at < 0 || at >= t.length) continue;
    if (!PRINTABLE.test(t[at])) return false;
  }
  return true;
}

// A label the joined text pieces glue to the front of an address ("E-mail" "info@..." read as "e-mailinfo@...") is
// not part of its local part: the address is kept when it is kept without the label
const LABEL = /^(?:e-?mail|mailto|mail|kontakt|contact|e-?post)[-:._]*/;
function keptInFile(address, matchers) {
  if (matchers.kept(address)) return true;
  const unglued = address.replace(LABEL, '');
  return unglued !== address && !unglued.startsWith('@') && matchers.kept(unglued);
}

/**
 * Which kinds of address a file's bytes (and the texts given beside them, e.g. pdftotext's) hold: { person, shape,
 * other } (booleans; other: an address default-deny does not keep that is no person's and not of the form). With
 * details, also the readings each kind was found in.
 */
export function scan(buf, matchers, extraTexts = [], details = null) {
  const found = { person: false, shape: false, other: false };
  const all = [...texts(buf), ...extraTexts.map((s) => ({ s, text: true, extra: true }))];
  // The kept addresses of the joined pieces: an address cut at a piece's edge ("havn" "en@...": "en@...") is excused
  // when the unspaced join holds a kept address that ends with it, and one with a piece glued before it ("w"
  // "pilot@...": "wpilot@...") when the spaced join holds a kept address it ends with
  const keptOf = (s) =>
    [...normalised(s).matchAll(matchers.address)]
      .map((m) => m[1])
      .filter((a) => keptInFile(a, matchers));
  const keptUnspaced = all.filter((r) => r.joined === 'unspaced').flatMap((r) => keptOf(r.s));
  const keptSpaced = all.filter((r) => r.joined === 'spaced').flatMap((r) => keptOf(r.s));
  const excused = (a) =>
    keptUnspaced.some((k) => k !== a && k.endsWith(a)) ||
    keptSpaced.some((k) => k !== a && a.endsWith(k));
  for (const { s, text, extra, persons: personsOnly, other: otherOnly } of all) {
    const t = normalised(s);
    if (!t) continue;
    const reading = extra ? 'pdftotext' : text ? 'text' : 'binary';
    const person =
      !otherOnly &&
      matchers.persons.some((p) => p.src !== 'shape' && t.includes(p.addr) && p.check.test(t));
    const shape =
      !otherOnly &&
      (matchers.persons.some((p) => p.src === 'shape' && t.includes(p.addr) && p.check.test(t)) ||
        [...t.matchAll(matchers.shape)].some((m) => !m[1].endsWith('@example.org')));
    let other = false;
    for (const m of personsOnly ? [] : t.matchAll(matchers.address)) {
      if (keptInFile(m[1], matchers)) continue;
      if (!text && !standsInText(t, m.index, m[1])) continue;
      if (excused(m[1])) continue;
      other = true;
      if (details) (details.other ??= new Map()).set(m[1], reading);
      else break;
    }
    if (details) {
      if (person) (details.person ??= new Set()).add(reading);
      if (shape) (details.shape ??= new Set()).add(reading);
    }
    found.person ||= person;
    found.shape ||= shape;
    found.other ||= other;
    if (!details && found.person && found.shape && found.other) break;
  }
  return found;
}

/** pdftotext (poppler), or null: GUIDES_PDFTOTEXT, the PATH, Git for Windows' folders. */
let pdftotextProgram;
export function pdftotext() {
  if (pdftotextProgram !== undefined) return pdftotextProgram;
  const named = process.env.GUIDES_PDFTOTEXT;
  const local = process.env.LOCALAPPDATA ?? '';
  const candidates = named
    ? [named]
    : [
        'pdftotext',
        join(local, 'Programs', 'Git', 'ucrt64', 'bin', 'pdftotext.exe'),
        join(local, 'Programs', 'Git', 'mingw64', 'bin', 'pdftotext.exe'),
        'C:\\Program Files\\Git\\ucrt64\\bin\\pdftotext.exe',
        'C:\\Program Files\\Git\\mingw64\\bin\\pdftotext.exe',
      ];
  pdftotextProgram = null;
  for (const program of candidates) {
    if (program === 'none') break;
    if (program !== 'pdftotext' && !existsSync(program)) continue;
    const run = spawnSync(program, ['-v'], { windowsHide: true, encoding: 'utf8' });
    if (!run.error && `${run.stdout}${run.stderr}`.includes('pdftotext')) {
      pdftotextProgram = program;
      break;
    }
  }
  return pdftotextProgram;
}

/** pdftotext's text of a PDF file ('' when it fails or is not there). */
function pdfText(path) {
  const program = pdftotext();
  if (!program) return Promise.resolve('');
  return new Promise((done) => {
    const chunks = [];
    const child = spawn(program, ['-q', '-enc', 'UTF-8', path, '-'], { windowsHide: true });
    const timer = setTimeout(() => child.kill(), 120_000);
    child.stdout.on('data', (chunk) => chunks.push(chunk));
    child.on('error', () => done(''));
    child.on('close', () => {
      clearTimeout(timer);
      done(Buffer.concat(chunks).toString('utf8'));
    });
  });
}

const isPdf = (buf) => buf.subarray(0, 1024).includes('%PDF-');

/** A file's scan, with pdftotext's text of a PDF when pdftotext is there. */
async function scanFile(path, buf, matchers, details) {
  const extra = isPdf(buf) && pdftotext() ? [await pdfText(path)] : [];
  return scan(buf, matchers, extra, details);
}

function* walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(path);
    else if (entry.isFile()) yield path;
  }
}

const kindOf = (found) => (found.person ? 'person' : found.shape ? 'shape' : 'other');
const typeOf = (name) => extname(name).slice(1).toLowerCase() || 'none';

function tally(counts, top, found, type) {
  counts.byTop[top] = (counts.byTop[top] ?? 0) + 1;
  counts.byKind[kindOf(found)] = (counts.byKind[kindOf(found)] ?? 0) + 1;
  counts.byType[type] = (counts.byType[type] ?? 0) + 1;
  counts.hits++;
}

const newCounts = () => ({
  files: 0,
  bytes: 0,
  hits: 0,
  byTop: {},
  byKind: {},
  byType: {},
  placeholder: {},
  leftOut: {},
});

/** The placeholder of a PDF left out: a valid one-page PDF with one line of text and nothing else. */
export function placeholderPdf() {
  const line = "Example document. The original is left out of the guides' copy.";
  const content = `BT /F1 12 Tf 72 770 Td (${line}) Tj ET\n`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
    `<< /Length ${content.length} >>\nstream\n${content}endstream`,
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((body, i) => {
    offsets.push(pdf.length);
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) pdf += `${String(offset).padStart(10, '0')} 00000 n \n`;
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

/**
 * Scans a repository folder; with remove, puts a placeholder in place of each PDF that holds an address default-deny
 * does not keep and deletes any other such file. Counts only.
 */
export async function scanDir(repo, matchers, { remove = false, onHit, details = false } = {}) {
  const counts = newCounts();
  const placeholder = placeholderPdf();
  const inFlight = new Set();
  const one = async (path) => {
    const buf = readFileSync(path);
    counts.files++;
    counts.bytes += buf.length;
    const detail = details ? {} : null;
    const found = await scanFile(path, buf, matchers, detail);
    if (!found.person && !found.shape && !found.other) return;
    const rel = relative(repo, path).split(sep);
    const type = typeOf(path);
    tally(counts, rel[0], found, type);
    onHit?.(rel.join('/'), found, detail);
    if (!remove) return;
    if (isPdf(buf) || type === 'pdf') {
      writeFileSync(path, placeholder);
      counts.placeholder[type] = (counts.placeholder[type] ?? 0) + 1;
    } else {
      rmSync(path);
      counts.leftOut[type] = (counts.leftOut[type] ?? 0) + 1;
    }
  };
  for (const path of walk(repo)) {
    const task = one(path).finally(() => inFlight.delete(task));
    inFlight.add(task);
    if (inFlight.size >= PDFTOTEXT_PARALLEL) await Promise.race(inFlight);
  }
  await Promise.all(inFlight);
  return counts;
}

/**
 * Scans niord_guides' RepoFile rows (after the repository import). The rows are read in one transaction that is
 * rolled back; preamble is SQL run first in it (empty in a run: the check is read only).
 */
export async function scanDb(matchers, { preamble = '', onHit } = {}) {
  const counts = newCounts();
  const psql = spawn(
    'docker',
    [
      'exec',
      '-i',
      CONTAINER,
      'psql',
      '-X',
      '-q',
      '-At',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      'niord',
      '-d',
      GUIDES_DB,
    ],
    { windowsHide: true },
  );
  let stderr = '';
  psql.stderr.on('data', (chunk) => (stderr += chunk));
  psql.stdin.end(
    `BEGIN;\n${preamble || 'SET TRANSACTION READ ONLY;'}\n` +
      `COPY (SELECT path, replace(encode(data, 'base64'), E'\\n', '') FROM public."RepoFile" ORDER BY path) TO STDOUT;\n` +
      'ROLLBACK;\n',
  );
  const closed = new Promise((res) => psql.on('close', res));
  // pdftotext reads a PDF row from a file of its own in a temporary folder, deleted at the end
  const dir = pdftotext() ? mkdtempSync(join(tmpdir(), 'guides-repo-')) : null;
  let n = 0;
  try {
    for await (const line of createInterface({ input: psql.stdout, crlfDelay: Infinity })) {
      const tab = line.indexOf('\t');
      if (tab < 0) continue;
      const path = line.slice(0, tab);
      const buf = Buffer.from(line.slice(tab + 1), 'base64');
      counts.files++;
      counts.bytes += buf.length;
      let extra = [];
      if (dir && isPdf(buf)) {
        const file = join(dir, `${n++}.pdf`);
        writeFileSync(file, buf);
        extra = [await pdfText(file)];
        rmSync(file, { force: true });
      }
      const found = scan(buf, matchers, extra);
      if (!found.person && !found.shape && !found.other) continue;
      tally(counts, path.split('/')[0], found, typeOf(path));
      onHit?.(path, found);
    }
  } finally {
    if (dir) rmSync(dir, { recursive: true, force: true });
  }
  const code = await closed;
  if (code !== 0)
    throw new Error(`repo-files: reading RepoFile failed (${code}): ${stderr.slice(0, 300)}`);
  return counts;
}

const report = (what, c) =>
  `repo-files: ${what}: ${c.files} files read (${(c.bytes / 1e6).toFixed(0)} MB); with an address default-deny ` +
  `does not keep: ${c.hits}` +
  (c.hits
    ? ` (per folder ${JSON.stringify(c.byTop)}, per kind ${JSON.stringify(c.byKind)}, per type ` +
      `${JSON.stringify(c.byType)})`
    : '');

/** check-db as the command line runs it: the exit code (0: clean; 1: a hit or an empty table). */
export async function checkDb({ log = console.log, error = console.error } = {}) {
  const started = Date.now();
  const matchers = loadMatchers();
  const c = await scanDb(matchers);
  log(
    `${report(`check of ${GUIDES_DB}'s RepoFile`, c)}; ${people(matchers)}; ${((Date.now() - started) / 1000).toFixed(0)} s`,
  );
  if (c.files === 0) {
    error(`repo-files: ${GUIDES_DB}'s RepoFile is empty: run the repository import first.`);
    return 1;
  }
  if (c.hits > 0) {
    error(
      `repo-files: the check failed: ${c.hits} RepoFile rows of ${GUIDES_DB} hold an address default-deny does not ` +
        'keep: run make-guides-home.sh and the repository import again.',
    );
    return 1;
  }
  return 0;
}

const people = (matchers) =>
  `${matchers.persons.filter((p) => p.src !== 'shape').length} persons' addresses and ${
    matchers.persons.filter((p) => p.src === 'shape').length
  } of a person's form read from ${SOURCE_DB}, the form and default-deny's rule; pdftotext ${
    pdftotext() ? 'read' : 'not found'
  }`;

if (process.argv[1] && resolve(fileURLToPath(import.meta.url)) === resolve(process.argv[1])) {
  const [mode, dir] = process.argv.slice(2);
  const started = Date.now();
  const repo = dir && resolve(dir);
  if (mode === 'leave-out' || mode === 'check') {
    // the guides home's repository only, never the test data's
    if (!repo || basename(repo) !== 'repo' || basename(dirname(repo)) !== '.niord-ng-guides')
      throw new Error(
        `repo-files: ${mode} takes the guides home's repo folder (.niord-ng-guides/repo), not ${dir}.`,
      );
  }
  if (mode === 'leave-out') {
    const matchers = loadMatchers();
    const c = await scanDir(repo, matchers, { remove: true });
    console.log(
      `${report('left out of the guides home', c)}; placeholders per type ${JSON.stringify(c.placeholder)}, ` +
        `left out per type ${JSON.stringify(c.leftOut)}; ${people(matchers)}; ` +
        `${((Date.now() - started) / 1000).toFixed(0)} s`,
    );
  } else if (mode === 'check') {
    const matchers = loadMatchers();
    const c = await scanDir(repo, matchers);
    console.log(
      `${report('check of the guides home', c)}; ${people(matchers)}; ${((Date.now() - started) / 1000).toFixed(0)} s`,
    );
    if (c.hits > 0) {
      console.error(
        'repo-files: the check failed: a file of the guides home still holds an address default-deny does not keep.',
      );
      process.exitCode = 1;
    }
  } else if (mode === 'check-db') {
    process.exitCode = await checkDb();
  } else {
    console.error('usage: node repo-files.mjs leave-out|check <.niord-ng-guides/repo> | check-db');
    process.exitCode = 2;
  }
}
