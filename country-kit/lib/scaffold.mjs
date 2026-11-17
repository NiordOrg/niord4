/**
 * The files of a new country that follow from its answers (lib/answers.mjs): the whole backend
 * module in be/, the manifest, the public site's configuration, the owners, the two workflows
 * that build it and the root README. The generator only returns them; init writes them, never over
 * a file that exists, with each file's mode.
 *
 *   scaffold({ answers, kit, version })  →  [{ path, data, mode }, …]
 *
 * `path` is relative to the repository root, with `/`; `data` a Buffer; `mode` 0o755 for the two
 * scripts a build runs (be/mvnw and be/scripts/check-country-build-group.sh) and 0o644 for every
 * other file. The answers are validated again here, so no caller can skip a rule.
 *
 * Text files are `{{slot}}` templates in templates/be/ and templates/ci.yml, filled by fill(),
 * which refuses a slot left unfilled and a slot the template does not have. JSON files, the
 * language block of the pom, info.config.ts and the README are generated in code. The pom
 * template is pom.xml.tmpl: a file named pom.xml would join the Maven cache key of niord-ng's CI
 * (the hash of every pom.xml) and be read as an invalid pom by GitHub's dependency graph.
 *
 * Every backend file the kit package carries (the Maven wrapper, the base product's settings file
 * and its catalog) is read through lib/where.mjs kitBackendBytes, so a package and niord-ng's tree
 * give the same bytes. The settings file is generated from the base product's file and catalog:
 * the answered keys in `values`, every other key of the base product's file in `useBaseDefault`
 * with a reason (USE_BASE_DEFAULT_REASONS, or the base product's default at the version for a key
 * the table does not know yet), and a required catalog key no answer sets refused.
 *
 * Nothing here writes branding or a copyright line: a branding text would require a branding file
 * for every UI language right after init (lib/branding.mjs). No be/third-party-maven/.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { imageWorkflow } from './image-workflow.mjs';
import { DEFAULT_LANGUAGE, readAnswers } from './answers.mjs';
import { KIT_BACKEND, kitBackendBytes } from './where.mjs';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const VERSION = /^[0-9][0-9A-Za-z.+_-]*$/;
const SLOT = /\{\{([A-Za-z][A-Za-z0-9]*)\}\}/g;
const MODULE_LINE = "  MODULE: ''";
const OWNER = '@OWNER';
const SCRIPT = 0o755;
const FILE = 0o644;

/**
 * The Java keywords and literals (JLS §3.9, §3.10.3, §3.10.8), which no package name may be. For a
 * two-letter code that is `do` and `if`.
 */
const JAVA_RESERVED = new Set([
  'abstract',
  'assert',
  'boolean',
  'break',
  'byte',
  'case',
  'catch',
  'char',
  'class',
  'const',
  'continue',
  'default',
  'do',
  'double',
  'else',
  'enum',
  'extends',
  'final',
  'finally',
  'float',
  'for',
  'goto',
  'if',
  'implements',
  'import',
  'instanceof',
  'int',
  'interface',
  'long',
  'native',
  'new',
  'package',
  'private',
  'protected',
  'public',
  'return',
  'short',
  'static',
  'strictfp',
  'super',
  'switch',
  'synchronized',
  'this',
  'throw',
  'throws',
  'transient',
  'try',
  'void',
  'volatile',
  'while',
  'true',
  'false',
  'null',
]);

/**
 * `text`, the template `name`, with every `{{slot}}` replaced by its string in `slots`, in one
 * pass, so a value is never read as a slot. Throws, naming the template and the slot, on a slot
 * left unfilled, on a slot given that the template does not have, and on a value not a string.
 * A GitHub expression, `${{ … }}`, is no slot: a slot has no space inside its braces.
 */
export function fill(name, text, slots) {
  const used = new Set([...text.matchAll(SLOT)].map(([, slot]) => slot));
  for (const slot of used)
    if (!Object.hasOwn(slots, slot))
      throw new Error(`${name}: the slot {{${slot}}} is left unfilled`);
  for (const [slot, value] of Object.entries(slots)) {
    if (!used.has(slot)) throw new Error(`${name}: no slot {{${slot}}} to fill`);
    if (typeof value !== 'string')
      throw new Error(`${name}: the slot {{${slot}}} is filled with a ${typeof value}, not a text`);
  }
  return text.replace(SLOT, (_, slot) => slots[slot]);
}

