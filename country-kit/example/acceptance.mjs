/**
 * The new-country acceptance tests (fe-country-split-plan §4.8), run by the example's harness
 * (run.mjs) after the example itself, against the same zip:
 *
 *   - the untouched init: a country `yy` made by init into a folder of its own and never edited,
 *     as the Quick start starts one: both packages and SHA256SUMS, the release notes' two lines,
 *     in yy/.niord/, only the kit package unzipped (into .niord/kit/), the answers in
 *     .niord/init-answers.json (YY_ANSWERS, made up), then `node .niord/kit/init.mjs --answers
 *     .niord/init-answers.json --sha256-file .niord/SHA256SUMS` in an empty repository: no pom and
 *     no wrapper first. Init must keep nothing, write the Maven wrapper with the bytes of the tree's
 *     be/ (and, off Windows, with its exec bits) and the root's .niord/.gitignore; then yy sets up
 *     pinned by both hashes (never NIORD_BASE). With --backend (untouchedBackend), niord-base and
 *     its test-jar are installed from the tree's be/ into the scratch Maven repository as the zip's
 *     version, and yy's be/mvnw verify runs with Docker (the country build group,
 *     CountryDefaultsChecksTest over the generated settings, CountryContractGuardTest over the
 *     generated application.yml), then the group check and the override check as the country's
 *     ci.yml runs them. Then, unless --skip-frontend, yy builds — check-build warning that it has
 *     no bundle baseline yet — and passes `npm test`, `npm run lint` and `npm run e2e -- --list`;
 *     it starts its baseline as check-build's warning says, after which check-build passes without
 *     the warning; with --image its public-site image is built and checked, with the warnings of a
 *     country that has no legal document and no branding text yet. `untouched` runs these in that
 *     order, then publishing and pinning;
 *   - publishing and pinning, with --image (Docker): the example's home pack zz published to a
 *     local registry:2.8.3 (from Docker Hub) with the two commands pack.yml's token jobs run
 *     (pack.mjs validate, then oci.mjs publish, later withdraw), anonymously, against that
 *     auth-less registry; the untouched yy pinning it by digest with `--update-pins`, setting up
 *     and building from the pin (a production build from a pinned pack, which guard-build
 *     accepts), then zz:1 withdrawn and yy's set-up stopped with the reason (publishAndPin). The
 *     token flow and pack.yml's own bash are tested in oci.test.mjs and pack-workflow.test.mjs;
 *     GHCR itself only a home's first publish shows;
 *   - the fixture upgrade: the example's work copy, set up at the zip's version, bumped to a zip
 *     and a kit package derived from them (fixtureZip: one English text changed, the example's
 *     overridden template changed, roleGuard deprecated with its removal version; fixtureKitZip:
 *     the kit's marker at the new version and an API-CHANGES.md section of it), must fail set-up
 *     on the override alone and write the upgrade report; after the routine actions of every bump
 *     (the home pack translated again, `--accept-override`) it sets up and builds green;
 *   - the upgrade test: the same mechanism from the example of the previous published release
 *     (--upgrade-from, and its kit package with --upgrade-kit-from, which it needs) to the zip and
 *     kit package under test. The previous example and API-CHANGES.md come from that kit package.
 *     When set-up finds the stub changed (the kit's STUB_CHANGED), the new stub is copied from the
 *     unpacked kit package and set-up runs once more, which must get past the stub and into its
 *     checks; the routine actions then take that run's overrides. A failure left after the
 *     routine actions passes only when an API-CHANGES.md section new since the previous release,
 *     found as the upgrade report finds it, says a country must act. Frontend CI gives the latest
 *     release's packages; without them the harness says the test is skipped, also in the job
 *     summary.
 *
 * The pure parts (the fixture zip and kit package, the API-CHANGES.md sections, the classification
 * of a set-up's errors, the stub's source, the verdict, the summary line) are exported for
 * acceptance.test.mjs; the steps take the harness's runner, so nothing here starts a process by
 * itself.
 */
import fs from 'node:fs';
import path from 'node:path';
import { serializeCatalog } from '../catalog.mjs';
import { packEntries } from '../lib/pack-archive.mjs';
import { lockText } from '../lib/pins.mjs';
import { STUB_CHANGED } from '../setup-kit.mjs';
import { newChangeSections, sectionActions } from '../lib/upgrade-report.mjs';
import { readZip, writeZip } from '../lib/zip.mjs';
import { sha256 } from '../source-zip.mjs';
import { fingerprint } from '../stage-languages.mjs';
import { kitBackendBytes, MARKER, markerText, where } from '../lib/where.mjs';
import { pseudoLocalise } from './pseudo-localise.mjs';

const { be: BE } = where();

/** The country init makes and nobody edits. */
export const UNTOUCHED = 'yy';
/** The local tag of the untouched country's public-site image; removed at the end. */
export const UNTOUCHED_TAG = 'niord-yy-info:example';
/** The fixture's next version, and the version its deprecation names as the one it goes in. */
export const NEXT_VERSION = '4.0.1-example';
export const REMOVED_IN = '4.0.2-example';
/** The administration app's English key whose text the fixture changes. */
export const FIXTURE_KEY = 'common.retry';
export const FIXTURE_TEXT = 'Try again';
/**
 * The base product file the example overrides and the fixture changes. A security fix the base
 * product records for it (security.json) shows in the fixture upgrade as a SECURITY line, which
 * classifySetup counts as routine: the fixture's own change, settled by `--accept-override`.
 */
export const FIXTURE_TEMPLATE =
  'ui-apps/admin/src/app/features/messages/components/message-selection-toggle/message-selection-toggle.html';
const ENGLISH_ADMIN = 'languages/en/i18n/admin/en.json';
const PUBLIC_API = 'ui-apps/admin/src/public-api.ts';
/** API-CHANGES.md in the kit package. */
const KIT_CHANGES = 'API-CHANGES.md';
const CATALOG = 'catalog.json';
/** The root's .niord/.gitignore that init and set-up write: the folder ignores itself. */
const NIORD_GITIGNORE = '*\n';
/** Where set-up writes the upgrade report, relative to the country's fe/, and its headings. */
export const REPORT = '.niord/niord-upgrade-report.md';
export const REPORT_HEADINGS = [
  '## Versions',
  '## To do',
  '## To look at',
  '## New for you',
  '## Dependency changes',
];
export const SKIPPED =
  "example: upgrade test: skipped — no previous niord-fe-src was given (--upgrade-from; Frontend CI takes the latest release's); the mechanism ran on the fixture pair";

const text = (bytes) => bytes.toString('utf8');
/** A text as a regular expression matching it literally. */
const literal = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The comment line the fixture appends to the overridden template. */
export const templateLine = (version) =>
  `<!-- ${version}: the example harness's fixture change to a file the example overrides -->`;

