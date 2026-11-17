/**
 * Guarded overrides (fe-country-split-plan §4.9): a country's copy of a base product source file,
 * compiled in its place through `fileReplacements`, each an entry of the manifest's `overrides`:
 *
 *   { file, with, basedOn, reason, added }
 *
 * `file` is the base product's file (relative to its fe/, under `ui-apps/<app>/src/app/`), `with`
 * the country's copy at the same path below `<app>/overrides/`, `basedOn` the fingerprint of every
 * base product file the copy was made from — the file and, for a component, its template and
 * styles. Set-up fails while any of them differs from the base product it builds against, until
 * the country has compared its copy and recorded the new fingerprints.
 *
 *   addOverride      `node setup.mjs --add-override <base file> --reason <text>`: refuses what
 *                    cannot or may not be overridden, copies the file and its resources, and
 *                    returns the manifest entry
 *   acceptOverride   `node setup.mjs --accept-override <file>`: records the current fingerprints
 *                    and returns what to review, with the base product's own diff of each changed
 *                    file since the recorded fingerprint when the previous version's zip is at hand
 *                    (diffSinceRecorded)
 *   checkOverrides   every set-up: each entry against the base product, and the printed list
 *   baseSpecs        the base product's spec beside each overridden file, which the country's
 *                    `npm test` runs against the copy
 *
 * The copy's relative imports are written as `@niord/<app>/internal/…`, the alias an override file
 * reaches the base product's sources through (§4.3): a copy compiles at its own path, where a
 * relative path would reach nothing. That is a one-time scaffold of a file the country then owns,
 * made from the base product's own text; no manifest value is ever written into a generated file.
 *
 * Node built-ins only: set-up runs before `npm ci`.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { compileList, globToRegExp } from './include.mjs';
import { compare, parseVersion } from './semver.mjs';

const posix = path.posix;
const APP_CODE = /^ui-apps\/(admin|info)\/src\/app\/(.+)$/;
const REPLACEABLE = /\.(ts|mjs|js|json)$/;
const SCRIPT = /\.(ts|mjs|js)$/;
const MAX_REASON = 200;
// The most line pairs the review's line diff compares; a larger pair is compared by hand.
const MAX_DIFF_CELLS = 4_000_000;

/**
 * A file's fingerprint: `sha256:` and the hash of its bytes with every CRLF read as LF, so a
 * Windows checkout and the zip give the same value. Bytes, not text: any file hashes the same way.
 */
export function fingerprint(bytes) {
  const normalised = Buffer.from(bytes.toString('latin1').replace(/\r\n/g, '\n'), 'latin1');
  return `sha256:${createHash('sha256').update(normalised).digest('hex')}`;
}

/** Whether a protected glob of seams.json matches `file`. */
export const protectedMatch = (globs, file) =>
  (globs ?? []).some((glob) => globToRegExp(glob).test(file));

