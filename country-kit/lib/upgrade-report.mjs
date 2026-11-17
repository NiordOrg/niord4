/**
 * The upgrade report, `.niord/niord-upgrade-report.md` (fe-country-split-plan §4.12): what a new
 * base product version means for this country, on one page and in §4.12's order — the versions
 * crossed; what to do, which set-up or the build fails on until it is done; what to look at
 * (warnings, and what API-CHANGES.md asks of a country, which set-up does not check);
 * what is new for the country; and the dependency changes. Set-up writes it for every version
 * change, after its checks and also when they fail, since a red bump is when it is needed; a
 * country pipeline's bump pull request carries it. Every item names the file and the action.
 *
 * The same findings are also written as data, `.niord/niord-upgrade-report.json`
 * (`upgradeReportData`), for a workflow to read: the sections with their actions in full, the new
 * fixes, the To-do and To-look-at items, the tracked kit templates changed since the previous kit
 * and every other template that changed, and the keys a foreign pack lacks. Without the previous version's files (no zip, or no set-up record and
 * `--previous <version>` instead) the sections and fixes are compared by version.
 *
 * It is made from structured inputs, never from the checks' message texts: the findings set-up's
 * checks return (pack.mjs checkPacks, lib/text-overrides.mjs, lib/dependencies.mjs, lib/api.mjs
 * deprecatedUsesOf, the withdrawn pins set-up's pull found), the manifest's overrides against the base product, the country's branding
 * files against branding.schema.json, and the base product's own files beside the previous
 * version's (lib/previous-base.mjs): the kit's API-CHANGES.md, security.json and seams.json, the
 * four API files and package.json. The kit's files are read from `current.kit` and from the
 * previous version's kit zip, and the report names them `.niord/kit/<file>`, from the repository
 * root. Each structured finding a To-do item is made from carries the line its check put in
 * `checks.errors`, and To do lists, as set-up printed it, every line no item accounts for, so a
 * failed check always reaches To do.
 *
 * What this kit version cannot know is said plainly, not guessed: the release notes (the zip has
 * none; API-CHANGES.md and security.json stand in), the keys the base product added to its
 * settings file and the settings keys that left the catalog (the zip carries no backend settings
 * list) and the advisories (`npm run build`'s audit, after `npm ci`). A section that needs the previous version's files says once why
 * they are not at hand. Node built-ins only: set-up runs before `npm ci`.
 */
import fs from 'node:fs';
import path from 'node:path';
import { flatten } from '../stage-languages.mjs';
import { readApi } from './api.mjs';
import { diffSinceRecorded, fingerprint, protectedMatch } from './overrides.mjs';
import { compare, parseVersion } from './semver.mjs';
import { parseJsonc } from './workspace.mjs';

export const REPORT = '.niord/niord-upgrade-report.md';
/** The report's data, beside it: the shape `upgradeReportData` gives, format 1. */
export const REPORT_DATA = '.niord/niord-upgrade-report.json';
/** The internal key of a kit file in files.now/files.before and previous-base's map. */
const KIT_PREFIX = 'country-kit/';
const CHANGES = 'country-kit/API-CHANGES.md';
/**
 * The kit's stub template, relative to the country's fe/: what a country copies when the stub
 * changed. A set-up from a base product checkout copies the kit into ../.niord/kit/ as the kit
 * package unpacks, so it is there in every layout.
 */
export const STUB_TEMPLATE = '../.niord/kit/templates/setup.mjs';
/** The pins of the foreign packs, relative to the country's fe/. */
const LOCK = 'niord-pins.lock.json';
const SECURITY = 'country-kit/security.json';
const SEAMS = 'country-kit/seams.json';
const BRANDING = 'country-kit/branding.schema.json';
/** The names the report prints for the kit's files: where a country has them, from its root. */
const CHANGES_LABEL = '.niord/kit/API-CHANGES.md';
const SECURITY_LABEL = '.niord/kit/security.json';
const SEAMS_LABEL = '.niord/kit/seams.json';
const APPS = ['admin', 'info'];
const COUNTRY_NAMESPACE = 'country.';
const FRAMEWORK = '@angular/';
const MAX_LISTED = 20;
const MAX_DIFF_LINES = 40;
/**
 * The kit templates a country keeps a copy of, compared with the previous kit's, in the order the
 * report names them: the template, whether the country copies it over its file or merges it into
 * it, that file, from the repository root, and, for a template that is one part of the file, which
 * part. init assembles `.github/workflows/image.yml` from the image template and its two job
 * templates, and writes `.github/workflows/ci.yml` from the ci template with the country's code
 * filled in, so a changed ci template is a merge. The stub, templates/setup.mjs, is not here: a
 * changed stub is a failed check and a To-do item of its own. Every other file of the folder that
 * changed is named only in one line, with no action (`otherTemplatesChanged`).
 */
const TEMPLATES = [
  ['AGENTS.md', 'merge', 'AGENTS.md'],
  ['base-bump.yml', 'copy', '.github/workflows/base-bump.yml'],
  ['ci.yml', 'merge', '.github/workflows/ci.yml'],
  ['image.yml', 'merge', '.github/workflows/image.yml'],
  ['image-backend-job.yml', 'merge', '.github/workflows/image.yml', 'the backend job of'],
  ['image-frontend-job.yml', 'merge', '.github/workflows/image.yml', 'the frontend job of'],
  ['pack.yml', 'copy', '.github/workflows/pack.yml'],
  ['start-local.sh', 'copy', 'start-local.sh'],
];
/** The kit's templates, from the repository root. */
const TEMPLATES_DIR = '.niord/kit/templates';
/**
 * What the line of the other templates leaves out: the eight above, and the stub, whose change is
 * a To-do item of its own.
 */
const TRACKED = new Set([...TEMPLATES.map(([name]) => name), 'setup.mjs']);

