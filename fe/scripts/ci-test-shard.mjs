#!/usr/bin/env node
/**
 * Runs one shard of the administration app's unit-test suite.
 *
 *   node scripts/ci-test-shard.mjs <shard> <total>      # e.g. 2 4 = the second of four shards
 *   node scripts/ci-test-shard.mjs <shard> <total> --dry-run
 *
 * The suite runs with file parallelism off (vitest.config.ts), so one runner executes the spec
 * files one after another. CI can split them over several runners; it runs one shard (`1 1`),
 * because every shard first compiles the whole test bundle (see fe-ci.yml).
 *
 * The file list comes from `ng test niord-app --list-tests`, the same discovery the real run uses,
 * and every file is assigned by its index in the sorted list modulo the shard count. Each spec
 * therefore lands in exactly one shard by construction; a new spec needs no registration anywhere.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Only the administration suite is sharded; the public site's specs run in one job
// (`npm run test:info`). Both `ng test` calls below name this project, because a bare `ng test`
// in a workspace with two projects runs BOTH targets and would mix the two file lists into one
// set of shards.
const PROJECT = 'niord-app';

// The CLI is run by this Node directly, with no shell in between: the `--include` arguments
// below are absolute paths, and a shell splits a path with a space in it — a Windows profile
// folder named after its owner — into two arguments that match nothing.
const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const NG = path.join(FE, 'node_modules', '@angular', 'cli', 'bin', 'ng.js');
const ng = (...args) => [NG, ...args];

const [shardArg, totalArg, ...flags] = process.argv.slice(2);
const shard = Number(shardArg);
const total = Number(totalArg);
if (
  !Number.isInteger(shard) ||
  !Number.isInteger(total) ||
  total < 1 ||
  shard < 1 ||
  shard > total
) {
  console.error('usage: ci-test-shard.mjs <shard 1..total> <total> [--dry-run]');
  process.exit(2);
}

const listing = execFileSync(process.execPath, ng('test', PROJECT, '--list-tests'), {
  encoding: 'utf8',
  cwd: FE,
});
const files = listing
  .split(/\r?\n/)
  .map((line) => line.trim().replaceAll('\\', '/'))
  .filter((line) => /\.(spec|test)\.ts$/.test(line))
  .sort();
if (files.length === 0) {
  console.error('No test files discovered — refusing to report an empty shard as green.');
  process.exit(1);
}

const mine = files.filter((_, index) => index % total === shard - 1);
console.log(`Shard ${shard}/${total}: ${mine.length} of ${files.length} test files`);
if (flags.includes('--dry-run')) {
  mine.forEach((file) => console.log(`  ${file}`));
  process.exit(0);
}
if (mine.length === 0) {
  // More shards than files: nothing to run here, and the other shards cover every file.
  process.exit(0);
}

// The listing is workspace-relative, and the builder joins a relative `--include` onto the
// project's source root after stripping a `ui-apps/admin/src/` prefix. A spec outside that folder —
// the shared map kit's — would therefore be joined onto it, match nothing, and disappear from the
// shard while the run still reported success. An absolute pattern is passed through untouched.
const result = spawnSync(
  process.execPath,
  ng(
    'test',
    PROJECT,
    '--watch=false',
    ...mine.flatMap((file) => ['--include', path.resolve(FE, file)]),
  ),
  { stdio: 'inherit', cwd: FE },
);
process.exit(result.status ?? 1);
