/**
 * The files git sees in a tree, and the names the source zip gives two of its parts: what the zip
 * builder (source-zip.mjs) and set-up's local `NIORD_BASE` copy of a checkout (lib/local-sources.mjs)
 * both list a tree by. They share it from here rather than from source-zip.mjs, so set-up imports
 * nothing of the publish tool, which the kit package leaves out (kit-include.json).
 *
 * Node built-ins only.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

/** The folder the zip names the kit's files under, as a tree before the kit moved held them. */
export const KIT_FOLDER = 'country-kit/';
export const CATALOG_ENTRY = 'catalog.json';

/**
 * The files of `fe` that git sees, tracked or not but never ignored, relative and with `/`,
 * sorted. A tracked file deleted from the working tree is left out.
 */
export function gitFiles(fe) {
  const result = spawnSync('git', ['ls-files', '-co', '--exclude-standard', '-z'], {
    cwd: fe,
    encoding: 'buffer',
    maxBuffer: 256 * 1024 * 1024,
  });
  if (result.error)
    throw new Error(
      `git is not available (${result.error.message}): the zip is built from git's file list`,
    );
  if (result.status !== 0)
    throw new Error(`git ls-files failed in ${fe}: ${result.stderr.toString('utf8').trim()}`);
  return [
    ...new Set(
      result.stdout
        .toString('utf8')
        .split('\0')
        .filter(Boolean)
        .filter((relative) => {
          try {
            fs.lstatSync(path.join(fe, relative));
            return true;
          } catch (error) {
            if (error.code === 'ENOENT') return false;
            throw error;
          }
        }),
    ),
  ].sort();
}
