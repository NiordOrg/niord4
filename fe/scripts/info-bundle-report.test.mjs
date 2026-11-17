/**
 * Negative tests for the tolerance of scripts/info-bundle-report.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * The report runs as it does in CI, against a crafted build in a temporary folder: an index.html,
 * an initial script and one landing chunk. The files are random bytes, which brotli cannot shrink,
 * so each one's transfer is exactly its size and a test can grow a group by a chosen number of
 * bytes. A baseline is recorded with --update-baseline and then lowered, which is the same as the
 * build having grown by that much.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'info-bundle-report.mjs');
const LANDING_ENTRY = 'ui-apps/info/src/app/features/messages/messages.routes.ts';

let dir;
let recorded;

function run(...args) {
  const result = spawnSync(
    process.execPath,
    [
      SCRIPT,
      ...['--dist', path.join(dir, 'browser')],
      ...['--stats', path.join(dir, 'stats.json')],
      ...['--baseline', path.join(dir, 'baseline.json')],
      ...['--json', path.join(dir, 'report.json')],
      ...['--md', path.join(dir, 'report.md')],
      ...args,
    ],
    { encoding: 'utf8', env: { ...process.env, GITHUB_STEP_SUMMARY: '' } },
  );
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

/** Runs the gate against the recorded baseline with `group` recorded `grownBy` bytes smaller. */
function gateAfterGrowth(group, grownBy) {
  const baseline = structuredClone(recorded);
  baseline.groups[group].transfer -= grownBy;
  baseline.totals.firstVisit.transfer -= grownBy;
  fs.writeFileSync(path.join(dir, 'baseline.json'), JSON.stringify(baseline));
  return run();
}

describe('info-bundle-report tolerance', () => {
  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'info-bundle-report-'));
    const browser = path.join(dir, 'browser');
    fs.mkdirSync(browser);
    // index.html is kept near the size of the real one, a few hundred bytes, where 2 % is a
    // handful of bytes. The random tail is text, so the file still parses as HTML.
    fs.writeFileSync(
      path.join(browser, 'index.html'),
      `<!doctype html><script type="module" src="main.js"></script><!-- ${randomBytes(300).toString('base64')} -->`,
    );
    fs.writeFileSync(path.join(browser, 'main.js'), randomBytes(2_000));
    // The landing chunk is large enough that 2 % of it is well above the byte floor.
    fs.writeFileSync(path.join(browser, 'landing.js'), randomBytes(20_000));
    fs.writeFileSync(
      path.join(dir, 'stats.json'),
      JSON.stringify({
        outputs: {
          'main.js': { imports: [] },
          'landing.js': { imports: [], entryPoint: LANDING_ENTRY },
        },
      }),
    );
    fs.writeFileSync(
      path.join(dir, 'baseline.json'),
      JSON.stringify({
        tolerancePercent: 2,
        toleranceBytes: 64,
        landingRoute: '/messages/details',
        landingEntryPoints: [LANDING_ENTRY],
      }),
    );
    const update = run('--update-baseline');
    assert.equal(update.status, 0, update.output);
    recorded = JSON.parse(fs.readFileSync(path.join(dir, 'baseline.json'), 'utf8'));
    assert.equal(recorded.groups.landingLazyJs.transfer, 20_000);
    assert.ok(
      recorded.groups.index.transfer * 0.02 < 64,
      'index.html must be small for the floor to matter',
    );
  });

  after(() => fs.rmSync(dir, { recursive: true, force: true }));

  it('passes the build it recorded', () => {
    const gate = gateAfterGrowth('index', 0);
    assert.equal(gate.status, 0, gate.output);
  });

  it('lets a small group grow by the byte floor although that is more than 2 %', () => {
    const gate = gateAfterGrowth('index', 64);
    assert.equal(gate.status, 0, gate.output);
  });

  it('fails a small group that grows past the byte floor', () => {
    const gate = gateAfterGrowth('index', 65);
    assert.equal(gate.status, 1, gate.output);
    assert.match(gate.output, /index: .* \(\+65 B,/);
  });

  it('lets a large group grow by 2 % although that is more than the byte floor', () => {
    const gate = gateAfterGrowth('landingLazyJs', 390);
    assert.equal(gate.status, 0, gate.output);
  });

  it('fails a large group that grows past 2 %', () => {
    const gate = gateAfterGrowth('landingLazyJs', 420);
    assert.equal(gate.status, 1, gate.output);
    assert.match(gate.output, /landingLazyJs: /);
  });

  it('refuses the removed --separation mode instead of printing a pass', () => {
    const gate = run('--separation');
    assert.equal(gate.status, 2, gate.output);
  });
});