/** The Java package under org.niord of the country `cc`: the code, or `<cc>_` for a Java keyword. */
export const javaPackage = (cc) => (JAVA_RESERVED.has(cc) ? `${cc}_` : cc);

/** The application class of the country `cc`: `Niord<Cc>Application`. */
export const className = (cc) => `Niord${cc[0].toUpperCase()}${cc.slice(1)}Application`;

/**
 * Why a new standard country leaves each of these keys of the base product's settings file to the
 * base product's default, as its settings file's `useBaseDefault` gives it.
 */
export const USE_BASE_DEFAULT_REASONS = Object.freeze({
  mode: "production, the base product's mode; a test or staging deployment sets its own on the Settings page",
  baseUri:
    "the local stack's address; a deployment sets its own on the Settings page or in its niord.json",
  adminAppUrl:
    'empty, so the links in mails point at baseUri; a deployment that serves the administration app elsewhere sets it on the Settings page',
  authServerUrl:
    "the local stack's address; a deployment sets its own on the Settings page or in its niord.json",
  documentationUrl: "the base product's documentation, for a country with none of its own",
  editorFieldsBase: "the base product's message editor fields, the standard set",
  editorFieldsNw: "the base product's editor fields of a navigational warning, the standard set",
  editorFieldsNm: "the base product's editor fields of a notice to mariners, the standard set",
  stdTemplateFields: "the base product's fields of the standard template",
  wmsLayerEnabled: 'off: a WMS layer needs a WMS service of the deployment',
  openSeaMapLayerEnabled: 'off until the deployment chooses to show the OpenSeaMap layer',
  osmUrl: 'the public OpenStreetMap tile server, the standard map',
  openSeaMapUrl: 'the public OpenSeaMap tile server, used once its layer is switched on',
  mapTileContact:
    'empty until the deployment names its contact for the tile servers (before go-live)',
  disableLucene: "false: the Lucene index, the base product's standard message search",
});

/** The English list with the answered languages after it. */
const languagesOf = (answers) => [DEFAULT_LANGUAGE, ...answers.languages];

/** The settings keys the answers set, each with its value from the complete answers. */
export const ANSWERED = Object.freeze({
  country: (answers) => answers.country.toUpperCase(),
  organisation: (answers) => answers.organisation,
  'niord.home': () => '${NIORD_HOME}',
  defaultLanguage: () => DEFAULT_LANGUAGE,
  modelLanguages: languagesOf,
  siteLanguages: languagesOf,
  infoSiteLanguages: languagesOf,
  infoDefaultLanguage: () => DEFAULT_LANGUAGE,
  mapDefaultLatitude: (answers) => answers.map.lat,
  mapDefaultLongitude: (answers) => answers.map.lon,
  mapDefaultZoomLevel: (answers) => answers.map.zoom,
});

/**
 * The country's settings file, `{ values, useBaseDefault }`, from the complete `answers`, the base
 * product's settings file `baseSettings` and its catalog `catalog` (both parsed) at `version`:
 * `values` the answered keys, `useBaseDefault` every other key of the base product's file with its
 * reason, both in the base product's file's order. Throws on a required catalog key no answer
 * sets, and on an answered key the catalog does not declare or declares as state.
 */
export function countrySettings({ answers, baseSettings, catalog, version }) {
  const declared = new Map((catalog?.settings ?? []).map((entry) => [entry.key, entry]));
  for (const key of Object.keys(ANSWERED)) {
    const entry = declared.get(key);
    if (!entry)
      throw new Error(
        `settings: the answered key ${key} is not in the base product's catalog: the kit's ANSWERED table must follow the catalog`,
      );
    if (entry.scope === 'state')
      throw new Error(
        `settings: the answered key ${key} is a state setting in the base product's catalog, which no settings file sets`,
      );
  }
  for (const entry of declared.values())
    if (entry.required && !Object.hasOwn(ANSWERED, entry.key))
      throw new Error(
        `settings: the base product's catalog requires ${entry.key}, which no answer sets: init cannot write a settings file without it`,
      );
  const values = {};
  const useBaseDefault = [];
  const baseKeys = Object.keys(baseSettings?.values ?? {});
  for (const key of baseKeys)
    if (Object.hasOwn(ANSWERED, key)) values[key] = ANSWERED[key](answers);
    else
      useBaseDefault.push({
        key,
        reason: Object.hasOwn(USE_BASE_DEFAULT_REASONS, key)
          ? USE_BASE_DEFAULT_REASONS[key]
          : `the base product's default at ${version}`,
      });
  for (const key of Object.keys(ANSWERED))
    if (!Object.hasOwn(values, key)) values[key] = ANSWERED[key](answers);
  return { values, useBaseDefault };
}

