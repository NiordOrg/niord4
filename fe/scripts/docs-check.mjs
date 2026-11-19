#!/usr/bin/env node
/**
 * Documentation guard.
 *
 *   node scripts/docs-check.mjs            # errors exit 1, warnings are listed
 *   node scripts/docs-check.mjs --quiet    # errors only
 *
 * Checks the hand-written documentation (README.md, the AGENTS.md files and their one-line
 * CLAUDE.md importers, docs/**, the review skill) for what a reviewer keeps missing by eye — a
 * name that no longer exists, or a word that must not be written:
 *
 *  1. Relative markdown links that point at a file which does not exist.
 *  2. Backticked paths that do not exist, when they lead into an application folder
 *     (`ui-apps/admin/…`) or into a folder beside the applications — `styles/`, `shared-map/`,
 *     `shared-html/`, `scripts/`, `languages/` — written from the workspace or with `fe/` in
 *     front, or into the kit, `country-kit/`, written from the repository root, where the kit
 *     sits beside fe/. A backticked `fe/country-kit/…` is an error wherever it is written: the
 *     kit is not there. These are the paths a move or a rename leaves behind.
 *  3. `npm run <script>` naming a script that package.json does not have.
 *  4. `--configuration <name>`, or `-c <name>` in an `ng` command, naming a configuration that no
 *     target in angular.json has.
 *  5. An `ng` command written with arguments that does not name its project. In this workspace an
 *     unpinned `ng build` or `ng serve` stops with "Cannot determine project", and an unpinned
 *     `ng test` or `ng lint` runs both applications' targets one after the other, so a recipe
 *     without the project either fails or does the wrong thing. A bare `ng build` in prose names
 *     the command, not a recipe, and is left alone.
 *  6. Vocabulary that must not appear in forward-looking documentation: references to the
 *     rewrite process, to sub-feature tracking ids, or to documents that no longer exist. A few
 *     words are only warned about because they have legitimate domain meanings (a publication
 *     series can be served from the previous system's rows).
 *  7. A country reads the base product's documents in two packages: the kit package at
 *     `.niord/kit/` at its repository root, and the source zip at `fe/.niord/base/`. In a
 *     document the kit package carries (selected by country-kit/kit-include.json through
 *     country-kit/lib/include.mjs: the kit's README, API-CHANGES and docs/, named country-kit/…),
 *     a relative link must name a file or folder of the kit package. A base product file has no
 *     relative form across the two roots, so it is written as a backticked `fe/.niord/base/…`
 *     path. A backticked `.niord/kit/…` path (or `../.niord/kit/…`, written from fe/) must name a
 *     file of the kit package, with the two entries its build adds, docs/country-api.md and
 *     kit.json; a backticked `country-kit/…` names the kit package too. A backticked
 *     `fe/.niord/base/…` or `.niord/base/…` path, or one into a workspace folder, must name a
 *     file of the source zip (country-kit/zip-include.json's globs). A
 *     backticked `fe/.niord/kit/…` is an error: the kit is not unpacked there. In the kit's
 *     API-CHANGES.md, a section below Unreleased records a released version with that version's
 *     paths, which today's packages need not hold (4.0.87 names files the source zip carried
 *     before the kit had its own package): there these package checks of backticked paths are
 *     left out, and its links and the other rules are read as anywhere. In a document the source
 *     zip carries outside the kit, a link stays inside the zip and never leads into the kit. One
 *     the include lists leave out fails even though it exists here. In the kit's guides
 *     (country-kit/docs/), check 3 reads the scripts that country-kit/lib/workspace.mjs generates
 *     into a country's package.json, not this workspace's.
 *  8. A relative markdown link's `#fragment` (or a `#fragment` alone, within the document) into
 *     a document this guard reads — the set below, the agent template included — must name a
 *     heading of it by GitHub's rule, or an explicit `<a id="…">` or `<a name="…">` there. By
 *     that rule a heading's text keeps its inline code without the backticks, reduces links,
 *     images, HTML tags and emphasis to their text and decodes character references; it is put in
 *     lower case, every character but letters, marks, decimal digits, `_`, `-` and the space is
 *     removed, and each space becomes a hyphen; the second and later of equal slugs are given -1,
 *     -2 and so on. A fragment into a file the guard does not read, into a file that is not
 *     markdown, or in an external address is left alone; a heading inside a code fence is no
 *     heading. In API-CHANGES' released sections it applies as the links do.
 *
 * Globs, `<placeholders>` and `{a,b}` lists name a shape rather than a thing, so checks 2 to 4
 * and 7 leave them alone. Every check but 8 reads one line at a time: a code span that a line
 * break splits is not seen. Check 8 reads a link one line at a time and its target's headings
 * whole.
 *
 * The publications specifications are excluded: they are kept verbatim until go-live and are
 * guarded by scripts/publications instead. The country kit's own documents are read; its example
 * country is not, and of init's templates only the agent template (templates/AGENTS.md) is, and
 * only for its `.niord/kit/` and `.niord/base/` paths: its other links and paths are written for
 * a country repository, where they resolve.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { compileList, readIncludeList } from '../../country-kit/lib/include.mjs';
import { checkoutFiles, walkFiles } from '../../country-kit/lib/local-sources.mjs';
import { gitFiles } from '../../country-kit/lib/tree.mjs';
import { packageJson } from '../../country-kit/lib/workspace.mjs';
import { CATALOG_ENTRY, KIT_ADDED } from '../../country-kit/source-zip.mjs';
import { readAngularJson } from './workspace-paths-check.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// The agent files: AGENTS.md holds the rules, and a CLAUDE.md beside it imports them for Claude
// Code. A folder under ui-apps/ holding either is an application.
const AGENT_FILES = ['AGENTS.md', 'CLAUDE.md'];

const EXCLUDED = [/^docs\/publications\/(DATA-MODEL|API-CONTRACT|COMPILATION-SERIES-PLAN)\.md$/];

// The folders beside the applications that the documentation names by path: the three both
// applications build from, the workspace's own scripts and the language packs. The country kit,
// beside fe/, is the second root, KIT_FOLDER.
const WORKSPACE_FOLDERS = ['styles', 'shared-map', 'shared-html', 'scripts', 'languages'];

// The country kit's folders that hold a country repository's files, not this workspace's
// documentation: init's templates and the example country.
const KIT_COUNTRY_FOLDERS = ['templates', 'example'];

// The kit's guides, written for a country maintainer: an `npm run` there names a country
// workspace's script.
const KIT_GUIDES = 'country-kit/docs/';

// The kit's folder, the second root beside fe/, as the base product's tree and the documentation
// name it.
const KIT_FOLDER = 'country-kit/';

// The kit's change log, whose sections below Unreleased record released versions.
const API_CHANGES = 'country-kit/API-CHANGES.md';

// The templates that are read after all, for their `.niord/kit/` and `.niord/base/` paths only:
// the agent template, which names the kit's and the base product's files as a country sees them.
const KIT_TEMPLATE_DOCUMENTS = ['country-kit/templates/AGENTS.md'];

// A path into the base product as a country's workspace holds it, written from the workspace or
// from the repository.
const BASE_PATH = /^(?:fe\/)?\.niord\/base\//;

// A path into the kit package as a country holds it, written from the repository or from fe/.
const KIT_PATH = /^(?:\.\.\/)?\.niord\/kit\//;

// The likeliest wrong path into the kit: a country unpacks it at the repository root, not in fe/.
const MISPLACED_KIT = /^fe\/\.niord\/kit\//;

// Folders never walked when the kit's folder has no git, as for the source zip's local copy.
const UNWALKED = new Set(['node_modules', '.niord', 'dist', '.angular', 'coverage', '.git']);

// The `ng` commands that run a project's target, and so need to be told which project.
const NG_TARGET_COMMANDS = ['build', 'serve', 'test', 'lint', 'e2e', 'extract-i18n', 'deploy'];

const ERROR_PATTERNS = [
  [/\breplatform/i, 'rewrite-process wording'],
  [
    /\bmigration\b(?![^\n]*\b(flyway|schema|V\d+__|database)\b)/i,
    'rewrite-process wording (schema/Flyway migrations are fine)',
  ],
  [/\bbatch(es)?\s?\d+\b/i, 'batch tracking reference'],
  [/\bPhase\s?Z\b/i, 'batch process reference'],
  [/\b(MSG|EDITOR|ADMIN|SCHED|AUTH)-\d{3}\b/, 'sub-feature tracking id'],
  [/migration_docs/, 'link into the removed migration folder'],
  [
    /\.claude[\\/](AGENTS|ai-agent-playbook|ai-prompts|angular-guidelines|coding-standards|typescript-guidelines|ui-standards|styling-reference|frontend-architecture|country-specific-deployment-strategy|definition-of-done|niord-migration-checklist|feature-inventory-template)/,
    'link to a removed .claude document',
  ],
  [/niord-(implement|legacy-analysis|phase-z|batch-implementation-plan)\b/, 'removed skill'],
  [/\bdiscrepancy[- ]log\b/i, 'removed log'],
  [/\benhancements-over-legacy\b/i, 'removed list'],
];

const WARN_PATTERNS = [
  [/\blegacy\b/i, '"legacy": allowed only for the previous-system rows'],
  [/\bPrimeNG\b/, 'PrimeNG: allowed only inside a "do not add" rule'],
  [
    /\b(ruling|decision)\s+[A-Z]?-?\d+\b|\bR-[xvi]+\b|\bD\d{1,2}\b\s*\|/,
    'ruling / decision id — restate as a rule instead',
  ],
  [
    /\b(HANDOVER|WORK-QUEUE|RULINGS-B|DECISIONS-2026|CUTOFF-MODEL|LEDGER)\b/,
    'name of a merged source document',
  ],
  [/\bTODO\b|\bFIXME\b/, 'open marker'],
];

function walk(dir, out = [], skip = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!skip.includes(entry.name) && entry.name !== 'node_modules') walk(p, out);
    } else if (entry.name.endsWith('.md')) out.push(p);
  }
  return out;
}

/**
 * What the documentation may name: the application folders, the npm scripts, the Angular projects
 * and every configuration a target of theirs declares.
 *
 * The applications live one folder each under `ui-apps/`, each with its own AGENTS.md (or a
 * CLAUDE.md) beside its sources; everything else at the workspace root is shared by both or
 * belongs to the workspace.
 * An application is therefore named by its path from here — `ui-apps/admin` — which is how the
 * documentation spells it and what the backticked-path check matches against.
 *
 * `kit` is the country kit's folder, beside `root` (null when there is none). With the kit, `zip`
 * is the source zip as the documents it carries are read: `carries`, the include list as a
 * predicate over paths as the zip names them; `files`, what the zip holds (the checkout's files
 * the list selects, as kit:zip and set-up's local copy take them, and the generated catalog);
 * `folders`, every folder that holds one of them. `kitPackage` is the kit package in the same
 * shape, its paths named from the kit's folder: kit-include.json as `carries`, and as `files` the
 * kit's files the list selects (git's list, or a walk where the folder has no git) with the two
 * entries the build adds. `countryScripts` is the script names of the package.json the kit
 * generates for a country. Without the kit, or without its include list, each is null.
 */
