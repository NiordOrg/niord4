/**
 * The base product's npm packages, for the kit's tests. In the base product's
 * tree the kit sits beside fe/, so Node does not find fe/node_modules from the kit's files; an
 * ESM import cannot be redirected by NODE_PATH, so the packages are required through the base
 * product's package.json instead:
 *
 *   import { requireFromBase } from './test-fixtures/base-modules.mjs';
 *   const yaml = requireFromBase('js-yaml');
 *
 * The kit's own code imports no npm package and needs nothing of this; test-fixtures/ is not in
 * the source zip.
 */
import { createRequire } from 'node:module';
import path from 'node:path';
import { where } from '../lib/where.mjs';

/** `require` as the base product's package.json would resolve it, in fe/node_modules. */
export const requireFromBase = createRequire(path.join(where().base, 'package.json'));
