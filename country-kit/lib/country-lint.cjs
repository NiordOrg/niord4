'use strict';
/**
 * The country lint: the ESLint plugin `niord-country` and the blocks that apply it to a country's
 * files (fe-country-split-plan §4.3, the country API; §4.11 rule 3, the security rules).
 *
 * Country code imports base product code through the country API alone — `@niord/admin/api` or
 * `@niord/info/api`, and in a spec their `/testing` entry — and stays inside its own folders
 * otherwise. A guarded override (§4.9) is the one file that may reach `@niord/<app>/internal/*`.
 * A country's e2e specs, outside both applications, import the base product's login fixtures
 * (`@niord/admin/e2e`), `@playwright/test`, `node:` built-ins and their own folder alone.
 * `no-restricted-imports` patterns cannot hold this: a pattern cannot tell how far a relative path
 * climbs, and a later `no-restricted-imports` block replaces an earlier one instead of adding to
 * it. So the rule resolves each relative specifier against the file and its allowed folders.
 *
 * One configuration loads this file: the one the kit generates in a country workspace, which
 * applies the plugin to the country's files and requires the base product's configuration first.
 * That configuration is CommonJS, hence this file; it uses Node built-ins only, as the rest of the
 * kit does. The base product's `eslint.config.js` takes the shared lists (the anonymous packages,
 * the bypass calls) from its own `eslint-shared.cjs`, an equal copy that a test holds to this
 * file's.
 */
const fs = require('node:fs');
const path = require('node:path');

/** Packages the public site never imports: it has no sign-in and no edit flow. */
const INFO_ANONYMOUS_PACKAGES = ['keycloak-js', 'keycloak-js/*', '@angular/forms/signals'];
const INFO_ANONYMOUS_MESSAGE =
  'The public site is anonymous and read-only: it has no authentication and no edit flow.';

/** The DomSanitizer calls that switch Angular's own sanitiser off for one value. */
const BYPASS_SECURITY_TRUST = [
  'bypassSecurityTrustHtml',
  'bypassSecurityTrustScript',
  'bypassSecurityTrustStyle',
  'bypassSecurityTrustUrl',
  'bypassSecurityTrustResourceUrl',
];

/**
 * The pipes a country template may bind `[innerHTML]` through: the message-HTML sanitiser, the
 * plain-text pipe, which escapes, and a translation, whose markup the pack and branding checks
 * hold to the English text's and which Angular sanitises as any plain string.
 */
const INNER_HTML_PIPES = ['safeMessageHtml', 'plainTextToHtml', 'transloco'];

const COUNTRY_TRUSTED_HTML =
  'Country code never marks a value safe, wherever it lies. Render message HTML through the ' +
  'safeMessageHtml pipe and anything else with a plain binding, which Angular sanitises ' +
  '(fe-country-split-plan §4.11 rule 3).';

const API = (app) => `@niord/${app}/api`;
const SEE = 'fe-country-split-plan §4.3, country-kit/API-CHANGES.md';

/** A package pattern as no-restricted-imports writes one: a name, or `name/*` for its subpaths. */
function matchesPackage(specifier, pattern) {
  if (pattern.endsWith('/*')) return specifier.startsWith(pattern.slice(0, -1));
  return specifier === pattern;
}

const isPackage = (specifier, name) => specifier === name || specifier.startsWith(`${name}/`);

/** Whether `file` lies in one of `dirs` (or is one). */
function inside(file, dirs) {
  return dirs.some((dir) => {
    const relative = path.relative(dir, file);
    return (
      relative === '' ||
      (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative))
    );
  });
}

const dirsSchema = { type: 'array', items: { type: 'string' } };

const isRelative = (specifier) =>
  specifier === '.' || specifier === '..' || /^\.\.?[\\/]/.test(specifier);

const literal = (node) =>
  node && node.type === 'Literal' && typeof node.value === 'string' ? node.value : undefined;