export function readWorkspace(root = ROOT, kit = path.join(root, '..', 'country-kit')) {
  const APP_ROOT = 'ui-apps';
  const apps = fs
    .readdirSync(path.join(root, APP_ROOT), { withFileTypes: true })
    .filter(
      (e) =>
        e.isDirectory() &&
        AGENT_FILES.some((name) => fs.existsSync(path.join(root, APP_ROOT, e.name, name))),
    )
    .map((e) => `${APP_ROOT}/${e.name}`);
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  const angular = readAngularJson(path.join(root, 'angular.json'));
  const configurations = new Set();
  for (const project of Object.values(angular.projects))
    for (const target of Object.values(project.architect ?? {}))
      for (const name of Object.keys(target.configurations ?? {})) configurations.add(name);
  let zip = null;
  let kitPackage = null;
  let countryScripts = null;
  const kitFolder = fs.existsSync(kit) ? path.resolve(kit) : null;
  const includeFile = kitFolder && path.join(kitFolder, 'zip-include.json');
  if (includeFile && fs.existsSync(includeFile)) {
    const list = readIncludeList(includeFile);
    const files = new Set([...checkoutFiles(root, list).map(({ name }) => name), CATALOG_ENTRY]);
    zip = { carries: compileList(list), files, folders: foldersOf(files) };
    const example = path.join(kitFolder, 'example', 'fe');
    countryScripts = new Set(
      Object.keys(packageJson({ fe: example, cc: 'xx', basePackage: pkg }).scripts),
    );
  }
  const kitIncludeFile = kitFolder && path.join(kitFolder, 'kit-include.json');
  if (kitIncludeFile && fs.existsSync(kitIncludeFile)) {
    const carries = compileList(readIncludeList(kitIncludeFile));
    const files = new Set([...kitFiles(kitFolder).filter(carries), ...Object.values(KIT_ADDED)]);
    kitPackage = { carries, files, folders: foldersOf(files) };
  }
  return {
    root,
    kit: kitFolder,
    apps,
    scripts: new Set(Object.keys(pkg.scripts ?? {})),
    projects: new Set(Object.keys(angular.projects)),
    configurations,
    zip,
    kitPackage,
    countryScripts,
  };
}