const code = (text) => `\`${text}\``;
const listed = (items) =>
  items.length > MAX_LISTED
    ? `${items.slice(0, MAX_LISTED).map(code).join(', ')} and ${items.length - MAX_LISTED} more`
    : items.map(code).join(', ');
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
const toPosix = (p) => p.replaceAll('\\', '/');
const parse = (text) => {
  if (text === undefined) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
};

/** Whether `version` came after `than`; Unreleased is the newest, and an unreadable one counts. */
function newerThan(version, than) {
  if (version === 'Unreleased') return true;
  try {
    return compare(parseVersion(version), parseVersion(than)) > 0;
  } catch {
    return true;
  }
}

/**
 * The version sections of an API-CHANGES.md text: `[{ heading, version, body }]` per `## ` heading
 * whose first word is `Unreleased` or a version; the policy sections are left out.
 */
export function changeSections(text) {
  const sections = [];
  let open;
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    const heading = /^## (.+?)\s*$/.exec(line);
    if (heading) {
      open = { heading: heading[1], lines: [] };
      sections.push(open);
    } else if (/^# /.test(line)) open = undefined;
    else open?.lines.push(line);
  }
  return sections
    .map(({ heading, lines }) => {
      const word = heading.split(/[\s:]/)[0];
      let version;
      if (/^unreleased$/i.test(word)) version = 'Unreleased';
      else
        try {
          parseVersion(word);
          version = word;
        } catch {
          version = undefined;
        }
      return { heading, version, body: lines.join('\n').trim() };
    })
    .filter((section) => section.version);
}

const folded = (text) => text.replace(/\s+/g, ' ').trim();
/** A "What a country must do" and its answer, the paragraph it stands in. */
const MUST = /What\s+a\s+country\s+must\s+do\**:?\**\s*((?:[^\n]+\n?)+)/g;
/** A list item's start at the margin: its indent and its text. */
const ITEM = /^( {0,3})(?:[-*]|\d+\.)\s+(.*)$/;

/**
 * What each entry of a section says a country must do, white space folded: one text per "What a
 * country must do", or, when that text (its trailing bold stripped) ends in a colon and a list
 * follows, one text per list item instead, the lead-in first and the item whole (`at its bump:
 * run set-up.`), so each reads on its own. The list may follow on the very next line or after a
 * blank line. A line that is no item start continues the item before it, a deeper-indented list
 * included, while no blank line has come since; after a blank line only an item start continues
 * the list. A lead-in followed by no list stays one action, the lead-in alone, and an answer not
 * ending in a colon is one, however long: no action is cut.
 */
export function countryActions(text) {
  const body = text.replace(/\r\n?/g, '\n');
  return [...body.matchAll(MUST)].flatMap((match) => {
    const whole = folded(match[1]);
    const bare = whole.replace(/\*+$/, '');
    const answer = bare.endsWith(':') ? bare : whole;
    // An answer that starts on a line of its own may open with the list: the colon is then the
    // "What a country must do"'s own.
    const prefix = match[0].slice(0, match[0].length - match[1].length);
    const ownLine = /\n\s*$/.test(prefix);
    const lines = match[1].replace(/\n$/, '').split('\n');
    const first = lines.findIndex((line, i) => (i > 0 || ownLine) && ITEM.test(line));
    const leadIn = folded((first === -1 ? lines : lines.slice(0, first)).join('\n')).replace(
      /\*+$/,
      '',
    );
    if (!(leadIn ? leadIn.endsWith(':') : /:\**\s*$/.test(prefix))) return [answer];

    const items = [];
    let margin;
    const started = (line) => {
      const item = ITEM.exec(line);
      if (!item || (margin !== undefined && item[1].length > margin)) return false;
      margin ??= item[1].length;
      items.push([item[2]]);
      return true;
    };
    if (first !== -1)
      for (const line of lines.slice(first)) if (!started(line)) items.at(-1).push(line);
    let blank = false;
    for (const line of body.slice(match.index + match[0].length).split('\n')) {
      if (!line.trim()) blank = true;
      else if (started(line)) blank = false;
      else if (items.length && !blank) items.at(-1).push(line);
      else break;
    }
    if (!items.length) return [answer];
    return items.map((item) => folded([leadIn, ...item].join(' ')));
  });
}

/** The actions of a section that are not "Nothing"; a section that names none must be read. */
export function requiredActions(section) {
  const actions = countryActions(section.body);
  if (!actions.length) return ['the section names no action: read it'];
  return actions.filter((action) => !/^nothing\b/i.test(action));
}

const LEAD = /^\*\*(.+?)\*\*/;

/**
 * The entries of a section's body, `[{ lead, text }]`: an entry opens with a paragraph whose bold
 * lead is not a bold "What a country must do", and holds the paragraphs up to the next one; `lead`
 * is that bold text, white space folded. What stands before the first lead is an entry of the lead
 * ''. `text` is the entry's paragraphs, a blank line between them.
 */
export function changeEntries(body) {
  const paragraphs = [];
  let open;
  for (const line of body.replace(/\r\n?/g, '\n').split('\n')) {
    if (!line.trim()) open = undefined;
    else if (open) open.push(line);
    else paragraphs.push((open = [line]));
  }
  const entries = [];
  for (const paragraph of paragraphs.map((lines) => lines.join('\n'))) {
    const lead = LEAD.exec(folded(paragraph))?.[1];
    if (lead !== undefined && !/^what a country must do/i.test(lead))
      entries.push({ lead: folded(lead), paragraphs: [paragraph] });
    else if (entries.length) entries.at(-1).paragraphs.push(paragraph);
    else entries.push({ lead: '', paragraphs: [paragraph] });
  }
  return entries.map(({ lead, paragraphs: texts }) => ({ lead, text: texts.join('\n\n') }));
}