/**
 * The visitors of every place a module names another, each calling `check(node, specifier)`; the
 * specifier is undefined where it is built at run time. Both import rules use them, so the two
 * never read different places.
 */
function moduleVisitors(check) {
  return {
    ImportDeclaration: (node) => check(node.source, node.source.value),
    ExportNamedDeclaration: (node) => node.source && check(node.source, node.source.value),
    ExportAllDeclaration: (node) => check(node.source, node.source.value),
    ImportExpression: (node) => check(node.source, literal(node.source)),
    TSImportEqualsDeclaration(node) {
      const reference = node.moduleReference;
      if (reference.type === 'TSExternalModuleReference')
        check(reference.expression, literal(reference.expression));
    },
    // `type T = import('…').T` reaches a module as surely as an import declaration does.
    TSImportType(node) {
      const argument = node.argument;
      check(node, argument?.type === 'TSLiteralType' ? literal(argument.literal) : undefined);
    },
  };
}

const imports = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Country code imports the base product through its application’s country API and stays in its own folders.',
    },
    schema: [
      {
        type: 'object',
        properties: {
          app: { enum: ['admin', 'info'] },
          roots: dirsSchema,
          specRoots: dirsSchema,
          internalRoots: dirsSchema,
          banned: {
            type: 'array',
            items: {
              type: 'object',
              properties: { pattern: { type: 'string' }, message: { type: 'string' } },
              required: ['pattern', 'message'],
              additionalProperties: false,
            },
          },
          otherAppPackages: { type: 'array', items: { type: 'string' } },
        },
        required: ['app', 'roots'],
        additionalProperties: false,
      },
    ],
    messages: {
      runtime:
        'An import() with a specifier built at run time cannot be checked. Write the path as a string literal: ' +
        'a file of your own folder, or {{api}} for the base product ({{see}}).',
      relative:
        '"{{specifier}}" leaves the country’s folders. Import the base product through {{api}}; ' +
        'a need it does not meet is a new export of the API ({{see}}).',
      absolute:
        '"{{specifier}}" is an absolute path. Import a file of your own folder relatively, and the base product through {{api}} ({{see}}).',
      url: '"{{specifier}}" names a URL. A country loads no remote code; import the base product through {{api}} ({{see}}).',
      node: '"{{specifier}}" is a Node built-in, which only a spec may import. Import the base product through {{api}} ({{see}}).',
      testing:
        '{{api}}/testing holds test helpers and is for specs only. Import the base product through {{api}} ({{see}}).',
      internal:
        '"{{specifier}}" reaches the base product’s internals, which only a guarded override in {{app}}/overrides/ may do. ' +
        'Import through {{api}} instead ({{see}}).',
      niord:
        '"{{specifier}}" is not this application’s country API. Country code of the {{app}} application imports the base product through {{api}} alone ({{see}}).',
      banned: '"{{specifier}}": {{message}}',
      otherApp:
        '"{{specifier}}" is a country package for the other application (fe/country/package.json names its "app"). ' +
        'Import the base product through {{api}} ({{see}}).',
    },
  },
  create(context) {
    const options = context.options[0];
    const { app } = options;
    const roots = options.roots.map((dir) => path.resolve(dir));
    const specRoots = (options.specRoots ?? []).map((dir) => path.resolve(dir));
    const internalRoots = (options.internalRoots ?? []).map((dir) => path.resolve(dir));
    const banned = options.banned ?? [];
    const otherAppPackages = options.otherAppPackages ?? [];
    const filename = path.resolve(context.filename);
    const spec = filename.endsWith('.spec.ts');
    const data = { app, api: API(app), see: SEE };

    const report = (node, messageId, extra = {}) =>
      context.report({ node, messageId, data: { ...data, ...extra } });

    function check(node, specifier) {
      if (typeof specifier !== 'string') return report(node, 'runtime');
      if (isRelative(specifier)) {
        const target = path.resolve(path.dirname(filename), specifier);
        if (inside(target, roots) || (spec && inside(target, specRoots))) return undefined;
        return report(node, 'relative', { specifier });
      }
      if (/^[\\/]/.test(specifier) || /^[a-z]:/i.test(specifier) || path.isAbsolute(specifier))
        return report(node, 'absolute', { specifier });
      if (/^[a-z][a-z0-9+.-]*:/i.test(specifier)) {
        if (specifier.startsWith('node:'))
          return spec ? undefined : report(node, 'node', { specifier });
        return report(node, 'url', { specifier });
      }
      if (specifier.startsWith('@niord/')) {
        if (specifier === API(app)) return undefined;
        if (specifier === `${API(app)}/testing`) return spec ? undefined : report(node, 'testing');
        if (specifier.startsWith(`@niord/${app}/internal/`))
          return inside(filename, internalRoots)
            ? undefined
            : report(node, 'internal', { specifier });
        return report(node, 'niord', { specifier });
      }
      const ban = banned.find((entry) => matchesPackage(specifier, entry.pattern));
      if (ban) return report(node, 'banned', { specifier, message: ban.message });
      if (otherAppPackages.some((name) => isPackage(specifier, name)))
        return report(node, 'otherApp', { specifier });
      return undefined;
    }

    return moduleVisitors(check);
  },
};

