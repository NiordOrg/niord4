/**
 * The country API (fe-country-split-plan §4.3): what each application lets country code import,
 * `@niord/admin/api` and `@niord/info/api` (ui-apps/<app>/src/public-api.ts), and the specs-only
 * `@niord/admin/api/testing` and `@niord/info/api/testing` (src/testing-api.ts).
 *
 * Each file has one form, so the kit can read it without a TypeScript parser: a file-header
 * comment, then only `export { … } from '…';` and `export type { … } from '…';` statements, each
 * immediately preceded by its own contract comment. No `export *`, no local declaration, no other
 * statement. A deprecated export is a statement of its own whose comment carries a line
 * `Deprecated since <version>: <what to use instead>`, or with the version it ends in,
 * `Deprecated since <version>, removed in <version>: <what to use instead>` (never a JSDoc
 * @deprecated tag, which the base product's type-aware lint would turn into an error at once); a
 * `Deprecated since` line of another form fails, as does an end not after the start. Set-up warns
 * about every use of one in the country's files (deprecatedUses, deprecatedUsesOf for the upgrade
 * report), and the kit's API-CHANGES.md (.niord/kit/API-CHANGES.md at a country's root) records it.
 */
import fs from 'node:fs';
import path from 'node:path';
import { compare, parseVersion } from './semver.mjs';
import { parseJsonc } from './workspace.mjs';

/** The four entry points, as fe/tsconfig.json maps them. */
export const API_ENTRIES = [
  '@niord/admin/api',
  '@niord/admin/api/testing',
  '@niord/info/api',
  '@niord/info/api/testing',
];

// A contract comment's line that starts a deprecation, and the form it must have.
const DEPRECATED_LINE = /^[ \t]*\*?[ \t]*(Deprecated since\b.*?)[ \t]*$/m;
const DEPRECATED =
  /^Deprecated since ([0-9A-Za-z][0-9A-Za-z.+_-]*)(?:, removed in ([0-9A-Za-z][0-9A-Za-z.+_-]*))?:\s*(.+)$/;