/**
 * The sections of an API-CHANGES.md text, `current`, new for a country coming from
 * `previousVersion`: `[{ heading, version, body, entries }]`. `previous` is that version's copy,
 * or undefined when it is not at hand. Two rules, in this order.
 *
 * The version rule: a section is new only when its heading's version is newer than
 * `previousVersion`, and Unreleased always is. A renamed section is named after the version that
 * published it (API-CHANGES.md, "How an entry is written"), so a section of a version the country
 * already has is never new, even with a reworded lead. Without the previous copy the sections it
 * keeps are listed whole (`entries` null).
 *
 * The per-entry rule, beside the previous copy's Unreleased. An Unreleased section with entries is
 * compared with it per entry, so a bump onto an unreleased build lists what came since: an entry
 * is new without an entry of its lead there and changed when its text, white space folded, is
 * another. A released section the previous copy has no heading of was that copy's Unreleased in
 * part, so it is compared with it by lead alone: it lists the leads the previous Unreleased
 * lacked, and an entry whose text was edited since is not listed again. `entries` are those
 * listed, and a section none of whose entries is listed is left out. Every other section is listed
 * whole when the previous copy has no section of its heading or one with another body. A section
 * with no text asks nothing and is never listed.
 */
export function newChangeSections(current, previous, previousVersion) {
  const sections = changeSections(current ?? '').filter(
    (section) => section.body && newerThan(section.version, previousVersion),
  );
  const whole = (section) => ({ ...section, entries: null });
  if (previous === undefined) return sections.map(whole);
  const earlier = changeSections(previous);
  const before = earlier.find((candidate) => candidate.version === 'Unreleased');
  const listed = [];
  for (const section of sections) {
    const entries = changeEntries(section.body);
    const leads = entries.some((entry) => entry.lead);
    const match = earlier.find((candidate) => candidate.heading === section.heading);
    if (before && leads && (section.version === 'Unreleased' || !match)) {
      const key =
        section.version === 'Unreleased'
          ? (entry) => `${entry.lead}\n${folded(entry.text)}`
          : (entry) => entry.lead;
      const known = new Set(changeEntries(before.body).map(key));
      const fresh = entries.filter((entry) => !known.has(key(entry)));
      if (fresh.length) listed.push({ ...section, entries: fresh });
    } else if (!match || match.body !== section.body) listed.push(whole(section));
  }
  return listed;
}

/**
 * What a listed section asks of a country: a section listed whole, its required actions; one
 * compared per entry, the actions of each listed entry that are not "Nothing", and for an entry
 * that names none, that it must be read.
 */
export function sectionActions(section) {
  if (!section.entries) return requiredActions(section);
  return section.entries.flatMap((entry) => {
    const actions = countryActions(entry.text);
    if (!actions.length)
      return [`the entry “${entry.lead || '(the opening text)'}” names no action: read it`];
    return actions.filter((action) => !/^nothing\b/i.test(action));
  });
}

/** The fixes of security.json not in the previous zip's copy, or else by version. */
function newFixes(files, previous) {
  const current = parse(files.now(SECURITY))?.fixes ?? [];
  const old = parse(files.before(SECURITY));
  if (!old) return current.filter((fix) => newerThan(fix.version, previous.version));
  const seen = new Set((old.fixes ?? []).map((fix) => JSON.stringify(fix)));
  return current.filter((fix) => !seen.has(JSON.stringify(fix)));
}

/** A pack's file for an application, relative to the country's fe/. */
function packFileOf(findings, fe, { lang, app, bundle }) {
  const dir = findings.packDirs?.[lang];
  const root = dir ? toPosix(path.relative(fe, dir)) : `packs/${lang}`;
  return app === 'backend'
    ? `${root}/backend/${bundle}.properties`
    : `${root}/i18n/${app}/${lang}.json`;
}

/** A pattern of branding.schema.json as a regular expression of the path it names. */
function brandingPattern(pattern) {
  const escaped = pattern.replace(/[.*+?^${}()|[\]\\]/g, (c) =>
    c === '{' || c === '}' ? c : `\\${c}`,
  );
  return new RegExp(`^${escaped.replace('{lang}', '([a-z]{2,3})').replace('{doc}', '([^/.]+)')}$`);
}

/** Every file under a folder, relative with `/`; an absent folder has none. */
function filesUnder(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => toPosix(path.relative(dir, path.join(entry.parentPath, entry.name))))
    .sort();
}

/**
 * The branding items to do, from branding.schema.json and the country's branding folders: a
 * required key or file the country lacks, which the schema added, and a key or file it fills that
 * the schema no longer has. `found` is the branding check's findings per application, `{ kind,
 * file, key?, error }` with `file` relative to `<app>/assets/`; `accounted` holds the `error` of
 * each finding an item covers: 'required-key' and 'unknown-key' by the add and delete items of
 * their texts file, 'required-file' and 'unnamed-file' by the item of their file.
 */
