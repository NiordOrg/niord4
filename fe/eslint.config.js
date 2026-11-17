// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');
const shared = require('./eslint-shared.cjs');

/**
 * A time zone comes from the domain's settings, never from the machine the browser is
 * running on. These four selectors catch every way the browser's own zone leaks into a
 * value: the `Date` mutators and readers (which are all local-zone), the multi-argument
 * `Date` constructor (which builds a local-zone instant), and an `Intl.DateTimeFormat`
 * built without a `timeZone` (which renders in the local zone).
 *
 * A bare `HH:mm` setting — a cut-off time, a scheduled send time — is the one legitimate
 * exception: it is a clock with no date, so no zone can resolve it and it is carried on a
 * browser-local `Date` on purpose. Those sites take an inline disable naming the reason.
 *
 * The selectors apply to both applications, and each application has its own helpers in its own
 * `shared/utils/zoned-date.ts` (neither may import the other's), so every message names both.
 */
const TIME_ZONE_RESTRICTIONS = [
  {
    selector:
      'CallExpression[callee.property.name=/^(setHours|setMinutes|setSeconds|setMilliseconds|setDate|setMonth|setFullYear)$/]',
    message:
      "This mutates a Date in the browser's time zone. Build the instant in the domain's zone instead, with the application's own shared/utils/zoned-date.ts: zonedDateToUtc / wallClockToInstant / startOfDayInZone / endOfDayInZone / shiftDaysInZone in the administration app, zonedWallClockToInstantMs / startOfZonedDayMs / endOfZonedDayMs on the public site.",
  },
  {
    selector:
      'CallExpression[callee.property.name=/^(getHours|getMinutes|getSeconds|getDate|getDay|getMonth|getFullYear|getTimezoneOffset)$/]',
    message:
      "This reads a Date in the browser's time zone. Read the wall clock in the domain's zone with getZonedDateParts(instant, timeZone) from the application's own shared/utils/zoned-date.ts.",
  },
  {
    selector: 'NewExpression[callee.name="Date"][arguments.length>=2]',
    message:
      "new Date(year, month, ...) builds an instant in the browser's time zone. Build it in the domain's zone with the application's own shared/utils/zoned-date.ts: zonedDateToUtc(...) / wallClockToInstant(...) in the administration app, or zonedWallClockAsLocalDate(...) for the wall clock a bare HH:mm field carries; zonedWallClockToInstantMs(...) on the public site.",
  },
  {
    selector:
      'NewExpression[callee.object.name="Intl"][callee.property.name="DateTimeFormat"]:not(:has(ObjectExpression > Property[key.name="timeZone"]))',
    message:
      "An Intl.DateTimeFormat with no timeZone formats in the browser's time zone. Render through the application's formatter — LocaleFormatService in the administration app (it applies the session domain's zone), DateFormatService or formatInZone(...) on the public site — or pass an explicit timeZone.",
  },
];

/**
 * The files that ARE the time-zone conversion layer: the zone helpers, the format module and the
 * date/time controls read and build browser-local wall clocks on purpose, and are the only places
 * allowed to. Specs are exempt so they can construct and inspect local dates freely — a spec
 * asserts against an explicit zone instead (see docs/coding-standards.md).
 *
 * The public site needs no entry here, and the asymmetry is deliberate: its own copy of the zone
 * helpers builds every instant through Date.UTC and reads every clock through an
 * Intl.DateTimeFormat that carries a timeZone, because it has no picker and no bare-HH:mm carrier
 * to convert for. It passes the four selectors unaided.
 */
const ADMIN_TIME_ZONE_LAYER = [
  'ui-apps/admin/src/app/shared/utils/zoned-date.ts',
  'ui-apps/admin/src/app/core/main-shell/services/date-time-format.ts',
  'ui-apps/admin/src/app/core/main-shell/services/locale-format.service.ts',
  'ui-apps/admin/src/app/shared/forms/date-picker/**',
  'ui-apps/admin/src/app/shared/forms/time-field/**',
  'ui-apps/admin/src/app/shared/testing/zone-testing.ts',
];