/**
 * The fixture's next version of a source zip, derived from its bytes: the English text of
 * FIXTURE_KEY changed, with its catalog fingerprint recomputed as the catalog computes it;
 * FIXTURE_TEMPLATE with a comment line appended; roleGuard's contract comment in the
 * administration app's API marked `Deprecated since <version>, removed in <removedIn>: …`, whose
 * API-CHANGES.md section the kit package's fixture adds (fixtureKitZip); and the catalog's version
 * set. Returns `{ zip, sha256 }`; the same input gives the same bytes. Throws when one of the three
 * is not where it should be.
 */
export function fixtureZip(zip, { version = NEXT_VERSION, removedIn = REMOVED_IN } = {}) {
  const entries = new Map(readZip(zip).map(({ name, data }) => [name, data]));
  const entry = (name) => {
    if (!entries.has(name)) throw new Error(`the zip has no ${name}`);
    return text(entries.get(name));
  };

  const english = JSON.parse(entry(ENGLISH_ADMIN));
  const trail = FIXTURE_KEY.split('.');
  const holder = trail.slice(0, -1).reduce((node, part) => node?.[part], english);
  const last = trail.at(-1);
  if (typeof holder?.[last] !== 'string')
    throw new Error(`${ENGLISH_ADMIN} has no text ${FIXTURE_KEY}`);
  holder[last] = FIXTURE_TEXT;
  entries.set(ENGLISH_ADMIN, Buffer.from(`${JSON.stringify(english, null, 2)}\n`));

  const catalog = JSON.parse(entry(CATALOG));
  const key = catalog.apps?.admin?.[FIXTURE_KEY];
  if (!key) throw new Error(`${CATALOG} has no admin key ${FIXTURE_KEY}`);
  if (key.placeholders?.length || key.markup)
    throw new Error(`${FIXTURE_KEY} has placeholders or markup; the fixture changes a plain text`);
  key.fp = fingerprint(FIXTURE_TEXT);
  catalog.version = version;
  entries.set(CATALOG, Buffer.from(serializeCatalog(catalog)));

  const template = entry(FIXTURE_TEMPLATE);
  entries.set(
    FIXTURE_TEMPLATE,
    Buffer.from(`${template}${template.endsWith('\n') ? '' : '\n'}${templateLine(version)}\n`),
  );

  const api = entry(PUBLIC_API);
  const marked = api.replace(
    /(\r?\n)( \*\/\r?\nexport \{ roleGuard \})/,
    `$1 *$1 * Deprecated since ${version}, removed in ${removedIn}: the guard a later version names$1$2`,
  );
  if (marked === api) throw new Error(`${PUBLIC_API} has no roleGuard statement`);
  entries.set(PUBLIC_API, Buffer.from(marked));

  const next = writeZip([...entries].map(([name, data]) => ({ name, data })));
  return { zip: next, sha256: sha256(next) };
}

/**
 * The fixture's next version of a kit package, derived from its bytes: its marker (kit.json) at
 * `version`, and an API-CHANGES.md section of that version above Unreleased for the deprecation
 * fixtureZip makes, which asks nothing of a country. Returns `{ zip, sha256 }`; the same input
 * gives the same bytes. Throws when the package has no marker or no Unreleased section.
 */
export function fixtureKitZip(kitZip, { version = NEXT_VERSION, removedIn = REMOVED_IN } = {}) {
  const entries = new Map(readZip(kitZip).map(({ name, data }) => [name, data]));
  if (!entries.has(MARKER)) throw new Error(`the kit package has no ${MARKER}`);
  entries.set(MARKER, Buffer.from(markerText(version)));
  if (!entries.has(KIT_CHANGES)) throw new Error(`the kit package has no ${KIT_CHANGES}`);
  const changes = text(entries.get(KIT_CHANGES));
  const at = changes.search(/^## Unreleased/m);
  if (at < 0) throw new Error(`${KIT_CHANGES} has no Unreleased section`);
  const section = [
    `## ${version}`,
    '',
    `**\`roleGuard\` from \`@niord/admin/api\` is deprecated** (the example harness's fixture`,
    'version, never published). Why: the fixture upgrade needs a deprecated export the example uses.',
    '',
    `What a country must do: nothing in ${version}; \`roleGuard\` goes in ${removedIn}.`,
    '',
    '',
  ].join('\n');
  entries.set(KIT_CHANGES, Buffer.from(`${changes.slice(0, at)}${section}${changes.slice(at)}`));

  const next = writeZip([...entries].map(([name, data]) => ({ name, data })));
  return { zip: next, sha256: sha256(next) };
}

/**
 * sectionActions' text for an entry or section that names no action: an ask to read, never an
 * action that explains a failure.
 */
const READ_IT = /names no action: read it$/;

/**
 * The sections of the API-CHANGES.md text `current` that the upgrade report lists for a country
 * coming from `previousVersion`, whose copy of the file is `previous` (undefined when it is not at
 * hand): the version rule first, then each entry against the previous copy's Unreleased
 * (newChangeSections). `[{ heading, version, entries, musts, action }]`, `musts` what
 * sectionActions asks of each and `action` true when one of them is not an ask to read: a
 * section or an entry that names no action never explains a failure. A policy section is never
 * one.
 */
export function upgradeSections(current, previous, previousVersion) {
  return newChangeSections(current, previous, previousVersion).map((section) => {
    const musts = sectionActions(section);
    return {
      heading: section.heading,
      version: section.version,
      entries: section.entries,
      musts,
      action: musts.some((must) => !READ_IT.test(must)),
    };
  });
}

const OVERRIDE_CHANGED =
  /^override (\S+): the base product changed (?:(?! and removed ).)+ since the copy was based on it/;
const FIXTURE_SECURITY = new RegExp(`^SECURITY: \\S+ fixed ${literal(FIXTURE_TEMPLATE)} `);

/**
 * A set-up's findings, read from its output: `{ errors, routine, stub, other }`. `routine` is the
 * files of the overrides whose base files changed (each `--accept-override`'s argument), the one
 * finding every bump may bring that the country settles without deciding anything; `stub` is true
 * when the base product changed the stub (setup-kit.mjs STUB_CHANGED), which the country settles
 * by copying the kit's template; every other error is `other`. A SECURITY line for
 * FIXTURE_TEMPLATE is routine too: the fixture changes that file on every run, so a security fix
 * the base product once recorded for it would otherwise turn every later run red, and
 * `--accept-override`, the routine action, settles it. A set-up that failed before its checks
 * printed none is one `other` line.
 */
export function classifySetup({ status, output }) {
  const errors = output
    .split(/\r?\n/)
    .map((line) => /^setup: error: (.*)$/.exec(line)?.[1])
    .filter(Boolean);
  const routine = [];
  const other = [];
  let stub = false;
  for (const error of errors) {
    const match = OVERRIDE_CHANGED.exec(error);
    if (match) routine.push(match[1]);
    else if (error === STUB_CHANGED) stub = true;
    else if (!FIXTURE_SECURITY.test(error)) other.push(error);
  }
  if (status !== 0 && !errors.length)
    other.push(`set-up failed before its checks (exit ${status})`);
  return { errors, routine: [...new Set(routine)], stub, other };
}

/**
 * Whether an upgrade passes: `failure` is what still fails after the routine actions (undefined
 * when nothing does), `sections` the API-CHANGES.md sections new since the previous version.
 * `{ pass, lines }`: a failure passes only when a section says a country must act, which `lines`
 * names.
 */
export function upgradeVerdict({ failure, sections }) {
  if (!failure) return { pass: true, lines: [] };
  const acting = sections.filter((section) => section.action);
  if (!acting.length)
    return {
      pass: false,
      lines: [
        `${failure}, and no section of API-CHANGES.md new since the previous version says a country must act`,
      ],
    };
  return {
    pass: true,
    lines: [
      `${failure}, which API-CHANGES.md says a country must act on:`,
      ...acting.map((section) => `  ${section.heading}: ${section.musts.join(' / ')}`),
    ],
  };
}

/**
 * Appends lines to the job summary when $GITHUB_STEP_SUMMARY names one, each a paragraph of its
 * own after what the job's steps wrote before; true when it did.
 */
export function writeStepSummary(lines, env = process.env) {
  if (!env.GITHUB_STEP_SUMMARY) return false;
  fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `\n${lines.join('\n\n')}\n`);
  return true;
}

