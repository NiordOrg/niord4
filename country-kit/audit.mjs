#!/usr/bin/env node
/**
 * The npm audit of a country workspace, run from its fe/:
 *
 *   node ../.niord/kit/audit.mjs
 *
 * The generated `build` script runs it before the builds, and `npm run audit` runs it alone (the
 * country's weekly workflow does). It runs, with constant arguments,
 *
 *   npm audit --omit=dev --audit-level=high --json
 *   npm audit signatures --json
 *
 * and reads their reports whatever npm's exit code. A finding is an advisory of severity high or
 * critical, named by its GitHub advisory id and the package it is in, or a package whose registry
 * signature is invalid or missing, named `<name>@<version>`. Each fails the audit unless
 * `audit-accepted.json` in fe/ lists it with a reason:
 *
 *   [
 *     { "advisory": "GHSA-xxxx-xxxx-xxxx", "package": "<name>", "reason": "…", "added": "YYYY-MM-DD" },
 *     { "signature": "<name>@<version>", "reason": "…", "added": "YYYY-MM-DD" }
 *   ]
 *
 * An absent file is an empty list; a file outside that shape fails. The accepted list is printed
 * in full on every run, and an entry that no longer matches a finding is a warning. A report that
 * is no JSON — the registry did not answer — fails too: no build goes out without its audit. Two
 * outside faults are run again. An answer of the registry's that npm could not read (UNREADABLE)
 * is retried, ATTEMPTS runs in all with a growing pause between them (PAUSES), and fails when it
 * stays unreadable; a report decides as any other, so a retry never turns a finding into a pass.
 * And `npm audit signatures` crashing on Windows with no report is run once more with a fresh npm
 * cache of its own (CRASHED); a second crash fails.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ACCEPTED_FILE = 'audit-accepted.json';
const AUDIT = ['audit', '--omit=dev', '--audit-level=high', '--json'];
const SIGNATURES = ['audit', 'signatures', '--json'];
// 0xC0000409, a Windows process crash: `npm audit signatures` has ended so once, with no report,
// over the user's shared npm cache, and passed when run again. Only that run is retried, once,
// with a cache of its own; any other exit without a report is the registry's answer.
const CRASHED = 3221226505;
// An answer npm fetched and could not read: minipass-fetch's text for a body that is no JSON, in
// npm's error report as `npm audit signatures` prints it (code FETCH_ERROR, the text as summary)
// and as `npm audit` does (no code, the text as message). The registry has answered one
// attestation request with "OK", and a run minutes later passed. npm retries a connection that
// failed and a 5xx answer itself before it reports them, but not this, so only this is retried:
// never another FETCH_ERROR (a timeout npm has retried, a redirect, a size), a network code, an
// HTTP status (401, 403, 404, 5xx) or an answer that is no report.
const UNREADABLE = /^invalid json response body at /;
const ATTEMPTS = 3;
// Before the second attempt and before the third: 8 s at most in all, beside npm's own runs.
const PAUSES = [2000, 6000];
const SEVERE = ['high', 'critical'];
const GHSA = /GHSA(?:-[0-9a-z]{4}){3}/i;
const ADVISORY = /^GHSA(?:-[0-9a-z]{4}){3}$/;
const NAME = /^(?:@[a-z0-9-~][a-z0-9-._~]*\/)?[a-z0-9-~][a-z0-9-._~]*$/;
const SIGNATURE =
  /^((?:@[a-z0-9-~][a-z0-9-._~]*\/)?[a-z0-9-~][a-z0-9-._~]*)@(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const ADVISORY_FIELDS = ['added', 'advisory', 'package', 'reason'];
const SIGNATURE_FIELDS = ['added', 'reason', 'signature'];

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isText = (value) => typeof value === 'string' && value.trim() !== '';
const isDate = (value) =>
  typeof value === 'string' &&
  DATE.test(value) &&
  new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;

/** Whether npm printed a JSON report, its error report included. */
function isReport(text) {
  try {
    return isObject(JSON.parse(text));
  } catch {
    return false;
  }
}

