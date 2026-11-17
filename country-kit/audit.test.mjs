/**
 * Tests for country-kit/audit.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * npm's reports are fixtures in test-fixtures/audit/ — a clean pair as npm printed them for the
 * base product's tree, one with findings of every kind, npm's error report — and npm itself is
 * injected, so no test touches the registry. One negative case per rule.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  acceptedLines,
  checkAudit,
  main,
  parseAudit,
  parseSignatures,
  readAuditAccepted,
} from './audit.mjs';

const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), 'test-fixtures', 'audit');
const text = (name) => fs.readFileSync(path.join(FIXTURES, name), 'utf8');

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** A country fe/ with the audit-accepted.json given (text, or a value written as JSON). */
function workspace(accepted) {
  const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-audit-'));
  temporary.push(fe);
  if (accepted !== undefined)
    fs.writeFileSync(
      path.join(fe, 'audit-accepted.json'),
      typeof accepted === 'string' ? accepted : JSON.stringify(accepted),
    );
  return fe;
}

/**
 * npm as a test plays it: the reports named, an exit status, and the calls made, each with the
 * options it was given. `signatureRuns`, when given, scripts each `audit signatures` call in turn:
 * `{ status, stdout, stderr }`, its stdout a fixture's name or the text itself.
 */
function npm({
  audit = 'audit-clean.json',
  signatures = 'signatures-clean.json',
  status = 0,
  stderr = '',
  signatureRuns,
} = {}) {
  const calls = [];
  const options = [];
  const report = (name) => (name.endsWith('.json') ? text(name) : name);
  const run = (args, given) => {
    calls.push({ args, cwd: given.cwd });
    options.push(given);
    if (args.includes('signatures') && signatureRuns) {
      const scripted = signatureRuns.shift();
      return { stderr: '', ...scripted, stdout: report(scripted.stdout) };
    }
    return { status, stdout: report(args.includes('signatures') ? signatures : audit), stderr };
  };
  return { run, calls, options };
}

/** main's exit code and printed lines; `remove`, when given, replaces its folder removal. */
function audit(fe, npmOptions, { remove } = {}) {
  const out = [];
  const err = [];
  const { run, calls, options } = npm(npmOptions);
  const code = main({
    cwd: fe,
    run,
    ...(remove ? { remove } : {}),
    out: (line) => out.push(line),
    err: (line) => err.push(line),
  });
  return { code, out, err, calls, options };
}

function assertNamed(lines, ...fragments) {
  for (const fragment of fragments)
    assert.ok(
      lines.some((line) => line.includes(fragment)),
      `expected a line naming "${fragment}", got:\n${lines.join('\n') || '(none)'}`,
    );
}

const ACCEPTED_MINIMIST = {
  advisory: 'GHSA-xvch-5gv4-984h',
  package: 'minimist',
  reason: 'Reached only through the build.',
  added: '2026-10-20',
};

describe('npm audit: the reports', () => {
  it('reads a clean report as no finding', () => {
    assert.deepEqual(parseAudit(text('audit-clean.json')), []);
    assert.deepEqual(parseSignatures(text('signatures-clean.json')), []);
  });

  it('severity: takes each high or critical advisory by its id and package, and nothing milder', () => {
    assert.deepEqual(parseAudit(text('audit-findings.json')), [
      {
        kind: 'advisory',
        advisory: 'GHSA-35jh-r3h4-6jhm',
        package: 'lodash',
        severity: 'high',
        title: 'Command Injection in lodash',
      },
      {
        kind: 'advisory',
        advisory: 'GHSA-xvch-5gv4-984h',
        package: 'minimist',
        severity: 'critical',
        title: 'Prototype Pollution in minimist',
      },
    ]);
  });

  it('signatures: takes each invalid or missing signature as <name>@<version>', () => {
    assert.deepEqual(parseSignatures(text('signatures-problems.json')), [
      { kind: 'signature', signature: 'left-pad@1.3.0', problem: 'invalid' },
      { kind: 'signature', signature: '@zz/berths@1.0.0', problem: 'missing' },
    ]);
  });

  it("no report: npm's error, a text that is no JSON or a report of another shape throws", () => {
    assert.throws(
      () => parseAudit(text('audit-error.json')),
      /`npm audit` failed \(ENOTFOUND\): request to/,
    );
    assert.throws(() => parseAudit('npm ERR! network'), /`npm audit` printed no JSON report/);
    assert.throws(() => parseAudit('{}'), /a report without vulnerabilities/);
    assert.throws(() => parseSignatures(''), /`npm audit signatures` printed no JSON report/);
    assert.throws(() => parseSignatures('{"invalid":[]}'), /without its invalid and missing lists/);
  });
});