function brandingItems({ fe, manifest, schema, found = {} }) {
  const items = [];
  const accounted = [];
  const account = (app, test) => {
    for (const finding of found[app] ?? [])
      if (finding.error !== undefined && test(finding)) accounted.push(finding.error);
  };
  for (const app of APPS) {
    const section = schema?.apps?.[app];
    const use = manifest.apps?.[app];
    if (!section || !use) continue;
    const dir = path.join(fe, app, 'assets');
    const texts = {};
    for (const lang of use.uiLanguages) {
      try {
        const file = path.join(dir, section.texts.path.replace('{lang}', lang));
        if (fs.existsSync(file)) texts[lang] = flatten(JSON.parse(fs.readFileSync(file, 'utf8')));
      } catch {
        // The branding check names it.
      }
    }
    for (const [lang, keys] of Object.entries(texts)) {
      const relative = section.texts.path.replace('{lang}', lang);
      const file = `${app}/assets/${relative}`;
      const lacking = Object.entries(section.keys ?? {})
        .filter(([key, entry]) => entry.required && !Object.hasOwn(keys, key))
        .map(([key]) => key);
      if (lacking.length) {
        items.push(`${code(file)}: add ${listed(lacking)}, which the branding schema requires`);
        account(
          app,
          (f) => f.kind === 'required-key' && f.file === relative && lacking.includes(f.key),
        );
      }
      const gone = Object.keys(keys).filter(
        (key) => !Object.hasOwn(section.keys ?? {}, key) && !key.startsWith(COUNTRY_NAMESPACE),
      );
      if (gone.length) {
        items.push(
          `${code(file)}: delete ${listed(gone)}, which the branding schema no longer has`,
        );
        account(
          app,
          (f) => f.kind === 'unknown-key' && f.file === relative && gone.includes(f.key),
        );
      }
    }
    const present = filesUnder(dir);
    for (const [pattern, entry] of Object.entries(section.files ?? {}))
      if (entry.required)
        for (const lang of pattern.includes('{lang}') ? use.uiLanguages : [undefined]) {
          const relative = pattern.replace('{lang}', lang);
          if (!present.includes(relative)) {
            items.push(
              `${code(`${app}/assets/${relative}`)}: add it, a file the branding schema requires`,
            );
            account(app, (f) => f.kind === 'required-file' && f.file === relative);
          }
        }
    const known = [
      brandingPattern(section.texts.path),
      ...Object.keys(section.files ?? {}).map(brandingPattern),
    ];
    const legal = section.legal ? brandingPattern(section.legal.path) : undefined;
    const unnamed = present.filter((relative) => {
      if (known.some((pattern) => pattern.test(relative))) return false;
      const doc = legal?.exec(relative)?.[1];
      return !(doc && Object.hasOwn(section.legal.documents ?? {}, doc));
    });
    for (const relative of unnamed) {
      items.push(
        `${code(`${app}/assets/${relative}`)}: delete it, a file the branding schema no longer names`,
      );
      account(app, (f) => f.kind === 'unnamed-file' && f.file === relative);
    }
  }
  return { items, accounted };
}

/**
 * The overrides' items to do: changed or removed base files with their diffs, protected files.
 * `found` is the override check's findings, `{ file, kind, error, version?, changed? }`;
 * `accounted` holds the `error` of each finding an item of its entry covers: 'protected' by the
 * protected item, 'changed' by a changed-file or the removed item, 'removed' by the removed item,
 * and 'security' by the item of its fix and file. A seam, copy or resource finding is no item's.
 */
function overrideItems({ manifest, base, previous, current, seams, fixes, found = [] }) {
  const items = [];
  const accounted = [];
  let unavailable = false;
  for (const entry of manifest.overrides ?? []) {
    const of = `${code(entry.with)} (the override of ${code(entry.file)})`;
    const accept = code(`node setup.mjs --accept-override ${entry.file}`);
    const account = (test) => {
      for (const finding of found)
        if (finding.error !== undefined && finding.file === entry.file && test(finding))
          accounted.push(finding.error);
    };
    if (protectedMatch(seams?.protected, entry.file)) {
      items.push(
        `${of}: its file joined the protected list (${SEAMS_LABEL}): replace the override with an extension point, or ask the base product for one`,
      );
      account((f) => f.kind === 'protected');
    }
    let changed = false;
    const removed = [];
    for (const [file, recorded] of Object.entries(entry.basedOn ?? {})) {
      const at = path.join(base, file);
      if (!fs.existsSync(at)) {
        removed.push(file);
        continue;
      }
      if (fingerprint(fs.readFileSync(at)) === recorded) continue;
      changed = true;
      const since = diffSinceRecorded({
        file,
        recorded,
        current: fs.readFileSync(at, 'utf8'),
        previous,
      });
      const head = `${of}: the base product changed ${code(file)}: compare the copy with the base product's diff`;
      if (since.diff) {
        const shown = since.diff.slice(0, MAX_DIFF_LINES);
        items.push(
          [
            `${head} below (${previous.version} to ${current.version}; - before, + now), adjust the copy, then run ${accept}`,
            '',
            '  ```diff',
            ...shown.map((line) => `  ${line}`),
            ...(since.diff.length > shown.length
              ? [`  … and ${since.diff.length - shown.length} more line(s)`]
              : []),
            '  ```',
          ].join('\n'),
        );
      } else if (previous.unavailable) {
        unavailable = true;
        items.push(
          `${head} since the recorded fingerprint (not available, below), adjust the copy, then run ${accept}`,
        );
      } else
        items.push(
          `${head} since the recorded fingerprint (not available: ${since.unavailable}), adjust the copy, then run ${accept}`,
        );
    }
    if (removed.length) {
      items.push(
        `${of}: the base product removed ${listed(removed)}: remove the entry and the copy, then add the override again or use an extension point`,
      );
      account((f) => f.kind === 'removed');
    }
    if (changed || removed.length) account((f) => f.kind === 'changed');
    for (const fix of fixes)
      for (const file of (fix.files ?? []).filter((name) =>
        Object.hasOwn(entry.basedOn ?? {}, name),
      )) {
        items.push(
          `${of}: the security fix of ${fix.version} changed ${code(file)} (${fix.summary}): merge it into the copy, then run ${accept}`,
        );
        account((f) => f.kind === 'security' && f.version === fix.version && f.changed === file);
      }
  }
  return { items, unavailable, accounted };
}