/**
 * The two applications ship separately and neither may import the other. The public site is
 * built from a Docker context that does not contain `ui-apps/admin/` at all, so an import reaching
 * across would not fail here but at release time, in the image build; these patterns make it
 * fail at the keyboard instead. `keycloak-js` and `@angular/forms/signals` have no place in an
 * anonymous read-only site with no login and no edit flow.
 *
 * A path into the other application is written in one of two spellings, and each list names
 * both: one that spells the application folder (`…/ui-apps/admin/…`), matched by the glob, and a
 * relative one that climbs out of the application and never spells `ui-apps/`
 * (`../../../../admin/src/app/…`, with or without a leading `./`), matched by the regex. A
 * specifier assembled at run time is beyond the reach of any pattern.
 *
 * Every block below repeats these patterns, because `no-restricted-imports` is REPLACED by the
 * last matching block, never merged with the earlier ones.
 */
const INFO_SEPARATION_MESSAGE =
  'The public site must not import administration code: the separation check (npm run separation:info) fails a build that has some. Copy what you need into ui-apps/info/, or promote it to a folder both applications share.';
// The anonymity bans are in eslint-shared.cjs. The country lint, which holds a country's
// public-site files to them as well, keeps an equal copy, and a test holds the two equal.
const { INFO_ANONYMOUS_PACKAGES, INFO_ANONYMOUS_MESSAGE } = shared;
const INFO_APP_SEPARATION = [
  { group: ['**/ui-apps/admin/**'], message: INFO_SEPARATION_MESSAGE },
  { regex: '^(\\./)?(\\.\\./)+(ui-apps/)?admin/', message: INFO_SEPARATION_MESSAGE },
  { group: [...INFO_ANONYMOUS_PACKAGES], message: INFO_ANONYMOUS_MESSAGE },
];

const ADMIN_SEPARATION_MESSAGE =
  'The administration app must not import the public site. Promote the shared piece to a folder both applications share instead.';
const ADMIN_APP_SEPARATION = [
  { group: ['**/ui-apps/info/**'], message: ADMIN_SEPARATION_MESSAGE },
  { regex: '^(\\./)?(\\.\\./)+(ui-apps/)?info/', message: ADMIN_SEPARATION_MESSAGE },
];

/**
 * The country APIs, `@niord/admin/api` and `@niord/info/api` with their `/testing` entries, are a
 * one-way facade: country code imports the base product through them, and base product code
 * never imports them, so the barrels can neither close an import cycle nor become a shortcut
 * between the base product's own folders. Every block below carries the ban.
 */
const COUNTRY_API_BAN = {
  group: ['@niord/admin/api', '@niord/admin/api/*', '@niord/info/api', '@niord/info/api/*'],
  message:
    'Base product code never imports a country API; it is what country code imports the base product through (fe-country-split-plan §4.3). Import the module itself.',
};
const INFO_SEPARATION = [...INFO_APP_SEPARATION, COUNTRY_API_BAN];
const ADMIN_SEPARATION = [...ADMIN_APP_SEPARATION, COUNTRY_API_BAN];

/**
 * `no-restricted-imports` sees static imports and re-exports only, and a lazy route or a lazily
 * opened dialog is written as a dynamic `import()`. These `no-restricted-syntax` selectors hold
 * the same line for it: the specifier as a string literal, or the leading text of a template
 * literal. The regexes are the separation patterns above in one expression each.
 */
function dynamicImportBans(pattern, message) {
  return [
    { selector: `ImportExpression > Literal[value=${pattern}]`, message },
    {
      selector: `ImportExpression > TemplateLiteral > TemplateElement:first-child[value.raw=${pattern}]`,
      message,
    },
  ];
}

/** The package patterns of a `group` (`name`, or `name/*` for its subpaths) as one expression. */
function packagesExpression(patterns) {
  const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const alternatives = patterns.map((pattern) =>
    pattern.endsWith('/*') ? `^${escape(pattern.slice(0, -1))}` : `^${escape(pattern)}$`,
  );
  return new RegExp(alternatives.join('|'));
}