const STATEMENT = /^export\s+(type\s+)?\{([^}]*)\}\s*from\s*(['"])([^'"\n]+)\3\s*;$/;
const NAME = /^(type\s+)?([A-Za-z_$][\w$]*)(?:\s+as\s+([A-Za-z_$][\w$]*))?$/;

/**
 * A contract comment's deprecation: `{ since, removedIn?, instead }`, or undefined when it has
 * none. Throws on a `Deprecated since` line outside the form, and on an end that is not a later
 * version than the start (when both are semantic versions).
 */
export function deprecationOf(comment, label = 'the API') {
  const line = DEPRECATED_LINE.exec(comment)?.[1];
  if (line === undefined) return undefined;
  const match = DEPRECATED.exec(line);
  if (!match || /[.,]$/.test(match[1]) || /[.,]$/.test(match[2] ?? ''))
    throw new Error(
      `${label}: "${line}" is not \`Deprecated since <version>: <what to use instead>\` or \`Deprecated since <version>, removed in <version>: <what to use instead>\`, each version a plain one`,
    );
  const [, since, removedIn, instead] = match;
  if (removedIn !== undefined) {
    let later = true;
    try {
      later = compare(parseVersion(removedIn), parseVersion(since)) > 0;
    } catch {
      // Not semantic versions: the form is all that is held.
    }
    if (!later) throw new Error(`${label}: "${line}" ends no later than it starts`);
  }
  return {
    since,
    ...(removedIn !== undefined ? { removedIn } : {}),
    instead: instead.trim().replace(/\.$/, ''),
  };
}

/**
 * The exports of one API file: `[{ name, type, module, deprecated? }]`, `type` true for a type-only
 * export and `deprecated` `{ since, removedIn?, instead }` when its contract comment says so
 * (deprecationOf). Throws on any statement outside the form, on an export without a contract
 * comment before it and on a deprecation line outside its form.
 */
export function readApi(file, text = fs.readFileSync(file, 'utf8')) {
  const label = path.basename(file);
  const tokens = [];
  let i = 0;
  while (i < text.length) {
    if (/\s/.test(text[i])) {
      i++;
      continue;
    }
    if (text.startsWith('/**', i)) {
      const end = text.indexOf('*/', i + 3);
      if (end < 0) throw new Error(`${label}: a comment is never closed`);
      tokens.push({ kind: 'comment', text: text.slice(i, end + 2) });
      i = end + 2;
      continue;
    }
    const end = text.indexOf(';', i);
    const statement = (end < 0 ? text.slice(i) : text.slice(i, end + 1)).trim();
    tokens.push({ kind: 'statement', text: statement });
    i = end < 0 ? text.length : end + 1;
  }
  if (tokens[0]?.kind !== 'comment')
    throw new Error(`${label}: the file starts with its header comment`);
  const entries = [];
  for (let t = 1; t < tokens.length; t++) {
    const token = tokens[t];
    const line = token.text.split('\n')[0];
    if (token.kind === 'comment') {
      if (tokens[t + 1]?.kind !== 'statement')
        throw new Error(`${label}: a comment that is no export's contract`);
      continue;
    }
    const match = STATEMENT.exec(token.text);
    if (!match)
      throw new Error(
        `${label}: "${line}" is not \`export { … } from '…';\` or \`export type { … } from '…';\`: the API has no \`export *\`, no local declaration and no other statement`,
      );
    if (tokens[t - 1]?.kind !== 'comment' || t - 1 === 0)
      throw new Error(`${label}: "${line}" has no contract comment of its own before it`);
    const deprecation = deprecationOf(tokens[t - 1].text, label);
    const names = match[2]
      .split(',')
      .map((part) => part.trim())
      .filter(Boolean);
    if (!names.length) throw new Error(`${label}: "${line}" exports nothing`);
    for (const part of names) {
      const name = NAME.exec(part.replace(/\s+/g, ' '));
      if (!name) throw new Error(`${label}: "${part}" in "${line}" is not a name`);
      entries.push({
        name: name[3] ?? name[2],
        type: Boolean(match[1] || name[1]),
        module: match[4],
        ...(deprecation ? { deprecated: deprecation } : {}),
      });
    }
  }
  const seen = new Set();
  for (const { name } of entries) {
    if (seen.has(name)) throw new Error(`${label}: ${name} is exported twice`);
    seen.add(name);
  }
  return entries;
}

/**
 * The four API files of an unpacked base product (`base`, its fe/), from the paths of its
 * tsconfig.json: entry point → absolute file. Throws when one is not mapped or does not exist.
 */
export function apiFiles(base) {
  const tsconfig = parseJsonc(
    fs.readFileSync(path.join(base, 'tsconfig.json'), 'utf8'),
    'tsconfig.json',
  );
  const paths = tsconfig.compilerOptions?.paths ?? {};
  const out = {};
  for (const entry of API_ENTRIES) {
    const target = paths[entry]?.[0];
    if (typeof target !== 'string')
      throw new Error(`the base product's tsconfig.json does not map ${entry}`);
    const file = path.resolve(base, target);
    if (!fs.existsSync(file))
      throw new Error(`${entry} is mapped to ${target}, which the base product does not hold`);
    out[entry] = file;
  }
  return out;
}

/** Every .ts file under the country's admin/ and info/, relative to `fe` with `/`. */
function countryTypeScript(fe) {
  const out = [];
  for (const app of ['admin', 'info']) {
    const dir = path.join(fe, app);
    if (!fs.existsSync(dir)) continue;
    for (const entry of fs.readdirSync(dir, { recursive: true, withFileTypes: true }))
      if (entry.isFile() && entry.name.endsWith('.ts'))
        out.push(path.relative(fe, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'));
  }
  return out.sort();
}

const SPECIFIER = '(@niord\\/(?:admin|info)\\/api(?:\\/testing)?)';
const STATIC = new RegExp(
  `\\b(?:import|export)\\s+(?:type\\s+)?([^;'"]*?)\\s*from\\s*['"]${SPECIFIER}['"]`,
  'g',
);
const DYNAMIC = new RegExp(`\\bimport\\s*\\(\\s*['"]${SPECIFIER}['"]\\s*\\)`, 'g');

/**
 * The names a file's text takes from each API entry point: `[{ specifier, names }]`, `names` null
 * for a namespace import, `export *` or a dynamic import, which reach every name.
 */
export function apiImports(text) {
  const out = [];
  for (const match of text.matchAll(STATIC)) {
    const clause = match[1].trim();
    const braces = /\{([^}]*)\}/.exec(clause);
    if (!braces) {
      out.push({ specifier: match[2], names: null });
      continue;
    }
    const names = braces[1]
      .split(',')
      .map((part) =>
        part
          .trim()
          .replace(/^type\s+/, '')
          .split(/\s+as\s+/)[0]
          .trim(),
      )
      .filter(Boolean);
    out.push({ specifier: match[2], names });
    if (/\*\s+as\s+/.test(clause.replace(braces[0], '')))
      out.push({ specifier: match[2], names: null });
  }
  for (const match of text.matchAll(DYNAMIC)) out.push({ specifier: match[1], names: null });
  return out;
}

/**
 * Every use of a deprecated API export in the country's .ts files: `[{ file, name, specifier,
 * since, removedIn?, instead }]`, `file` relative to `fe` with `/`. `apis` maps each entry point to
 * its readApi result.
 */
export function deprecatedUsesOf({ fe, apis }) {
  const uses = [];
  for (const file of countryTypeScript(fe)) {
    const text = fs.readFileSync(path.join(fe, file), 'utf8');
    for (const { specifier, names } of apiImports(text)) {
      const deprecated = (apis[specifier] ?? []).filter((entry) => entry.deprecated);
      for (const entry of deprecated)
        if (names === null || names.includes(entry.name))
          uses.push({ file, name: entry.name, specifier, ...entry.deprecated });
    }
  }
  return uses;
}

/** The warning set-up prints for one use of a deprecated API export (deprecatedUsesOf). */
export const deprecationWarning = (use) =>
  `${use.file} uses ${use.name} from ${use.specifier}, deprecated since ${use.since}: ${use.instead}; ${use.removedIn ? `it is removed in ${use.removedIn}` : 'its removal is not scheduled yet'} (.niord/kit/API-CHANGES.md, from the repository root)`;

/** One warning per use of a deprecated API export in the country's .ts files. */
export const deprecatedUses = ({ fe, apis }) =>
  deprecatedUsesOf({ fe, apis }).map(deprecationWarning);
