/**
 * The branding rows of the pack check (fe-country-split-plan §4.6) and the rules for brandable
 * files (§4.11 rule 4), for one application's branding folder of a country, `<app>/assets/`:
 *
 *   1. A key branding.schema.json does not name, outside the country's own `country.` namespace,
 *      fails; so does a key whose `{{ }}` placeholders are not the schema's, and a value that is
 *      not of its key's kind (`text` with markup, `url` that is not https:, `file` naming no file
 *      the country ships).
 *   2. A required key missing from a UI language's branding, a required file missing, a UI
 *      language without branding once any language has some, a legal document shipped in a
 *      language whose branding lacks the document's title, a key set without the key the schema
 *      says it `requires` (the footer logo without its alternative text, its link without its
 *      accessible name), and a footer label set without its dialog's title or with its document
 *      shipped in no branding language, fail. Where the schema's legal section says `labelCheck:
 *      "warning"` (the public site, whose default footer a country may replace with its own), such
 *      a footer label warns instead, and so does a key set without one it `recommends` (the
 *      footer logo without its alternative text). A legal document shipped without its footer
 *      label warns in either application.
 *   3. Every value's markup (catalog.mjs markupOf: tags, attributes, every href and src) equals
 *      the same key's in the reference branding — the country's English, else its default
 *      language's — where an href may differ only in its path, on the same https: host; markup
 *      in a key whose reference has none fails. Any href or src is https:, mailto:, a path or a
 *      fragment, never another scheme.
 *   4. The files are data. An image is a PNG, WebP or ICO by its signature, or an SVG with no
 *      script, foreignObject, event attribute or external reference, and only of the types the
 *      schema gives its name; a legal document holds only the elements and attributes the legal
 *      dialogs render (no script, style, iframe, form or svg, no event or style attribute, no
 *      javascript: or data: URL). A file the schema does not name, a link, anything but a
 *      regular file or folder, a file over 1 MiB and a key `__proto__`, `prototype` or
 *      `constructor` fail.
 *   5. The country's branding values and legal documents awaiting acceptance by a reader of the
 *      language (R5) are listed beside the folder, in `<app>/branding-unaccepted.json`, in the base
 *      product's format: `{ "note": "…", "<lang>": ["assets/branding/<lang>.json#<KEY>" |
 *      "assets/legal/<doc>.<lang>.html", …] }`, each language a UI language of the application. An
 *      entry that is neither shape, is for another language than its list's, names a file the
 *      country does not ship or a key its branding file or the schema lacks, or is listed twice,
 *      fails; while any list holds an entry, one warning says so, since go-live requires every
 *      list empty. The list is never staged: staging copies only the files the schema names.
 *   6. What the base product's specs once held on the copies of the countries' files it carried
 *      (fe-country-split-plan D23). A brandable PNG whose schema entry gives a `width` or `height`
 *      has that size, read from its header chunk (IHDR: bytes 12-15, the width at byte 16 and the
 *      height at 20, big-endian); a PNG without an IHDR chunk first fails. A value of a key whose
 *      entry `forbids` `year` holds no four digits standing alone once its character references
 *      and `{{ }}` placeholders are removed: the product fills `{{year}}`. A value of a key whose
 *      entry `forbids` `address` holds no http: or https: address, no www., no mailto: and no
 *      e-mail address, as written or with its character references decoded, and gives `{{url}}`
 *      only as an href (`href="{{url}}"`, either quote): the product fills it. Every shipped legal
 *      document whose entry `namesPreferences` names each key the public site stores in a
 *      reader's browser, with its prefix, from the base product's own list (PREFERENCES).
 *
 * Built-ins only, like the rest of the kit. checkBranding reads only the folder it is handed, the
 * list beside it and, for rule 6, the base product's list of stored keys, and returns what it
 * found; it writes nothing.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { markupOf } from '../catalog.mjs';
import { flatten, placeholders } from '../stage-languages.mjs';
import { where } from './where.mjs';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const BRANDING_SCHEMA_FILE = path.join(KIT, 'branding.schema.json');
export const COUNTRY_NAMESPACE = 'country.';
/** The country's list of what awaits acceptance, beside each application's branding folder. */
export const UNACCEPTED = 'branding-unaccepted.json';
/**
 * The names the public site stores in a reader's browser, its prefix and keys, relative to the
 * base product's fe/: the list the site's own preferences service imports.
 */