function parseReport(text, command) {
  let report;
  try {
    report = typeof text === 'string' ? JSON.parse(text) : text;
  } catch {
    throw new Error(`\`npm ${command}\` printed no JSON report`);
  }
  if (!isObject(report)) throw new Error(`\`npm ${command}\` printed no JSON report`);
  if (report.error !== undefined) {
    const { code, summary, detail } = isObject(report.error) ? report.error : {};
    // `npm audit` puts what failed in the report's message and leaves the error's texts empty.
    const said = String(
      [summary, detail, report.message].find(isText) ?? JSON.stringify(report.error),
    )
      .replace(/\s+/g, ' ')
      .trim();
    const error = new Error(`\`npm ${command}\` failed${code ? ` (${code})` : ''}: ${said}`);
    // The one fault run again (main): what npm said of it.
    if ((code === undefined || code === 'FETCH_ERROR') && UNREADABLE.test(said))
      error.unreadable = said;
    throw error;
  }
  return report;
}

/**
 * The high and critical advisories of an `npm audit --json` report, one per advisory and package:
 * `{ kind: 'advisory', advisory, package, severity, title }`. Throws on a report that is no JSON,
 * or that carries npm's error.
 */
export function parseAudit(json) {
  const report = parseReport(json, 'audit');
  if (!isObject(report.vulnerabilities))
    throw new Error('`npm audit` printed a report without vulnerabilities');
  const findings = new Map();
  for (const [key, vulnerability] of Object.entries(report.vulnerabilities))
    for (const via of Array.isArray(vulnerability?.via) ? vulnerability.via : []) {
      // A text names another vulnerable package, whose own entry carries the advisory.
      if (!isObject(via) || !SEVERE.includes(via.severity)) continue;
      const advisory = GHSA.exec(String(via.url ?? ''))?.[0] ?? `npm advisory ${via.source}`;
      const pkg = typeof via.name === 'string' ? via.name : key;
      findings.set(`${advisory} ${pkg}`, {
        kind: 'advisory',
        advisory,
        package: pkg,
        severity: via.severity,
        title: typeof via.title === 'string' ? via.title : '',
      });
    }
  return [...findings.values()];
}

/**
 * The packages of an `npm audit signatures --json` report whose registry signature is invalid or
 * missing: `{ kind: 'signature', signature: '<name>@<version>', problem }`. Throws as parseAudit.
 */
export function parseSignatures(json) {
  const report = parseReport(json, 'audit signatures');
  if (!Array.isArray(report.invalid) || !Array.isArray(report.missing))
    throw new Error(
      '`npm audit signatures` printed a report without its invalid and missing lists',
    );
  const findings = new Map();
  for (const problem of ['invalid', 'missing'])
    for (const entry of report[problem]) {
      const signature = `${entry?.name}@${entry?.version}`;
      findings.set(signature, { kind: 'signature', signature, problem });
    }
  return [...findings.values()];
}

/**
 * `<fe>/audit-accepted.json`: `{ entries, errors }`, the entries in the file's shape and one error
 * per problem. An absent file is an empty list.
 */