/** The kit folder's files, relative and with `/`: git's list, or a walk where it has no git. */
function kitFiles(kitFolder) {
  try {
    return gitFiles(kitFolder);
  } catch {
    return walkFiles(kitFolder, { skip: UNWALKED });
  }
}

/** Every folder that holds one of `files`, named as they are. */
function foldersOf(files) {
  const folders = new Set();
  for (const file of files)
    for (
      let folder = path.posix.dirname(file);
      folder !== '.' && !folders.has(folder);
      folder = path.posix.dirname(folder)
    )
      folders.add(folder);
  return folders;
}

/**
 * The file a name stands for, a document's or a backticked path's: a name under country-kit/ is
 * the kit's, beside the workspace; any other is the workspace's own.
 */
export function fileOf(workspace, rel) {
  return workspace.kit && rel.startsWith(KIT_FOLDER)
    ? path.join(workspace.kit, rel.slice(KIT_FOLDER.length))
    : path.join(workspace.root, rel);
}

/**
 * The hand-written documentation the guard reads, by name: the workspace's relative to it, the
 * kit's as country-kit/…, its name in the zip.
 */
export function documentationFiles(workspace) {
  const { root, kit, apps } = workspace;
  const named = (folder, prefix) => (file) =>
    `${prefix}${path.relative(folder, file).replaceAll('\\', '/')}`;
  return [
    ...[
      path.join(root, 'README.md'),
      ...AGENT_FILES.flatMap((name) => [
        path.join(root, name),
        ...apps.map((app) => path.join(root, app, name)),
      ]),
      ...walk(path.join(root, 'docs')),
      ...(fs.existsSync(path.join(root, '.claude', 'skills'))
        ? walk(path.join(root, '.claude', 'skills')).filter((f) => f.includes('niord-review'))
        : []),
    ]
      .filter((f) => fs.existsSync(f))
      .map(named(root, '')),
    ...(kit ? walk(kit, [], KIT_COUNTRY_FOLDERS).map(named(kit, KIT_FOLDER)) : []),
  ].filter((rel) => !EXCLUDED.some((re) => re.test(rel)));
}

