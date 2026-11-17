/**
 * The files a build copies for an `assets` array, as the installed @angular/build resolves them,
 * and the output paths more than one input writes (fe-country-split-plan R9, §4.8).
 *
 * `ng build` copies its asset inputs concurrently, so when two inputs write the same output path
 * the file a reader gets is not defined (`@angular/build` `utils/resolve-assets.js`,
 * `tools/esbuild/utils.js`), and the build stays green. Which files a build copies is therefore
 * asked of the builder's own code rather than of a copy of its rules: a glob, an ignore list or
 * an output folder means here what it means to the build, and an upgrade that changes one is seen
 * the day it lands. The base product's contents:check (scripts/build-contents-check.mjs) and a
 * country's check-build (lib/build-checks.mjs) both resolve through this module.
 *
 * Nothing is imported at module load beyond Node's built-ins: the builder is found from the
 * workspace root at run time (createRequire), where `npm ci` installed it, so the kit stays
 * built-ins only and a workspace without node_modules fails with a message, not on import.
 */
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const toPosix = (p) => p.replaceAll('\\', '/');

/** The folder of the @angular/build installed for the workspace in `root`. */
export function builderDir(root) {
  try {
    return path.dirname(
      createRequire(path.join(root, 'package.json')).resolve('@angular/build/package.json'),
    );
  } catch {
    throw new Error('@angular/build is not installed: run `npm ci` in fe/ first');
  }
}

/**
 * One of @angular/build's own modules under src/utils/, loaded by file path: the package does not
 * export them. When an upgrade moves one, this throws rather than let a check list nothing.
 * `packageDir` is the package's folder, by default the one installed for the workspace in `root`.
 */
export function builderModule(file, { packageDir = undefined, root = process.cwd() } = {}) {
  const dir = packageDir ?? builderDir(root);
  const full = path.join(dir, 'src', 'utils', file);
  if (!fs.existsSync(full)) {
    let version = 'this version';
    try {
      version = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')).version;
    } catch {
      // The version only makes the message clearer.
    }
    throw new Error(
      `@angular/build ${version} has no src/utils/${file}: the builder moved its asset code, so this check cannot see what a build copies. Find where the builder resolves \`assets\` now and point country-kit/lib/asset-outputs.mjs at it.`,
    );
  }
  return createRequire(path.join(dir, 'package.json'))(full);
}

/**
 * The files a build copies for an `assets` array, as the builder resolves them: `source`
 * relative to the workspace root and `destination` relative to the output folder, both with `/`.
 * `packageDir` as for builderModule.
 */
export async function resolveOutputs(
  assets,
  { root, projectRoot, sourceRoot, packageDir = undefined },
) {
  const { normalizeAssetPatterns } = builderModule('normalize-asset-patterns.js', {
    packageDir,
    root,
  });
  const { resolveAssets } = builderModule('resolve-assets.js', { packageDir, root });
  if (typeof normalizeAssetPatterns !== 'function' || typeof resolveAssets !== 'function')
    throw new Error(
      '@angular/build no longer exports normalizeAssetPatterns and resolveAssets where country-kit/lib/asset-outputs.mjs expects them',
    );
  // The builder writes the normalised output back into the pattern objects; give it copies.
  const entries = normalizeAssetPatterns(
    structuredClone(assets),
    root,
    path.join(root, projectRoot),
    path.join(root, sourceRoot),
  );
  const files = await resolveAssets(entries, root);
  return files.map(({ source, destination }) => ({
    source: toPosix(path.relative(root, source)),
    destination: toPosix(destination),
  }));
}

/**
 * The output paths of `outputs` (resolveOutputs) that more than one input writes, in the order
 * they are first written: `[{ destination, sources }]`.
 */
export function duplicateOutputs(outputs) {
  const sources = new Map();
  for (const { source, destination } of outputs)
    sources.set(destination, [...(sources.get(destination) ?? []), source]);
  return [...sources]
    .filter(([, from]) => from.length > 1)
    .map(([destination, from]) => ({ destination, sources: from }));
}