describe('npm audit: audit-accepted.json', () => {
  it('reads an absent file as an empty list, and a file in its shape', () => {
    assert.deepEqual(readAuditAccepted(workspace()), { entries: [], errors: [] });
    const { entries, errors } = readAuditAccepted(path.join(FIXTURES, 'accepted'));
    assert.deepEqual(errors, []);
    assert.equal(entries.length, 3);
  });

  it('shape: unknown fields, an empty reason, a bad date, a bad id and a repeat fail', () => {
    const { entries, errors } = readAuditAccepted(
      workspace([
        { ...ACCEPTED_MINIMIST, until: '2027-01-01' },
        { ...ACCEPTED_MINIMIST, reason: ' ' },
        { ...ACCEPTED_MINIMIST, added: '2026-02-30' },
        { ...ACCEPTED_MINIMIST, advisory: 'CVE-2021-44906' },
        { signature: 'left-pad', reason: 'x', added: '2026-10-20' },
        { package: 'minimist', reason: 'x', added: '2026-10-20' },
        'GHSA-xvch-5gv4-984h',
        ACCEPTED_MINIMIST,
        ACCEPTED_MINIMIST,
      ]),
    );
    assert.deepEqual(entries, [ACCEPTED_MINIMIST]);
    assert.deepEqual(errors, [
      "audit-accepted.json's entry 1 has added, advisory, package, reason, until; it has exactly added, advisory, package, reason",
      "audit-accepted.json's entry 2 gives no reason",
      "audit-accepted.json's entry 3 has no added date, YYYY-MM-DD",
      'audit-accepted.json\'s entry 4 names the advisory "CVE-2021-44906", not GHSA-xxxx-xxxx-xxxx',
      'audit-accepted.json\'s entry 5 names the signature "left-pad", not <name>@<version>',
      "audit-accepted.json's entry 6 names neither an advisory nor a signature",
      "audit-accepted.json's entry 7 is not an object",
      "audit-accepted.json's entry 9 repeats GHSA-xvch-5gv4-984h minimist",
    ]);
    assert.deepEqual(readAuditAccepted(workspace('{ "advisory": ')).errors.length, 1);
    assert.deepEqual(readAuditAccepted(workspace({})).errors, [
      'audit-accepted.json is not a list',
    ]);
  });
});

describe('npm audit: the check', () => {
  const findings = [
    ...parseAudit(text('audit-findings.json')),
    ...parseSignatures(text('signatures-problems.json')),
  ];

  it('unaccepted: every finding not accepted fails, naming it', () => {
    const { errors } = checkAudit({ findings, accepted: [] });
    assert.deepEqual(errors, [
      'npm audit: GHSA-35jh-r3h4-6jhm (high) in lodash: Command Injection in lodash; take the version that fixes it, or list it in audit-accepted.json with a reason',
      'npm audit: GHSA-xvch-5gv4-984h (critical) in minimist: Prototype Pollution in minimist; take the version that fixes it, or list it in audit-accepted.json with a reason',
      'npm audit signatures: left-pad@1.3.0 has an invalid registry signature; list it in audit-accepted.json with a reason only once it is understood',
      'npm audit signatures: @zz/berths@1.0.0 has no registry signature; list it in audit-accepted.json with a reason only once it is understood',
    ]);
  });

  it('accepted: an accepted finding passes, the list is printed in full, a stale entry warns', () => {
    const { entries } = readAuditAccepted(path.join(FIXTURES, 'accepted'));
    const { errors, warnings, lines } = checkAudit({ findings, accepted: entries });
    assert.equal(errors.length, 2, errors.join('\n'));
    assertNamed(errors, 'GHSA-35jh-r3h4-6jhm (high) in lodash', 'left-pad@1.3.0');
    assert.deepEqual(warnings, [
      'audit-accepted.json accepts GHSA-aaaa-bbbb-cccc in ws, which the audit no longer finds; remove the entry',
    ]);
    assert.deepEqual(lines, [
      'npm audit: audit-accepted.json accepts 3 finding(s):',
      "  GHSA-xvch-5gv4-984h in minimist — Reached only through the build's mkdirp; the base product's next version drops it. (added 2026-10-20)",
      '  the signature of @zz/berths@1.0.0 — Published before its registry signed packages; checked by hand. (added 2026-10-21)',
      '  GHSA-aaaa-bbbb-cccc in ws — Fixed in the base product since; kept to show the warning. (added 2026-09-01)',
      'npm audit: 2 high or critical advisory finding(s), 2 signature problem(s), 2 of them accepted',
    ]);
    assert.deepEqual(acceptedLines([]), []);
  });
});

