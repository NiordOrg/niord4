/**
 * Runs a GitHub Actions step's own bash in a test: the `run: |` block of a named step, taken from
 * the workflow's text line by line, under bash with fake commands first on PATH.
 *
 *   import { runBash, stepEnv, stepRun } from './workflow-step.mjs';
 *   const script = stepRun(fs.readFileSync('../.github/workflows/publish.yml', 'utf8'),
 *                          { job: 'gate', step: 'Decide' });
 *   const result = runBash(script, { env: { EVENT: 'push' }, fakes: { gh: [...] } });
 *
 * No YAML library: the workflows' layout is plain enough to read by indentation. A test helper
 * only; the source zip carries scripts/ files by name, and not this one.
 *
 * bash is NIORD_BASH when set, otherwise on Windows Git's own (PowerShell would resolve `bash` to
 * WSL's launcher in System32), otherwise `bash` on PATH. Every path handed to bash is written with
 * forward slashes, which Git Bash and Linux both read.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const GIT_BASH = 'C:/Program Files/Git/bin/bash.exe';

/** The bash the steps run under. */
export function bashPath() {
  if (process.env.NIORD_BASH) return process.env.NIORD_BASH;
  if (process.platform === 'win32' && fs.existsSync(GIT_BASH)) return GIT_BASH;
  return 'bash';
}

const indentOf = (line) => line.length - line.trimStart().length;
const isContent = (line) => line.trim() !== '' && !line.trimStart().startsWith('#');

/** The lines from `start` up to the next content line indented `indent` or less. */
function blockEnd(lines, start, indent) {
  let end = start + 1;
  while (end < lines.length && !(isContent(lines[end]) && indentOf(lines[end]) <= indent)) end++;
  return end;
}

/** A mapping key's value on its own line, `key: value`, quotes left as written. */
function keyValue(line) {
  const match = /^\s*(?:- )?([A-Za-z0-9_-]+):\s?(.*)$/.exec(line);
  return match ? { key: match[1], value: match[2] } : undefined;
}

/**
 * The lines of one step: `{ lines, keyIndent }`, the step's own keys at `keyIndent`. `job` names
 * the job (a key under `jobs:`), or none for a document whose steps stand alone; `step` is the
 * step's name or the start of it (a name may end in an expression), or its id. Exactly one step
 * must match.
 */
function stepLines(text, { job, step }) {
  const lines = text.replaceAll('\r', '').split('\n');
  let from = 0;
  let to = lines.length;
  if (job !== undefined) {
    const jobs = lines.findIndex((line) => /^jobs:\s*$/.test(line));
    if (jobs < 0) throw new Error('the workflow has no jobs:');
    const start = lines.findIndex(
      (line, i) => i > jobs && indentOf(line) === 2 && line.trim() === `${job}:`,
    );
    if (start < 0) throw new Error(`the workflow has no job ${job}`);
    from = start + 1;
    to = blockEnd(lines, start, 2);
  }
  const found = [];
  for (let i = from; i < to; i++) {
    const item = /^(\s*)- /.exec(lines[i]);
    if (!item) continue;
    const indent = item[1].length;
    const end = Math.min(blockEnd(lines, i, indent), to);
    const keyIndent = indent + 2;
    const keys = {};
    for (let j = i; j < end; j++) {
      if (!isContent(lines[j])) continue;
      if (j === i || indentOf(lines[j]) === keyIndent) {
        const entry = keyValue(lines[j]);
        if (entry) keys[entry.key] = entry.value.trim();
      }
    }
    if (keys.name === undefined && keys.id === undefined) continue;
    const name = keys.name ?? '';
    if (name === step || (name && name.startsWith(step)) || keys.id === step)
      found.push({ lines: lines.slice(i, end), keyIndent });
    i = end - 1;
  }
  if (found.length === 0) throw new Error(`no step ${step}${job ? ` in job ${job}` : ''}`);
  if (found.length > 1) throw new Error(`${found.length} steps match ${step}`);
  return found[0];
}

