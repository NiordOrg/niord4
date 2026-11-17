#!/usr/bin/env node
/**
 * Guard for what each build configuration ships, read from angular.json without building.
 *
 *   node scripts/build-contents-check.mjs                       # every project, run from fe/
 *   node scripts/build-contents-check.mjs --project niord-app   # one project
 *
 * A build copies its `assets` inputs into its output, and nothing in the build asks whether a
 * file belongs there: a Danish bundle in the English build, another country's logo, a flag's
 * attribution file and a translator's review list all build green and are served to every
 * reader. Two inputs that write the same output path build green as well, and which of the two
 * files a reader gets is the order of the array. This check lists, for every build
 * configuration of every project, the files the builder would copy — through the installed
 * @angular/build's own asset code (country-kit/lib/asset-outputs.mjs, which a country's
 * check-build resolves through too), so the list is the builder's and needs no build — and holds
 * them to the configuration's language configuration: the file a configuration swaps in for
 * `country-specific/default/language.config.ts`, or that file itself.
 *
 *   1. One source per output path.
 *   2. The language bundles, `assets/i18n/<lang>.json`, are exactly the configuration's UI
 *      languages (the administration app's `uiLanguages`, the public site's `languages`). `en`
 *      ships only when they name it: staging completes every bundle from it, so no application
 *      has a runtime fallback language.
 *   3. The flags, `assets/img/flags/*`, are exactly the flag paths its languages name, and every
 *      named flag ships. A language with no flag path is shown by its code.
 *   4. A branding file, `assets/branding/<lang>.json`, and a legal document,
 *      `assets/legal/<doc>.<lang>.html`, only for a branding language: its `brandingLanguages`,
 *      in the administration app none when it names none, in the public site every language.
 *   5. No README, ATTRIBUTION or LICENSE file, no `-unverified.json` or `-unaccepted.json`
 *      review list, nothing under a `status/` or `node_modules/` folder.
 *   6. Nothing from another country's overlay, `<root>/public/assets/<cc>/` for a folder name
 *      of the project's `country-specific/`: only the configuration's own country (the folder
 *      its `country-specific/default/` replacements name, `default` when it has none).
 *   7. No overlay under its own folder name: an overlay's files go to `assets/`, so no output
 *      path starts with `assets/<cc>/` for such a folder name, the configuration's own included
 *      (a base input that stops ignoring its country's folder ships the overlay twice).
 *   8. The bundles and flags, every output under `assets/i18n/` or `assets/img/flags/`, come from
 *      the project's staged language folder, `.niord/build/<app>/` for the project in
 *      `ui-apps/<app>`: a pack reaches a build only as the staging step copied it, never from
 *      `languages/` or from an application's `public/` folder.
 *   9. Every language the configuration names (the administration app's `availableLanguages`,
 *      the public site's `languages`) has a pack, `languages/<lang>/`, and is named as that
 *      pack's `pack.json` names it, label and locale: a reader looks for their language by the
 *      way they write it, so its name is the same in every build of both applications.
 *
 * The staged folder is an asset input like any other, so its files must be there for the builder
 * to list them: run as a script, the check first stages the languages of the projects it checks
 * (country-kit/stage-languages.mjs); `checkBuildContents` itself reads only what it is handed.
 *
 * The language configuration is read by transpiling it with TypeScript and importing the result,
 * so it has to be plain data: a runtime import is refused (`import type` is erased).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import {
  builderDir,
  builderModule as kitBuilderModule,
  duplicateOutputs,
  resolveOutputs as kitResolveOutputs,
} from '../../country-kit/lib/asset-outputs.mjs';
import { readPack, stageLanguages, summary } from '../../country-kit/stage-languages.mjs';
import { readAngularJson } from './workspace-paths-check.mjs';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const DEFAULT_LANGUAGE_CONFIG = /\/country-specific\/default\/language\.config\.ts$/;
const COUNTRY_SWAP = /\/country-specific\/default\/.+$/;

/**
 * One of the installed @angular/build's own modules under src/utils/
 * (country-kit/lib/asset-outputs.mjs), by default the builder installed in fe/.
 */
export const builderModule = (file, packageDir = undefined) =>
  kitBuilderModule(file, { packageDir, root: FE });

/**
 * The files a build copies for an `assets` array, as fe/'s builder resolves them
 * (country-kit/lib/asset-outputs.mjs): a test's workspace in a temporary folder has no
 * node_modules of its own.
 */
export const resolveOutputs = (assets, options) =>
  kitResolveOutputs(assets, { packageDir: builderDir(FE), ...options });