/** Problems with an upgrade report's text: a heading missing or out of §4.12's order. */
export function reportProblems(report, { country, from, to }) {
  const problems = [];
  const title = `# Upgrade report: ${country} from ${from} to ${to}`;
  if (!report.split(/\r?\n/).includes(title)) problems.push(`it has no line "${title}"`);
  let at = -1;
  for (const heading of REPORT_HEADINGS) {
    const found = report.search(new RegExp(`^${heading}\\s*$`, 'm'));
    if (found < 0) problems.push(`it has no heading "${heading}"`);
    else if (found < at) problems.push(`"${heading}" is out of order`);
    else at = found;
  }
  return problems;
}

/** Writes the entries whose names start with `prefix` into `target`, the prefix taken off. */
export function writeEntries(entries, prefix, target) {
  let count = 0;
  for (const { name, data } of entries) {
    if (!name.startsWith(prefix)) continue;
    const file = path.join(target, ...name.slice(prefix.length).split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, data);
    count++;
  }
  return count;
}

/** The base product's Maven wrapper, copied into a country's be/. */
export function copyWrapper(be) {
  fs.copyFileSync(path.join(BE, 'mvnw'), path.join(be, 'mvnw'));
  fs.copyFileSync(path.join(BE, 'mvnw.cmd'), path.join(be, 'mvnw.cmd'));
  fs.cpSync(path.join(BE, '.mvn'), path.join(be, '.mvn'), { recursive: true });
  fs.chmodSync(path.join(be, 'mvnw'), 0o755);
}

/** A country's be/pom.xml with its parent's version set to `version`. */
export function bumpParent(pom, version) {
  const bumped = pom.replace(
    /(<parent>[\s\S]*?<version>)\s*[^<]*?\s*(<\/version>)/,
    `$1${version}$2`,
  );
  if (bumped === pom && !pom.includes(`<version>${version}</version>`))
    throw new Error('be/pom.xml has no <parent> with a <version>');
  return bumped;
}

/**
 * The untouched country's answers, made up: no real country's data. No language besides English,
 * so its pom has no pack block before a pin exists; the first domain and series take the
 * standard defaults.
 */
export const YY_ANSWERS = Object.freeze({
  country: UNTOUCHED,
  name: 'Example Country YY',
  organisation: 'The Example Maritime Authority',
  producerCode: 'YY00',
  timeZone: 'Europe/London',
  map: Object.freeze({ lat: 50, lon: -20 }),
  owners: Object.freeze(['@example']),
});

/** The release notes' two lines for both packages, as sha256sum writes them. */
export const sha256Sums = ({ version, sha256: hash, kitSha256 }) =>
  `${hash}  niord-fe-src-${version}.zip\n${kitSha256}  niord-country-kit-${version}.zip\n`;

/** The lines of init's output that name a file it kept. */
export const keptLines = (output) =>
  output
    .split(/\r?\n/)
    .filter((line) => line.startsWith('init: kept '))
    .map((line) => line.trimEnd());

/** The Maven wrapper's files in a country's be/, by their key in lib/where.mjs KIT_BACKEND. */
const WRAPPER = {
  mvnw: 'mvnw',
  mvnwCmd: 'mvnw.cmd',
  wrapperProperties: '.mvn/wrapper/maven-wrapper.properties',
};
/** The files of a country's be/ that init writes with mode 0755. */
const EXECUTABLES = ['mvnw', 'scripts/check-country-build-group.sh'];

/**
 * What keeps the Maven wrapper in the country's backend folder `be` from being the tree's, one
 * line each: a file missing or with other bytes than the tree's be/ gives with the kit package's
 * line ends (kitBackendBytes), and, off Windows, be/mvnw or the group script without exec bits.
 */
export function wrapperProblems(be, platform = process.platform) {
  const problems = [];
  for (const [key, relative] of Object.entries(WRAPPER)) {
    const file = path.join(be, ...relative.split('/'));
    if (!fs.existsSync(file)) problems.push(`be/${relative} is missing`);
    else if (!fs.readFileSync(file).equals(kitBackendBytes(key)))
      problems.push(`be/${relative} differs from the Maven wrapper of the tree's be/`);
  }
  if (platform !== 'win32')
    for (const relative of EXECUTABLES) {
      const stat = fs.statSync(path.join(be, ...relative.split('/')), { throwIfNoEntry: false });
      if (stat && !(stat.mode & 0o111))
        problems.push(`be/${relative} is not executable (mode 0755)`);
      else if (!stat) problems.push(`be/${relative} is missing`);
    }
  return [...new Set(problems)];
}

/** The line for the job summary when the generated country's backend was built (--backend). */
export const BACKEND_BUILT =
  "example: yy's backend built from init's scaffold: be/mvnw verify with the country build group on Docker, the group check and the override check";
/** The line for the job summary without --backend. */
export const BACKEND_SKIPPED =
  "example: the generated country's backend: not built (no --backend); Frontend CI passes it when a scaffold input changed, and Backend CI's Generated country job builds it";

/** Throws with `what` unless `pattern` is found in `output`. */
function expectIn(output, pattern, what) {
  if (!pattern.test(output)) throw new Error(`${what} (${pattern} not in the output)`);
}

/** Throws with `what` when `pattern` is found in `output`. */
function expectNotIn(output, pattern, what) {
  if (pattern.test(output)) throw new Error(`${what} (${pattern} in the output)`);
}

const NO_BASELINE = /info\/bundle-baseline\.json does not exist/;