/**
 * The pom's language block for the answered `languages`: '' for English only, otherwise the
 * maven-resources-plugin with two copies per language from set-up's staging (the backend half and
 * the flag) and the maven-antrun-plugin's require-staged-packs check with one line per language,
 * the flag's: every pack archive carries its flag, and a copy from a folder that does not exist
 * copies nothing, so a pack without a backend half builds and a build before set-up still fails.
 * Indented for the template's <plugins>, each line ending in a newline.
 */
export function packPlugins(languages) {
  if (languages.length === 0) return '';
  const staged = '${project.basedir}/../fe/.niord/packs';
  const execution = (id, output, directory, include) => [
    '<execution>',
    `    <id>${id}</id>`,
    '    <phase>process-resources</phase>',
    '    <goals>',
    '        <goal>copy-resources</goal>',
    '    </goals>',
    '    <configuration>',
    `        <outputDirectory>${output}</outputDirectory>`,
    '        <resources>',
    '            <resource>',
    `                <directory>${directory}</directory>`,
    '                <filtering>false</filtering>',
    '                <includes>',
    `                    <include>${include}</include>`,
    '                </includes>',
    '            </resource>',
    '        </resources>',
    '    </configuration>',
    '</execution>',
  ];
  const copies = languages.flatMap((lang) => [
    ...execution(
      `copy-${lang}-pack-backend`,
      `\${project.build.outputDirectory}/defaults/packs/${lang}`,
      `${staged}/${lang}/backend`,
      '*.properties',
    ),
    ...execution(
      `copy-${lang}-pack-flag`,
      '${project.build.outputDirectory}/META-INF/resources/img/flags',
      `${staged}/${lang}/img/flags`,
      `${lang}.png`,
    ),
  ]);
  const lines = [
    "<!-- The language packs' backend halves and flags, copied from set-up's staging,",
    '     fe/.niord/packs/<lang>/: the backend half into defaults/packs/<lang>/, where the base',
    "     product's text layering reads a pack, and the flag into META-INF/resources/img/flags/, the",
    '     flag a draft PDF shows. Set-up (node fe/setup.mjs) runs before any Maven build. Executions',
    '     of the inherited plugin rather than a <resources> section, which would replace the',
    '     inherited resources and their filtering. -->',
    '<plugin>',
    '    <groupId>org.apache.maven.plugins</groupId>',
    '    <artifactId>maven-resources-plugin</artifactId>',
    '    <executions>',
    ...copies.map((line) => `        ${line}`),
    '    </executions>',
    '</plugin>',
    '<!-- A copy from a folder that does not exist copies nothing and passes, so a build before',
    "     set-up would make a jar without the packs' texts: this check refuses it, naming each pack's",
    '     flag, which every pack archive carries. At validate, the first phase, so it guards every',
    "     lifecycle build. No <version>: Spring Boot's parent manages maven-antrun-plugin. -->",
    '<plugin>',
    '    <groupId>org.apache.maven.plugins</groupId>',
    '    <artifactId>maven-antrun-plugin</artifactId>',
    '    <executions>',
    '        <execution>',
    '            <id>require-staged-packs</id>',
    '            <phase>validate</phase>',
    '            <goals>',
    '                <goal>run</goal>',
    '            </goals>',
    '            <configuration>',
    '                <target>',
    '                    <fail message="fe/.niord/packs/ lacks a pack this build copies: run node fe/setup.mjs first">',
    '                        <condition>',
    '                            <not>',
    '                                <and>',
    ...languages.map(
      (lang) =>
        `                                    <available file="${staged}/${lang}/img/flags/${lang}.png" type="file"/>`,
    ),
    '                                </and>',
    '                            </not>',
    '                        </condition>',
    '                    </fail>',
    '                </target>',
    '            </configuration>',
    '        </execution>',
    '    </executions>',
    '</plugin>',
  ];
  return lines.map((line) => `            ${line}\n`).join('');
}