export function readAuditAccepted(fe) {
  const file = path.join(fe, ACCEPTED_FILE);
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === 'ENOENT') return { entries: [], errors: [] };
    throw error;
  }
  if (stat.isSymbolicLink() || !stat.isFile())
    return { entries: [], errors: [`${ACCEPTED_FILE} is not a regular file`] };
  let list;
  try {
    list = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    return { entries: [], errors: [`${ACCEPTED_FILE}: ${error.message}`] };
  }
  if (!Array.isArray(list)) return { entries: [], errors: [`${ACCEPTED_FILE} is not a list`] };
  const entries = [];
  const errors = [];
  const seen = new Set();
  for (const [i, entry] of list.entries()) {
    const name = `${ACCEPTED_FILE}'s entry ${i + 1}`;
    if (!isObject(entry)) {
      errors.push(`${name} is not an object`);
      continue;
    }
    const isAdvisory = Object.hasOwn(entry, 'advisory');
    const fields = isAdvisory ? ADVISORY_FIELDS : SIGNATURE_FIELDS;
    const problems = [];
    if (!isAdvisory && !Object.hasOwn(entry, 'signature'))
      problems.push('names neither an advisory nor a signature');
    else if (Object.keys(entry).sort().join() !== fields.join())
      problems.push(
        `has ${Object.keys(entry).sort().join(', ')}; it has exactly ${fields.join(', ')}`,
      );
    if (isAdvisory) {
      if (typeof entry.advisory !== 'string' || !ADVISORY.test(entry.advisory))
        problems.push(
          `names the advisory ${JSON.stringify(entry.advisory)}, not GHSA-xxxx-xxxx-xxxx`,
        );
      if (typeof entry.package !== 'string' || !NAME.test(entry.package))
        problems.push('names no package');
    } else if (Object.hasOwn(entry, 'signature') && !SIGNATURE.test(String(entry.signature)))
      problems.push(`names the signature ${JSON.stringify(entry.signature)}, not <name>@<version>`);
    if (!isText(entry.reason)) problems.push('gives no reason');
    if (!isDate(entry.added)) problems.push('has no added date, YYYY-MM-DD');
    const id = isAdvisory ? `${entry.advisory} ${entry.package}` : `${entry.signature}`;
    if (!problems.length && seen.has(id)) problems.push(`repeats ${id}`);
    if (problems.length) errors.push(`${name} ${problems.join('; ')}`);
    else {
      seen.add(id);
      entries.push(entry);
    }
  }
  return { entries, errors };
}

const entryName = (entry) =>
  entry.advisory !== undefined
    ? `${entry.advisory} in ${entry.package}`
    : `the signature of ${entry.signature}`;

/** The accepted entries as printed on every run; nothing when there are none. */
export function acceptedLines(entries) {
  if (!entries.length) return [];
  return [
    `npm audit: ${ACCEPTED_FILE} accepts ${entries.length} finding(s):`,
    ...entries.map((entry) => `  ${entryName(entry)} — ${entry.reason} (added ${entry.added})`),
  ];
}

/**
 * Holds the findings (parseAudit, parseSignatures) to the accepted entries (readAuditAccepted).
 * Returns the errors, one per finding not accepted; the warnings, one per accepted entry that
 * matches no finding; and the printed lines: the accepted list and a summary.
 */
export function checkAudit({ findings, accepted }) {
  const errors = [];
  const warnings = [];
  const matches = (entry, finding) =>
    finding.kind === 'advisory'
      ? entry.advisory === finding.advisory && entry.package === finding.package
      : entry.signature === finding.signature;
  for (const finding of findings) {
    if (accepted.some((entry) => matches(entry, finding))) continue;
    errors.push(
      finding.kind === 'advisory'
        ? `npm audit: ${finding.advisory} (${finding.severity}) in ${finding.package}${finding.title ? `: ${finding.title}` : ''}; take the version that fixes it, or list it in ${ACCEPTED_FILE} with a reason`
        : `npm audit signatures: ${finding.signature} has ${finding.problem === 'invalid' ? 'an invalid' : 'no'} registry signature; list it in ${ACCEPTED_FILE} with a reason only once it is understood`,
    );
  }
  for (const entry of accepted)
    if (!findings.some((finding) => matches(entry, finding)))
      warnings.push(
        `${ACCEPTED_FILE} accepts ${entryName(entry)}, which the audit no longer finds; remove the entry`,
      );
  const advisories = findings.filter((finding) => finding.kind === 'advisory').length;
  const lines = [
    ...acceptedLines(accepted),
    `npm audit: ${advisories} high or critical advisory finding(s), ${findings.length - advisories} signature problem(s), ${findings.length - errors.length} of them accepted`,
  ];
  return { errors, warnings, lines };
}