/**
 * The untouched init in `<work>/yy`, from the source zip `zip` and the kit package `kitZip` of
 * `version` and their hashes, as the Quick start starts a country: both packages and SHA256SUMS
 * in an empty repository's .niord/, the kit package unzipped into .niord/kit/, the answers
 * (YY_ANSWERS) in .niord/init-answers.json, which .niord/.gitignore keeps out of git, then
 * `node .niord/kit/init.mjs --answers … --sha256-file …`; then `node setup.mjs`. Init must keep
 * nothing (no file is there before it) and write the tree's Maven wrapper (wrapperProblems).
 * `h` is the harness: `{ step, run, env, … }`, `run(command, args, { cwd, env, capture,
 * allowFailure })` returning `{ status, output }`.
 */
export function untouchedInit({ work, zip, sha256: hash, kitZip, kitSha256, version, h }) {
  const root = path.join(work, UNTOUCHED);
  const niord = path.join(root, '.niord');
  const { step, run, env } = h;
  step(
    `${UNTOUCHED}: init from the two packages, as the Quick start: --answers and --sha256-file`,
    () => {
      fs.rmSync(root, { recursive: true, force: true });
      fs.mkdirSync(niord, { recursive: true });
      fs.writeFileSync(path.join(niord, `niord-fe-src-${version}.zip`), zip);
      fs.writeFileSync(path.join(niord, `niord-country-kit-${version}.zip`), kitZip);
      fs.writeFileSync(
        path.join(niord, 'SHA256SUMS'),
        sha256Sums({ version, sha256: hash, kitSha256 }),
      );
      writeEntries(readZip(kitZip), '', path.join(niord, 'kit'));
      fs.writeFileSync(
        path.join(niord, 'init-answers.json'),
        `${JSON.stringify(YY_ANSWERS, null, 2)}\n`,
      );
      const { output } = run(
        process.execPath,
        [
          '.niord/kit/init.mjs',
          '--answers',
          '.niord/init-answers.json',
          '--sha256-file',
          '.niord/SHA256SUMS',
        ],
        { cwd: root, env, capture: true },
      );
      const kept = keptLines(output).map(
        (line) => /^init: kept (.+?)(?:, which exists)?$/.exec(line)[1],
      );
      if (kept.length)
        throw new Error(`init kept ${kept.join(', ')}: the untouched init must write every file`);
      const problems = wrapperProblems(path.join(root, 'be'));
      if (problems.length) throw new Error(problems.join('; '));
      const ignore = path.join(niord, '.gitignore');
      const written = fs.existsSync(ignore) ? fs.readFileSync(ignore, 'utf8') : undefined;
      if (written !== NIORD_GITIGNORE)
        throw new Error(
          `init left .niord/.gitignore ${written === undefined ? 'unwritten' : `holding ${JSON.stringify(written)}`}, not ${JSON.stringify(NIORD_GITIGNORE)}`,
        );
    },
  );
  step(`${UNTOUCHED}: node setup.mjs`, () =>
    run(process.execPath, ['setup.mjs'], { cwd: path.join(root, 'fe'), env }),
  );
}

/**
 * The untouched country's backend, with --backend: niord-base of `version` and its test-jar
 * installed from the tree's backend folder `base` into the scratch Maven repository (niord-parent
 * of `version` is there from the harness's install step; -DskipTests still compiles and packages
 * the test-jar), both jars checked to be there; yy's `be/mvnw verify` with Docker, its
 * integration tests on Testcontainers; then the two checks the country's ci.yml runs after the
 * suite: the group count, be/scripts/check-country-build-group.sh, and the base product's
 * check-overrides.sh over the packaged jar against be/overrides.json. `h` adds to the harness
 * `{ maven(be, args, { env }), bash, backendEnv, m2 }`: backendEnv carries NIORD_HOME with
 * forward slashes on Windows (potential-errors.md PORT-1).
 */
export function untouchedBackend({ work, version, base = BE, h }) {
  const be = path.join(work, UNTOUCHED, 'be');
  const env = h.backendEnv;
  h.step(
    `${UNTOUCHED}: niord-base ${version} and its test-jar installed into the scratch Maven repository, from the working tree's be/`,
    () => {
      h.maven(base, [
        '-B',
        '-ntp',
        `-Drevision=${version}`,
        '-pl',
        'niord',
        'install',
        '-DskipTests',
      ]);
      const folder = path.join(h.m2, 'org', 'niord', 'base', 'niord-base', version);
      const missing = [`niord-base-${version}.jar`, `niord-base-${version}-tests.jar`].filter(
        (jar) => !fs.existsSync(path.join(folder, jar)),
      );
      if (missing.length)
        throw new Error(
          `the scratch Maven repository has no ${missing.join(' and no ')} in ${folder}`,
        );
    },
  );
  h.step(
    `${UNTOUCHED}: be/mvnw verify with Docker: the country build group, CountryDefaultsChecksTest over the generated settings, CountryContractGuardTest over the generated application.yml`,
    () => h.maven(be, ['-B', '-ntp', 'verify'], { env }),
  );
  h.step(
    `${UNTOUCHED}: be/scripts/check-country-build-group.sh, as the country's ci.yml runs it`,
    () => {
      const { output } = h.run(h.bash, ['scripts/check-country-build-group.sh'], {
        cwd: be,
        env,
        capture: true,
      });
      expectIn(
        output,
        /^the country build group ran: [1-9][0-9]* integration tests/m,
        'the country build group ran no integration test',
      );
    },
  );
  const jar = `target/niord-${UNTOUCHED}-${version}.jar`;
  h.step(`${UNTOUCHED}: the override check over ${jar}, as the country's ci.yml runs it`, () =>
    h.run(h.bash, [`${base.replaceAll('\\', '/')}/k8s/scripts/check-overrides.sh`, jar, '.'], {
      cwd: be,
      env,
    }),
  );
}

/**
 * The untouched country's frontend after set-up: npm ci, the build with check-build's warning
 * that there is no bundle baseline yet, `npm test`, `npm run lint`, `npm run e2e -- --list`, the
 * baseline started, and with --image (h.image) its public-site image.
 */