/** A language configuration's `COUNTRY_LANGUAGES`, transpiled and imported. */
export async function loadLanguageConfig(file) {
  const { outputText } = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    fileName: file,
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      removeComments: true,
    },
  });
  if (/\bimport\b|\brequire\s*\(|^\s*export\s*(\*|\{)[^;]*\bfrom\b/m.test(outputText))
    throw new Error(
      'imports at runtime; a language configuration is plain data (`import type` only)',
    );
  const module = await import(
    `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
  );
  if (!module.COUNTRY_LANGUAGES || typeof module.COUNTRY_LANGUAGES !== 'object')
    throw new Error('exports no COUNTRY_LANGUAGES object');
  return module.COUNTRY_LANGUAGES;
}

/** The folder names under a project's `country-specific/`: the countries an overlay can be. */
export function countryFolders(folder) {
  if (!fs.existsSync(folder)) return [];
  return fs
    .readdirSync(folder, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
}

const strings = (value) =>
  Array.isArray(value) && value.every((item) => typeof item === 'string') ? value : undefined;
const relativeAsset = (p) => p.replace(/^\.?\//, '');

/** What a language configuration says a build ships, in either application's shape. */
function languageFacts(languages) {
  // The administration app lists its bundles in `uiLanguages` beside `availableLanguages`, every
  // language it can name as content; the public site's `languages` are its bundles.
  const admin = 'uiLanguages' in languages;
  const options = admin ? languages.availableLanguages : languages.languages;
  const ui = admin
    ? strings(languages.uiLanguages)
    : Array.isArray(languages.languages)
      ? strings(languages.languages.map((option) => option?.value))
      : undefined;
  // Branding languages are the ones named; when a configuration names none, the administration
  // app ships no branding and the public site may ship it for every language.
  const branding =
    'brandingLanguages' in languages
      ? (strings(languages.brandingLanguages) ?? [])
      : admin
        ? []
        : (ui ?? []);
  const named = Array.isArray(options) ? options.filter((option) => option) : [];
  const flags = named
    .map((option) => option.flagPath)
    .filter((flag) => typeof flag === 'string')
    .map(relativeAsset);
  return { ui, branding, flags, named };
}

/**
 * The language configuration a build configuration compiles: the file it swaps in for
 * `country-specific/default/language.config.ts`, or that file itself.
 */
function languageFileOf(configuration, build, countryFolder) {
  const replacements = configuration.fileReplacements ?? build.options?.fileReplacements ?? [];
  return (
    replacements.find(
      ({ replace }) => typeof replace === 'string' && DEFAULT_LANGUAGE_CONFIG.test(replace),
    )?.with ?? `${countryFolder}/default/language.config.ts`
  );
}

/** A project's folders: its root, its source root and its `country-specific/` folder. */
function projectFolders(definition) {
  const projectRoot = definition.root ?? '';
  const sourceRoot = definition.sourceRoot ?? path.posix.join(projectRoot, 'src');
  return { projectRoot, sourceRoot, countryFolder: `${sourceRoot}/app/country-specific` };
}

/**
 * Per project and build configuration of a parsed angular.json, the UI languages its language
 * configuration names: `{ project, app, configuration, ui }`, `app` the project folder's name
 * (`admin`, `info`). The pack check (country-kit/pack.mjs) holds each pack to what these use.
 */
export async function configurationLanguages(
  config,
  { root = FE, loadLanguages = loadLanguageConfig } = {},
) {
  const rows = [];
  for (const [project, definition] of Object.entries(config.projects ?? {})) {
    const build = definition?.architect?.build;
    if (!build) continue;
    const { projectRoot, countryFolder } = projectFolders(definition);
    for (const [configuration, options] of Object.entries(build.configurations ?? {})) {
      const file = languageFileOf(options, build, countryFolder);
      let languages;
      try {
        languages = await loadLanguages(path.join(root, file));
      } catch (error) {
        throw new Error(`${project}:${configuration}: ${file} ${error.message}`, { cause: error });
      }
      const { ui } = languageFacts(languages);
      if (!ui) throw new Error(`${project}:${configuration}: ${file} names no UI languages`);
      rows.push({ project, app: path.posix.basename(projectRoot), configuration, ui });
    }
  }
  return rows;
}

/** A pack's `pack.json` as readPack checks it, by language, read once per run. */
function packReader(root) {
  const read = new Map();
  return (lang) => {
    if (!read.has(lang)) {
      const dir = path.join(root, 'languages', lang);
      if (!/^[a-z]{2,3}$/.test(lang) || !fs.existsSync(dir))
        read.set(lang, { error: `which has no pack, languages/${lang}/` });
      else
        try {
          read.set(lang, { pack: readPack(dir) });
        } catch (error) {
          read.set(lang, { error: `whose pack is refused: ${error.message}` });
        }
    }
    return read.get(lang);
  };
}

const FORBIDDEN_NAME = /^(README|ATTRIBUTION|LICEN[CS]E)/i;
const REVIEW_LIST = /-(unverified|unaccepted)\.json$/;
const FORBIDDEN_FOLDER = new Set(['status', 'node_modules']);

/**
 * Checks every build configuration of a parsed angular.json. Returns the errors, one line each
 * naming the configuration and the path, and what was checked. `root` is the workspace folder;
 * the resolver, the language loader and the country lister are replaceable so a test can hand
 * in a tree of its own.
 */
export async function checkBuildContents(
  config,
  {
    root = FE,
    project: only = undefined,
    resolve = resolveOutputs,
    loadLanguages = loadLanguageConfig,
    countries = countryFolders,
  } = {},
) {
  const errors = [];
  const checked = [];
  const packOf = packReader(root);
  const projects = Object.entries(config.projects ?? {}).filter(
    ([name, definition]) => (only === undefined || name === only) && definition?.architect?.build,
  );
  if (only !== undefined && projects.length === 0)
    errors.push(`angular.json: no project "${only}" with a build target`);

  for (const [projectName, definition] of projects) {
    const build = definition.architect.build;
    const { projectRoot, sourceRoot, countryFolder } = projectFolders(definition);
    const overlayCountries = new Set(countries(path.join(root, countryFolder)));
    const overlay = new RegExp(
      `^${projectRoot.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/public/assets/([^/]+)/`,
    );
    const staged = `.niord/build/${path.posix.basename(projectRoot)}/`;

    for (const [configurationName, configuration] of Object.entries(build.configurations ?? {})) {
      const name = `${projectName}:${configurationName}`;
      const fail = (message) => errors.push(`${name}: ${message}`);
      const assets = configuration.assets ?? build.options?.assets ?? [];
      const replacements = configuration.fileReplacements ?? build.options?.fileReplacements ?? [];

      const swaps = replacements.filter(
        ({ replace }) => typeof replace === 'string' && COUNTRY_SWAP.test(replace),
      );
      const country =
        swaps
          .map(({ with: replacement }) =>
            typeof replacement === 'string' && replacement.startsWith(`${countryFolder}/`)
              ? replacement.slice(countryFolder.length + 1).split('/')[0]
              : undefined,
          )
          .find(Boolean) ?? 'default';
      const languageFile = languageFileOf(configuration, build, countryFolder);

      let outputs;
      let languages;
      try {
        outputs = await resolve(assets, { root, projectRoot, sourceRoot });
      } catch (error) {
        fail(`its assets do not resolve: ${error.message}`);
        continue;
      }
      try {
        languages = await loadLanguages(path.join(root, languageFile));
      } catch (error) {
        fail(`${languageFile} ${error.message}`);
        continue;
      }
      checked.push({ configuration: name, files: outputs.length });
      const { ui, branding, flags, named } = languageFacts(languages);

      // 1. One source per output path.
      for (const { destination, sources: from } of duplicateOutputs(outputs))
        fail(
          `${destination} is written by ${from.length} inputs (${from.join(', ')}); one output path takes one source`,
        );
      const shipped = [...new Set(outputs.map(({ destination }) => destination))];

      // 2. The bundles are exactly the UI languages.
      if (!ui) {
        fail(
          `${languageFile} names no UI languages (\`uiLanguages\`, or \`languages\` with a \`value\` each)`,
        );
      } else {
        const bundles = shipped
          .map((destination) => destination.match(/^assets\/i18n\/([^/]+)\.json$/)?.[1])
          .filter(Boolean);
        for (const lang of bundles)
          if (!ui.includes(lang))
            fail(
              `ships assets/i18n/${lang}.json, but ${languageFile} names no UI language "${lang}"`,
            );
        for (const lang of ui)
          if (!bundles.includes(lang))
            fail(`ships no assets/i18n/${lang}.json for its UI language "${lang}"`);
      }

      // 3. The flags are the flag paths the languages name.
      for (const destination of shipped.filter((d) => d.startsWith('assets/img/flags/')))
        if (!flags.includes(destination))
          fail(`ships ${destination}, a flag no language of ${languageFile} names`);
      for (const flag of flags)
        if (!shipped.includes(flag))
          fail(`${languageFile} names the flag ${flag}, which this build does not ship`);

      // 4. Branding and legal documents only for a branding language.
      for (const destination of shipped) {
        const brandingLang = destination.match(/^assets\/branding\/([^/]+)\.json$/)?.[1];
        if (brandingLang && !branding.includes(brandingLang))
          fail(
            `ships ${destination}, but "${brandingLang}" is not a branding language of ${languageFile} (${branding.join(', ') || 'none'})`,
          );
        if (destination.startsWith('assets/legal/')) {
          const legalLang = destination.match(/^assets\/legal\/[^/]+\.([^./]+)\.html$/)?.[1];
          if (!legalLang) fail(`ships ${destination}, which is not named <doc>.<lang>.html`);
          else if (!branding.includes(legalLang))
            fail(
              `ships ${destination}, but "${legalLang}" is not a branding language of ${languageFile} (${branding.join(', ') || 'none'})`,
            );
        }
      }

      // 5. No attribution, licence or review list, nothing from a status or node_modules folder.
      for (const destination of shipped) {
        const segments = destination.split('/');
        const file = segments.at(-1);
        if (FORBIDDEN_NAME.test(file))
          fail(`ships ${destination}, a README, ATTRIBUTION or LICENSE file that serves no reader`);
        else if (REVIEW_LIST.test(file))
          fail(`ships ${destination}, a translation review list that only the specs read`);
        if (segments.slice(0, -1).some((segment) => FORBIDDEN_FOLDER.has(segment)))
          fail(`ships ${destination}, from a status/ or node_modules/ folder`);
      }

      // 6. Nothing from another country's overlay.
      for (const { source, destination } of outputs) {
        const from = source.match(overlay)?.[1];
        if (from && overlayCountries.has(from) && from !== country)
          fail(
            `ships ${destination} from the ${from} overlay (${source}), but this configuration is ${country}'s`,
          );
      }

      // 7. No overlay under its own folder name.
      for (const destination of shipped) {
        const folder = destination.match(/^assets\/([^/]+)\//)?.[1];
        if (folder && overlayCountries.has(folder))
          fail(
            `ships ${destination} under the ${folder} overlay's folder name; an overlay's files go to assets/, and the base input ignores assets/${folder}/**`,
          );
      }

      // 8. Bundles and flags only from the staged language folder.
      for (const { source, destination } of outputs)
        if (/^assets\/(i18n|img\/flags)\//.test(destination) && !source.startsWith(staged))
          fail(
            `ships ${destination} from ${source}; bundles and flags reach a build only from ${staged}, which the staging step writes from the language packs`,
          );

      // 9. Every language named as its pack names it.
      for (const { value, label, locale } of named) {
        const { pack, error } = packOf(String(value));
        if (error) fail(`${languageFile} names the language "${value}", ${error}`);
        else if (label !== pack.label || locale !== pack.locale)
          fail(
            `${languageFile} names "${value}" ${JSON.stringify(label)} (${locale}); its pack, languages/${value}/pack.json, names it ${JSON.stringify(pack.label)} (${pack.locale})`,
          );
      }
    }
  }
  return { errors, checked };
}

// Run as a script rather than imported by its test; see workspace-paths-check.mjs for why both
// sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const args = process.argv.slice(2);
  let project;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--project' && args[i + 1]) project = args[++i];
    else if (args[i].startsWith('--project=')) project = args[i].slice('--project='.length);
    else {
      console.error(`build-contents-check: unknown argument "${args[i]}" (--project <name>)`);
      process.exit(2);
    }
  }
  let result;
  try {
    const config = readAngularJson();
    const apps = Object.entries(config.projects ?? {})
      .filter(
        ([name, definition]) =>
          (project === undefined || name === project) && definition?.architect?.build,
      )
      .map(([, definition]) => path.posix.basename(definition.root ?? ''));
    if (apps.length)
      console.log(
        `stage-languages: ${summary(
          stageLanguages({
            packsDir: path.join(FE, 'languages'),
            outDir: path.join(FE, '.niord', 'build'),
            apps,
          }),
        )}`,
      );
    result = await checkBuildContents(config, { project });
  } catch (error) {
    console.error(`build-contents-check: ${error.message}`);
    process.exit(2);
  }
  const { errors, checked } = result;
  if (errors.length) {
    console.error(`build-contents-check: ${errors.length} problem(s):`);
    for (const e of errors) console.error('  ' + e);
    process.exit(1);
  }
  const files = checked.reduce((sum, { files: n }) => sum + n, 0);
  console.log(
    `build-contents-check: ${checked.length} build configuration(s) (${files} files): one source per file, exactly the bundles and flags their language configurations name and only from the staged language folder, branding only for branding languages, no attribution, review list or other country's overlay, no overlay under its own folder name, every language named as its pack names it.`,
  );
}