/**
 * A backticked path this guard resolves, as a path from the workspace, or under country-kit/ as
 * a path into the kit beside it — or null.
 */
function checkedPath(written, workspace) {
  const target = written.replace(/^fe\//, '');
  const folders = [...workspace.apps, ...WORKSPACE_FOLDERS];
  if (!folders.some((folder) => target.startsWith(folder + '/')) && !target.startsWith(KIT_FOLDER))
    return null;
  if (/[*<>{}]/.test(target)) return null;
  return target.replace(/[.,;:)]+$/, '');
}

/**
 * Whether a path names a file or a folder of a package, the source zip or the kit package, as
 * the package names it; '' is its root.
 */
function inSet(relative, set) {
  const r = relative.replace(/\/$/, '');
  return r === '' || set.files.has(r) || set.folders.has(r);
}

/** The `ng` command a code span or a line of a code block runs, when it runs a target. */
function ngRecipe(code, workspace) {
  const m = code.match(/^\s*(?:npx\s+)?ng\s+([a-z0-9-]+)(?:\s+(.*))?$/);
  if (!m || !NG_TARGET_COMMANDS.includes(m[1]) || !m[2]?.trim()) return null;
  const first = m[2].trim().split(/\s+/)[0];
  return workspace.projects.has(first) ? null : `ng ${m[1]}`;
}

/**
 * A backticked `.niord/base/…` or `.niord/kit/…` path as a path of its package, with the prefix
 * `prefix` matches taken off, or null for a shape.
 */