/** The lines of a step's key at its own indent, and what follows the key on its line. */
function keyBlock({ lines, keyIndent }, key) {
  const start = lines.findIndex(
    (line, i) => keyValue(line)?.key === key && (i === 0 || indentOf(line) === keyIndent),
  );
  if (start < 0) return undefined;
  const end = blockEnd(lines, start, keyIndent);
  return { inline: keyValue(lines[start]).value.trim(), body: lines.slice(start + 1, end) };
}

/** The text of a step's `run:`: the block of a `run: |`, dedented, or a one-line run. */
export function stepRun(text, where) {
  const block = keyBlock(stepLines(text, where), 'run');
  if (!block) throw new Error(`the step ${where.step} has no run:`);
  if (block.inline !== '|' && block.inline !== '|-') return `${block.inline}\n`;
  const body = block.body;
  while (body.length && body.at(-1).trim() === '') body.pop();
  if (body.length === 0) throw new Error(`the step ${where.step} has an empty run: block`);
  const indent = Math.min(...body.filter((line) => line.trim()).map(indentOf));
  return `${body.map((line) => line.slice(indent)).join('\n')}\n`;
}

/** A step's `env:` as written: key → the value's text, quotes and expressions left as they are. */
export function stepEnv(text, where) {
  const block = keyBlock(stepLines(text, where), 'env');
  const env = {};
  for (const line of block?.body ?? []) {
    if (!isContent(line)) continue;
    const entry = keyValue(line);
    if (entry) env[entry.key] = entry.value.trim();
  }
  return env;
}