/** The packages a country's e2e spec may import, each exactly: the login fixtures and Playwright. */
const E2E_PACKAGES = ['@niord/admin/e2e', '@playwright/test'];
const E2E_SEE = 'country-kit/README.md, End-to-end specs';

const e2eImports = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'A country’s e2e specs import the base product’s login fixtures, Playwright, Node built-ins and their own folder.',
    },
    schema: [
      {
        type: 'object',
        properties: { roots: dirsSchema },
        required: ['roots'],
        additionalProperties: false,
      },
    ],
    messages: {
      runtime:
        'An import() with a specifier built at run time cannot be checked. Write the path as a string literal ({{see}}).',
      imports:
        '"{{specifier}}" is not for an e2e spec. A spec imports @niord/admin/e2e (the login fixtures), ' +
        '@playwright/test, a node: built-in or a file of e2e/ ({{see}}).',
    },
  },
  create(context) {
    const roots = context.options[0].roots.map((dir) => path.resolve(dir));
    const filename = path.resolve(context.filename);
    const report = (node, messageId, specifier) =>
      context.report({ node, messageId, data: { specifier, see: E2E_SEE } });

    function check(node, specifier) {
      if (typeof specifier !== 'string') return report(node, 'runtime');
      if (E2E_PACKAGES.includes(specifier) || specifier.startsWith('node:')) return undefined;
      if (isRelative(specifier) && inside(path.resolve(path.dirname(filename), specifier), roots))
        return undefined;
      return report(node, 'imports', specifier);
    }

    return moduleVisitors(check);
  },
};

const innerHtml = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'A country template binds [innerHTML] only through the base product’s safe pipes or a translation.',
    },
    schema: [],
    messages: {
      innerHtml:
        'A country template binds [{{name}}] only through the ' +
        `${INNER_HTML_PIPES.slice(0, -1).join(', ')} or ${INNER_HTML_PIPES.at(-1)} pipe, as its last pipe; ` +
        'show any other value with a text binding, which Angular escapes ({{see}}).',
    },
  },
  create(context) {
    return {
      'BoundAttribute[name=/^(innerhtml|outerhtml)$/i]'(node) {
        // The binding's expression; Angular wraps a parenthesised one in a node of its own.
        let ast = node.value?.ast;
        while (ast?.type === 'ParenthesizedExpression') ast = ast.expression;
        if (ast?.type === 'BindingPipe' && INNER_HTML_PIPES.includes(ast.name)) return;
        context.report({
          node,
          messageId: 'innerHtml',
          data: { name: node.name, see: 'fe-country-split-plan §4.11 rule 3' },
        });
      },
    };
  },
};