const INFO_DYNAMIC_SEPARATION = [
  ...dynamicImportBans(/(^|\/)ui-apps\/admin\/|^(\.\/)?(\.\.\/)+admin\//, INFO_SEPARATION_MESSAGE),
  ...dynamicImportBans(packagesExpression(INFO_ANONYMOUS_PACKAGES), INFO_ANONYMOUS_MESSAGE),
];

const ADMIN_DYNAMIC_SEPARATION = dynamicImportBans(
  /(^|\/)ui-apps\/info\/|^(\.\/)?(\.\.\/)+info\//,
  ADMIN_SEPARATION_MESSAGE,
);

/**
 * Nothing outside `country-specific/` may import a country folder: the build swaps the
 * `default` file through `fileReplacements`, so a direct import of any other folder under it
 * would ship that folder's code into every build. Only `app.config.ts` imports a country folder,
 * and only `country-specific/default/`.
 */
const INFO_COUNTRY_MESSAGE =
  'Only app.config.ts imports a country folder, and only country-specific/default/. A difference that cannot be expressed as a config value, a token or a translation key needs a new extension point, not a country import.';
const INFO_COUNTRY = {
  // The regex form, because a country import is spelled relatively
  // (`../../country-specific/default/…`) and a glob group would have to guess how many levels up
  // it was written from.
  regex: '(^|/)country-specific/',
  message: INFO_COUNTRY_MESSAGE,
};
const INFO_COUNTRY_EXCEPT_DEFAULT = {
  regex: '(^|/)country-specific/(?!default/)',
  message: INFO_COUNTRY_MESSAGE,
};
// The same two as dynamic imports: app.config.ts loads its country routes with import().
const INFO_DYNAMIC_COUNTRY = dynamicImportBans(/(^|\/)country-specific\//, INFO_COUNTRY_MESSAGE);
const INFO_DYNAMIC_COUNTRY_EXCEPT_DEFAULT = dynamicImportBans(
  /(^|\/)country-specific\/(?!default\/)/,
  INFO_COUNTRY_MESSAGE,
);

/**
 * The shared OpenLayers kit belongs to neither application. Its dependency list is short on
 * purpose: `@angular/*`, `ol/*` and `rxjs` (plus `vitest` in its specs). Anything an application
 * owns — a translation, a session, a deployment setting — reaches the kit as an input, never as
 * an injection, so one map behaves the same in both. One exception, narrow on purpose: the
 * deployment's tile addresses, the same for every map an application draws, reach the tile layers
 * through the kit's own `MAP_TILE_SOURCES` token (`shared-map/tile-sources.ts`, empty by default),
 * which each application provides once, at its root. Lint cannot see an injection, so this
 * comment, the message below and docs/architecture.md are where the rule is kept.
 */
const MAP_KIT_ALLOWED =
  'fe/shared-map/ is shared by both applications and may import only @angular/*, ol/*, rxjs and (in specs) vitest. ' +
  "Anything an application defines is an input on the kit, never an injection — bar the kit's MAP_TILE_SOURCES token " +
  "for the deployment's tile addresses; see docs/architecture.md.";

/**
 * The shared message-HTML contract belongs to neither application either, and its dependency list
 * is shorter still: `@angular/*` (plus `vitest` in its specs) and nothing else. It renders no
 * markup of its own and reads no configuration, so it needs neither a map library nor a stream.
 */
const HTML_CONTRACT_ALLOWED =
  'fe/shared-html/ is shared by both applications and may import only @angular/* and (in specs) vitest. ' +
  'Anything an application defines is an argument, never an injection; see docs/architecture.md.';

/**
 * The DomSanitizer calls that switch Angular's own sanitiser off for one value. Message HTML is
 * the backend's stored content, written by editors and imported from other systems; a bypass on it
 * anywhere but the sanitiser that cleaned it first is a stored cross-site script. The list is in
 * eslint-shared.cjs. The country lint, which bans the same calls in a country's files with no
 * exemption, keeps an equal copy, and a test holds the two equal.
 */
const { BYPASS_SECURITY_TRUST } = shared;

/**
 * Who may call them: the message-HTML sanitiser, which marks safe what it has just sanitised; the
 * plain-text pipe, which marks safe what it has just escaped; and the sandboxed-HTML component,
 * whose document runs in an empty-sandbox iframe with an opaque origin.
 */
const TRUSTED_HTML_EXEMPT = [
  'shared-html/**',
  'ui-apps/admin/src/app/shared/pipes/plain-text-to-html.pipe.ts',
  'ui-apps/admin/src/app/shared/components/sandboxed-html/sandboxed-html.ts',
];

const TRUSTED_HTML_ONLY =
  'Only fe/shared-html/ (and two documented administration-app exemptions) may mark a value safe. ' +
  'Render message HTML through MessageHtmlSanitizer — the safeMessageHtml pipe — and anything else ' +
  'with a plain binding, which Angular sanitises; see docs/architecture.md, the message-HTML contract.';

const INFO_LAYER =
  'shared/ and core/ are below features/ in the public site too. Move the dependency down, or invert it through the NIORD_INFO_CONFIG token.';
const INFO_SIBLING =
  'Sibling-feature imports are forbidden in the public site. Promote the reusable piece into shared/ instead of coupling features.';

/**
 * Node's globals, for the workspace's scripts. They are declared here because the `globals`
 * package is not a dependency of the workspace.
 */
const NODE_GLOBALS = Object.fromEntries(
  [
    'process',
    'console',
    'Buffer',
    'URL',
    'URLSearchParams',
    'setTimeout',
    'clearTimeout',
    'setInterval',
    'clearInterval',
    'setImmediate',
    'clearImmediate',
    'queueMicrotask',
    'structuredClone',
    'fetch',
    'Headers',
    'Request',
    'Response',
    'FormData',
    'Blob',
    'AbortController',
    'AbortSignal',
    'TextEncoder',
    'TextDecoder',
    'performance',
    'crypto',
    'atob',
    'btoa',
  ].map((name) => [name, 'readonly']),
);

module.exports = defineConfig([
  {
    // Build output and the Angular build cache are generated, not app source.
    ignores: ['dist/**', '.angular/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    // The project service lets type-aware rules (like `no-deprecated`) read the TS program.
    // It's slower than non-type-aware lint, but only matters for full-project runs —
    // lint-staged runs ESLint on a tiny slice so commits stay fast.
    //
    // The tool configs belong to no tsconfig (the app and spec projects cover the `src/` folders
    // only), so the service is told to fall back to its default project for them —
    // otherwise editing one is a parse error the moment lint-staged reaches it.
    languageOptions: {
      parserOptions: {
        projectService: {
          allowDefaultProject: ['vitest.config.ts', 'playwright.config.ts', 'transloco.config.ts'],
        },
        tsconfigRootDir: __dirname,
      },
    },
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'app',
          style: 'camelCase',
        },
      ],
      '@angular-eslint/component-selector': [
        'error',
        {
          type: 'element',
          prefix: 'app',
          style: 'kebab-case',
        },
      ],
      // Surface any usage of an API marked `@deprecated` in JSDoc — Angular, RxJS, the
      // CDK and any other typed dependency. Type-aware, hence the project service above.
      '@typescript-eslint/no-deprecated': 'error',
      'no-restricted-syntax': ['error', ...TIME_ZONE_RESTRICTIONS],
    },
  },
  {
    // The time-zone conversion layer and the specs (see ADMIN_TIME_ZONE_LAYER).
    files: [...ADMIN_TIME_ZONE_LAYER, '**/*.spec.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
  // A dynamic import() across the applications. `no-restricted-syntax` is REPLACED by the last
  // matching block, like `no-restricted-imports`, so each of the five blocks restates the
  // time-zone selectors for the files that carry them and leaves them out for the exempt ones.
  {
    files: ['ui-apps/admin/**/*.ts'],
    ignores: [...ADMIN_TIME_ZONE_LAYER, '**/*.spec.ts'],
    rules: {
      'no-restricted-syntax': ['error', ...TIME_ZONE_RESTRICTIONS, ...ADMIN_DYNAMIC_SEPARATION],
    },
  },
  {
    files: [...ADMIN_TIME_ZONE_LAYER, 'ui-apps/admin/**/*.spec.ts'],
    rules: {
      'no-restricted-syntax': ['error', ...ADMIN_DYNAMIC_SEPARATION],
    },
  },
  // The public site's two blocks also carry INFO_COUNTRY's dynamic form, and app.config.ts, which
  // loads country-specific/default/country.routes with import(), has a block of its own.
  {
    files: ['ui-apps/info/**/*.ts'],
    ignores: ['**/*.spec.ts', 'ui-apps/info/src/app/app.config.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...TIME_ZONE_RESTRICTIONS,
        ...INFO_DYNAMIC_SEPARATION,
        ...INFO_DYNAMIC_COUNTRY,
      ],
    },
  },
  {
    files: ['ui-apps/info/**/*.spec.ts'],
    rules: {
      'no-restricted-syntax': ['error', ...INFO_DYNAMIC_SEPARATION, ...INFO_DYNAMIC_COUNTRY],
    },
  },
  {
    files: ['ui-apps/info/src/app/app.config.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...TIME_ZONE_RESTRICTIONS,
        ...INFO_DYNAMIC_SEPARATION,
        ...INFO_DYNAMIC_COUNTRY_EXCEPT_DEFAULT,
      ],
    },
  },
  {
    // Everything in ui-apps/admin/ that no layer block below covers: app.config.ts, environments/,
    // country-specific/, e2e/. The layer blocks repeat this pattern for the folders they own,
    // and this block comes first so that it cannot wipe their feature bans.
    files: ['ui-apps/admin/**/*.ts'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [...ADMIN_SEPARATION] }],
    },
  },
  {
    // Layer boundary: shared/ may depend on core/ but never on features/.
    // The `patterns` form catches relative imports (`../../features/messages/...`).
    files: ['ui-apps/admin/src/app/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: ['**/features/**'],
              message:
                'shared/ must not import from features/. Move the dependency into shared/ or invert it via an injection token.',
            },
          ],
        },
      ],
    },
  },
  {
    // Layer boundary: core/ is app-wide infrastructure and must never depend on
    // feature internals. If core needs something a feature owns, invert it via an
    // injection token or move the shared piece down into shared/.
    files: ['ui-apps/admin/src/app/core/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: ['**/features/**'],
              message:
                'core/ must never import feature internals. Invert the dependency via an injection token or move the shared piece into shared/.',
            },
          ],
        },
      ],
    },
  },
  {
    // Feature boundary: a feature must not import a sibling feature's internals.
    // The sole approved exception is features/messages/, the foundational hub the
    // other features build on (its shared-worthy primitives get promoted to shared/).
    files: ['ui-apps/admin/src/app/features/admin/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: ['**/features/editor/**', '**/features/home/**', '**/features/schedule/**'],
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
            {
              // Sibling-relative form (`../editor/...`) carries no `features/` segment,
              // so the glob above cannot see it — the regex covers that spelling.
              regex: '^(\\.\\./)+(editor|home|schedule)/',
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
          ],
        },
      ],
    },
  },
  {
    // Feature boundary: a feature must not import a sibling feature's internals.
    // The sole approved exception is features/messages/, the foundational hub the
    // other features build on (its shared-worthy primitives get promoted to shared/).
    files: ['ui-apps/admin/src/app/features/editor/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: ['**/features/admin/**', '**/features/home/**', '**/features/schedule/**'],
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
            {
              // Sibling-relative form (`../admin/...`) carries no `features/` segment,
              // so the glob above cannot see it — the regex covers that spelling.
              regex: '^(\\.\\./)+(admin|home|schedule)/',
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
          ],
        },
      ],
    },
  },
  {
    // Feature boundary: a feature must not import a sibling feature's internals.
    // The sole approved exception is features/messages/, the foundational hub the
    // other features build on (its shared-worthy primitives get promoted to shared/).
    files: ['ui-apps/admin/src/app/features/home/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: ['**/features/admin/**', '**/features/editor/**', '**/features/schedule/**'],
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
            {
              // Sibling-relative form (`../admin/...`) carries no `features/` segment,
              // so the glob above cannot see it — the regex covers that spelling.
              regex: '^(\\.\\./)+(admin|editor|schedule)/',
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
          ],
        },
      ],
    },
  },
  {
    // Feature boundary: a feature must not import a sibling feature's internals.
    // The sole approved exception is features/messages/, the foundational hub the
    // other features build on (its shared-worthy primitives get promoted to shared/).
    files: ['ui-apps/admin/src/app/features/schedule/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: ['**/features/admin/**', '**/features/editor/**', '**/features/home/**'],
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
            {
              // Sibling-relative form (`../admin/...`) carries no `features/` segment,
              // so the glob above cannot see it — the regex covers that spelling.
              regex: '^(\\.\\./)+(admin|editor|home)/',
              message:
                'Sibling-feature imports are forbidden. Only features/messages/ is an approved shared hub; promote reusable primitives into shared/ instead of coupling features.',
            },
          ],
        },
      ],
    },
  },
  {
    // Feature boundary: features/messages/ is the approved hub the other features
    // build on, so the dependency direction is one-way — messages must never import
    // the features that depend on it.
    files: ['ui-apps/admin/src/app/features/messages/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...ADMIN_SEPARATION,
            {
              group: [
                '**/features/admin/**',
                '**/features/editor/**',
                '**/features/home/**',
                '**/features/schedule/**',
              ],
              message:
                'messages/ is the hub; it must not import the features that build on it. Keep the dependency one-way (other features → messages).',
            },
            {
              // Sibling-relative form (`../editor/...`) carries no `features/` segment,
              // so the glob above cannot see it — the regex covers that spelling.
              regex: '^(\\.\\./)+(admin|editor|home|schedule)/',
              message:
                'messages/ is the hub; it must not import the features that build on it. Keep the dependency one-way (other features → messages).',
            },
          ],
        },
      ],
    },
  },
  {
    // The shared OpenLayers kit. Its image build context (the public site's) holds no
    // ui-apps/admin/, and the administration build holds no ui-apps/info/, so an import either way
    // breaks a release rather than a lint run — which is far too late to find out.
    files: ['shared-map/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            { group: ['**/ui-apps/admin/**', '**/ui-apps/info/**'], message: MAP_KIT_ALLOWED },
            // A relative escape out of the kit carries no application segment of its own.
            { regex: '^(\\.\\./)+(ui-apps|src)/', message: MAP_KIT_ALLOWED },
            {
              // Allow-list by negation: a bare specifier that is not @angular/*, ol/*, rxjs or
              // vitest is a dependency the kit may not take. `ol/*` means a module path — the
              // bare `ol` barrel is not on the list.
              regex: '^(?!\\.{1,2}/|@angular/|ol/|rxjs$|rxjs/|vitest$)[@a-z]',
              message: MAP_KIT_ALLOWED,
            },
          ],
        },
      ],
    },
  },
  {
    // The shared message-HTML contract. Same reason as the kit above: an import either way breaks
    // a release rather than a lint run, which is far too late to find out.
    files: ['shared-html/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/ui-apps/admin/**', '**/ui-apps/info/**'],
              message: HTML_CONTRACT_ALLOWED,
            },
            // A relative escape out of the folder carries no application segment of its own.
            { regex: '^(\\.\\./)+(ui-apps|src)/', message: HTML_CONTRACT_ALLOWED },
            {
              // Allow-list by negation: a bare specifier that is not @angular/* or vitest is a
              // dependency this folder may not take.
              regex: '^(?!\\.{1,2}/|@angular/|vitest$)[@a-z]',
              message: HTML_CONTRACT_ALLOWED,
            },
          ],
        },
      ],
    },
  },
  {
    // Everything in ui-apps/info/ that no layer block below covers: main.ts, app.ts,
    // niord-info.config.ts, provide-niord-info.ts, country-specific/, testing/.
    // A country folder is imported by none of them — a country file reaches its own with `./`.
    files: ['ui-apps/info/**/*.ts'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [...INFO_SEPARATION, INFO_COUNTRY] }],
    },
  },
  {
    // The one file that imports a country folder, and only `default/`: a country build swaps
    // that folder's files for its own through fileReplacements.
    files: ['ui-apps/info/src/app/app.config.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        { patterns: [...INFO_SEPARATION, INFO_COUNTRY_EXCEPT_DEFAULT] },
      ],
    },
  },
  {
    // Layer boundary: shared/ may depend on core/, never on features/.
    files: ['ui-apps/info/src/app/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...INFO_SEPARATION,
            INFO_COUNTRY,
            { group: ['**/features/**'], message: INFO_LAYER },
          ],
        },
      ],
    },
  },
  {
    // Layer boundary: core/ is application-wide infrastructure and never depends on a feature.
    files: ['ui-apps/info/src/app/core/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...INFO_SEPARATION,
            INFO_COUNTRY,
            { group: ['**/features/**'], message: INFO_LAYER },
          ],
        },
      ],
    },
  },
  {
    // i18n/ and routes/ are application-wide too, and no country folder belongs in either.
    files: ['ui-apps/info/src/app/{i18n,routes}/**/*.ts'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [...INFO_SEPARATION, INFO_COUNTRY] }],
    },
  },
  {
    // The public site has three flat features and no hub, so the bans between them are symmetric.
    files: ['ui-apps/info/src/app/features/messages/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...INFO_SEPARATION,
            INFO_COUNTRY,
            {
              group: ['**/features/publications/**', '**/features/teaser/**'],
              message: INFO_SIBLING,
            },
            {
              // The sibling-relative spelling (`../teaser/...`) carries no `features/` segment.
              regex: '^(\\.\\./)+(publications|teaser)/',
              message: INFO_SIBLING,
            },
          ],
        },
      ],
    },
  },
  {
    files: ['ui-apps/info/src/app/features/publications/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...INFO_SEPARATION,
            INFO_COUNTRY,
            {
              group: ['**/features/messages/**', '**/features/teaser/**'],
              message: INFO_SIBLING,
            },
            {
              regex: '^(\\.\\./)+(messages|teaser)/',
              message: INFO_SIBLING,
            },
          ],
        },
      ],
    },
  },
  {
    // Symmetric with the two blocks above: the teaser draws its own chart on the shared map kit,
    // so it needs nothing from the messages feature and this block names no exception.
    files: ['ui-apps/info/src/app/features/teaser/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            ...INFO_SEPARATION,
            INFO_COUNTRY,
            {
              group: ['**/features/messages/**', '**/features/publications/**'],
              message: INFO_SIBLING,
            },
            {
              regex: '^(\\.\\./)+(messages|publications)/',
              message: INFO_SIBLING,
            },
          ],
        },
      ],
    },
  },
  {
    // Telling Angular a string is safe (see TRUSTED_HTML_ONLY). The one block that sets
    // `no-restricted-properties`, so nothing later can replace it.
    files: ['**/*.ts'],
    ignores: TRUSTED_HTML_EXEMPT,
    rules: {
      'no-restricted-properties': [
        'error',
        ...BYPASS_SECURITY_TRUST.map((property) => ({ property, message: TRUSTED_HTML_ONLY })),
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
  {
    // The workspace's scripts run in Node. The omit-a-key idiom `({ key: _, ...rest })`
    // names a binding only to leave it out, so a rest sibling counts as used.
    files: ['**/*.mjs', '**/*.cjs', 'scripts/**/*.js'],
    extends: [eslint.configs.recommended],
    languageOptions: { ecmaVersion: 'latest', globals: NODE_GLOBALS },
    rules: {
      'no-unused-vars': ['error', { ignoreRestSiblings: true }],
    },
  },
  {
    // The workspace's package.json declares no module type, so a `.js` script is CommonJS.
    files: ['**/*.cjs', 'scripts/**/*.js'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: { ...NODE_GLOBALS, __dirname: 'readonly', __filename: 'readonly' },
    },
  },
  {
    // The callbacks these two pass to Playwright's page.evaluate run in Chromium, not in Node.
    files: ['scripts/info-container-test.mjs', 'scripts/info-layout-check.mjs'],
    languageOptions: {
      globals: {
        ...NODE_GLOBALS,
        document: 'readonly',
        window: 'readonly',
        self: 'readonly',
        requestAnimationFrame: 'readonly',
        innerWidth: 'readonly',
        innerHeight: 'readonly',
        getComputedStyle: 'readonly',
      },
    },
  },
]);