/** The extension points and API exports added since the previous version, and the overrides they may cover. */
function newForYou({ files, previous, base, manifest }) {
  if (previous.unavailable) return [`Not available: ${previous.unavailable}.`];
  const now = parse(files.now(SEAMS)) ?? {};
  const before = parse(files.before(SEAMS));
  const lines = [];
  const added = [];
  if (!before)
    lines.push(
      previous.kitUnavailable
        ? `- Extension points: not available: ${previous.kitUnavailable}.`
        : `- Extension points: not available: the previous zip has no readable ${SEAMS_LABEL}.`,
    );
  else
    for (const [kind, list, describe] of [
      ['hook', 'hooks', (hook) => `${code(hook.token)} in ${code(hook.file)}`],
      ['slot', 'slots', (slot) => `in ${code(slot.template)}`],
      ['seam', 'seams', (seam) => `at ${code(seam.country)}`],
    ])
      for (const point of now[list] ?? [])
        if (!(before[list] ?? []).some((old) => old.id === point.id)) {
          added.push({ kind, point });
          lines.push(
            `- The ${kind} ${code(point.id)} (${point.app}), ${describe(point)}${point.description ? `: ${point.description.split(/(?<=\.)\s/)[0]}` : ''} (${code(SEAMS_LABEL)})`,
          );
        }

  let tsconfig;
  try {
    tsconfig = parseJsonc(files.before('tsconfig.json') ?? '', 'tsconfig.json');
  } catch {
    tsconfig = undefined;
  }
  const nowConfig = parseJsonc(files.now('tsconfig.json') ?? '{}', 'tsconfig.json');
  for (const [entry, targets] of Object.entries(nowConfig.compilerOptions?.paths ?? {})) {
    if (!/^@niord\/(admin|info)\/api(\/testing)?$/.test(entry)) continue;
    const relative = path.posix.normalize(targets[0] ?? '');
    let names;
    try {
      names = readApi(path.join(base, relative)).map((item) => item.name);
    } catch {
      continue;
    }
    const oldTarget = tsconfig?.compilerOptions?.paths?.[entry]?.[0];
    const oldText = oldTarget ? files.before(path.posix.normalize(oldTarget)) : undefined;
    if (oldText === undefined) {
      lines.push(`- ${code(entry)}: not available: the previous zip does not map it.`);
      continue;
    }
    let oldNames;
    try {
      oldNames = new Set(readApi(relative, oldText).map((item) => item.name));
    } catch (error) {
      lines.push(
        `- ${code(entry)}: not available: the previous version's file cannot be read (${error.message}).`,
      );
      continue;
    }
    const fresh = names.filter((name) => !oldNames.has(name));
    if (fresh.length)
      lines.push(
        `- ${code(entry)} exports ${listed(fresh)} (${code(relative)}, ${code(CHANGES_LABEL)})`,
      );
  }

  for (const entry of manifest.overrides ?? [])
    for (const { kind, point } of added) {
      const file = point.file ?? point.template ?? point.replaces ?? point.base;
      if (!file || !Object.hasOwn(entry.basedOn ?? {}, file)) continue;
      const action =
        kind === 'hook'
          ? `provide ${code(point.token)} in ${code(`${point.app}/app/country.providers.ts`)}`
          : kind === 'slot'
            ? `register a component for it with provideSlot in ${code(`${point.app}/app/country.providers.ts`)}`
            : `fill it at ${code(point.country)}`;
      lines.push(
        `- ${code(entry.with)}: the new ${kind} ${code(point.id)} is in ${code(file)}, which the override is based on: if it covers the change, ${action} and remove the override`,
      );
    }
  if (!lines.length) lines.push('- No extension point and no API export was added.');
  return lines;
}

/** The base product's dependency versions moved since the previous version, framework first, and the country packages they touch. */
function dependencyChanges({ files, previous, findings }) {
  if (previous.unavailable) return [`Not available: ${previous.unavailable}.`];
  const now = parse(files.now('package.json'));
  const before = parse(files.before('package.json'));
  if (!now || !before) return ['Not available: the previous zip has no readable package.json.'];
  const versions = (pkg) => ({ ...(pkg.devDependencies ?? {}), ...(pkg.dependencies ?? {}) });
  const [a, b] = [versions(before), versions(now)];
  const moved = [...new Set([...Object.keys(a), ...Object.keys(b)])]
    .sort()
    .filter((name) => a[name] !== b[name])
    .map((name) => ({ name, from: a[name], to: b[name] }));
  const change = ({ from, to }) =>
    from === undefined
      ? `added at ${to}`
      : to === undefined
        ? `removed (was ${from})`
        : `${from} → ${to}`;
  const lines = [];
  const framework = new Map();
  for (const item of moved.filter((m) => m.name.startsWith(FRAMEWORK))) {
    const key = change(item);
    framework.set(key, [...(framework.get(key) ?? []), item.name]);
  }
  for (const [key, names] of framework) lines.push(`- Framework: ${listed(names)} ${key}.`);
  const others = moved.filter((m) => !m.name.startsWith(FRAMEWORK));
  for (const item of others.slice(0, MAX_LISTED))
    lines.push(`- ${code(item.name)} ${change(item)}.`);
  if (others.length > MAX_LISTED) lines.push(`- … and ${others.length - MAX_LISTED} more.`);
  if (!moved.length) lines.push("- The base product's package.json moved no dependency.");
  const peers = findings.dependencies?.peers ?? [];
  const movedNames = new Set(moved.map((m) => m.name));
  const touched = peers.filter((relation) => movedNames.has(relation.peer));
  if (!(findings.dependencies?.added ?? []).length) lines.push('- Country packages: none.');
  else if (!touched.length)
    lines.push('- Country packages: none has a peer the base product moved.');
  for (const relation of touched)
    lines.push(
      `- ${code('country/package.json')}: ${code(relation.name)} has the peer ${code(`${relation.peer} ${relation.range}`)}, now ${relation.version ?? 'absent'}: ${relation.rejected ? 'rejected (under To do)' : 'satisfied'}.`,
    );
  return lines;
}

