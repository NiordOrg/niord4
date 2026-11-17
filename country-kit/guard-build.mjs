#!/usr/bin/env node
/**
 * The first step of a country workspace's `npm run build`: refuses a production build of a
 * workspace that set-up staged from a local checkout (NIORD_BASE) or a local pack
 * (NIORD_PACK_<LANG>), and of one with no build report at all. Such a workspace is for a
 * developer's machine; what is deployed is built from the pinned zip and pinned packs alone.
 *
 *   node ../.niord/kit/guard-build.mjs        # run from the workspace, fe/
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isLocal } from './lib/build-report.mjs';

export const REPORT = path.join('.niord', 'build', 'niord-build-report.json');

/** Undefined when the workspace in `fe` may be built for production, else why not. */
export function refusal(fe) {
  const file = path.join(fe, REPORT);
  let report;
  try {
    report = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    return error.code === 'ENOENT'
      ? `${REPORT.replaceAll('\\', '/')} does not exist: run \`node setup.mjs\` first`
      : `${REPORT.replaceAll('\\', '/')} cannot be read: ${error.message}`;
  }
  if (isLocal(report))
    return `this workspace was set up from a local source (${report.provenance ?? 'no provenance recorded'}); a production build is made from the pinned zip and packs only: unset NIORD_BASE and NIORD_PACK_* and run \`node setup.mjs\` again`;
  return undefined;
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const reason = refusal(process.cwd());
  if (reason) {
    console.error(`build: ${reason}`);
    process.exit(1);
  }
}