function packagePath(written, prefix) {
  const target = written.replace(prefix, '');
  if (/[*<>{}]/.test(target)) return null;
  return target.replace(/[.,;:)]+$/, '');
}

// A backslash before an ASCII punctuation character escapes it in markdown.
const ESCAPED = /\\([!-/:-@[-`{-~])/g;

// While a heading's text is reduced, an escaped character stands in the private use area, out of
// reach of the patterns that read markdown, and is put back after: U+E021 to U+E07E.
const SHELTERED = 0xe000;
const SHELTERED_CHARACTER = /[\ue021-\ue07e]/g;

// A code span stands in the private use area from U+E100 on, one character each, while the rest
// of a heading is reduced, so a link whose text is code keeps it.
const CODE_SPAN = 0xe100;
const CODE_SPAN_CHARACTER = /[\ue100-\uf8ff]/g;

// Character references, decoded as GitHub renders them: the five named ones and numeric ones.
const ENTITY = /&(?:(amp|lt|gt|quot|apos)|#(\d{1,7})|#[xX]([0-9a-fA-F]{1,6}));/g;
const NAMED_ENTITY = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

/** The character a character reference stands for. */
function entityText(_, name, decimal, hex) {
  if (name) return NAMED_ENTITY[name];
  const point = decimal ? Number.parseInt(decimal, 10) : Number.parseInt(hex, 16);
  return String.fromCodePoint(point === 0 || point > 0x10ffff ? 0xfffd : point);
}

/**
 * A heading's text, its code spans standing in for themselves, as GitHub renders it: links and
 * images reduced to their text, HTML tags dropped, emphasis delimiters dropped (an underscore
 * inside a word is no delimiter), character references decoded and backslash escapes resolved.
 */
function proseText(raw) {
  let text = raw
    .replace(ESCAPED, (_, c) => String.fromCharCode(SHELTERED + c.charCodeAt(0)))
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<\/?[A-Za-z][^>]*>/g, '');
  let before;
  do {
    before = text;
    text = text
      .replace(/(\*\*?)(?=\S)(.+?)(?<=\S)\1/g, '$2')
      .replace(/(?<![\p{L}\p{N}_])(__?)(?=\S)(.+?)(?<=\S)\1(?![\p{L}\p{N}_])/gu, '$2');
  } while (text !== before);
  return text
    .replace(ENTITY, entityText)
    .replace(SHELTERED_CHARACTER, (c) => String.fromCharCode(c.charCodeAt(0) - SHELTERED));
}

/** The length of the run of backticks at `i`. */
function backticksAt(raw, i) {
  let end = i;
  while (raw[end] === '`') end++;
  return end - i;
}

/**
 * A heading's text as GitHub renders it, which its slug is made from. Its code spans are read
 * first, as CommonMark reads them — a run of backticks closed by the next run of the same length,
 * one space stripped from each side when both have one — and keep their text as written, without
 * the backticks; each stands aside as one character while the rest is reduced, so `<version>` in
 * code stays and a link whose text is code keeps that text.
 */
function headingText(raw) {
  const codes = [];
  let prose = '';
  let i = 0;
  while (i < raw.length) {
    if (raw[i] === '\\' && i + 1 < raw.length && /[!-/:-@[-`{-~]/.test(raw[i + 1])) {
      prose += raw.slice(i, i + 2);
      i += 2;
      continue;
    }
    if (raw[i] !== '`') {
      prose += raw[i++];
      continue;
    }
    const open = backticksAt(raw, i);
    let close = i + open;
    while (close < raw.length && !(raw[close] === '`' && backticksAt(raw, close) === open))
      close += raw[close] === '`' ? backticksAt(raw, close) : 1;
    if (close >= raw.length) {
      prose += raw.slice(i, i + open);
      i += open;
      continue;
    }
    let code = raw.slice(i + open, close);
    if (code.length > 1 && code.startsWith(' ') && code.endsWith(' ') && /[^ ]/.test(code))
      code = code.slice(1, -1);
    prose += String.fromCharCode(CODE_SPAN + codes.length);
    codes.push(code);
    i = close + open;
  }
  return proseText(prose)
    .replace(CODE_SPAN_CHARACTER, (c) => codes[c.charCodeAt(0) - CODE_SPAN] ?? c)
    .trim();
}

/**
 * The fragment GitHub gives a heading's text: lower case, every character but letters, marks,
 * decimal digits, `_`, `-` and the space removed, and each space a hyphen.
 */
function githubSlug(text) {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{Nd}\p{Pc} -]/gu, '')
    .replace(/ /g, '-');
}

/**
 * The fragments a document answers to: its headings' slugs in order, the second and later of
 * equal ones given -1, -2, … as GitHub does, then its explicit `<a id>` and `<a name>` anchors.
 * Nothing inside a code fence counts.
 */
function anchorsOf(text) {
  const headings = [];
  const explicit = [];
  const occurrences = new Map();
  let fence = null;
  for (const line of text.split(/\r?\n/)) {
    const fenceMark = line.match(/^\s*(```|~~~)/)?.[1];
    if (fence) {
      if (fenceMark === fence) fence = null;
      continue;
    }
    if (fenceMark) {
      fence = fenceMark;
      continue;
    }
    const heading = line.match(/^ {0,3}#{1,6}(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$/);
    if (heading) {
      const base = githubSlug(headingText(heading[1] ?? ''));
      let slug = base;
      while (occurrences.has(slug)) {
        occurrences.set(base, occurrences.get(base) + 1);
        slug = `${base}-${occurrences.get(base)}`;
      }
      occurrences.set(slug, 0);
      if (slug) headings.push(slug);
    }
    for (const m of line.matchAll(/<a\s[^>]*?(?<![\w-])(?:id|name)\s*=\s*["']([^"']+)["']/gi))
      explicit.push(m[1]);
  }
  return [...new Set([...headings, ...explicit])];
}

/** The edit distance between two strings. */
function levenshtein(a, b) {
  let previous = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++)
      current[j] = Math.min(
        previous[j] + 1,
        current[j - 1] + 1,
        previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    previous = current;
  }
  return previous[b.length];
}

/** Up to three of `names` nearest to `fragment`, ties in the document's order. */
function nearestAnchors(fragment, names) {
  return names
    .map((name, order) => ({ name, order, distance: levenshtein(fragment, name) }))
    .sort((x, y) => x.distance - y.distance || x.order - y.order)
    .slice(0, 3)
    .map(({ name }) => name);
}

// Per workspace: the documents the guard reads, as absolute paths, and the fragments each answers
// to, read once.
const readSets = new WeakMap();
const targetAnchors = new WeakMap();

/** Whether a file is one of the documents the guard reads. */
function isRead(workspace, file) {
  if (!readSets.has(workspace))
    readSets.set(
      workspace,
      new Set(checkedFiles(workspace).map((rel) => path.resolve(fileOf(workspace, rel)))),
    );
  return readSets.get(workspace).has(file);
}

/** The fragments a document of the guard's answers to, read from disk once per workspace. */
function anchorsOfFile(workspace, file) {
  if (!targetAnchors.has(workspace)) targetAnchors.set(workspace, new Map());
  const cache = targetAnchors.get(workspace);
  if (!cache.has(file)) cache.set(file, anchorsOf(fs.readFileSync(file, 'utf8')));
  return cache.get(file);
}

/**
 * What is wrong with a link's `#fragment`, or null: a fragment names a heading or an explicit
 * anchor of its target when that is a document the guard reads, or the document itself, `own`,
 * whose fragments `ownAnchors` reads from the text being checked.
 */
function brokenAnchor(target, workspace, own, ownAnchors) {
  const hash = target.indexOf('#');
  if (hash < 0 || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(target)) return null;
  let fragment = target.slice(hash + 1);
  if (!fragment) return null;
  try {
    fragment = decodeURIComponent(fragment);
  } catch {
    // A malformed escape is compared as written.
  }
  const file = target.slice(0, hash) ? path.resolve(path.dirname(own), target.slice(0, hash)) : own;
  let names;
  if (file === own) names = ownAnchors();
  else if (file.endsWith('.md') && isRead(workspace, file) && fs.existsSync(file))
    names = anchorsOfFile(workspace, file);
  else return null;
  if (names.includes(fragment)) return null;
  const hint = names.length
    ? `nearest: ${nearestAnchors(fragment, names)
        .map((name) => `#${name}`)
        .join(', ')}`
    : 'the target has no headings';
  return `broken anchor: ${target} (${hint})`;
}

/**
 * Checks one document. `rel` is its path from the workspace, which relative links resolve
 * against; `text` is its content. Which package carries the document, the kit package or the
 * source zip, is decided by its path, so a crafted document counts as the file it would be.
 */
export function checkDocument(rel, text, workspace) {
  rel = rel.replaceAll('\\', '/');
  const { zip, kitPackage } = workspace;
  const template = KIT_TEMPLATE_DOCUMENTS.includes(rel);
  // The document's name in the kit package, when it is one of the kit's.
  const kitName = rel.startsWith(KIT_FOLDER) ? rel.slice(KIT_FOLDER.length) : null;
  const inKit = !template && kitName !== null && Boolean(kitPackage?.carries(kitName));
  const inSource = !template && kitName === null && Boolean(zip?.carries(rel));
  const guide = rel.startsWith(KIT_GUIDES) && workspace.countryScripts !== null;
  const changes = rel === API_CHANGES;
  const errors = [];
  const warnings = [];
  const lines = text.split(/\r?\n/);
  let inFence = false;
  // In the kit's API-CHANGES.md: past '## Unreleased', and then past the next heading, every line
  // is a released version's record.
  let unreleased = false;
  let released = false;
  // The document's own file, and the fragments its text answers to, for check 8.
  const own = path.resolve(fileOf(workspace, rel));
  let anchors = null;
  const ownAnchors = () => (anchors ??= anchorsOf(text));
  lines.forEach((line, i) => {
    const fenceLine = /^\s*```/.test(line);
    if (fenceLine) inFence = !inFence;
    const where = `${rel}:${i + 1}`;
    if (changes && !inFence && line.startsWith('## ')) {
      if (unreleased) released = true;
      else if (/^## Unreleased\s*$/.test(line)) unreleased = true;
    }

    for (const m of line.matchAll(/`([A-Za-z0-9_.-]+\/[^`\s]*)`/g)) {
      if (m[1].startsWith(`fe/${KIT_FOLDER}`)) {
        errors.push(`${where}  the kit is at country-kit/, beside fe/: ${m[1]}`);
        continue;
      }
      if ((inKit || template) && MISPLACED_KIT.test(m[1])) {
        errors.push(
          `${where}  the kit unpacks at .niord/kit/ at the repository root, not in fe/: ${m[1]}`,
        );
        continue;
      }
      // A released version's paths are that version's packages', not today's.
      if (released) continue;
      if (kitPackage && (inKit || template) && KIT_PATH.test(m[1])) {
        const target = packagePath(m[1], KIT_PATH);
        if (target !== null && !inSet(target, kitPackage))
          errors.push(`${where}  not in the kit package: ${m[1]}`);
        continue;
      }
      if (zip && (inKit || inSource || template) && BASE_PATH.test(m[1])) {
        const target = packagePath(m[1], BASE_PATH);
        if (target !== null && !inSet(target, zip))
          errors.push(`${where}  not in the source zip: ${m[1]}`);
        continue;
      }
      if (template) continue;
      const target = checkedPath(m[1], workspace);
      if (target === null) continue;
      if (inKit && target.startsWith(KIT_FOLDER)) {
        if (!inSet(target.slice(KIT_FOLDER.length), kitPackage))
          errors.push(`${where}  not in the kit package: ${m[1]}`);
      } else if (inKit || inSource) {
        if (zip && !inSet(target, zip)) errors.push(`${where}  not in the source zip: ${m[1]}`);
      } else if (!fs.existsSync(fileOf(workspace, target)))
        errors.push(`${where}  path does not exist: ${m[1]}`);
    }
    // The agent template's other paths, its links and its scripts are a country repository's.
    if (template) return;

    for (const [re, why] of ERROR_PATTERNS)
      if (re.test(line)) errors.push(`${where}  ${why}: ${line.trim().slice(0, 120)}`);
    if (!inFence)
      for (const [re, why] of WARN_PATTERNS)
        if (re.test(line)) warnings.push(`${where}  ${why}: ${line.trim().slice(0, 120)}`);

    // A guide names a country workspace's scripts only, so a base product script fails there too.
    const scripts = guide ? workspace.countryScripts : workspace.scripts;
    for (const m of line.matchAll(/\bnpm run ([A-Za-z0-9:_.-]+)(?![<{*A-Za-z0-9:_.-])/g)) {
      const script = m[1].replace(/[.:]+$/, '');
      if (!scripts.has(script))
        errors.push(
          `${where}  no npm script of that name${guide ? ' in a country workspace' : ''}: npm run ${script}`,
        );
    }

    for (const m of line.matchAll(
      /(?:--configuration[= ]|\bng\s+[a-z0-9-]+\b[^`\n]*?\s-c\s+)([A-Za-z0-9_,-]+)(?![<{*A-Za-z0-9_.,-])/g,
    ))
      for (const name of m[1].split(',').filter(Boolean))
        if (!workspace.configurations.has(name))
          errors.push(`${where}  no configuration of that name in angular.json: ${name}`);

    const code = inFence && !fenceLine ? [line] : [...line.matchAll(/`([^`]+)`/g)].map((m) => m[1]);
    for (const span of code) {
      const recipe = ngRecipe(span, workspace);
      if (recipe)
        errors.push(`${where}  \`${recipe}\` without its project: ${span.trim().slice(0, 100)}`);
    }

    for (const m of line.matchAll(/\[[^\]]*\]\(([^)\s]+)\)/g)) {
      const target = m[1];
      const anchor = brokenAnchor(target, workspace, own, ownAnchors);
      if (anchor) errors.push(`${where}  ${anchor}`);
      if (/^(https?:|mailto:|#)/.test(target)) continue;
      const clean = target.split('#')[0];
      if (!clean) continue;
      if (inKit) {
        const r = path.posix.normalize(path.posix.join(path.posix.dirname(kitName), clean));
        if (r === '..' || r.startsWith('../') || !inSet(r, kitPackage))
          errors.push(
            `${where}  link leaves the kit package (a base product file is written as a backticked fe/.niord/base/ path): ${target}`,
          );
        continue;
      }
      if (inSource) {
        const r = path.posix.normalize(path.posix.join(path.posix.dirname(rel), clean));
        if (r.replace(/\/$/, '') === KIT_FOLDER.slice(0, -1) || r.startsWith(KIT_FOLDER))
          errors.push(
            `${where}  link into the kit package, which a country unpacks at .niord/kit/, outside the source zip: ${target}`,
          );
        else if (r === '..' || r.startsWith('../') || !inSet(r, zip))
          errors.push(`${where}  link leaves the source zip: ${target}`);
        continue;
      }
      const resolved = path.resolve(path.dirname(fileOf(workspace, rel)), clean);
      if (!fs.existsSync(resolved)) errors.push(`${where}  broken link: ${target}`);
    }
  });
  return { errors, warnings };
}