/** A bash word that is the text given, whatever it holds. */
const quote = (value) => `'${String(value).replaceAll("'", `'\\''`)}'`;

/** A `case` pattern: `*` matches anything, every other character is itself. */
const pattern = (glob) =>
  glob
    .split('*')
    .map((part) => (part ? quote(part) : ''))
    .join('*') || '*';

/**
 * A fake command: a bash script that appends `<name> <args>` to the call log and answers from a
 * table, the first entry whose `match` (a glob over the arguments joined by spaces) fits: `out`
 * on stdout, `err` on stderr, `script` (bash, with the arguments in "$@") run, then `exit` (0 by
 * default). An unknown call fails with exit 97. Standard input is read only by a call with
 * `--password-stdin`, as `docker login` reads it; any other leaves it alone, so a fake called in a
 * loop that reads its own input does not swallow it.
 */
function writeFake(bin, name, answers) {
  const branches = answers.map((answer, i) => {
    const lines = [`  ${pattern(answer.match)})`];
    if (answer.out !== undefined) {
      fs.writeFileSync(path.join(bin, `${name}.${i}.out`), answer.out);
      lines.push(`    cat "$FAKE_BIN/${name}.${i}.out"`);
    }
    if (answer.err !== undefined) {
      fs.writeFileSync(path.join(bin, `${name}.${i}.err`), answer.err);
      lines.push(`    cat "$FAKE_BIN/${name}.${i}.err" >&2`);
    }
    if (answer.script) lines.push(answer.script);
    lines.push(`    exit ${answer.exit ?? 0} ;;`);
    return lines.join('\n');
  });
  const text = [
    '#!/usr/bin/env bash',
    `printf '%s\\n' "${name} $*" >> "$FAKE_LOG"`,
    'case " $* " in *\' --password-stdin \'*) cat > /dev/null ;; esac',
    'case "$*" in',
    ...branches,
    `  *) echo "fake ${name}: no answer for: $*" >&2; exit 97 ;;`,
    'esac',
    '',
  ].join('\n');
  fs.writeFileSync(path.join(bin, name), text, { mode: 0o755 });
  fs.chmodSync(path.join(bin, name), 0o755);
}

/** key=value lines of a GITHUB_OUTPUT-style file; a later line wins. */
function keyValues(file) {
  const values = {};
  if (!fs.existsSync(file)) return values;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const at = line.indexOf('=');
    if (at > 0) values[line.slice(0, at)] = line.slice(at + 1).replace(/\r$/, '');
  }
  return values;
}

const slashes = (value) => value.replaceAll('\\', '/');

/**
 * Runs `script` under bash in `cwd` (a fresh temporary folder by default) with `env` over this
 * process's environment, minus anything of a real runner's (GITHUB_*, RUNNER_*, GH_*), and the
 * fakes first on PATH. GITHUB_OUTPUT, GITHUB_STEP_SUMMARY, GITHUB_ENV and RUNNER_TEMP are files
 * and a folder of the run's own. Returns `{ status, stdout, stderr, outputs, summary, calls }`,
 * `calls` each fake's call as `<name> <args>`.
 *
 * The run's folder (the fakes, the step's script, the call log, the output files and RUNNER_TEMP)
 * is removed before the function returns, also when bash cannot be started. It is kept when `keep`
 * is true, for a test that reads a file the step left there, or when NIORD_KEEP_STEP_DIR is set,
 * to look at a failing run; the result then also has `dir`, the folder with forward slashes, and
 * the caller removes it. `timeout` is bash's limit in milliseconds (60 s by default); a step that
 * makes many fake calls on a loaded Windows workstation passes a longer one.
 */
export function runBash(
  script,
  { env = {}, cwd, fakes = {}, keep = false, timeout = 60_000 } = {},
) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-step-'));
  const kept = keep || Boolean(process.env.NIORD_KEEP_STEP_DIR);
  try {
    const result = runIn(dir, script, { env, cwd, fakes, timeout });
    return kept ? { ...result, dir: slashes(dir) } : result;
  } finally {
    if (!kept) fs.rmSync(dir, { recursive: true, force: true });
  }
}

/** runBash's run in its folder `dir`. */
function runIn(dir, script, { env, cwd, fakes, timeout }) {
  const bin = path.join(dir, 'bin');
  const temp = path.join(dir, 'runner-temp');
  fs.mkdirSync(bin);
  fs.mkdirSync(temp);
  for (const [name, answers] of Object.entries(fakes)) writeFake(bin, name, answers);
  const log = path.join(dir, 'calls.log');
  fs.writeFileSync(log, '');
  const files = {
    GITHUB_OUTPUT: path.join(dir, 'output'),
    GITHUB_STEP_SUMMARY: path.join(dir, 'summary'),
    GITHUB_ENV: path.join(dir, 'env'),
  };
  for (const file of Object.values(files)) fs.writeFileSync(file, '');
  // On Windows a PATH entry must be bash's own form (/c/...), since `:` separates its entries.
  const prelude = [
    'if command -v cygpath > /dev/null 2>&1; then PATH="$(cygpath -u "$FAKE_BIN"):$PATH"; else PATH="$FAKE_BIN:$PATH"; fi',
    'export PATH',
  ].join('\n');
  const file = path.join(dir, 'step.sh');
  fs.writeFileSync(file, `${prelude}\n${script.replaceAll('\r', '')}`);
  const inherited = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !/^(GITHUB_|RUNNER_|GH_|ACTIONS_)/.test(key)),
  );
  const result = spawnSync(bashPath(), [slashes(file)], {
    cwd: cwd ?? dir,
    encoding: 'utf8',
    timeout,
    env: {
      ...inherited,
      ...Object.fromEntries(Object.entries(files).map(([key, value]) => [key, slashes(value)])),
      RUNNER_TEMP: slashes(temp),
      FAKE_BIN: slashes(bin),
      FAKE_LOG: slashes(log),
      ...env,
    },
  });
  if (result.error) throw result.error;
  return {
    status: result.status,
    stdout: result.stdout,
    stderr: result.stderr,
    outputs: keyValues(files.GITHUB_OUTPUT),
    summary: fs.readFileSync(files.GITHUB_STEP_SUMMARY, 'utf8'),
    calls: fs
      .readFileSync(log, 'utf8')
      .split('\n')
      .map((line) => line.replace(/\r$/, ''))
      .filter(Boolean),
  };
}