/**
 * The kit templates changed since the previous kit, byte for byte (the kit's .gitattributes keeps
 * both LF): `{ names, unavailable, line }`, `names` in TEMPLATES' order, a template only one kit has
 * counting as changed, or null with `unavailable` the reason when the previous kit's files are not
 * at hand; `line` the To-look-at item, undefined when none changed.
 */
function templatesChanged({ kit, previous }) {
  const dir = TEMPLATES_DIR;
  const unavailable = previous.unavailable ?? previous.kitUnavailable;
  if (unavailable)
    return {
      names: null,
      unavailable,
      line: `Templates changed since ${previous.version}: not available: ${unavailable}; compare each file of ${code(`${dir}/`)} with the country's copy`,
    };
  const changed = [];
  for (const [name, verb, target, part] of TEMPLATES) {
    let now;
    try {
      now = fs.readFileSync(path.join(kit, 'templates', name));
    } catch {
      now = undefined;
    }
    const before = previous.files?.get(`${KIT_PREFIX}templates/${name}`);
    if (now === undefined && before === undefined) continue;
    if (now && before && now.equals(before)) continue;
    changed.push({
      name,
      text:
        now === undefined
          ? `${code(name)} (the kit no longer has it: see ${code(CHANGES_LABEL)})`
          : `${code(name)} (${verb} ${code(`${dir}/${name}`)} ${verb === 'copy' ? 'over' : 'into'} ${part ? `${part} ` : ''}${code(target)})`,
    });
  }
  return {
    names: changed.map((item) => item.name),
    unavailable: null,
    line: changed.length
      ? `Templates changed since ${previous.version}, from the repository root: ${changed.map((item) => item.text).join(', ')}`
      : undefined,
  };
}

/**
 * Every other kit template changed since the previous kit, byte for byte: `{ names, line }`,
 * `names` the paths from templates/ (the be/ folder and any deeper one included) of the files not
 * in TRACKED that differ or that only one kit has, sorted, or null when the previous kit's files
 * are not at hand; `line` the one line under To look at, with no action, undefined when none
 * changed or when null, since the templatesChanged item then asks for every file to be compared.
 */
function otherTemplatesChanged({ kit, previous }) {
  if (previous.unavailable ?? previous.kitUnavailable) return { names: null, line: undefined };
  const prefix = `${KIT_PREFIX}templates/`;
  const before = new Map();
  for (const [key, data] of previous.files ?? [])
    if (key.startsWith(prefix)) before.set(key.slice(prefix.length), data);
  const now = new Set(filesUnder(path.join(kit, 'templates')));
  const names = [...new Set([...now, ...before.keys()])]
    .filter((name) => !TRACKED.has(name))
    .filter(
      (name) =>
        !now.has(name) ||
        !before.has(name) ||
        !fs.readFileSync(path.join(kit, 'templates', name)).equals(before.get(name)),
    )
    .sort();
  return {
    names,
    line: names.length
      ? `Other templates changed since ${previous.version}, in ${code(`${TEMPLATES_DIR}/`)}: ${listed(names)} (no action: a country that keeps its own copy compares it).`
      : undefined,
  };
}

/** The keys each foreign pack lacks, `[{ lang, keys }]`, in the order the pack check found them. */
function missingForeign(findings, home) {
  const byLang = new Map();
  for (const missing of findings.packs?.missing ?? []) {
    if (home(missing.lang)) continue;
    const keys = byLang.get(missing.lang) ?? new Set();
    for (const key of missing.keys) keys.add(key);
    byLang.set(missing.lang, keys);
  }
  return [...byLang].map(([lang, keys]) => ({ lang, keys: [...keys] }));
}

/**
 * The report's Markdown. `previous` is lib/previous-base.mjs's `{ version, files }`, with
 * `kitUnavailable` when the kit's files are not among them, or `{ version, unavailable }`;
 * `current` `{ version, base, kit }`, `base` the unpacked base product (.niord/base) and `kit`, which
 * is required, the kit folder the kit's files are read from now; `checks` set-up's runChecks
 * result, of which the report reads `errors` and `findings`. `previous.option` marks a previous
 * version named by `--previous` without a set-up record.
 *
 * Every structured finding of `checks.findings` a To-do item is made from carries `error`, the
 * line exactly as `checks.errors` holds it, its prefix included: packs.missing, packs.markup,
 * packs.placeholders (`{ lang, app, bundle?, key, error }`, a text whose placeholders are not
 * English's), textOverrides.markup, textOverrides.leftCatalog, dependencies.peers (the rejected
 * ones), withdrawn[], stub (`{ error }`), overrides[] (`{ file, kind, error, version?, changed? }`)
 * and branding.{app}[] (`{ kind, file, key?, error }`). An item accounts for the lines of the
 * findings it was made from, and To do lists every other line as it is, once, after the items. A
 * finding without `error` accounts for nothing.
 *
 * Returns `{ markdown, data }`, both from the same findings. `data`, format 1, holds in this order
 * `country`, `from`, `to`, `failed` (the number of failed checks), `sections` (`[{ heading,
 * entries, actions }]`: `entries` the leads listed, of an Unreleased compared with the previous
 * Unreleased per entry or a released section compared with it by lead, or null for a section
 * listed whole; `actions` in full, as the Markdown lists them), `fixes` (`[{ version, summary }]`),
 * `todo` and `look` (the Markdown's To-do and To-look-at items), `stubChanged`,
 * `templatesChanged` (the names, or null with `templatesUnavailable` the reason),
 * `otherTemplatesChanged` (every other template of the folder, by its path from templates/,
 * changed, added or removed, sorted, or null when templatesChanged is), `missingForeign` (`[{ lang, keys }]` per foreign pack) and `notChecked` (what set-up does not
 * check).
 */