/** `text` with LF line ends. */
const lf = (text) => text.replace(/\r\n/g, '\n');

/** JSON as the kit writes it: two spaces, a closing newline. */
const json = (value) => `${JSON.stringify(value, null, 2)}\n`;

/**
 * The manifest of the answers: country, name, apps, packs, locales, overrides, in the manifest
 * schema's order.
 */
function manifest(answers) {
  const languages = languagesOf(answers);
  return {
    country: answers.country,
    name: answers.name,
    apps: {
      admin: {
        uiLanguages: languages,
        contentLanguages: languages,
        defaultLanguage: DEFAULT_LANGUAGE,
      },
      info: { uiLanguages: languages, defaultLanguage: DEFAULT_LANGUAGE },
    },
    packs: Object.fromEntries(answers.languages.map((lang) => [lang, { pinned: true }])),
    locales: {},
    overrides: [],
  };
}

/** The bootstrap's one domain with its one series. */
function domains(answers) {
  return [
    {
      domainId: answers.domain.id,
      name: answers.domain.name,
      active: true,
      publish: true,
      sortOrder: 1,
      timeZone: answers.timeZone,
      lat: answers.map.lat,
      lon: answers.map.lon,
      zoomLevel: answers.map.zoom,
      messageSeries: [
        {
          seriesId: answers.series.id,
          mainType: answers.domain.mainType,
          shortFormat: answers.series.shortFormat,
          numberSequenceType: answers.series.numberSequenceType,
        },
      ],
    },
  ];
}

/** The public site's country configuration (the seam info.config), with the domain's time zone. */
const infoConfig = (answers) => `import type { InfoConfig } from '@niord/info/api';

/**
 * The public site's configuration: the clock in the time zone of the country's waters, no area
 * filter (the message views query the whole data set) and the base product's default footer.
 */
export const COUNTRY_INFO: InfoConfig = {
  timeZone: '${answers.timeZone}',
  executionMode: 'production',
  wmsLayer: false,
  rootAreas: [],
};
`;

/** The repository's README: what each part is and the next commands. */
function readme(answers) {
  const setup = answers.languages.length ? 'node setup.mjs --update-pins' : 'node setup.mjs';
  return `# ${answers.name}

The Niord country repository of ${answers.name} (\`${answers.country}\`): the base product's
backend and frontend with this country's configuration, data and branding.

- \`be/\`: the backend, a Maven module on the base product's \`niord-parent\`. The parent's version
  in \`be/pom.xml\` is the pin of the whole repository.
- \`fe/\`: the frontend, a country workspace on the pinned source zip of the same version
  (\`fe/niord-country.json\`, \`fe/niord-pins.lock.json\`).
- \`start-local.sh\`: the local stack in one command.
- \`.niord/\`: the base product's country kit, which set-up fetches; never committed.

Next, from the repository root:

\`\`\`bash
(cd fe && ${setup})
./start-local.sh
\`\`\`

The country guide is \`.niord/kit/docs/country-guide.md\`.
`;
}

/** The template's instruction to fill @OWNER, and what the filled file says in its place. */
const OWNER_INSTRUCTION = `Replace ${OWNER} with this country's maintainers.`;
const OWNER_FILLED = "Init filled in this country's maintainers; change them here.";

/**
 * CODEOWNERS: every rule line's @OWNER replaced by the owners, and the comment's instruction to
 * replace it by a sentence that holds once they are in. A comment still naming @OWNER fails, so a
 * reworded template cannot leave a stale instruction in the file the country commits.
 */
function codeowners(text, owners) {
  const lines = text
    .split('\n')
    .map((line) =>
      line.trimStart().startsWith('#')
        ? line.replace(OWNER_INSTRUCTION, OWNER_FILLED)
        : line.replaceAll(OWNER, owners.join(' ')),
    );
  const stale = lines.find((line) => line.includes(OWNER));
  if (stale !== undefined) {
    throw new Error(`templates/CODEOWNERS: a comment still names ${OWNER}: ${stale.trim()}`);
  }
  return lines.join('\n');
}