const plugin = {
  meta: { name: 'niord-country' },
  rules: { imports, 'e2e-imports': e2eImports, 'inner-html': innerHtml },
};

/** `no-restricted-properties` for the five bypass calls, with the given message. */
const bypassBans = (message) => BYPASS_SECURITY_TRUST.map((property) => ({ property, message }));

/**
 * The country packages of each application, from fe/country/package.json's `niord.dependencies`
 * (`app`: admin, info or both). Set-up has checked the file before lint runs, so a file that is
 * missing or unreadable here simply names none.
 */
function countryPackages(dir) {
  const only = { admin: [], info: [] };
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(path.join(dir, 'country', 'package.json'), 'utf8'));
  } catch {
    return only;
  }
  const entries = manifest?.niord?.dependencies;
  if (!entries || typeof entries !== 'object') return only;
  for (const [name, entry] of Object.entries(entries))
    if (entry && Object.hasOwn(only, entry.app)) only[entry.app].push(name);
  return only;
}

/**
 * The blocks a country workspace's generated ESLint configuration ends with; `dir` is the
 * country's fe/. Per application: the imports rule, with relative paths kept to <app>/app,
 * <app>/overrides and <app>/environments (and <app>/assets for a spec), internals for the
 * overrides alone, the public site's anonymity bans and the other application's country packages
 * refused; the bypassSecurityTrust* ban restated, since the base product's block exempts paths a
 * country file could imitate; [innerHTML] only through the safe pipes, in templates and, through
 * angular-eslint's processor, inline templates. The e2e specs, e2e/**, import only the login
 * fixtures (`@niord/admin/e2e`), `@playwright/test`, `node:` built-ins and files of e2e/. In every
 * country file, no inline configuration, so an `eslint-disable` disables nothing and is itself
 * reported.
 */
function countryBlocks({ dir }) {
  const at = (...parts) => path.join(dir, ...parts);
  const only = countryPackages(dir);
  const other = { admin: 'info', info: 'admin' };
  const blocks = ['admin', 'info'].map((app) => ({
    files: [`${app}/**/*.ts`],
    plugins: { 'niord-country': plugin },
    rules: {
      'niord-country/imports': [
        'error',
        {
          app,
          roots: [at(app, 'app'), at(app, 'overrides'), at(app, 'environments')],
          specRoots: [at(app, 'assets')],
          internalRoots: [at(app, 'overrides')],
          banned:
            app === 'info'
              ? INFO_ANONYMOUS_PACKAGES.map((pattern) => ({
                  pattern,
                  message: INFO_ANONYMOUS_MESSAGE,
                }))
              : [],
          otherAppPackages: only[other[app]],
        },
      ],
      'no-restricted-properties': ['error', ...bypassBans(COUNTRY_TRUSTED_HTML)],
    },
  }));
  blocks.push(
    {
      files: ['admin/**/*.html', 'info/**/*.html'],
      plugins: { 'niord-country': plugin },
      rules: { 'niord-country/inner-html': 'error' },
    },
    {
      files: ['e2e/**/*.ts'],
      plugins: { 'niord-country': plugin },
      rules: { 'niord-country/e2e-imports': ['error', { roots: [at('e2e')] }] },
    },
    {
      files: ['admin/**/*.ts', 'admin/**/*.html', 'info/**/*.ts', 'info/**/*.html', 'e2e/**/*.ts'],
      linterOptions: { noInlineConfig: true, reportUnusedDisableDirectives: 'error' },
    },
  );
  return blocks;
}

module.exports = {
  INFO_ANONYMOUS_PACKAGES,
  INFO_ANONYMOUS_MESSAGE,
  BYPASS_SECURITY_TRUST,
  INNER_HTML_PIPES,
  plugin,
  countryBlocks,
};