function runNpm(args, { cwd, cache }) {
  // npm is a .cmd file on Windows, which Node starts only through a shell, given one command line
  // (arguments beside a shell are deprecated); the arguments are this file's own constants, and a
  // cache folder goes in the environment, so the line stays constant text.
  const windows = process.platform === 'win32';
  const result = spawnSync(windows ? `npm ${args.join(' ')}` : 'npm', windows ? [] : args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    shell: windows,
    ...(cache ? { env: { ...process.env, npm_config_cache: cache } } : {}),
  });
  if (result.error) throw result.error;
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

// Retried: on Windows, endpoint protection can still hold a file npm has just written.
const removeFolder = (folder) =>
  fs.rmSync(folder, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });

// Waits without a timer, as the audit runs npm synchronously.
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

/**
 * The audit of the workspace in `cwd`. `run(args, { cwd, cache })` runs npm, with `cache` as its
 * cache folder when given, and returns its exit status and output (tests pass their own);
 * `remove(folder)` deletes the crash retry's cache folder; `pause(ms)` waits before a run again
 * after an unreadable answer; `out` and `err` print. Returns the exit code: 0 when every finding
 * is accepted, 1 otherwise.
 */
export function main({
  cwd = process.cwd(),
  run = runNpm,
  remove = removeFolder,
  pause = sleep,
  out = console.log,
  err = console.error,
} = {}) {
  const errors = [];
  const findings = [];
  for (const [command, args, parse] of [
    ['audit', AUDIT, parseAudit],
    ['audit signatures', SIGNATURES, parseSignatures],
  ]) {
    // The crash retry is one run more inside an attempt, and at most once per command.
    let retried = false;
    for (let attempt = 1; ; attempt++) {
      let result;
      try {
        result = run(args, { cwd });
        if (
          args === SIGNATURES &&
          !retried &&
          result.status === CRASHED &&
          !isReport(result.stdout)
        ) {
          out(
            `npm audit signatures: exit ${CRASHED} with no report, a Windows process crash; running it once more with a fresh npm cache`,
          );
          retried = true;
          const cache = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-audit-npm-cache-'));
          try {
            result = run(args, { cwd, cache });
          } finally {
            // A folder left behind is a warning: the retry's answer decides the audit, not its clean-up.
            try {
              remove(cache);
            } catch (error) {
              out(
                `warning: the npm cache ${cache} could not be removed (${error.code ?? error.message})`,
              );
            }
          }
        }
        findings.push(...parse(result.stdout));
        break;
      } catch (error) {
        if (error.unreadable !== undefined && attempt < ATTEMPTS) {
          const wait = PAUSES[attempt - 1];
          out(
            `npm ${command}: attempt ${attempt} of ${ATTEMPTS} failed (exit ${result.status}), the registry's answer could not be read: ${error.unreadable}; trying again in ${wait / 1000} s`,
          );
          pause(wait);
          continue;
        }
        const said = String(result?.stderr ?? '')
          .trim()
          .split(/\r?\n/)
          .find(Boolean);
        const after = [
          ...(attempt > 1 ? [`${attempt} attempts`] : []),
          ...(retried ? ['one retry with a fresh npm cache'] : []),
        ];
        errors.push(
          `${error.message}${result ? ` (exit ${result.status})` : ''}${after.length ? `, after ${after.join(' and ')}` : ''}${said ? `: ${said}` : ''}; the audit needs the registry, and no build goes out without it`,
        );
        break;
      }
    }
  }
  const { entries, errors: fileErrors } = readAuditAccepted(cwd);
  errors.push(...fileErrors);
  const checked = checkAudit({ findings, accepted: entries });
  errors.push(...checked.errors);
  for (const line of checked.lines) out(line);
  for (const warning of checked.warnings) out(`warning: ${warning}`);
  for (const error of errors) err(`error: ${error}`);
  if (errors.length) {
    err(`npm audit: ${errors.length} error(s); the audit failed`);
    return 1;
  }
  out('npm audit: passed');
  return 0;
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  try {
    process.exit(main());
  } catch (error) {
    console.error(`npm audit: ${error.message}`);
    process.exit(2);
  }
}