export const PREFERENCES = 'ui-apps/info/src/app/core/services/preference-keys.json';
const ENGLISH = 'en';
const LANG = '([a-z]{2,3})';
const MAX_BYTES = 1024 * 1024;

const SIGNATURES = {
  png: (bytes) =>
    bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
  webp: (bytes) =>
    bytes.subarray(0, 4).toString('latin1') === 'RIFF' &&
    bytes.subarray(8, 12).toString('latin1') === 'WEBP',
  ico: (bytes) => bytes.subarray(0, 4).equals(Buffer.from([0x00, 0x00, 0x01, 0x00])),
};

// The elements and attributes the legal dialogs render: what Angular's sanitiser keeps of a
// document's text, without the ones a document has no use for.
const LEGAL_ELEMENTS = new Set(
  `a abbr b blockquote br caption cite code dd del dfn div dl dt em h1 h2 h3 h4 h5 h6 hr i ins
  kbd li mark ol p pre q s small span strong sub sup table tbody td tfoot th thead tr u ul`.split(
    /\s+/,
  ),
);
const LEGAL_ATTRIBUTES = new Set([
  'href',
  'title',
  'lang',
  'dir',
  'target',
  'rel',
  'colspan',
  'rowspan',
  'scope',
  'headers',
  'abbr',
]);
const TAG = /<(\/?)([A-Za-z][A-Za-z0-9:-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>/g;
const ATTRIBUTE = /([^\s"'>/=]+)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s"'=<>`]+))?/g;

let schema;
/** The branding contract, read once. */
export function brandingSchema() {
  schema ??= JSON.parse(fs.readFileSync(BRANDING_SCHEMA_FILE, 'utf8'));
  return schema;
}

/**
 * The names the public site stores, `{ prefix, keys }`, from PREFERENCES under `base`: the base
 * product's fe/ as the layout resolver finds it, which is fe/ in the base product's tree and
 * `.niord/base/` in a country's workspace, so the list read is always the base product's own.
 * Throws when the file is not of that shape.
 */
export function readPreferences(base = where().base) {
  const list = JSON.parse(fs.readFileSync(path.join(base, PREFERENCES), 'utf8'));
  const { prefix, keys } = list ?? {};
  if (
    typeof prefix !== 'string' ||
    !prefix ||
    !Array.isArray(keys) ||
    !keys.length ||
    !keys.every((key) => typeof key === 'string' && key)
  )
    throw new Error(
      `${PREFERENCES} is not { "prefix": "<a prefix>", "keys": ["<a key>", …] }, the names the public site stores`,
    );
  return { prefix, keys };
}

/** The reference branding's language: English when it is a UI language, else the default. */
export function referenceLanguageOf(uiLanguages, defaultLanguage) {
  return uiLanguages.includes(ENGLISH) ? ENGLISH : (defaultLanguage ?? uiLanguages[0]);
}

const escapeRe = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** A schema path with `{lang}` and `{doc}` as a regular expression capturing them, in order. */
function pathPattern(pattern) {
  const names = [];
  const source = pattern
    .split(/(\{lang\}|\{doc\})/)
    .map((part) => {
      if (part === '{lang}') {
        names.push('lang');
        return LANG;
      }
      if (part === '{doc}') {
        names.push('doc');
        return '([a-z][a-z0-9-]*)';
      }
      return escapeRe(part);
    })
    .join('');
  return { re: new RegExp(`^${source}$`), names };
}

function matchPath(pattern, relative) {
  const { re, names } = pathPattern(pattern);
  const match = relative.match(re);
  if (!match) return undefined;
  return Object.fromEntries(names.map((name, i) => [name, match[i + 1]]));
}

const fill = (pattern, values) =>
  pattern.replace(/\{(lang|doc)\}/g, (_, name) => values[name] ?? `{${name}}`);

/** Every file and link under `dir`, relative with `/`; links are listed, never followed. */
function walk(dir, problems, base = dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const relative = path.relative(base, full).replaceAll('\\', '/');
    if (entry.isSymbolicLink()) problems.push(`${relative} is a link`);
    else if (entry.isDirectory()) walk(full, problems, base, out);
    else if (entry.isFile()) out.push(relative);
    else problems.push(`${relative} is not a regular file`);
  }
  return out;
}

/** Decodes the character references a browser decodes in an attribute's value. */
function decodeReferences(text) {
  return text
    .replace(/&#x([0-9a-f]+);?/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);?/g, (_, dec) => String.fromCodePoint(Number(dec)))
    .replace(/&colon;/gi, ':')
    .replace(/&tab;/gi, '\t')
    .replace(/&newline;/gi, '\n');
}

/** The scheme of a URL as a browser reads it, lower case, or undefined for a path. */
function schemeOf(url) {
  // eslint-disable-next-line no-control-regex -- a browser drops control characters and spaces from a URL's scheme
  const cleaned = decodeReferences(url).replace(/[\u0000- \u007f]/g, '');
  if (cleaned.startsWith('//')) return '//';
  return cleaned.match(/^([a-z][a-z0-9+.-]*):/i)?.[1].toLowerCase();
}

/** Why an href or src may not be in a brandable value or file, or undefined when it may. */
function unsafeUrl(url, { placeholder = false } = {}) {
  if (placeholder && /^\{\{\s*[\w.]+\s*\}\}$/.test(url)) return undefined;
  const scheme = schemeOf(url);
  if (scheme === undefined || scheme === 'https' || scheme === 'mailto') return undefined;
  return scheme === '//' ? 'leaves the site without saying https:' : `uses ${scheme}:`;
}

/** An image's problems as data: its type by signature, and an SVG's content. */
export function imageProblems(bytes, types) {
  for (const type of types) {
    if (type === 'svg') {
      const text = bytes.toString('utf8').replace(/^\uFEFF/, '');
      if (!/^\s*(<\?xml[^>]*>\s*)?(<!--[\s\S]*?-->\s*)*<svg[\s>]/i.test(text)) continue;
      const problems = [];
      if (/<script[\s>/]/i.test(text)) problems.push('an SVG with a script');
      if (/<foreignObject[\s>/]/i.test(text)) problems.push('an SVG with a foreignObject');
      if (/\son[a-z]+\s*=/i.test(text)) problems.push('an SVG with an event attribute');
      for (const [, value] of text.matchAll(/(?:href|src)\s*=\s*["']([^"']*)["']/gi))
        if (!value.trim().startsWith('#'))
          problems.push(`an SVG with the external reference ${value}`);
      for (const [, value] of text.matchAll(/url\(\s*['"]?([^'")]*)/gi))
        if (!value.trim().startsWith('#'))
          problems.push(`an SVG with the external reference url(${value})`);
      if (/@import/i.test(text)) problems.push('an SVG with an @import');
      return problems;
    }
    if (SIGNATURES[type]?.(bytes)) return [];
  }
  return [`not ${types.map((type) => type.toUpperCase()).join(' or ')} by its content`];
}

/** A PNG's pixel size from its header chunk, IHDR, which comes first; undefined without one. */
export function pngSize(bytes) {
  if (!SIGNATURES.png(bytes) || bytes.length < 24) return undefined;
  if (bytes.subarray(12, 16).toString('latin1') !== 'IHDR') return undefined;
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

/** Why a PNG is not the size its schema entry gives (rule 6), or undefined when it is. */
function sizeProblem(bytes, { width, height }) {
  if (width === undefined && height === undefined) return undefined;
  const size = pngSize(bytes);
  if (!size) return 'is a PNG without its header chunk (IHDR) first, so its size cannot be read';
  if ((width ?? size.width) === size.width && (height ?? size.height) === size.height)
    return undefined;
  const given =
    width === undefined
      ? `a height of ${height} px`
      : height === undefined
        ? `a width of ${width} px`
        : `a size of ${width}×${height} px`;
  return `is ${size.width}×${size.height} px; branding.schema.json gives it ${given}`;
}

const PLACEHOLDER = /\{\{\s*[\w.]+\s*\}\}/g;
const REFERENCE = /&(?:#\d+|#x[0-9a-f]+|[a-z][a-z0-9]*);?/gi;
const URL_PLACEHOLDER = /\{\{\s*url\s*\}\}/;
const ADDRESS = /(?:https?:|mailto:|www\.)[^\s"'<>]*|[^\s"'<>@:;/=]+@[^\s"'<>@]+\.[^\s"'<>@]+/i;

/**
 * The rules a key's schema entry may name in `forbids` (rule 6): each finds, in a value, what the
 * product fills itself, and answers what the value has written in, one line each.
 */
export const FORBIDDEN = {
  year: (value) => {
    const digits = value
      .replace(REFERENCE, ' ')
      .replace(PLACEHOLDER, ' ')
      .match(/(?<!\d)\d{4}(?!\d)/);
    return digits
      ? [
          `has four digits standing alone, ${digits[0]}, written in: a year goes to {{year}}, which the product fills with the deployment's own`,
        ]
      : [];
  },
  address: (value) => {
    const problems = [];
    const address = (decodeReferences(value).match(ADDRESS) ?? value.match(ADDRESS))?.[0];
    if (address)
      problems.push(
        `has the address ${address.replace(/[.,;:!?)]+$/, '')} written in: the product fills {{url}} from info.config.ts`,
      );
    if (URL_PLACEHOLDER.test(value.replace(/href\s*=\s*(["'])\{\{\s*url\s*\}\}\1/gi, '')))
      problems.push(
        'gives {{url}} outside an href: the product fills it with the address of the accessibility statement, which the link opens',
      );
    return problems;
  },
};

/** A legal document's problems: anything outside the legal dialogs' allow-list. */
export function legalProblems(html) {
  const problems = [];
  const text = html.replace(/<!--[\s\S]*?-->/g, '');
  for (const [, , rawName, attributes] of text.matchAll(TAG)) {
    const name = rawName.toLowerCase();
    if (!LEGAL_ELEMENTS.has(name)) problems.push(`the element <${name}>`);
    for (const [, rawAttribute, rawValue] of attributes.matchAll(ATTRIBUTE)) {
      const attribute = rawAttribute.toLowerCase();
      const value = (rawValue ?? '').replace(/^(["'])([\s\S]*)\1$/, '$2');
      if (/^on/.test(attribute)) problems.push(`the event attribute ${attribute}`);
      else if (attribute === 'style') problems.push('a style attribute');
      else if (!LEGAL_ATTRIBUTES.has(attribute)) problems.push(`the attribute ${attribute}`);
      else if (attribute === 'href') {
        const why = unsafeUrl(value);
        if (why) problems.push(`an href that ${why}`);
      }
    }
  }
  if (/<[A-Za-z/!?]/.test(text.replace(TAG, ''))) problems.push('markup that is not a tag');
  return [...new Set(problems)];
}

/** A markup list with each https: href reduced to its host: the part a translation may not move. */
const markupKey = (item) => {
  const match = item.match(/^href=(.*)$/s);
  if (!match) return item;
  try {
    const url = new URL(match[1]);
    if (url.protocol === 'https:') return `href=https://${url.host}/…`;
  } catch {
    // Not an absolute URL: compared as written.
  }
  return item;
};

const same = (a, b) => a.length === b.length && a.every((item, i) => item === b[i]);

/**
 * Checks one application's branding folder. `dir` is the country's `<app>/assets/`; the folder
 * may be absent. Returns the errors and warnings, one line each, the files staging may copy
 * (`{ source, output }`, `source` relative to `dir`) and the UI languages that have branding.
 * Beside them, the errors the upgrade report makes an item of, structured, as `findings`: each
 * `{ kind, file, key?, error }`, `file` relative to `dir` and `error` its line in `errors`;
 * `kind` is `unnamed-file` (a file the schema does not name), `unknown-key` (a key the schema
 * does not have), `required-key` (a required key a texts file lacks) or `required-file`.
 * `preferences` (`{ prefix, keys }`) are the names the public site stores; without them they are
 * read (readPreferences), and only when the section has a document that names them.
 */
export function checkBranding({
  schema: contract = brandingSchema(),
  app,
  dir,
  uiLanguages,
  referenceLanguage = referenceLanguageOf(uiLanguages),
  preferences,
}) {
  const section = contract.apps?.[app];
  if (!section) throw new Error(`branding.schema.json has no application "${app}"`);
  for (const [key, entry] of Object.entries(section.keys))
    for (const rule of entry.forbids ?? [])
      if (!Object.hasOwn(FORBIDDEN, rule))
        throw new Error(
          `branding.schema.json: ${app} ${key} forbids "${rule}", which lib/branding.mjs does not know`,
        );
  const labelCheck = section.legal?.labelCheck;
  if (labelCheck !== undefined && labelCheck !== 'warning')
    throw new Error(
      `branding.schema.json: ${app} legal labelCheck is "${labelCheck}", which lib/branding.mjs does not know`,
    );
  const errors = [];
  const warnings = [];
  const label = `${app}/assets/`;
  const findings = [];
  const fail = (relative, message) => {
    const error = `${label}${relative}: ${message}`;
    errors.push(error);
    return error;
  };
  const find = (kind, file, error, key = undefined) => {
    errors.push(error);
    findings.push({ kind, file, ...(key === undefined ? {} : { key }), error });
  };

  let listed = [];
  if (fs.existsSync(dir)) {
    const stat = fs.lstatSync(dir);
    if (stat.isSymbolicLink() || !stat.isDirectory())
      return {
        errors: [`${label} is not a folder`],
        warnings,
        files: [],
        brandingLanguages: [],
        findings: [],
      };
    const walked = [];
    listed = walk(dir, walked).sort();
    for (const problem of walked) errors.push(`${label}${problem}`);
  }

  const texts = {};
  const shipped = new Map();
  const legal = [];
  for (const relative of listed) {
    const full = path.join(dir, relative);
    const size = fs.statSync(full).size;
    if (size > MAX_BYTES) {
      fail(relative, `is ${size} bytes, over the ${MAX_BYTES} a brandable file may be`);
      continue;
    }
    const outsideUi = (lang) => {
      if (lang === undefined || uiLanguages.includes(lang)) return false;
      fail(relative, `is for "${lang}", which is not a UI language of ${app}`);
      return true;
    };

    const text = matchPath(section.texts.path, relative);
    if (text) {
      if (outsideUi(text.lang)) continue;
      try {
        const parsed = JSON.parse(fs.readFileSync(full, 'utf8'));
        texts[text.lang] = flatten(parsed);
        shipped.set(relative, fill(section.texts.output, text));
      } catch (error) {
        fail(relative, error.message);
      }
      continue;
    }

    const fileEntry = Object.entries(section.files ?? {})
      .map(([pattern, entry]) => [entry, matchPath(pattern, relative)])
      .find(([, values]) => values);
    if (fileEntry) {
      const [entry, values] = fileEntry;
      if (outsideUi(values.lang)) continue;
      const bytes = fs.readFileSync(full);
      const problems = imageProblems(bytes, entry.types);
      const size = problems.length ? undefined : sizeProblem(bytes, entry);
      if (problems.length) fail(relative, `is ${problems.join('; ')}`);
      else if (size) fail(relative, size);
      else shipped.set(relative, fill(entry.output, values));
      continue;
    }

    const document = section.legal && matchPath(section.legal.path, relative);
    if (document && Object.hasOwn(section.legal.documents, document.doc)) {
      if (outsideUi(document.lang)) continue;
      const html = fs.readFileSync(full, 'utf8');
      const problems = legalProblems(html);
      if (problems.length)
        fail(relative, `holds ${problems.join(', ')}, outside what a legal dialog renders`);
      else {
        shipped.set(relative, fill(section.legal.output, document));
        legal.push({ ...document, html });
      }
      continue;
    }

    findings.push({
      kind: 'unnamed-file',
      file: relative,
      error: fail(relative, 'is a file branding.schema.json does not name'),
    });
  }

  // 1. Every key is the schema's or the country's own, with the schema's placeholders and kind.
  const outputs = new Set(shipped.values());
  for (const [lang, keys] of Object.entries(texts))
    for (const [key, value] of Object.entries(keys)) {
      const file = section.texts.path.replace('{lang}', lang);
      const where = `${file} ${key}`;
      const entry = section.keys[key];
      if (!entry) {
        if (!key.startsWith(COUNTRY_NAMESPACE))
          find(
            'unknown-key',
            file,
            `${label}${where} is not a key of branding.schema.json (the country's own keys start with "${COUNTRY_NAMESPACE}")`,
            key,
          );
        continue;
      }
      if (!same(placeholders(value), [...entry.placeholders].sort()))
        errors.push(
          `${label}${where} has the placeholders ${placeholders(value).join(', ') || 'none'}, not ${entry.placeholders.join(', ') || 'none'}`,
        );
      if (entry.kind === 'text' && markupOf(value).length)
        errors.push(`${label}${where} is plain text and has markup`);
      if (entry.kind === 'url') {
        let url;
        try {
          url = new URL(value);
        } catch {
          // Reported below.
        }
        if (url?.protocol !== 'https:') errors.push(`${label}${where} is not an https: URL`);
      }
      if (entry.kind === 'file' && !outputs.has(value))
        errors.push(`${label}${where} names ${value}, which is not a brandable file it ships`);
      // Rule 6: what the product fills itself, not written into the value.
      for (const rule of entry.forbids ?? [])
        for (const problem of FORBIDDEN[rule](value)) errors.push(`${label}${where} ${problem}`);
    }

  // 2. Required keys and files, branding for every UI language, the legal documents' titles.
  const branded = uiLanguages.filter((lang) => texts[lang]);
  const requiredKeys = Object.entries(section.keys)
    .filter(([, entry]) => entry.required)
    .map(([key]) => key);
  if (branded.length || requiredKeys.length)
    for (const lang of uiLanguages)
      if (!texts[lang])
        errors.push(
          `${label}${section.texts.path.replace('{lang}', lang)} does not exist, and ${lang} is a UI language of ${app}`,
        );
  for (const lang of branded)
    for (const key of requiredKeys)
      if (!Object.hasOwn(texts[lang], key)) {
        const file = section.texts.path.replace('{lang}', lang);
        find('required-key', file, `${label}${file} has no ${key}, which is required`, key);
      }
  for (const [pattern, entry] of Object.entries(section.files ?? {}))
    if (entry.required)
      for (const lang of pattern.includes('{lang}') ? uiLanguages : [undefined]) {
        const relative = fill(pattern, { lang });
        if (!shipped.has(relative))
          find('required-file', relative, `${label}${relative} does not exist; it is required`);
      }
  // A key that shows something needs the text that names it to a reader (the schema's
  // `requires`): a logo without its alternative text, a link without its accessible name, would
  // otherwise carry the key's own name, which is what the translation gives a key it lacks.
  for (const lang of branded)
    for (const [key, entry] of Object.entries(section.keys))
      for (const needed of entry.requires ?? [])
        if (Object.hasOwn(texts[lang], key) && !Object.hasOwn(texts[lang], needed))
          errors.push(
            `${label}${section.texts.path.replace('{lang}', lang)} sets ${key} and not ${needed}, which it requires`,
          );
  // The same as a warning for a key set without one it `recommends`: the public site's default
  // footer, which a country may replace with its own, draws a logo without its text as decoration.
  for (const lang of branded)
    for (const [key, entry] of Object.entries(section.keys))
      for (const wanted of entry.recommends ?? [])
        if (Object.hasOwn(texts[lang], key) && !Object.hasOwn(texts[lang], wanted))
          warnings.push(
            `${label}${section.texts.path.replace('{lang}', lang)} sets ${key} and not ${wanted}, which it recommends`,
          );
  // Where the section's footer is one a country may replace with its own (`labelCheck:
  // "warning"`), what the default footer would show wrongly is a warning, and the lines name it.
  const labelWarns = labelCheck === 'warning';
  const footer = labelWarns ? 'the default footer' : 'the footer';
  const labelFindings = labelWarns ? warnings : errors;
  for (const { doc, lang } of legal) {
    const { titleKey, labelKey } = section.legal.documents[doc];
    const relative = fill(section.legal.path, { doc, lang });
    if (!texts[lang] || !Object.hasOwn(texts[lang], titleKey))
      errors.push(
        `${label}${relative} is shipped, but ${section.texts.path.replace('{lang}', lang)} has no ${titleKey}, its title`,
      );
    else if (labelKey && !Object.hasOwn(texts[lang], labelKey))
      warnings.push(
        `${label}${relative} is shipped, but ${section.texts.path.replace('{lang}', lang)} has no ${labelKey}, so ${footer} never offers it`,
      );
  }
  // Rule 6: a statement of what the site stores in a reader's browser names each of its names.
  const naming = legal.filter(({ doc }) => section.legal.documents[doc].namesPreferences);
  if (naming.length) {
    const { prefix, keys } = preferences ?? readPreferences();
    const names = keys.map((key) => prefix + key);
    for (const { doc, lang, html } of naming) {
      const missing = names.filter(
        (name) => !new RegExp(`${escapeRe(name)}(?![A-Za-z0-9_])`).test(html),
      );
      if (missing.length)
        errors.push(
          `${label}${fill(section.legal.path, { doc, lang })}: does not name ${missing.join(', ')}, which the public site stores in a reader's browser (${PREFERENCES})`,
        );
    }
  }
  // A footer label offers its document: the label needs the document in some branding language,
  // which the dialog falls back through, and the dialog's title beside it.
  for (const [doc, { titleKey, labelKey }] of Object.entries(section.legal?.documents ?? {})) {
    if (!labelKey) continue;
    const labelled = branded.filter((lang) => Object.hasOwn(texts[lang], labelKey));
    if (labelled.length && !legal.some((shippedDoc) => shippedDoc.doc === doc))
      labelFindings.push(
        `${label}${section.texts.path.replace('{lang}', labelled[0])} sets ${labelKey}, so ${footer} offers the ${doc}, but no ${fill(section.legal.path, { doc, lang: '{lang}' })} is shipped for any branding language`,
      );
    for (const lang of labelled)
      if (!Object.hasOwn(texts[lang], titleKey))
        labelFindings.push(
          `${label}${section.texts.path.replace('{lang}', lang)} sets ${labelKey} and not ${titleKey}, the title of the dialog it opens`,
        );
  }

  // 3. Markup equal to the reference branding's; links only of the schemes branding may use.
  const reference = texts[referenceLanguage] ?? {};
  for (const [lang, keys] of Object.entries(texts))
    for (const [key, value] of Object.entries(keys)) {
      const where = `${label}${section.texts.path.replace('{lang}', lang)} ${key}`;
      const markup = markupOf(value);
      for (const item of markup) {
        const url = item.match(/^(?:href|src)=(.*)$/s)?.[1];
        const why = url !== undefined && unsafeUrl(url, { placeholder: true });
        if (why) errors.push(`${where} has a link that ${why}`);
      }
      if (lang === referenceLanguage) continue;
      const own = markup.map(markupKey).sort();
      const theirs = markupOf(reference[key] ?? '')
        .map(markupKey)
        .sort();
      if (same(own, theirs)) continue;
      if (!theirs.length)
        errors.push(
          `${where} has markup where the reference branding (${referenceLanguage}) has none`,
        );
      else {
        const plain = (items) => items.filter((item) => !/^(href|src)=/.test(item));
        errors.push(
          same(plain(own), plain(theirs))
            ? `${where} links elsewhere than the reference branding (${referenceLanguage}): an href may differ only in its path, on the same https: host`
            : `${where} has the markup ${own.join(' ')}, not the reference branding's (${referenceLanguage}) ${theirs.join(' ')}`,
        );
      }
    }

  // 5. The values and documents awaiting acceptance, listed beside the folder.
  const unaccepted = checkUnaccepted({
    file: path.join(dir, '..', UNACCEPTED),
    app,
    section,
    uiLanguages,
    texts,
    outputs,
  });
  errors.push(...unaccepted.errors);
  warnings.push(...unaccepted.warnings);

  return {
    errors,
    warnings,
    files: [...shipped].map(([source, output]) => ({ source, output })),
    brandingLanguages: branded,
    findings,
  };
}

/**
 * The country's list of branding values and legal documents awaiting acceptance, `file`, against
 * what the branding check found: `texts` (language → key → value) and `outputs` (the paths the
 * shipped files land at). An absent list is an empty one. Returns the errors and the warning.
 */
function checkUnaccepted({ file, app, section, uiLanguages, texts, outputs }) {
  const label = `${app}/${UNACCEPTED}`;
  const errors = [];
  const fail = (message) => ({ errors: [`${label}: ${message}`], warnings: [] });
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === 'ENOENT') return { errors, warnings: [] };
    return fail(error.message);
  }
  if (!stat.isFile()) return fail('is not a regular file');
  let list;
  try {
    list = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    return fail(error.message);
  }
  if (list === null || typeof list !== 'object' || Array.isArray(list))
    return fail('is not an object of a note and a list per language');

  const counts = [];
  let total = 0;
  for (const [field, entries] of Object.entries(list)) {
    if (field === 'note') {
      if (typeof entries !== 'string') errors.push(`${label}: note is not a string`);
      continue;
    }
    if (!/^[a-z]{2,3}$/.test(field)) {
      errors.push(`${label}: ${field} is neither the note nor a language`);
      continue;
    }
    if (!uiLanguages.includes(field)) {
      errors.push(`${label}: ${field} is not a UI language of ${app}`);
      continue;
    }
    if (!Array.isArray(entries)) {
      errors.push(`${label}: ${field} is not a list`);
      continue;
    }
    const seen = new Set();
    for (const entry of entries) {
      const where = `${label}: ${field}: ${JSON.stringify(entry)}`;
      if (typeof entry !== 'string') {
        errors.push(`${where} is not a file's path`);
        continue;
      }
      if (seen.has(entry)) {
        errors.push(`${where} is listed twice`);
        continue;
      }
      seen.add(entry);
      const [output, key, ...rest] = entry.split('#');
      const values =
        key === undefined
          ? section.legal && matchPath(section.legal.output, output)
          : !rest.length && key && matchPath(section.texts.output, output);
      if (!values || (key === undefined && !Object.hasOwn(section.legal.documents, values.doc))) {
        errors.push(
          `${where} is neither ${fill(section.texts.output, { lang: field })}#<key> nor ${section.legal ? fill(section.legal.output, { lang: field }) : 'a legal document'}`,
        );
        continue;
      }
      if (values.lang !== field) {
        errors.push(`${where} is for "${values.lang}", not ${field}, the language of its list`);
        continue;
      }
      if (!outputs.has(output)) {
        errors.push(`${where} names ${output}, which the country's branding does not ship`);
        continue;
      }
      if (key === undefined) continue;
      // A key the country's branding sets: the schema's, or the country's own (rule 1).
      if (!Object.hasOwn(texts[field] ?? {}, key))
        errors.push(`${where} names the key ${key}, which ${output} does not set`);
      else if (!Object.hasOwn(section.keys, key) && !key.startsWith(COUNTRY_NAMESPACE))
        errors.push(`${where} names the key ${key}, which branding.schema.json does not name`);
    }
    if (entries.length) counts.push(`${field} ${entries.length}`);
    total += entries.length;
  }
  const warnings = counts.length
    ? [
        `${total} branding value(s) and legal document(s) await acceptance (${counts.join(', ')}): ${label}; go-live requires every list empty`,
      ]
    : [];
  return { errors, warnings };
}
