/**
 * ESLint's configuration for the kit's scripts in the base product's tree, run from this folder:
 *
 *   npm run lint:kit         # from fe/, which runs ESLint here; npm run lint:scripts runs it too
 *
 * ESLint takes the configuration of the folder it runs in and lints below it, and the kit sits
 * beside fe/, so the kit carries its own. The kit has no node_modules: its plugins are the base
 * product's, loaded through fe/'s package.json. The Node globals are a copy of
 * fe/eslint.config.js's, held equal by tooling.test.mjs. Not in the source zip.
 */
import { createRequire } from 'node:module';
import path from 'node:path';
import { where } from './lib/where.mjs';

const require = createRequire(path.join(where().base, 'package.json'));
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');

/**
 * Node's globals, for the kit's scripts. They are declared here because the `globals` package is
 * not a dependency of the workspace.
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

export default defineConfig([
  {
    // The example country's own files are linted with the base product's rules inside its own
    // generated workspace (example/run.mjs runs its `npm run lint`); here they belong to no
    // tsconfig, and the aliases they import exist only in that workspace. The harness scripts
    // beside them are the kit's and are linted here.
    ignores: ['example/fe/**', 'example/be/**'],
  },
  {
    // The kit's scripts run in Node. The omit-a-key idiom `({ key: _, ...rest })` names a binding
    // only to leave it out, so a rest sibling counts as used.
    files: ['**/*.mjs', '**/*.cjs'],
    extends: [eslint.configs.recommended],
    languageOptions: { ecmaVersion: 'latest', globals: NODE_GLOBALS },
    rules: {
      'no-unused-vars': ['error', { ignoreRestSiblings: true }],
    },
  },
  {
    files: ['**/*.cjs'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: { ...NODE_GLOBALS, __dirname: 'readonly', __filename: 'readonly' },
    },
  },
]);