export function upgradeReportData({ country, manifest, fe, previous, current, checks }) {
  const { base, kit } = current;
  if (!kit)
    throw new Error(
      "upgradeReportData: current.kit, the folder the kit's files are read from, is required",
    );
  const files = {
    now: (relative) => {
      try {
        return fs.readFileSync(
          relative.startsWith(KIT_PREFIX)
            ? path.join(kit, relative.slice(KIT_PREFIX.length))
            : path.join(base, relative),
          'utf8',
        );
      } catch {
        return undefined;
      }
    },
    before: (relative) => previous.files?.get(relative)?.toString('utf8'),
  };
  const findings = checks.findings ?? {};
  const failed = checks.errors?.length ?? 0;
  const home = (lang) => manifest.packs?.[lang]?.home === true;

  // Versions.
  const sections = newChangeSections(files.now(CHANGES), files.before(CHANGES), previous.version);
  const fixes = newFixes(files, previous);
  // A line taken by version says why: the previous zip is not at hand, or the kit's file is not.
  const read = (byVersion) =>
    previous.unavailable
      ? ' (by version: the previous zip is not at hand)'
      : previous.kitUnavailable && byVersion
        ? ` (by version: ${previous.kitUnavailable})`
        : '';
  const readChanges = read(files.before(CHANGES) === undefined);
  const readFixes = read(!parse(files.before(SECURITY)));
  const named = (s) =>
    s.entries
      ? `${code(s.heading)} (entries new or changed since ${previous.version}'s copy: ${s.entries.map((entry) => `“${entry.lead || '(the opening text)'}”`).join(', ')})`
      : code(s.heading);
  const versions = [
    `- The base product moves from ${previous.version} to ${current.version}.`,
    `- Release notes: not in the zip; the sections below come from ${code(CHANGES_LABEL)} and ${code(SECURITY_LABEL)}, the kit's files, from the repository root.`,
    `- ${code(CHANGES_LABEL)}, sections new since ${previous.version}${readChanges}: ${sections.length ? sections.map(named).join(', ') : 'none'}.`,
    `- ${code(SECURITY_LABEL)}, fixes new since ${previous.version}${readFixes}: ${fixes.length ? fixes.map((fix) => `${fix.version} (${fix.summary})`).join('; ') : 'none'}.`,
  ];

  // To do: what set-up or the build fails on until it is done. Each item accounts for the check
  // lines of the findings it was made from; every other line is listed after them as it is.
  const todo = [];
  const accounted = new Set();
  const account = (...lines) => {
    for (const line of lines) if (line !== undefined) accounted.add(line);
  };
  for (const item of findings.withdrawn ?? []) {
    todo.push(
      `${code(LOCK)}: the pinned pack ${item.lang}, ${code(item.reference)}, is withdrawn by its home (${item.date}): ${item.reason}; move the pin to the home's next pack or back to the last good one with ${code('node setup.mjs --update-pins')} (or ${code(`--update-pins ${item.lang}=<tag>`)})`,
    );
    account(item.error);
  }
  if (findings.stub) {
    todo.push(
      `${code('setup.mjs')}: the base product changed the stub: copy ${code(STUB_TEMPLATE)} over it (${code(`cp ${STUB_TEMPLATE} setup.mjs`)}), commit it, and run ${code('node setup.mjs')} again`,
    );
    account(findings.stub.error);
  }
  for (const missing of findings.packs?.missing ?? []) {
    const file = packFileOf(findings, fe, missing);
    const keys = `${plural(missing.keys.length, 'new key')}: ${listed(missing.keys)}`;
    todo.push(
      home(missing.lang)
        ? `${code(file)}: translate ${keys}`
        : `${code(file)} lacks ${keys}: wait for the ${missing.lang} home's pack, accept English in ${code('missing-keys-accepted.json')}, or write the country's own wording in ${code(`text-overrides/${missing.lang}.json`)}`,
    );
    account(missing.error);
  }
  for (const item of findings.packs?.placeholders ?? []) {
    todo.push(
      `${code(packFileOf(findings, fe, item))}: ${code(item.key)}'s placeholders no longer match English's: ${home(item.lang) ? "give the translation English's placeholders" : "wait for the home's fixed pack"}`,
    );
    account(item.error);
  }
  for (const item of findings.packs?.markup ?? []) {
    todo.push(
      `${code(packFileOf(findings, fe, item))}: ${code(item.key)}'s markup no longer matches English's: ${home(item.lang) ? 'give the translation the English markup' : "wait for the home's fixed pack"}`,
    );
    account(item.error);
  }
  for (const item of findings.textOverrides?.markup ?? []) {
    todo.push(
      `${code(`text-overrides/${item.lang}.json`)}: ${item.app} ${code(item.key)}'s markup no longer matches English's: give the override the English markup`,
    );
    account(item.error);
  }
  const seams = parse(files.now(SEAMS));
  const overrides = overrideItems({
    manifest,
    base,
    previous,
    current,
    seams,
    fixes,
    found: findings.overrides,
  });
  todo.push(...overrides.items);
  account(...overrides.accounted);
  const brandings = brandingItems({
    fe,
    manifest,
    schema: parse(files.now(BRANDING)),
    found: findings.branding,
  });
  todo.push(...brandings.items);
  account(...brandings.accounted);
  for (const item of findings.textOverrides?.leftCatalog ?? []) {
    todo.push(
      `${code(`text-overrides/${item.lang}.json`)}: ${item.app} ${code(item.key)} left the catalog: ${item.renamedTo ? `move the override to ${code(item.renamedTo)}, its new name` : 'remove the override'}`,
    );
    account(item.error);
  }
  for (const relation of (findings.dependencies?.peers ?? []).filter((r) => r.rejected)) {
    todo.push(
      `${code('country/package.json')}: ${code(relation.name)} needs the peer ${code(`${relation.peer} ${relation.range}`)}, and the base product now has ${relation.version ?? 'none'}: update ${code(relation.name)} to a version that takes it, then run ${code('node setup.mjs --update-country-lock')}`,
    );
    account(relation.error);
  }
  // A failed check no item accounts for is listed as set-up printed it, so none is lost.
  for (const line of new Set(checks.errors ?? [])) if (!accounted.has(line)) todo.push(line);
  // What API-CHANGES.md asks of a country: set-up checks none of it (a deprecated export it uses
  // is a warning), so it is listed under To look at, apart from what set-up enforces.
  const actions = sections.map(sectionActions);
  const asked = [];
  sections.forEach((section, i) => {
    for (const action of actions[i])
      asked.push(`${code(CHANGES_LABEL)}, ${code(section.heading)}: ${action}`);
  });
  const notChecked = [
    'Keys the base product added to its settings file, and settings keys that left the catalog: not available: the zip carries no backend settings list.',
    `Dependency advisories not accepted: checked by ${code('npm run build')}'s audit after ${code('npm ci')}, against ${code('audit-accepted.json')}.`,
  ];

  // To look at.
  const look = [];
  for (const item of findings.packs?.changedEnglish ?? [])
    look.push(
      `${code(packFileOf(findings, fe, item))}: English changed under ${code(item.key)}: ${home(item.lang) ? 'check the translation, then accept it (`pack accept`)' : "the home's next pack checks it"}`,
    );
  for (const item of findings.textOverrides?.stale ?? [])
    look.push(
      `${code(`text-overrides/${item.lang}.json`)}: ${item.app} ${code(item.key)} was written against another English text: look at it, then record the fp ${item.fp}`,
    );
  for (const item of findings.textOverrides?.redundant ?? [])
    look.push(
      `${code(`text-overrides/${item.lang}.json`)}: ${item.app} ${code(item.key)} equals the pack's text: remove the override`,
    );
  const differing = new Map();
  for (const item of findings.textOverrides?.differing ?? []) {
    const key = `text-overrides/${item.lang}.json`;
    differing.set(key, [...(differing.get(key) ?? []), `${item.app} ${item.key}`]);
  }
  for (const [file, keys] of differing)
    look.push(
      `${code(file)}: ${plural(keys.length, 'override')} ${keys.length === 1 ? 'differs' : 'differ'} from the pack's text, ${listed(keys)}: keep each only while the difference is deliberate`,
    );
  for (const use of findings.deprecated ?? [])
    look.push(
      `${code(use.file)} uses ${code(use.name)} from ${code(use.specifier)}, deprecated since ${use.since}, ${use.removedIn ? `removed in ${use.removedIn}` : 'removal not scheduled'}: ${use.instead}`,
    );
  const why = {
    translated: 'the pack now translates it',
    overridden: 'a text override supplies it',
    'left the catalog': 'the catalog no longer has it',
  };
  for (const item of findings.packs?.hatchStale ?? [])
    look.push(
      `${code('missing-keys-accepted.json')}: ${item.lang} ${item.app}${item.bundle ? ` ${item.bundle}` : ''} ${code(item.key)}: ${why[item.why] ?? item.why}; remove the entry`,
    );
  const templates = templatesChanged({ kit, previous });
  if (templates.line) look.push(templates.line);
  // Its own paragraph after the items, never one of them: it asks nothing.
  const others = otherTemplatesChanged({ kit, previous });

  const bullet = (items) => (items.length ? items.map((item) => `- ${item}`) : ['- Nothing.']);
  const failedSentence = `Set-up of ${current.version} failed: ${plural(failed, 'check')} (\`node setup.mjs\` prints each with its file and action).`;
  const lines = [
    `# Upgrade report: ${country} from ${previous.version} to ${current.version}`,
    '',
    failed
      ? previous.option
        ? `${failedSentence} There is no set-up record; set-up writes one when it passes.`
        : `${failedSentence} The last good set-up stays recorded as ${previous.version} until it passes.`
      : `Set-up of ${current.version} passed.`,
    '',
    '## Versions',
    '',
    ...versions,
    '',
    '## To do',
    '',
    ...(todo.length ? ['Set-up or the build fails until each is done.', ''] : []),
    ...bullet(todo),
    ...(overrides.unavailable
      ? [
          '',
          `The base product's diffs since the recorded fingerprints: not available: ${previous.unavailable}.`,
        ]
      : []),
    '',
    'Not checked by set-up:',
    '',
    ...notChecked.map((line) => `- ${line}`),
    '',
    '## To look at',
    '',
    ...bullet(look),
    ...(others.line ? ['', others.line] : []),
    ...(asked.length
      ? [
          '',
          `What ${code(CHANGES_LABEL)} asks of a country (set-up does not check these):`,
          '',
          ...asked.map((item) => `- ${item}`),
        ]
      : []),
    '',
    '## New for you',
    '',
    ...newForYou({ files, previous, base, manifest }),
    '',
    '## Dependency changes',
    '',
    ...dependencyChanges({ files, previous, findings }),
    '',
  ];
  const data = {
    format: 1,
    country,
    from: previous.version,
    to: current.version,
    failed,
    sections: sections.map((section, i) => ({
      heading: section.heading,
      entries: section.entries ? section.entries.map((entry) => entry.lead) : null,
      actions: actions[i],
    })),
    fixes: fixes.map((fix) => ({ version: fix.version, summary: fix.summary })),
    todo,
    look,
    stubChanged: Boolean(findings.stub),
    templatesChanged: templates.names,
    templatesUnavailable: templates.unavailable,
    otherTemplatesChanged: others.names,
    missingForeign: missingForeign(findings, home),
    notChecked,
  };
  return { markdown: lines.join('\n'), data };
}

/** The report's Markdown alone: `upgradeReportData`'s `markdown`. */
export const upgradeReport = (input) => upgradeReportData(input).markdown;

/** The report data's text as set-up writes it: JSON, two spaces, a final newline. */
export const reportDataText = (data) => `${JSON.stringify(data, null, 2)}\n`;