export function untouchedFrontend({ work, h }) {
  const fe = path.join(work, UNTOUCHED, 'fe');
  const { step, run, env } = h;
  step(`${UNTOUCHED}: npm ci`, () =>
    run('npm', ['ci', '--prefer-offline', '--no-audit', '--no-fund'], { cwd: fe, env }),
  );
  step(`${UNTOUCHED}: npm run build, warning that there is no bundle baseline yet`, () => {
    const { output } = run('npm', ['run', 'build'], { cwd: fe, env, capture: true });
    expectIn(output, NO_BASELINE, 'check-build did not warn that the country has no baseline yet');
  });
  step(`${UNTOUCHED}: npm test`, () => run('npm', ['test'], { cwd: fe, env }));
  step(`${UNTOUCHED}: npm run lint`, () => run('npm', ['run', 'lint'], { cwd: fe, env }));
  step(`${UNTOUCHED}: npm run e2e -- --list, no spec yet`, () =>
    run('npm', ['run', 'e2e', '--', '--list'], { cwd: fe, env }),
  );
  step(`${UNTOUCHED}: the bundle baseline started as check-build's warning says`, () => {
    fs.copyFileSync(
      path.join(fe, '.niord', 'base', 'ui-apps', 'info', 'bundle-baseline.json'),
      path.join(fe, 'info', 'bundle-baseline.json'),
    );
    run('npm', ['run', 'bundle:info', '--', '--update-baseline'], { cwd: fe, env });
    const { output } = run(
      process.execPath,
      [path.join('..', '.niord', 'kit', 'check-build.mjs')],
      {
        cwd: fe,
        env,
        capture: true,
      },
    );
    expectNotIn(output, NO_BASELINE, 'check-build still warns about the baseline');
  });
  if (!h.image) return;
  try {
    step(`${UNTOUCHED}: the public-site image, with no legal document and no branding text`, () => {
      const { output } = run('npm', ['run', 'image:info', '--', '--tag', UNTOUCHED_TAG], {
        cwd: fe,
        env,
        capture: true,
      });
      expectIn(
        output,
        /warning: .*holds no legal document.*\(--no-legal-doc\): add info\/assets\/legal\/disclaimer\.en\.html/,
        'image:info did not warn that there is no legal document',
      );
      expectIn(
        output,
        /warning: .*holds no en\.json.*\(--no-branding-text\): add info\/assets\/branding\/en\.json/,
        'image:info did not warn that there is no branding text',
      );
    });
  } finally {
    h.removeImage(UNTOUCHED_TAG);
  }
}

/**
 * The untouched country, in this order: init and set-up (untouchedInit); its backend when
 * `h.backend` (untouchedBackend); unless `h.skipFrontend`, its frontend (untouchedFrontend) and
 * then, with `h.image`, publishing and pinning zz (`publish`, publishAndPin). Returns the
 * publishing step's line for the job summary: REGISTRY_SKIPPED without --image, null with
 * --skip-frontend.
 */
export function untouched({
  work,
  zip,
  sha256: hash,
  kitZip,
  kitSha256,
  version,
  country,
  catalog,
  h,
  publish = publishAndPin,
}) {
  untouchedInit({ work, zip, sha256: hash, kitZip, kitSha256, version, h });
  if (h.backend) untouchedBackend({ work, version, h });
  if (h.skipFrontend) return null;
  untouchedFrontend({ work, h });
  return h.image ? publish({ work, country, catalog, h }) : REGISTRY_SKIPPED;
}

/**
 * The registry the harness publishes to: Docker's registry:2, pulled from Docker Hub, on
 * 127.0.0.1 and a free port.
 */
export const REGISTRY_IMAGE = 'registry:2.8.3';
/** The owner the example publishes zz under, and the reason its withdrawal gives. */
export const PACK_OWNER = 'example';
export const WITHDRAW_REASON = 'the example harness withdraws its first publish';
export const REGISTRY_SKIPPED =
  'example: publishing and pinning zz: skipped — the local registry needs Docker (--image); the OCI client, pack.yml and set-up ran on the in-process registry of their tests';
/** The step's line for the job summary when it ran; verify-local's row repeats it. */
export const REGISTRY_PUBLISHED = `example: zz published to a local ${REGISTRY_IMAGE} with the two commands pack.yml's token jobs run (pack.mjs validate, then oci.mjs publish), anonymously, pinned by digest in ${UNTOUCHED} with --update-pins, set up and built from the pin, then withdrawn with oci.mjs withdraw, which stopped set-up with the reason`;

const REGISTRY_PREFIX = 'niord-example-registry-';

/** The harness's registry container name, one per run. */
export const registryName = (pid = process.pid) => `${REGISTRY_PREFIX}${pid}`;

/**
 * The registry containers among `names` that an interrupted run left: named
 * niord-example-registry-<pid>, the pid neither this run's (`self`) nor a live process's
 * (`isAlive`), so the registry of a run going on beside this one is never touched.
 */
export function staleRegistries(names, isAlive, self = process.pid) {
  return names.filter((name) => {
    const pid = new RegExp(`^${REGISTRY_PREFIX}([0-9]+)$`).exec(name)?.[1];
    return pid !== undefined && Number(pid) !== self && !isAlive(Number(pid));
  });
}

/** Whether a process `pid` runs: signal 0 tests it, ESRCH when there is none. */
function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code !== 'ESRCH';
  }
}

/** Removes the registry containers interrupted runs left (staleRegistries). */
function removeStaleRegistries(h) {
  const { output } = h.run(
    'docker',
    ['ps', '-a', '--filter', `name=${REGISTRY_PREFIX}`, '--format', '{{.Names}}'],
    { capture: true, quiet: true },
  );
  const names = output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  for (const name of staleRegistries(names, processAlive)) {
    console.log(`example: removing ${name}, the registry of an interrupted run`);
    h.removeContainer(name);
  }
}

/**
 * The host port `docker port <container> 5000/tcp` printed, for its 127.0.0.1 binding; throws
 * when there is none (a registry bound elsewhere is never used).
 */
export function dockerPort(output) {
  const ports = output
    .split(/\r?\n/)
    .map((line) => /^127\.0\.0\.1:([0-9]{1,5})$/.exec(line.trim())?.[1])
    .filter(Boolean);
  if (ports.length !== 1)
    throw new Error(`docker port printed no one 127.0.0.1 binding: ${output.trim()}`);
  return Number(ports[0]);
}

/**
 * The untouched country's manifest with zz pinned under the example's owner, as a UI and content
 * language of the administration app; the public site stays English.
 */
export function pinnedManifest(manifest, lang = 'zz', owner = PACK_OWNER) {
  const admin = manifest.apps.admin;
  return {
    ...manifest,
    apps: {
      ...manifest.apps,
      admin: {
        ...admin,
        uiLanguages: [...new Set([...admin.uiLanguages, lang])],
        contentLanguages: [...new Set([...admin.contentLanguages, lang])],
      },
    },
    packs: { ...manifest.packs, [lang]: { pinned: true, owner } },
  };
}

/**
 * What keeps `.niord/packs/<lang>/` of the country `fe` from holding exactly the canonical
 * archive of its home pack `packs/<lang>/`, one line each: a file missing, one more, or other bytes.
 */