/**
 * The documents the guard reads, by name: the documentation set, and the agent template for its
 * .niord/kit/ and .niord/base/ paths. Check 8's targets are the same list.
 */
function checkedFiles(workspace) {
  return [
    ...documentationFiles(workspace),
    ...KIT_TEMPLATE_DOCUMENTS.filter((rel) => fs.existsSync(fileOf(workspace, rel))),
  ];
}

/** The whole documentation set, and the agent template for its .niord/kit/ and .niord/base/ paths. */
export function checkDocumentation(workspace = readWorkspace()) {
  const files = checkedFiles(workspace);
  const errors = [];
  const warnings = [];
  for (const rel of files) {
    const result = checkDocument(rel, fs.readFileSync(fileOf(workspace, rel), 'utf8'), workspace);
    errors.push(...result.errors);
    warnings.push(...result.warnings);
  }
  return { files, errors, warnings };
}

// Run as a script rather than imported by its test. Node reports the main module by its real
// path, so both sides are resolved through links: compared as typed, a run through a symlinked
// or junctioned folder would look like an import, check nothing and exit 0.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const quiet = process.argv.includes('--quiet');
  const { files, errors, warnings } = checkDocumentation();
  if (!quiet && warnings.length) {
    console.log(`${warnings.length} warning(s):`);
    for (const w of warnings) console.log('  ' + w);
  }
  if (errors.length) {
    console.error(`\n${errors.length} error(s):`);
    for (const e of errors) console.error('  ' + e);
    process.exit(1);
  }
  console.log(
    `docs-check: ${files.length} files, no errors${warnings.length ? `, ${warnings.length} warning(s)` : ''}.`,
  );
}