/** A component's `templateUrl`, `styleUrl` and `styleUrls` paths, as written: a lexical scan. */
export function componentResources(source) {
  const found = [];
  for (const match of source.matchAll(/\b(?:templateUrl|styleUrl)\s*:\s*(['"`])([^'"`\n]+)\1/g))
    found.push(match[2]);
  for (const match of source.matchAll(/\bstyleUrls\s*:\s*\[([^\]]*)\]/g))
    for (const item of match[1].matchAll(/(['"`])([^'"`\n]+)\1/g)) found.push(item[2]);
  return [...new Set(found)];
}

/** The application and the path below `src/app/` of a base product file, or undefined. */
export function appCode(file) {
  const match = APP_CODE.exec(file);
  return match ? { app: match[1], rest: match[2] } : undefined;
}

/** Where the country's copy of a base product file lives: `<app>/overrides/<path below src/app/>`. */
export function overridePath(file) {
  const code = appCode(file);
  if (!code)
    throw new Error(`${file} is not under ui-apps/admin/src/app/ or ui-apps/info/src/app/`);
  return `${code.app}/overrides/${code.rest}`;
}

/** A relative path written from one base product file to another, resolved; `/`-separated. */
const resolveFrom = (file, specifier) =>
  posix.normalize(posix.join(posix.dirname(file), specifier));

// A module specifier that starts a relative path, in `from '…'`, `import '…'` and `import('…')`.
const RELATIVE_SPECIFIER = /(\bfrom\s*|\bimport\s*\(\s*|\bimport\s*)(['"])(\.\.?\/[^'"\n]*)\2/g;

/** Every relative module specifier of a script, in order. */
export const relativeSpecifiers = (source) =>
  [...source.matchAll(RELATIVE_SPECIFIER)].map((match) => match[3]);

/**
 * A base product script with every relative module specifier written as the internal alias of
 * the file it resolves to. Throws on one that resolves outside the application's `src/app/`,
 * which the alias does not reach.
 */
export function rewriteImports(source, { app, file }) {
  const root = `ui-apps/${app}/src/app/`;
  return source.replace(RELATIVE_SPECIFIER, (_whole, lead, quote, specifier) => {
    const target = resolveFrom(file, specifier);
    if (!target.startsWith(root))
      throw new Error(
        `${file} imports ${specifier}, which lies outside ${root}: an override reaches the base product through @niord/${app}/internal/* only`,
      );
    const rest = target.slice(root.length).replace(SCRIPT, '');
    return `${lead}${quote}@niord/${app}/internal/${rest}${quote}`;
  });
}

/**
 * A script written for the place `from` with every relative module specifier rewritten for the
 * place `to`, so each still names the same file (both paths relative to one folder, with `/`).
 */
export function relocateImports(source, { from, to }) {
  return source.replace(RELATIVE_SPECIFIER, (_whole, lead, quote, specifier) => {
    const moved = posix.relative(posix.dirname(to), resolveFrom(from, specifier));
    return `${lead}${quote}${moved.startsWith('.') ? moved : `./${moved}`}${quote}`;
  });
}

const fail = (message) => {
  throw new Error(message);
};

/** The base product files an override copies: the file, then each resource its component names. */
function sourcesOf({ base, file }) {
  const text = fs.readFileSync(path.join(base, file), 'utf8');
  const resources = SCRIPT.test(file)
    ? componentResources(text).map((resource) => resolveFrom(file, resource))
    : [];
  return { text, resources };
}

/** The ids and files of every seam, generated ones included, by the base product path they take. */
function seamFiles(seams) {
  const out = new Map();
  for (const seam of [...(seams.seams ?? []), ...(seams.generated ?? [])])
    for (const file of [seam.replaces, seam.base].filter(Boolean)) out.set(file, seam);
  return out;
}

/**
 * The manifest entry of a new override of `file`, after copying the file and its component's
 * resources into the country's overrides folder. `include` is the zip's include list
 * (`{ include, exclude }`), `today` the date it is added on (`YYYY-MM-DD`). Refuses, each with its
 * reason: a file outside an application's code, one that cannot be replaced, a spec, a file the
 * base product does not ship, a protected file, a seam's file, a file already overridden and a
 * copy that would overwrite a file.
 */
export function addOverride({ fe, base, manifest, file, reason, seams, include, today }) {
  if (typeof file !== 'string' || !file) fail('--add-override needs the base product file to copy');
  if (file.includes('\\') || file.split('/').some((part) => !part || part === '.' || part === '..'))
    fail(`${file} is not a path relative to the base product's fe/, written with /`);
  const code = appCode(file);
  if (!code)
    fail(
      `${file} is not under ui-apps/admin/src/app/ or ui-apps/info/src/app/: only application code is overridden; for anything else a country asks the base product for a hook`,
    );
  if (!REPLACEABLE.test(file))
    fail(
      `${file} cannot be replaced: only TypeScript, JavaScript and JSON files can; a template or stylesheet is overridden through its component's .ts file`,
    );
  if (file.endsWith('.spec.ts'))
    fail(
      `${file} is a spec: the base product's spec of an overridden file runs against the country's copy, and the country adds its own beside the copy`,
    );
  if (typeof reason !== 'string' || !reason.trim())
    fail('--add-override needs --reason <text>: why the country cannot do without the copy');
  // eslint-disable-next-line no-control-regex -- a reason is plain text: no control character
  if ([...reason].length > MAX_REASON || /[\u0000-\u001f\u007f-\u009f]/.test(reason))
    fail(`the reason is plain text of at most ${MAX_REASON} characters`);
  const shipped = compileList(include);
  if (!fs.existsSync(path.join(base, file)) || !shipped(file))
    fail(`${file} is not a file of the base product's source zip`);
  if (protectedMatch(seams.protected, file))
    fail(
      `${file} is protected (seams.json; country-kit/README.md, Guarded overrides): it is never overridden; a country asks the base product for a hook instead`,
    );
  const seam = seamFiles(seams).get(file);
  if (seam)
    fail(
      `${file} is the seam ${seam.id}: fill the seam instead${seam.country ? `, at ${seam.country}` : ''}`,
    );
  const existing = (manifest.overrides ?? []).find((entry) => entry.file === file);
  if (existing) fail(`${file} is already overridden by ${existing.with}`);

  const { text, resources } = sourcesOf({ base, file });
  for (const resource of resources) {
    if (!appCode(resource) || appCode(resource).app !== code.app)
      fail(`${file} names ${resource}, which lies outside its application's src/app/`);
    if (!fs.existsSync(path.join(base, resource)) || !shipped(resource))
      fail(`${file} names ${resource}, which the base product's source zip does not hold`);
  }
  const copies = [file, ...resources].map((source) => ({ source, target: overridePath(source) }));
  for (const { target } of copies)
    if (fs.existsSync(path.join(fe, target)))
      fail(`${target} exists: --add-override never overwrites a file`);

  const copy = SCRIPT.test(file) ? rewriteImports(text, { app: code.app, file }) : text;
  for (const { source, target } of copies) {
    const to = path.join(fe, target);
    fs.mkdirSync(path.dirname(to), { recursive: true });
    if (source === file) fs.writeFileSync(to, copy);
    else fs.copyFileSync(path.join(base, source), to);
  }
  return {
    file,
    with: overridePath(file),
    basedOn: Object.fromEntries(
      [file, ...resources].map((source) => [
        source,
        fingerprint(fs.readFileSync(path.join(base, source))),
      ]),
    ),
    reason: reason.trim(),
    added: today,
  };
}

/** The entry an argument names, by its base product file or its country copy. */
export function findOverride(manifest, target) {
  const entry = (manifest.overrides ?? []).find(
    (candidate) => candidate.file === target || candidate.with === target,
  );
  if (!entry)
    throw new Error(
      `niord-country.json has no override of ${target}: name an entry's file or its copy`,
    );
  return entry;
}

/** Whether a fix's version came after `since`; an unreadable version counts as newer. */
function newerThan(version, since) {
  try {
    return compare(parseVersion(version), parseVersion(since)) > 0;
  } catch {
    return true;
  }
}

/**
 * The fixes of security.json that name `file` and came after `since`, the base product version
 * the last set-up built against (its record): a fix the country set up with before is no longer
 * news, as the upgrade report has it. Without a record (`since` undefined) none is.
 */
const fixesOf = (security, file, since) =>
  since === undefined
    ? []
    : (security?.fixes ?? []).filter(
        (fix) => (fix.files ?? []).includes(file) && newerThan(fix.version, since),
      );

/**
 * The lines that differ between two texts, from a longest common subsequence of their lines:
 * `-<n> text` for a line only `a` has (its line number in `a`), `+<n> text` for one only `b` has.
 */
export function lineDiff(a, b) {
  const left = a.replace(/\r\n/g, '\n').split('\n');
  const right = b.replace(/\r\n/g, '\n').split('\n');
  const n = left.length;
  const m = right.length;
  if ((n + 1) * (m + 1) > MAX_DIFF_CELLS)
    return [`(${n} and ${m} lines: too large for a line diff here; compare the files by hand)`];
  const width = m + 1;
  const table = new Uint32Array((n + 1) * width);
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      table[i * width + j] =
        left[i] === right[j]
          ? table[(i + 1) * width + j + 1] + 1
          : Math.max(table[(i + 1) * width + j], table[i * width + j + 1]);
  const out = [];
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && left[i] === right[j]) {
      i++;
      j++;
    } else if (i < n && (j === m || table[(i + 1) * width + j] >= table[i * width + j + 1]))
      out.push(`-${i + 1} ${left[i++]}`);
    else out.push(`+${j + 1} ${right[j++]}`);
  }
  return out;
}

/**
 * The base product's own diff of `file` since the country recorded its fingerprint: `{ diff }`
 * (lineDiff, - before, + now) from the previous version's file to `current`, the file's text now,
 * or `{ unavailable }` saying why not. `previous` is the version the last set-up built against,
 * `{ version, files }` with its zip's files (path → bytes, lib/previous-base.mjs) or
 * `{ unavailable }`; the diff is given only when the previous file is the one the fingerprint
 * recorded, so it shows exactly what changed under the country's copy.
 */
export function diffSinceRecorded({ file, recorded, current, previous }) {
  if (!previous) return { unavailable: 'no previous base product version is known' };
  if (previous.unavailable) return { unavailable: previous.unavailable };
  const bytes = previous.files.get(file);
  if (!bytes)
    return { unavailable: `the previous version's zip (${previous.version}) does not hold it` };
  if (fingerprint(bytes) !== recorded)
    return {
      unavailable: `the recorded fingerprint is not the previous version's file (${previous.version})`,
    };
  return { diff: lineDiff(bytes.toString('utf8'), current) };
}

/**
 * Records the base product's current fingerprints for the override `target` names (its file or
 * its copy) and returns `{ entry, lines }`: the updated entry and the review to print — per base
 * file the recorded and the current fingerprint, for a changed one the base product's own diff
 * since the recorded fingerprint (diffSinceRecorded; `previous` as there, `version` the base
 * product version set-up builds against now) or why it is not available, the lines in which the
 * country's copy differs from the base product's current file, and a SECURITY block for a changed
 * file a security fix newer than `previous.version` names (fixesOf). Fails when a file the override was based on is no longer in the
 * base product.
 */
export function acceptOverride({ fe, base, manifest, target, security, previous, version }) {
  const entry = findOverride(manifest, target);
  const removed = Object.keys(entry.basedOn).filter(
    (file) => !fs.existsSync(path.join(base, file)),
  );
  if (removed.length)
    throw new Error(
      `the base product no longer has ${removed.join(', ')}: the override of ${entry.file} cannot be accepted; remove the entry and ${entry.with}, then add it again or use an extension point`,
    );
  const { resources } = sourcesOf({ base, file: entry.file });
  const files = [...new Set([...Object.keys(entry.basedOn), ...resources])];
  const basedOn = Object.fromEntries(
    files.map((file) => [file, fingerprint(fs.readFileSync(path.join(base, file)))]),
  );
  const { app } = appCode(entry.file);
  const lines = [`review of the override ${entry.file} -> ${entry.with}:`];
  const securityLines = [];
  for (const file of files) {
    const before = entry.basedOn[file];
    const after = basedOn[file];
    lines.push(
      before === undefined
        ? `  ${file}: now also based on it, ${after}`
        : before === after
          ? `  ${file}: unchanged, ${after}`
          : `  ${file}: ${before} -> ${after}`,
    );
    if (before !== undefined && before !== after) {
      const since = diffSinceRecorded({
        file,
        recorded: before,
        current: fs.readFileSync(path.join(base, file), 'utf8'),
        previous,
      });
      const head = `    the base product's diff of ${file} since the recorded fingerprint`;
      lines.push(
        ...(since.diff
          ? [
              `${head} (${previous.version} to ${version ?? 'now'}; - before, + now):`,
              ...since.diff.map((line) => `      ${line}`),
            ]
          : [`${head}: not available: ${since.unavailable}`]),
      );
      for (const fix of fixesOf(security, file, previous?.version))
        securityLines.push(`  SECURITY: ${fix.version} changed ${file}: ${fix.summary}`);
    }
    const copy = path.join(fe, overridePath(file));
    if (!fs.existsSync(copy)) continue;
    let current = fs.readFileSync(path.join(base, file), 'utf8');
    // The copy's imports are the alias; the base file's are written the same way, so the diff
    // shows only what the country changed.
    if (file === entry.file && SCRIPT.test(file)) current = rewriteImports(current, { app, file });
    const diff = lineDiff(current, fs.readFileSync(copy, 'utf8'));
    lines.push(
      diff.length
        ? `    ${overridePath(file)} against the base product's current file (- base, + copy):`
        : `    ${overridePath(file)} is the base product's current file`,
      ...diff.map((line) => `      ${line}`),
    );
  }
  if (securityLines.length)
    lines.push(
      'SECURITY: a security fix changed a file this override copies; merge it into the copy:',
      ...securityLines,
    );
  lines.push(`recorded the current fingerprints for ${entry.file}`);
  return { entry: { ...entry, basedOn }, lines };
}

/** The base product's spec beside an overridden file, `<dir>/<stem>.spec.ts`, where it exists. */
export function baseSpec(base, file) {
  const spec = file.replace(/\.(ts|mjs|js|json)$/, '.spec.ts');
  return spec !== file && fs.existsSync(path.join(base, spec)) ? spec : undefined;
}

/** The base product's specs of every overridden file, each once. */
export const baseSpecs = (base, overrides) => [
  ...new Set((overrides ?? []).map((entry) => baseSpec(base, entry.file)).filter(Boolean)),
];

/**
 * Every override against the base product set-up builds with: `{ errors, lines, findings }`. An
 * override fails when its file left the base product, joined the protected list or is a seam's
 * file (generated seams included), when its copy or a resource the copy names is missing, and
 * when a file it was based on changed or went — then
 * the country compares and runs `--accept-override`; a changed file a security fix newer than
 * `since` names fails with SECURITY first (`since`: the version of the last set-up record, as
 * the upgrade report filters the fixes; none without a record). `lines` is the list set-up
 * prints, as it prints the escape hatch. `findings` holds, for the upgrade report, each error as
 * `{ file, kind, error }`: `file` the override's, `error` its line in `errors` and `kind` one of
 * `removed`, `protected`, `seam`, `copy` (the copy is missing), `resource` (a resource the copy
 * names is missing), `security` (with the fix's `version` and the `changed` file it names) and
 * `changed` (a file the copy was based on changed or went).
 */
export function checkOverrides({ fe, base, manifest, seams, security, include, since }) {
  const errors = [];
  const lines = [];
  const findings = [];
  const overrides = manifest.overrides ?? [];
  const shipped = compileList(include);
  for (const entry of overrides) {
    const at = `override ${entry.file}`;
    const accept = `\`node setup.mjs --accept-override ${entry.file}\``;
    const fail = (kind, error, extra = {}) => {
      errors.push(error);
      findings.push({ file: entry.file, kind, error, ...extra });
    };
    if (!fs.existsSync(path.join(base, entry.file)) || !shipped(entry.file)) {
      fail(
        'removed',
        `${at}: removed from the base product; remove the entry and ${entry.with}, or ask the base product for a hook`,
      );
      continue;
    }
    if (protectedMatch(seams.protected, entry.file))
      fail('protected', `${at}: joined the protected list: replace it with an extension point`);
    // A seam's file is replaced by the country's seam file; an override of it would win silently.
    const seam = seamFiles(seams).get(entry.file);
    if (seam)
      fail(
        'seam',
        `${at}: is the seam ${seam.id}: fill the seam instead${seam.country ? `, at ${seam.country}` : ''}`,
      );
    const copy = path.join(fe, entry.with);
    if (!fs.existsSync(copy)) fail('copy', `${at}: its copy ${entry.with} does not exist`);
    else if (SCRIPT.test(entry.with))
      for (const resource of componentResources(fs.readFileSync(copy, 'utf8'))) {
        const resolved = resolveFrom(entry.with, resource);
        if (!fs.existsSync(path.join(fe, resolved)))
          fail(
            'resource',
            `${at}: ${entry.with} names ${resource}, and ${resolved} does not exist`,
          );
      }
    const changed = [];
    const removed = [];
    for (const [file, recorded] of Object.entries(entry.basedOn)) {
      const where = path.join(base, file);
      if (!fs.existsSync(where)) removed.push(file);
      else if (fingerprint(fs.readFileSync(where)) !== recorded) changed.push(file);
    }
    for (const file of changed)
      for (const fix of fixesOf(security, file, since))
        fail(
          'security',
          `SECURITY: ${fix.version} fixed ${file} (${fix.summary}), which ${at} copies: merge the fix into ${entry.with}, then run ${accept}`,
          { version: fix.version, changed: file },
        );
    if (changed.length || removed.length)
      fail(
        'changed',
        `${at}: the base product ${[
          changed.length ? `changed ${changed.join(', ')}` : '',
          removed.length ? `removed ${removed.join(', ')}` : '',
        ]
          .filter(Boolean)
          .join(
            ' and ',
          )} since the copy was based on it: compare ${entry.with} with the base product's, then run ${accept}`,
      );
  }
  if (overrides.length) {
    lines.push(`the guarded overrides, niord-country.json, replace ${overrides.length} file(s):`);
    for (const entry of overrides) {
      const spec = baseSpec(base, entry.file);
      lines.push(
        `  ${entry.file} -> ${entry.with} — ${entry.reason} (added ${entry.added})${spec ? `; the base product's ${posix.basename(spec)} runs against it` : ''}`,
      );
    }
  }
  return { errors, lines, findings };
}

/** Reads the base product's security.json; a missing or malformed file fails. */
export function readSecurity(file) {
  let security;
  try {
    security = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(
      error.code === 'ENOENT'
        ? `the base product has no country-kit/security.json`
        : `country-kit/security.json: ${error.message}`,
      { cause: error },
    );
  }
  const problems = securityProblems(security);
  if (problems.length) throw new Error(`country-kit/security.json: ${problems.join('; ')}`);
  return security;
}

/** What is wrong with a security.json: its format, and each fix's version, files and summary. */
export function securityProblems(security) {
  const problems = [];
  if (security?.format !== 1) problems.push('format is not 1');
  if (!Array.isArray(security?.fixes)) return [...problems, 'fixes is not a list'];
  security.fixes.forEach((fix, i) => {
    const at = `fixes[${i}]`;
    const known = ['version', 'files', 'summary'];
    for (const key of Object.keys(fix ?? {}))
      if (!known.includes(key)) problems.push(`${at}.${key} is an unknown field`);
    if (typeof fix?.version !== 'string' || !/^\d+\.\d+\.\d+$/.test(fix.version))
      problems.push(`${at}.version is not a plain version`);
    if (
      !Array.isArray(fix?.files) ||
      !fix.files.length ||
      !fix.files.every(
        (file) =>
          typeof file === 'string' &&
          !file.includes('\\') &&
          !file.startsWith('/') &&
          file.split('/').every((part) => part && part !== '.' && part !== '..'),
      )
    )
      problems.push(`${at}.files is not a list of clean paths relative to fe/`);
    if (typeof fix?.summary !== 'string' || !fix.summary.trim())
      problems.push(`${at}.summary is empty`);
  });
  return problems;
}