export function stagedPackProblems(fe, lang) {
  const expected = new Map(
    packEntries(path.join(fe, 'packs', lang), lang).map(({ name, data }) => [name, data]),
  );
  const staged = path.join(fe, '.niord', 'packs', lang);
  if (!fs.existsSync(staged)) return [`.niord/packs/${lang}/ does not exist`];
  const found = fs
    .readdirSync(staged, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(staged, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    );
  const problems = [];
  for (const name of found)
    if (!expected.has(name)) problems.push(`.niord/packs/${lang}/${name} is not in the archive`);
    else if (!fs.readFileSync(path.join(staged, name)).equals(expected.get(name)))
      problems.push(`.niord/packs/${lang}/${name} differs from the archive's`);
  for (const name of expected.keys())
    if (!found.includes(name)) problems.push(`.niord/packs/${lang}/${name} is missing`);
  return problems;
}

/** Waits until the registry at `host` answers /v2/, or throws after about 30 s. */
function waitForRegistry(host, run) {
  const probe = `fetch('http://${host}/v2/').then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))`;
  for (let attempt = 0; attempt < 60; attempt++) {
    if (
      run(process.execPath, ['-e', probe], { allowFailure: true, capture: true, quiet: true })
        .status === 0
    )
      return;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
  }
  throw new Error(`the registry at ${host} did not answer /v2/`);
}

/**
 * zz published and pinned through a local registry, with Docker (--image): the registry started on
 * 127.0.0.1 and a free port; zz archived and published from the example `country` with the two
 * commands pack.yml's token jobs run (`pack.mjs validate` against `catalog`, then the kit's
 * lib/oci.mjs `publish`), anonymously, against that auth-less registry; the untouched country yy,
 * already built, given zz as a pinned pack, its pin written by `node setup.mjs --update-pins`
 * against that registry, then set up, guard-build accepting the pinned pack, and built; zz:1
 * withdrawn with oci.mjs `withdraw`, after which yy's set-up fails naming the reason. Returns the
 * line for the job summary.
 *
 * The registry container, niord-example-registry-<pid>, and the registry image when the run
 * pulled it, are removed at the end on every exit the harness reaches. An interrupted run (Ctrl-C)
 * leaves its container: the next run removes it before it starts its own, or remove it by hand
 * with `docker rm -f niord-example-registry-<pid>`.
 */
export function publishAndPin({ work, country, catalog, h }) {
  const xx = path.join(country, 'fe');
  const yy = path.join(work, UNTOUCHED, 'fe');
  const client = path.join(country, '.niord', 'kit', 'lib', 'oci.mjs');
  const name = registryName();
  const pulled =
    h.run('docker', ['image', 'inspect', REGISTRY_IMAGE], {
      allowFailure: true,
      capture: true,
      quiet: true,
    }).status !== 0;
  let host;
  let reference;
  try {
    h.step(`a local registry (${REGISTRY_IMAGE}) on 127.0.0.1`, () => {
      removeStaleRegistries(h);
      h.run('docker', ['run', '-d', '--name', name, '-p', '127.0.0.1::5000', REGISTRY_IMAGE], {
        capture: true,
      });
      host = `127.0.0.1:${dockerPort(h.run('docker', ['port', name, '5000/tcp'], { capture: true }).output)}`;
      waitForRegistry(host, h.run);
      console.log(`example: the registry answers at http://${host}`);
    });
    const packEnv = {
      ...h.env,
      PACK_LANG: 'zz',
      PACK_REGISTRY: `http://${host}`,
      PACK_TOKEN: 'unused',
      GITHUB_ACTOR: PACK_OWNER,
      GITHUB_REPOSITORY_OWNER: PACK_OWNER,
      GITHUB_REPOSITORY: `${PACK_OWNER}/niord-xx`,
      GITHUB_RUN_NUMBER: '1',
      GITHUB_STEP_SUMMARY: path.join(work, 'pack-summary.md'),
    };
    delete packEnv.GITHUB_OUTPUT;
    h.step(
      "xx: zz published with pack.yml's two commands, anonymously: validate's archive, then oci.mjs publish",
      () => {
        const out = path.join(work, 'pack');
        fs.rmSync(out, { recursive: true, force: true });
        const validated = h.run(
          process.execPath,
          [
            path.join('..', '.niord', 'kit', 'pack.mjs'),
            'validate',
            'zz',
            '--catalog',
            catalog,
            '--out',
            out,
          ],
          { cwd: xx, env: packEnv, capture: true },
        );
        expectIn(
          validated.output,
          /^publish: true$/m,
          'pack.mjs validate did not say zz is published',
        );
        const line = fs.readFileSync(path.join(out, 'niord-lang-zz.zip.sha256'), 'utf8');
        const sha = /^([0-9a-f]{64}) {2}niord-lang-zz\.zip\s*$/.exec(line)?.[1];
        if (!sha) throw new Error(`niord-lang-zz.zip.sha256 is not one sha256sum line: ${line}`);
        const archive = path.join(out, 'niord-lang-zz.zip');
        const published = h.run(
          process.execPath,
          [client, 'publish', '--archive', archive, '--sha256', sha],
          { cwd: work, env: packEnv, capture: true },
        );
        reference = new RegExp(
          `published (${literal(host)}/${PACK_OWNER}/niord-lang-zz:1@sha256:[0-9a-f]{64})`,
        ).exec(published.output)?.[1];
        if (!reference) throw new Error('oci.mjs publish printed no reference');
        expectIn(
          published.output,
          /the first publish of niord-lang-zz: the empty withdrawn marker pushed/,
          'the first publish pushed no withdrawn marker',
        );
      },
    );
    const yyEnv = { ...h.env };
    delete yyEnv.NIORD_PACK_REGISTRY;
    h.step(`${UNTOUCHED}: zz pinned by its digest with node setup.mjs --update-pins`, () => {
      const file = path.join(yy, 'niord-country.json');
      const manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
      fs.writeFileSync(file, `${JSON.stringify(pinnedManifest(manifest), null, 2)}\n`);
      const { output } = h.run(process.execPath, ['setup.mjs', '--update-pins'], {
        cwd: yy,
        env: { ...yyEnv, NIORD_PACK_REGISTRY: `http://${host}` },
        capture: true,
      });
      expectIn(
        output,
        new RegExp(`zz: no pin -> ${literal(reference)}`),
        '--update-pins did not pin zz:1',
      );
      const lock = JSON.parse(fs.readFileSync(path.join(yy, 'niord-pins.lock.json'), 'utf8'));
      if (lock.packs.zz !== reference)
        throw new Error(`the lock pins ${lock.packs.zz}, not ${reference}`);
    });
    h.step(`${UNTOUCHED}: node setup.mjs from the pinned zz, and guard-build accepts it`, () => {
      const { output } = h.run(process.execPath, ['setup.mjs'], {
        cwd: yy,
        env: yyEnv,
        capture: true,
      });
      expectIn(
        output,
        /setup: pack zz: .* (pulled and verified|verified from \.niord\/oci\/), staged into \.niord\/packs\/zz\//,
        'set-up did not stage the pinned zz',
      );
      const report = JSON.parse(
        fs.readFileSync(path.join(yy, '.niord', 'build', 'niord-build-report.json'), 'utf8'),
      );
      if (report.packs.zz?.source !== 'pinned' || report.packs.zz?.reference !== reference)
        throw new Error(`the build report records zz as ${JSON.stringify(report.packs.zz)}`);
      if (!fs.existsSync(path.join(yy, '.niord', 'packs', 'zz', 'pack.json')))
        throw new Error('.niord/packs/zz/ holds no pack.json');
      h.run(process.execPath, [path.join('..', '.niord', 'kit', 'guard-build.mjs')], {
        cwd: yy,
        env: yyEnv,
      });
    });
    h.step(`${UNTOUCHED}: npm run build, a production build from the pinned zz`, () =>
      h.run('npm', ['run', 'build'], { cwd: yy, env: yyEnv }),
    );
    h.step("zz:1 withdrawn with oci.mjs withdraw; yy's set-up then fails naming the reason", () => {
      const withdrawn = h.run(process.execPath, [client, 'withdraw'], {
        cwd: work,
        env: { ...packEnv, PACK_TAG: '1', PACK_REASON: WITHDRAW_REASON },
        capture: true,
      });
      expectIn(
        withdrawn.output,
        new RegExp(`withdrew ${literal(reference)}`),
        'oci.mjs withdraw did not withdraw zz:1',
      );
      const setup = h.run(process.execPath, ['setup.mjs'], {
        cwd: yy,
        env: yyEnv,
        capture: true,
        allowFailure: true,
      });
      if (setup.status === 0) throw new Error("yy's set-up passed on a withdrawn pin");
      expectIn(
        setup.output,
        new RegExp(
          `setup: error: pack zz: ${literal(reference)} is withdrawn \\(\\d{4}-\\d{2}-\\d{2}\\): ${literal(WITHDRAW_REASON)}`,
        ),
        "yy's set-up did not name the withdrawn pin and its reason",
      );
    });
  } finally {
    h.removeContainer(name);
    if (pulled) h.removeImage(REGISTRY_IMAGE);
  }
  return REGISTRY_PUBLISHED;
}

/** The English pack of a zip (languages/en/) written into `dir`, for the pseudo-localiser. */
function englishOf(zip, dir) {
  fs.rmSync(dir, { recursive: true, force: true });
  writeEntries(
    readZip(zip).filter(({ name }) => name.startsWith('languages/en/')),
    '',
    dir,
  );
  return dir;
}

/** The data of the zip entry `name`; throws when there is none. */
function entryNamed(entries, name) {
  const found = entries.find((entry) => entry.name === name);
  if (!found) throw new Error(`the zip has no ${name}`);
  return found.data;
}

/** The reason an escape-hatch entry of the upgrade test gives. */
export const HATCH_REASON = 'the previous English text is not in the zip';

/**
 * Adds the backend keys `skipped` (`[{ bundle, key }]`) to the escape hatch of the country `fe`,
 * missing-keys-accepted.json, for zz; returns the entries added.
 */
export function addToHatch(fe, skipped, today = new Date().toISOString().slice(0, 10)) {
  const file = path.join(fe, 'missing-keys-accepted.json');
  const hatch = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : [];
  const added = skipped.map(({ bundle, key }) => ({
    lang: 'zz',
    app: 'backend',
    bundle,
    key,
    reason: HATCH_REASON,
    added: today,
  }));
  fs.writeFileSync(file, `${JSON.stringify([...hatch, ...added], null, 2)}\n`);
  return added;
}

/**
 * The country's be/pom.xml and pins moved to `version`, the source zip's `hash` and, when given,
 * the kit package's `kitHash`.
 */
export function bump(country, version, hash, kitHash) {
  const pom = path.join(country, 'be', 'pom.xml');
  fs.writeFileSync(pom, bumpParent(fs.readFileSync(pom, 'utf8'), version));
  fs.writeFileSync(
    path.join(country, 'fe', 'niord-pins.lock.json'),
    lockText({ version, sha256: hash, ...(kitHash ? { kit: { sha256: kitHash } } : {}) }),
  );
}

/**
 * Where the country's new stub is: the unpacked kit package's template,
 * .niord/kit/templates/setup.mjs, which set-up fetched. Throws naming it when it is not there.
 */
export function stubSource(country) {
  const file = path.join(country, '.niord', 'kit', 'templates', 'setup.mjs');
  if (!fs.existsSync(file)) throw new Error(`the kit's stub is not at ${file}`);
  return file;
}

/** The routine action for a stub the base product changed: its new copy over fe/setup.mjs. */
export function copyStub(country, h) {
  h.step(`${path.basename(country)}: the kit's new stub copied over setup.mjs`, () =>
    fs.copyFileSync(stubSource(country), path.join(country, 'fe', 'setup.mjs')),
  );
}

/**
 * The routine actions of a bump after the stub (copyStub): the home pack zz translated again
 * from the new version's English (the pseudo-localiser) with the zz SITE_TITLE written into the
 * public site's page when the country has one (info/app/index.html), and `--accept-override` for
 * each override in `routine`. The page's lang is the country's own and is left as it is. Returns
 * the accept runs' outputs.
 */
function routineActions({ country, zip, routine, work, h }) {
  const fe = path.join(country, 'fe');
  h.step(`${path.basename(country)}: zz translated from the new English`, () => {
    const english = englishOf(zip, path.join(work, `${path.basename(country)}-english`));
    const index = path.join(fe, 'info', 'app', 'index.html');
    pseudoLocalise({
      fe: english,
      be: BE,
      out: path.join(fe, 'packs', 'zz'),
      ...(fs.existsSync(index) ? { index } : {}),
    });
    fs.rmSync(english, { recursive: true, force: true });
  });
  const outputs = [];
  for (const file of routine)
    h.step(
      `${path.basename(country)}: node setup.mjs --accept-override ${path.posix.basename(file)}`,
      () => {
        outputs.push(
          h.run(process.execPath, ['setup.mjs', '--accept-override', file], {
            cwd: fe,
            env: h.env,
            capture: true,
            allowFailure: true,
          }),
        );
      },
    );
  return outputs;
}

/**
 * The fixture upgrade on the example's work copy `country` (set up and built at `from`): bumped to
 * the fixture's next zip and kit package (`to`: `{ version, zip, sha256, kitZip, kitSha256 }`),
 * which the harness has installed. Returns the line for the job summary.
 */
export function fixtureUpgrade({ country, from, to, work, h }) {
  const fe = path.join(country, 'fe');
  const lockBefore = fs.readFileSync(path.join(fe, 'package-lock.json'), 'utf8');
  const added = new RegExp(`\\+\\d*\\s*${literal(templateLine(to.version))}`);
  let routine;
  h.step(`xx: bumped to ${to.version}, set-up fails on the override and writes the report`, () => {
    bump(country, to.version, to.sha256, to.kitSha256);
    const first = h.run(process.execPath, ['setup.mjs'], {
      cwd: fe,
      env: h.env,
      capture: true,
      allowFailure: true,
    });
    const findings = classifySetup(first);
    if (first.status === 0)
      throw new Error('set-up passed; the changed overridden file must fail it');
    if (findings.other.length || !findings.routine.length)
      throw new Error(
        `set-up must fail on the override alone; it failed on: ${findings.errors.join('; ') || 'nothing it named'}`,
      );
    routine = findings.routine;
    const file = path.join(fe, REPORT);
    if (!fs.existsSync(file)) throw new Error(`set-up wrote no ${REPORT}`);
    const report = fs.readFileSync(file, 'utf8');
    const problems = reportProblems(report, { country: 'xx', from: from.version, to: to.version });
    if (problems.length) throw new Error(`${REPORT}: ${problems.join('; ')}`);
    expectIn(report, new RegExp(literal(FIXTURE_KEY)), `${REPORT} does not name the changed key`);
    expectIn(
      report,
      /message-selection-toggle\.html/,
      `${REPORT} does not name the changed overridden file`,
    );
    expectIn(report, added, `${REPORT} has no '+' line of the base product's diff`);
    expectIn(
      report,
      new RegExp(`roleGuard[^\\n]*removed in ${literal(REMOVED_IN)}`),
      `${REPORT} does not name roleGuard with its removal version`,
    );
  });
  const [accepted] = routineActions({ country, zip: to.zip, routine, work, h });
  h.step(
    'xx: --accept-override printed the base product diff since the recorded fingerprint',
    () => {
      if (accepted.status !== 0)
        throw new Error(`node setup.mjs --accept-override exited ${accepted.status}`);
      expectIn(accepted.output, /since the recorded fingerprint/, 'no diff since the recorded one');
      expectIn(accepted.output, added, "the diff has no '+' line");
    },
  );
  h.step('xx: node setup.mjs green, with the deprecation warning', () => {
    const { output } = h.run(process.execPath, ['setup.mjs'], {
      cwd: fe,
      env: h.env,
      capture: true,
    });
    expectIn(
      output,
      new RegExp(`uses roleGuard from @niord/admin/api, deprecated since ${literal(to.version)}`),
      'no deprecation warning',
    );
  });
  if (fs.readFileSync(path.join(fe, 'package-lock.json'), 'utf8') !== lockBefore)
    h.step('xx: npm ci, the generated package-lock.json changed', () =>
      h.run('npm', ['ci', '--prefer-offline', '--no-audit', '--no-fund'], { cwd: fe, env: h.env }),
    );
  h.step(`xx: npm run build at ${to.version}`, () =>
    h.run('npm', ['run', 'build'], { cwd: fe, env: h.env }),
  );
  return `example: fixture upgrade ${from.version} to ${to.version}: set-up red on the override alone, ${REPORT} written, then green after the routine actions, and built`;
}

/**
 * The upgrade test: the example of the previous published release (`from`: `{ version, zip,
 * sha256, kitZip, kitSha256 }`, installed by the harness) set up at its version in `<work>/up`,
 * then bumped to `to`, the zip and kit package under test (`{ version, zip, sha256, kitZip,
 * kitSha256 }`). The previous example and API-CHANGES.md are its kit package's. When set-up finds
 * the stub changed, the new stub is copied (copyStub) and set-up runs once more, which must no
 * longer find it and must get into its checks; then the routine actions, with the overrides of
 * the last of those runs; set-up; npm ci; build. Returns the line for the job summary; throws
 * when a failure is left that no new API-CHANGES.md section asks a country to act on.
 */
export function upgradeTest({ from, to, work, h }) {
  if (!from.kitZip) throw new Error("the upgrade test needs the previous release's kit package");
  const country = path.join(work, 'up');
  const fe = path.join(country, 'fe');
  h.step(`up: the example of ${from.version}, set up at its version`, () => {
    fs.rmSync(country, { recursive: true, force: true });
    const entries = readZip(from.zip);
    const kitEntries = readZip(from.kitZip);
    if (!writeEntries(kitEntries, 'example/be/', path.join(country, 'be')))
      throw new Error(`the kit package of ${from.version} has no example/be/`);
    writeEntries(kitEntries, 'example/fe/', fe);
    copyWrapper(path.join(country, 'be'));
    // zz as the previous release's example had it. The zip carries that release's English for
    // both applications but not its backend bundles, and this tree's be/ may have removed,
    // renamed or re-placeholdered a key since; so the backend half is made from the previous
    // catalog (pseudoLocalise's `catalog`), and a key with markup it cannot make up goes to the
    // work copy's escape hatch. The set-up stays a plain step: it must pass at its own version.
    const english = englishOf(from.zip, path.join(work, 'up-english'));
    const catalog = JSON.parse(text(entryNamed(entries, CATALOG)));
    const { skipped } = pseudoLocalise({
      fe: english,
      be: BE,
      out: path.join(fe, 'packs', 'zz'),
      catalog,
    });
    fs.rmSync(english, { recursive: true, force: true });
    if (skipped.length) addToHatch(fe, skipped);
    bump(country, from.version, from.sha256, from.kitSha256);
    h.run(process.execPath, ['setup.mjs'], { cwd: fe, env: h.env });
  });
  let first;
  h.step(`up: bumped to ${to.version}, set-up`, () => {
    bump(country, to.version, to.sha256, to.kitSha256);
    first = h.run(process.execPath, ['setup.mjs'], {
      cwd: fe,
      env: h.env,
      capture: true,
      allowFailure: true,
    });
    if (!fs.existsSync(path.join(fe, REPORT))) throw new Error(`set-up wrote no ${REPORT}`);
  });
  let findings = classifySetup(first);
  if (findings.stub) {
    // The stub stops set-up beside its checks; with the new stub set-up runs them, and the
    // overrides it finds then are the ones to accept.
    copyStub(country, h);
    h.step('up: node setup.mjs with the new stub, past the stub check', () => {
      const again = h.run(process.execPath, ['setup.mjs'], {
        cwd: fe,
        env: h.env,
        capture: true,
        allowFailure: true,
      });
      findings = classifySetup(again);
      if (findings.stub) throw new Error('set-up still finds the stub changed after the copy');
      if (again.status !== 0 && !findings.errors.length)
        throw new Error(
          `with the new stub, set-up failed before its checks (exit ${again.status})`,
        );
    });
  }
  routineActions({ country, zip: to.zip, routine: findings.routine, work, h });
  const entryOf = (zip, name) => readZip(zip).find((entry) => entry.name === name)?.data;
  const previous = entryOf(from.kitZip, KIT_CHANGES);
  const sections = upgradeSections(
    text(entryOf(to.kitZip, KIT_CHANGES) ?? Buffer.alloc(0)),
    previous && text(previous),
    from.version,
  );
  let failure;
  const tryStep = (name, action) => {
    if (failure) return;
    try {
      h.step(name, action);
    } catch {
      failure = `${name} failed`;
    }
  };
  tryStep('up: node setup.mjs after the routine actions', () =>
    h.run(process.execPath, ['setup.mjs'], { cwd: fe, env: h.env }),
  );
  tryStep('up: npm ci', () =>
    h.run('npm', ['ci', '--prefer-offline', '--no-audit', '--no-fund'], { cwd: fe, env: h.env }),
  );
  tryStep(`up: npm run build at ${to.version}`, () =>
    h.run('npm', ['run', 'build'], { cwd: fe, env: h.env }),
  );
  const verdict = upgradeVerdict({ failure, sections });
  for (const line of verdict.lines) console.log(`example: upgrade test: ${line}`);
  if (!verdict.pass) throw new Error(`the upgrade from ${from.version} to ${to.version} failed`);
  return `example: upgrade test: ${from.version} to ${to.version} ${failure ? 'failed where API-CHANGES.md says a country must act' : 'set up and built green'}`;
}