describe('npm audit: the command', () => {
  it('runs both audits with constant arguments in the workspace and passes a clean tree', () => {
    const fe = workspace();
    const { code, out, err, calls } = audit(fe);
    assert.equal(code, 0, err.join('\n'));
    assert.deepEqual(calls, [
      { args: ['audit', '--omit=dev', '--audit-level=high', '--json'], cwd: fe },
      { args: ['audit', 'signatures', '--json'], cwd: fe },
    ]);
    assert.deepEqual(out, [
      'npm audit: 0 high or critical advisory finding(s), 0 signature problem(s), 0 of them accepted',
      'npm audit: passed',
    ]);
  });

  it("reads npm's report whatever its exit code, and fails on what is not accepted", () => {
    const { code, err } = audit(workspace([ACCEPTED_MINIMIST]), {
      audit: 'audit-findings.json',
      status: 1,
    });
    assert.equal(code, 1);
    assertNamed(
      err,
      'error: npm audit: GHSA-35jh-r3h4-6jhm (high) in lodash',
      'npm audit: 1 error(s)',
    );
    const accepted = audit(
      workspace([
        ACCEPTED_MINIMIST,
        { ...ACCEPTED_MINIMIST, advisory: 'GHSA-35jh-r3h4-6jhm', package: 'lodash' },
      ]),
      { audit: 'audit-findings.json', status: 1 },
    );
    assert.equal(accepted.code, 0, accepted.err.join('\n'));
    assertNamed(
      accepted.out,
      'npm audit: audit-accepted.json accepts 2 finding(s):',
      'npm audit: passed',
    );
  });

  it('no registry: a report that is no JSON fails the audit, naming what npm said', () => {
    const { code, err } = audit(workspace(), {
      audit: 'audit-error.json',
      signatures: '',
      status: 1,
      stderr: 'npm error code ENOTFOUND\n',
    });
    assert.equal(code, 1);
    assertNamed(
      err,
      'error: `npm audit` failed (ENOTFOUND): request to https://registry.npmjs.org/-/npm/v1/security/advisories/bulk failed',
      'error: `npm audit signatures` printed no JSON report (exit 1): npm error code ENOTFOUND; the audit needs the registry',
    );
  });

  describe('a Windows process crash of `npm audit signatures`', () => {
    const CRASH = 3221226505;
    const crashed = { status: CRASH, stdout: '' };
    const clean = { status: 0, stdout: 'signatures-clean.json' };
    const SIGNATURES = ['audit', 'signatures', '--json'];

    it('runs it once more with a fresh npm cache of its own, removed afterwards, and passes', () => {
      const fe = workspace();
      const { code, out, err, calls, options } = audit(fe, { signatureRuns: [crashed, clean] });
      assert.equal(code, 0, err.join('\n'));
      assert.deepEqual(
        calls.map((call) => call.args),
        [['audit', '--omit=dev', '--audit-level=high', '--json'], SIGNATURES, SIGNATURES],
      );
      assert.deepEqual(options[0], { cwd: fe });
      assert.deepEqual(options[1], { cwd: fe });
      assert.equal(options[2].cwd, fe);
      assert.equal(typeof options[2].cache, 'string');
      assert.match(path.basename(options[2].cache), /^niord-audit-npm-cache-/);
      assert.ok(!fs.existsSync(options[2].cache), 'the npm cache folder is removed');
      assert.equal(out.filter((line) => line.includes(`exit ${CRASH}`)).length, 1, out.join('\n'));
      assertNamed(out, 'a fresh npm cache', 'npm audit: passed');
    });

    it('passes a retry whose cache folder cannot be removed, with a warning naming it', () => {
      const removals = [];
      const { code, out, err, options } = audit(
        workspace(),
        { signatureRuns: [crashed, clean] },
        {
          remove: (folder) => {
            removals.push(folder);
            fs.rmSync(folder, { recursive: true, force: true });
            throw Object.assign(new Error('EBUSY: resource busy or locked'), { code: 'EBUSY' });
          },
        },
      );
      assert.equal(code, 0, err.join('\n'));
      assert.deepEqual(err, []);
      assert.deepEqual(removals, [options[2].cache]);
      assert.ok(
        out.includes(`warning: the npm cache ${options[2].cache} could not be removed (EBUSY)`),
        out.join('\n'),
      );
      assertNamed(out, 'npm audit: passed');
    });

    it('fails when it crashes again, naming the exit code and the retry', () => {
      const { code, err, calls, options } = audit(workspace(), {
        signatureRuns: [crashed, { ...crashed }],
      });
      assert.equal(code, 1);
      assert.equal(calls.length, 3);
      assert.ok(!fs.existsSync(options[2].cache));
      assertNamed(
        err,
        `error: \`npm audit signatures\` printed no JSON report (exit ${CRASH}), after one retry with a fresh npm cache; the audit needs the registry`,
      );
    });

    it('retries neither another exit without a report nor a crash that printed one', () => {
      const failed = audit(workspace(), { signatureRuns: [{ status: 1, stdout: '' }, clean] });
      assert.equal(failed.code, 1);
      assert.equal(failed.calls.length, 2);
      const reported = audit(workspace(), {
        signatureRuns: [{ status: CRASH, stdout: 'signatures-problems.json' }, clean],
      });
      assert.equal(reported.calls.length, 2);
      assertNamed(reported.err, 'npm audit signatures: left-pad@1.3.0 has an invalid');
    });
  });

  it('malformed: a malformed audit-accepted.json fails a clean audit', () => {
    const { code, err } = audit(workspace([{ advisory: 'GHSA-xvch-5gv4-984h' }]));
    assert.equal(code, 1);
    assertNamed(err, "error: audit-accepted.json's entry 1 has advisory");
  });
});