/** image.yml with its MODULE, the artifactId; a template with no MODULE line, or two, fails. */
function imageWithModule(text, cc) {
  const lines = text.split('\n');
  const at = lines.flatMap((line, i) => (line === MODULE_LINE ? [i] : []));
  if (at.length !== 1)
    throw new Error(
      `templates/image.yml has ${at.length} lines "${MODULE_LINE}", not the one init fills with the artifactId`,
    );
  lines[at[0]] = `  MODULE: 'niord-${cc}'`;
  return lines.join('\n');
}

/**
 * Every file of the country that follows from `answers`, for the kit folder `kit` (this kit when
 * none is given) and the base product's `version`: `[{ path, data, mode }]` in the order init
 * writes them. Throws, writing nothing, on refused answers (every problem named), a version not
 * a plain version, a kit without its templates or backend files, and a settings file the answers
 * cannot complete.
 */
export function scaffold({ answers, kit = KIT, version }) {
  if (typeof version !== 'string' || !VERSION.test(version))
    throw new Error(
      `"${version ?? ''}" is not a base product version: a digit, then letters, digits, '.', '+', '_' or '-'`,
    );
  const a = readAnswers(answers);
  const cc = a.country;
  const pkg = javaPackage(cc);
  const template = (name) =>
    lf(fs.readFileSync(path.join(kit, 'templates', ...name.split('/')), 'utf8'));
  const filled = (name, slots) => fill(`templates/${name}`, template(name), slots);
  const backend = (key) => kitBackendBytes(key, kit);
  const modeOf = (key) => (KIT_BACKEND[key].executable ? SCRIPT : FILE);
  const parsed = (key) => JSON.parse(backend(key).toString('utf8'));

  const files = [
    [
      'be/pom.xml',
      filled('be/pom.xml.tmpl', { version, cc, packPlugins: packPlugins(a.languages) }),
    ],
    ['be/mvnw', backend('mvnw'), modeOf('mvnw')],
    ['be/mvnw.cmd', backend('mvnwCmd'), modeOf('mvnwCmd')],
    [
      'be/.mvn/wrapper/maven-wrapper.properties',
      backend('wrapperProperties'),
      modeOf('wrapperProperties'),
    ],
    ['be/.gitattributes', template('be/gitattributes')],
    ['be/.gitignore', template('be/gitignore')],
    ['be/.java-version', template('be/java-version')],
    ['be/overrides.json', '{ "overrides": [] }\n'],
    [
      `be/src/main/java/org/niord/${pkg}/${className(cc)}.java`,
      filled('be/Application.java.tmpl', {
        pkg,
        CC: cc.toUpperCase(),
        className: className(cc),
      }),
    ],
    [
      'be/src/main/resources/application.yml',
      filled('be/application.yml.tmpl', {
        cc,
        // Quoted: a code such as 0123, TRUE or NULL is a number, a boolean or null to YAML
        producerCode: a.producerCode ? `    producer-code: "${a.producerCode}"\n` : '',
      }),
    ],
    [
      'be/src/main/resources/application-local.yml',
      filled('be/application-local.yml.tmpl', { cc }),
    ],
    [
      'be/src/main/resources/defaults/country/settings.json',
      json(
        countrySettings({
          answers: a,
          baseSettings: parsed('baseSettings'),
          catalog: parsed('settingsCatalog'),
          version,
        }),
      ),
    ],
    ['be/src/main/resources/defaults/country/README.md', template('be/defaults-country-README.md')],
    ['be/bootstrap/README.md', template('bootstrap-README.md')],
    ['be/bootstrap/batch-set.json', json([{ jobName: 'domain-import', fileName: 'domains.json' }])],
    ['be/bootstrap/domains.json', json(domains(a))],
    [
      'be/scripts/check-country-build-group.sh',
      template('be/check-country-build-group.sh'),
      SCRIPT,
    ],
    ['fe/niord-country.json', json(manifest(a))],
    ['fe/info/app/info.config.ts', infoConfig(a)],
    ['.github/CODEOWNERS', codeowners(template('CODEOWNERS'), a.owners)],
    ['.github/workflows/image.yml', imageWithModule(lf(imageWorkflow(kit)), cc)],
    ['.github/workflows/ci.yml', filled('ci.yml', { cc })],
    ['README.md', readme(a)],
  ];
  return files.map(([file, data, mode = FILE]) => ({
    path: file,
    data: Buffer.isBuffer(data) ? data : Buffer.from(data, 'utf8'),
    mode,
  }));
}
