/**
 * The secret-pattern scan (fe-country-split-plan §4.11 rule 4): every seam, branding, legal and
 * text-override file a country writes ships to anonymous browsers, so a secret never goes in one.
 * Set-up scans the country's fe/ inputs and the build check scans both applications' dist with
 * these patterns; a finding names the file, the line and the pattern, never the value — at most
 * its first four characters and its length.
 *
 * The patterns err towards few false positives: token formats with their issuer's prefix, a PEM
 * private key, a JWT, credentials written into a URL, and a name ending in a secret-like word
 * (password, secret, token, api key, client secret, private key, access key) assigned a quoted
 * value of 16 or more characters with no whitespace and a Shannon entropy of at least 3.5 bits per
 * character. A placeholder (`${…}`, `<…>`), an empty value, one character repeated, a dotted
 * path of names, a constant's name, a hyphenated slug or a short run of hex digits never counts
 * (secretLike). What is
 * still a false positive goes into the base product's secret-scan.json with its reason, so a
 * country can never silence the scan by itself.
 */
import fs from 'node:fs';
import path from 'node:path';
import { globToRegExp } from './include.mjs';
import { COUNTRY_CODE } from './workspace.mjs';

/** Every pattern by its id. `secret` picks the part of a match that is the secret itself. */
export const PATTERNS = [
  {
    id: 'private-key',
    pattern: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY(?: BLOCK)?-----/g,
  },
  { id: 'aws-access-key-id', pattern: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g },
  { id: 'github-token', pattern: /\bgh[pousr]_[A-Za-z0-9]{36,}\b/g },
  { id: 'github-fine-grained-token', pattern: /\bgithub_pat_[A-Za-z0-9_]{22,}\b/g },
  { id: 'gitlab-token', pattern: /\bglpat-[A-Za-z0-9_-]{20,}/g },
  { id: 'slack-token', pattern: /\bxox[abprs]-[A-Za-z0-9-]{10,}/g },
  { id: 'google-api-key', pattern: /\bAIza[0-9A-Za-z_-]{35}/g },
  { id: 'stripe-live-key', pattern: /\b[rs]k_live_[0-9A-Za-z]{24,}\b/g },
  { id: 'npm-token', pattern: /\bnpm_[A-Za-z0-9]{36}\b/g },
  { id: 'anthropic-key', pattern: /\bsk-ant-[A-Za-z0-9_-]{20,}/g },
  { id: 'openai-project-key', pattern: /\bsk-proj-[A-Za-z0-9_-]{20,}/g },
  { id: 'jwt', pattern: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/g },
  // The scheme is bounded, and the name below starts at its keyword, so neither pattern backtracks
  // over a long run of letters (a data: URI, a minified bundle) from every position in it.
  {
    id: 'url-credentials',
    pattern: /\b[a-z][a-z0-9+.-]{0,30}:\/\/([^/\s:@'"`]{1,256}):([^/\s@'"`]{1,256})@/gi,
    secret: (match) => (placeholder(match[1]) || placeholder(match[2]) ? undefined : match[2]),
  },
  {
    // A name that ends in a secret-like word (`apiKey`, `CLIENT_SECRET`, `accessTokenSecret`,
    // `password2`; not `tokenData` or `passwordResetUrl`), as a key, quoted or not, or an
    // identifier, then `:` or `=` and a quoted value; `==` and `!=` are comparisons and never match.
    id: 'secret-assignment',
    pattern:
      /(?:pass(?:word|wd)?|pwd|secret|token|api[_-]?key|client[_-]?secret|private[_-]?key|access[_-]?key)(?:[_-]?key)?s?\d*["'`]?\s*[:=]\s*(["'`])((?:(?!\1)[^\\\r\n]|\\.){0,4096})\1/gi,
    secret: (match) => (secretLike(match[2]) ? match[2] : undefined),
  },
];
export const PATTERN_IDS = PATTERNS.map(({ id }) => id);

/** Files never read: images, fonts and documents, which hold no text a secret could be typed into. */
const BINARY = /\.(?:png|ico|webp|jpe?g|gif|woff2?|pdf)$/i;
const ENTRY_FIELDS = ['country', 'files', 'pattern', 'reason', 'scope'];
export const SCOPES = ['inputs', 'dist'];
export const MIN_LENGTH = 16;
export const MIN_ENTROPY = 3.5;

/** A placeholder a template fills in, not a value: `${…}`, `<…>`, `{{…}}`. */
const placeholder = (value) =>
  /\$\{[^}]*\}/.test(value) || /^<[^>]*>$/.test(value) || /^\{\{.*\}\}$/.test(value);

/** Shannon entropy in bits per character. */
export function entropy(value) {
  const counts = new Map();
  for (const c of value) counts.set(c, (counts.get(c) ?? 0) + 1);
  const length = [...value].length;
  let bits = 0;
  for (const n of counts.values()) bits -= (n / length) * Math.log2(n / length);
  return bits;
}

/**
 * Whether a value assigned to a secret-like name looks like a secret rather than a word, a
 * placeholder or an identifier. Four shapes are identifiers, not secrets: a dotted path of names
 * (`admin.publications.error.unknownToken`, a translation key), a constant's name in capitals
 * and underscores (`PROMULGATION_RENDERERS`, an injection token), lower-case words joined by
 * hyphens (`access-token-xyz`, a spec's stand-in), and hex digits shorter than 32 (a text's
 * fingerprint in a pack's status files; a hex secret has 128 bits or more).
 */
export function secretLike(value) {
  if (value.length < MIN_LENGTH || /\s/.test(value) || placeholder(value)) return false;
  if (new Set(value).size === 1) return false;
  if (/^[A-Za-z_$][\w$-]*(?:\.[\w$-]+)+$/.test(value)) return false;
  if (/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/.test(value)) return false;
  if (/^[a-z][a-z0-9]*(?:-[a-z0-9]+)+$/.test(value)) return false;
  if (/^[0-9a-f]+$/i.test(value) && value.length < 32) return false;
  return entropy(value) >= MIN_ENTROPY;
}

/**
 * The allow-list's entries compiled, and every problem with them. An entry is `{ pattern, files,
 * scope, reason }` with an optional `country`: `pattern` one of PATTERN_IDS, `files` a glob over
 * the scanned paths, `scope` the scan it is for (`inputs`, set-up's scan of a country's fe/;
 * `dist`, the build check's of both applications' output), `country` the one country it is for.
 * An entry without a reason, with an unknown id, field or scope, or with a glob that does not
 * compile is an error.
 */
export function compileAllow(list, label = 'secret-scan.json') {
  const errors = [];
  const entries = [];
  if (!Array.isArray(list?.allow)) return { entries, errors: [`${label}: allow is not a list`] };
  list.allow.forEach((entry, i) => {
    const where = `${label}: allow[${i}]`;
    const before = errors.length;
    const unknown = Object.keys(entry ?? {}).filter((key) => !ENTRY_FIELDS.includes(key));
    if (unknown.length) errors.push(`${where} has the unknown field(s) ${unknown.join(', ')}`);
    if (typeof entry?.reason !== 'string' || !entry.reason.trim())
      errors.push(`${where} has no reason`);
    if (!PATTERN_IDS.includes(entry?.pattern))
      errors.push(
        `${where}: ${JSON.stringify(entry?.pattern)} is not a pattern id (${PATTERN_IDS.join(', ')})`,
      );
    if (!SCOPES.includes(entry?.scope))
      errors.push(`${where}: scope is ${SCOPES.join(' or ')}, not ${JSON.stringify(entry?.scope)}`);
    if (entry?.country !== undefined && !COUNTRY_CODE.test(entry.country))
      errors.push(
        `${where}: ${JSON.stringify(entry.country)} is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z`,
      );
    let files;
    try {
      if (typeof entry?.files !== 'string') throw new Error('files is not a glob');
      files = globToRegExp(entry.files);
    } catch (error) {
      errors.push(`${where}: ${error.message}`);
    }
    if (errors.length === before) entries.push({ ...entry, index: i, glob: files });
  });
  return { entries, errors };
}

/** Reads secret-scan.json, held to its format; a missing or unreadable file is an error. */
export function readAllow(file) {
  try {
    const list = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (list?.format !== 1)
      return { allow: { allow: [] }, errors: [`${path.basename(file)} is not of format 1`] };
    return { allow: list, errors: [] };
  } catch (error) {
    return { allow: { allow: [] }, errors: [`${path.basename(file)}: ${error.message}`] };
  }
}

/** The line number of an offset in `text`, found by halving over its line starts. */
function lineIndex(text) {
  const starts = [0];
  for (let i = text.indexOf('\n'); i !== -1; i = text.indexOf('\n', i + 1)) starts.push(i + 1);
  return (offset) => {
    let low = 0;
    let high = starts.length - 1;
    while (low < high) {
      const middle = (low + high + 1) >> 1;
      if (starts[middle] <= offset) low = middle;
      else high = middle - 1;
    }
    return low + 1;
  };
}

/** What a finding shows of a secret: its first four characters and its length, never more. */
const hint = (secret) => `${secret.slice(0, 4)}…, ${secret.length} characters`;

/** The findings in one text, `{ line, id, hint }` each, in the order of the text. */
export function scanText(text) {
  const lineAt = lineIndex(text);
  const found = [];
  for (const { id, pattern, secret } of PATTERNS) {
    for (const match of text.matchAll(pattern)) {
      const value = secret ? secret(match) : match[0];
      if (value === undefined) continue;
      found.push({ index: match.index, line: lineAt(match.index), id, hint: hint(value) });
    }
  }
  return found.sort((a, b) => a.index - b.index).map(({ index: _index, ...rest }) => rest);
}

/**
 * Scans `files` (paths relative to `root`, with `/`) for secrets. Binary files by their
 * extension and any file with a NUL byte are skipped. `allow` is secret-scan.json's content;
 * the entries of `scope` (and of `country`, when an entry names one) apply, and an entry of that
 * scope whose glob matches none of the scanned files is an error, as is an entry the allow-list
 * itself gets wrong. Returns `{ findings, errors, scanned }`: one line per finding,
 * `<file>:<line>: <pattern id> (<first 4>…, <n> characters)`, and the allow-list's problems.
 */
export function scanFiles({ root, files, allow = { allow: [] }, scope = 'inputs', country }) {
  const { entries, errors } = compileAllow(allow);
  const applied = entries.filter(
    (entry) => entry.scope === scope && (entry.country === undefined || entry.country === country),
  );
  const used = new Set();
  const findings = [];
  let scanned = 0;
  for (const file of [...files].sort()) {
    for (const entry of applied) if (entry.glob.test(file)) used.add(entry.index);
    if (BINARY.test(file)) continue;
    const bytes = fs.readFileSync(path.join(root, file));
    if (bytes.includes(0)) continue;
    scanned++;
    for (const { line, id, hint: shown } of scanText(bytes.toString('utf8'))) {
      if (applied.some((entry) => entry.pattern === id && entry.glob.test(file))) continue;
      findings.push(`${file}:${line}: ${id} (${shown})`);
    }
  }
  for (const entry of applied)
    if (!used.has(entry.index))
      errors.push(
        `secret-scan.json: allow[${entry.index}]'s files ${entry.files} match no file of the ${scope} scan`,
      );
  return { findings, errors, scanned };
}

/** Every regular file under `dir`, relative to `root` and with `/`; links and other entries are left out. */
export function filesUnder(root, dir) {
  const folder = path.join(root, dir);
  if (!fs.existsSync(folder)) return [];
  return fs
    .readdirSync(folder, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(root, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}