describe('info-bundle-report in a country workspace (--root)', () => {
  let country;

  /** Runs the report from `country` with the relative paths a country's bundle:info passes. */
  function runInCountry(...args) {
    const result = spawnSync(
      process.execPath,
      [
        SCRIPT,
        ...['--dist', 'dist/niord-info/browser'],
        ...['--stats', 'dist/niord-info/browser-stats.json'],
        ...['--baseline', 'info/bundle-baseline.json'],
        ...['--json', 'dist/niord-info-bundle-report.json'],
        ...['--md', 'dist/niord-info-bundle-report.md'],
        ...args,
      ],
      { cwd: country, encoding: 'utf8', env: { ...process.env, GITHUB_STEP_SUMMARY: '' } },
    );
    return { status: result.status, output: `${result.stdout}${result.stderr}` };
  }

  before(() => {
    country = fs.mkdtempSync(path.join(os.tmpdir(), 'info-bundle-report-country-'));
    const browser = path.join(country, 'dist', 'niord-info', 'browser');
    fs.mkdirSync(browser, { recursive: true });
    fs.mkdirSync(path.join(country, 'info'));
    fs.writeFileSync(
      path.join(browser, 'index.html'),
      '<!doctype html><script type="module" src="main.js"></script>',
    );
    fs.writeFileSync(path.join(browser, 'main.js'), randomBytes(2_000));
    fs.writeFileSync(path.join(browser, 'landing.js'), randomBytes(5_000));
    // The stats of a country workspace name the base product's files under .niord/base/.
    fs.writeFileSync(
      path.join(country, 'dist', 'niord-info', 'browser-stats.json'),
      JSON.stringify({
        outputs: {
          'main.js': { imports: [] },
          'landing.js': { imports: [], entryPoint: `.niord/base/${LANDING_ENTRY}` },
        },
      }),
    );
    fs.writeFileSync(
      path.join(country, 'info', 'bundle-baseline.json'),
      JSON.stringify({
        tolerancePercent: 2,
        toleranceBytes: 64,
        landingRoute: '/messages/details',
        landingEntryPoints: [LANDING_ENTRY],
      }),
    );
  });

  after(() => fs.rmSync(country, { recursive: true, force: true }));

  it("matches the baseline's entry points under --root, with every path relative to the current folder", () => {
    const update = runInCountry('--root', '.niord/base/', '--update-baseline');
    assert.equal(update.status, 0, update.output);
    const recorded = JSON.parse(
      fs.readFileSync(path.join(country, 'info', 'bundle-baseline.json'), 'utf8'),
    );
    assert.equal(recorded.groups.landingLazyJs.transfer, 5_000);
    assert.deepEqual(recorded.landingEntryPoints, [LANDING_ENTRY]);
    assert.match(recorded._comment, /Written to info\/bundle-baseline\.json by --update-baseline/);
    assert.ok(fs.existsSync(path.join(country, 'dist', 'niord-info-bundle-report.md')));
    const gate = runInCountry('--root', '.niord/base/');
    assert.equal(gate.status, 0, gate.output);
  });

  it('fails without --root: the entry point matches no output', () => {
    const gate = runInCountry();
    assert.equal(gate.status, 2, gate.output);
    assert.match(gate.output, /matches no output/);
  });

  it('refuses a --root out of the workspace', () => {
    const gate = runInCountry('--root', '../base/');
    assert.equal(gate.status, 2, gate.output);
  });
});
