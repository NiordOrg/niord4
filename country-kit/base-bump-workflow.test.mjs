import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { compileList, readIncludeList } from './lib/include.mjs';
import { lockText } from './lib/pins.mjs';
import { requireFromBase } from './test-fixtures/base-modules.mjs';
import { runBash, stepEnv } from '../fe/scripts/workflow-step.mjs';

const yaml = requireFromBase('js-yaml');

const KIT = path.dirname(fileURLToPath(import.meta.url));
const TEXT = fs.readFileSync(path.join(KIT, 'templates', 'base-bump.yml'), 'utf8');
const WORKFLOW = yaml.load(TEXT);
// github-actions[bot]'s noreply address, as GitHub writes it.
const BOT = '41898282+github-actions[bot]@users.noreply.github.com';
const MARKER = '<!-- niord-base-bump -->';
const OLD = '4.0.97';
const NEW = '4.0.110';
const HEAD = '1'.repeat(40);
const BASE = '2'.repeat(40);
const PUSHED = '3'.repeat(40);
const FE_SHA = 'a'.repeat(64);
const KIT_SHA = 'b'.repeat(64);
const DA_PIN = `ghcr.io/sifa-dk/niord-lang-da:2@sha256:${'d'.repeat(64)}`;
// The home's newest da pack, which the pin refresh moves DA_PIN to.
const DA_PIN3 = `ghcr.io/sifa-dk/niord-lang-da:3@sha256:${'f'.repeat(64)}`;
const KL_PIN = `ghcr.io/sifa-dk/niord-lang-kl:5@sha256:${'e'.repeat(64)}`;
const KL_PIN6 = `ghcr.io/sifa-dk/niord-lang-kl:6@sha256:${'c'.repeat(64)}`;
const ENV = { BASE_REPO: 'NiordOrg/niord4', STATUS_CONTEXT: 'niord/upgrade-actions' };
const REPO = 'o/r';
const RUN_URL = 'https://github.com/o/r/actions/runs/7';
const COMMENT_URL = 'https://github.com/o/r/pull/12#issuecomment-555';
// The real node, for the fake node to pass every call but set-up's to.
const REAL_NODE = process.execPath.replaceAll('\\', '/');

const slashes = (value) => value.replaceAll('\\', '/');
const sha256 = (data) => createHash('sha256').update(data).digest('hex');

/** Every step of every job: `{ job, name, step }`, a step without a name by its `uses:`. */
const steps = Object.entries(WORKFLOW.jobs).flatMap(([job, { steps: list }]) =>
  list.map((step) => ({ job, name: step.name ?? step.uses, step })),
);

/** A step of a job by its name. */
const stepOf = (job, name) => {
  const found = steps.filter((s) => s.job === job && s.name === name);
  assert.equal(found.length, 1, `${job}: ${name}`);
  return found[0].step;
};

/**
 * A step's run as YAML reads it, which is what the runner executes: workflow-step.mjs's stepRun
 * also takes the comment above the next step into a `run: |` block and indents it, which would
 * break the `node <<'JS'` heredocs' terminators.
 */
const runOf = (job, name) => stepOf(job, name).run;

/** The status functions; a condition without one is GitHub's implicit `success() && (…)`. */
const STATUS_FUNCTION = /\b(?:always|success|failure|cancelled)\(\)/;

/**
 * An `if:` evaluated against a run's context, the subset the template's conditions use:
 * single-quoted strings, contexts (`github`, `inputs`, `needs`, `steps` dotted names, looked up in
 * `context`, a missing one empty, an object truthy), `startsWith` and `contains` (case-insensitive,
 * as GitHub's), `always()`, `==`, `!=`, `&&`, `||`, `!` and parentheses. `failed` says a step
 * before (or a needed job) did not succeed, which a condition without a status function obeys.
 */
function evaluate(condition, context, { failed = false, label = condition } = {}) {
  const strings = [];
  let expression = String(condition).replace(/'((?:[^']|'')*)'/g, (_, text) => {
    strings.push(text.replaceAll("''", "'"));
    return ` __s[${strings.length - 1}] `;
  });
  expression = expression
    .replace(
      /\b(?:github|inputs|needs|steps)(?:\.[A-Za-z_][\w-]*)+/g,
      (name) => `__c(${JSON.stringify(name)})`,
    )
    .replace(/\bstartsWith\(/g, '__startsWith(')
    .replace(/\bcontains\(/g, '__contains(')
    .replace(/\balways\(\)/g, 'true');
  assert.match(expression, /^[\s\w[\]"().,=!&|-]*$/, `${label}: ${expression}`);
  const lookup = (name) => (Object.hasOwn(context, name) ? context[name] : '');
  const lower = (value) => (typeof value === 'string' ? value.toLowerCase() : String(value ?? ''));
  const startsWith = (a, b) => lower(a).startsWith(lower(b));
  const contains = (a, b) => lower(a).includes(lower(b));
  const value = Function(
    '__c',
    '__s',
    '__startsWith',
    '__contains',
    `return (${expression});`,
  )(lookup, strings, startsWith, contains);
  return STATUS_FUNCTION.test(condition) ? Boolean(value) : !failed && Boolean(value);
}

/** A job's `if:` against a run's context; a job that needs compute obeys its result. */
function runs(job, context) {
  const { if: condition, needs } = WORKFLOW.jobs[job];
  assert.ok(condition, `${job} has no if:`);
  const failed = needs !== undefined && context['needs.compute.result'] !== 'success';
  return evaluate(condition, context, { failed, label: job });
}

/**
 * Which steps of a job run, by name, in a context, the steps named in `failing` failing when they
 * run: a step after a failed one runs only when its condition holds a status function.
 */
function stepsRunning(job, context, failing = []) {
  let failed = false;
  const ran = [];
  for (const { name, step } of steps.filter((s) => s.job === job)) {
    const run =
      step.if === undefined ? !failed : evaluate(step.if, context, { failed, label: name });
    if (!run) continue;
    ran.push(name);
    if (failing.includes(name)) failed = true;
  }
  return ran;
}

/** A folder of its own for one case, holding `files`, removed after `fn`. */
function inDir(prefix, files, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `niord-bump-${prefix}-`));
  try {
    write(dir, files);
    return fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
}

/** Writes `files`, a relative path → its text, below `dir`. */
function write(dir, files) {
  for (const [relative, text] of Object.entries(files)) {
    const file = path.join(dir, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
  }
}

/** A file's text below `dir`, or undefined. */
const readIn = (dir, relative) => {
  const file = path.join(dir, relative);
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined;
};

/**
 * A step's run in a folder of its own holding `files`, with RUNNER_TEMP its temp/: `list` the text
 * the step left in $RUNNER_TEMP/work/<file>.
 */
const runListing = (prefix, files, script, file, { env = {}, fakes } = {}) =>
  inDir(prefix, files, (dir) => {
    const result = runBash(script, {
      cwd: dir,
      env: { ...env, RUNNER_TEMP: slashes(path.join(dir, 'temp')) },
      fakes,
    });
    return { ...result, list: readIn(dir, `temp/work/${file}`) };
  });

/** The comment's boxes, ticked or not, one per item. */
const boxesOf = (comment) => comment.split('\n').filter((line) => /^- \[[ x]\] /.test(line));

/** The hidden marker a template's box ends in: the template's name and its SHA-256. */
const TEMPLATE_MARK_RE = /^<!-- niord-base-bump-template: (\S+) ([0-9a-f]{64}) -->$/;

/** The kit's templates at NEW, as Build the comment finds them in .niord/kit/templates/. */
const KIT_FILES = Object.fromEntries(
  [
    'AGENTS.md',
    'image.yml',
    'image-backend-job.yml',
    'image-frontend-job.yml',
    'ci.yml',
    'pack.yml',
    'base-bump.yml',
    'start-local.sh',
    'new-thing.md',
  ].map((name) => [name, `# ${name} of 4.0.110\n`]),
);

/** The marker at the end of a template's box: the template's content in `kit` by its SHA-256. */
const mark = (name, kit = KIT_FILES) =>
  ` <!-- niord-base-bump-template: ${name} ${sha256(kit[name])} -->`;

/**
 * The marker at the end of the comparison of every template: the SHA-256 of the listing sha256sum
 * prints of every file of `kit`, sorted by its relative path.
 */
const folderMark = (kit = KIT_FILES) =>
  ` <!-- niord-base-bump-template: * ${sha256(
    Object.keys(kit)
      .sort()
      .map((name) => `${sha256(kit[name])}  ${name}\n`)
      .join(''),
  )} -->`;

/** recount's Count and set the status on pull request 12, gh giving `comment` as comment 555. */
const recountWith = (comment, { env = {}, ids = '555\n' } = {}) =>
  runBash(runOf('recount', 'Count and set the status'), {
    env: { ...ENV, PR: '12', COMPUTE: 'skipped', RUN_URL, REPO, GH_TOKEN: 'token', ...env },
    fakes: {
      gh: [
        { match: 'pr view 12 --repo o/r --json headRefOid --jq .headRefOid', out: `${HEAD}\n` },
        { match: 'api --paginate repos/o/r/issues/12/comments --jq *', out: ids },
        { match: 'api repos/o/r/issues/comments/555 --jq .body', out: comment },
        { match: 'api repos/o/r/issues/comments/555 --jq .html_url', out: `${COMMENT_URL}\n` },
        { match: 'api -X POST repos/o/r/statuses/* *' },
      ],
    },
  });

/** The status call a recount made. */
const statusCall = (result) => result.calls.find((c) => c.includes('/statuses/'));

describe("base-bump.yml: the template's shape", () => {
  it('is valid YAML with the three jobs, apply and recount after compute', () => {
    assert.deepEqual(Object.keys(WORKFLOW.jobs), ['compute', 'apply', 'recount']);
    assert.equal(WORKFLOW.jobs.compute.needs, undefined);
    assert.equal(WORKFLOW.jobs.apply.needs, 'compute');
    assert.equal(WORKFLOW.jobs.recount.needs, 'compute');
  });

  it('runs on a base bump pull request, on an edited comment and on a dispatch naming the pull request', () => {
    assert.deepEqual(WORKFLOW.on, {
      pull_request: { types: ['opened', 'synchronize', 'reopened'], paths: ['be/pom.xml'] },
      issue_comment: { types: ['edited'] },
      workflow_dispatch: {
        inputs: {
          pr: {
            description: 'The pull request to run on, its number',
            required: true,
            type: 'string',
          },
        },
      },
    });
  });

  it('reads only at the top, and runs the runs of one pull request one at a time, none dropped', () => {
    assert.deepEqual(WORKFLOW.permissions, { contents: 'read' });
    // GitHub's default queue keeps one pending run per group and cancels it when a third arrives:
    // a tick's recount would cancel a pending re-pin.
    assert.deepEqual(WORKFLOW.concurrency, {
      group:
        'base-bump-${{ github.event.pull_request.number || github.event.issue.number || inputs.pr }}',
      'cancel-in-progress': false,
      queue: 'max',
    });
  });

  it('names the base product’s repository and the status once, in the workflow’s env', () => {
    assert.deepEqual(WORKFLOW.env, ENV);
    for (const job of Object.values(WORKFLOW.jobs)) assert.equal(job.env, undefined);
    assert.equal(TEXT.split('NiordOrg/niord4').length - 1, 1);
    assert.equal(TEXT.split('niord/upgrade-actions').length - 1, 1);
    assert.match(runOf('compute', 'Check the release notes'), / --repo "\$BASE_REPO" /);
    // The header says it is the one value that changes when the repository moves.
    assert.match(TEXT, /BASE_REPO is the one place that names\n# the base product's repository/);
  });

  it('gives compute read permissions only, apply the writes it needs, and recount the status', () => {
    // issues: read for Build the comment, which reads the earlier comment as recount does.
    assert.deepEqual(WORKFLOW.jobs.compute.permissions, {
      contents: 'read',
      packages: 'read',
      'pull-requests': 'read',
      issues: 'read',
    });
    assert.ok(!Object.values(WORKFLOW.jobs.compute.permissions).includes('write'));
    assert.deepEqual(WORKFLOW.jobs.apply.permissions, {
      contents: 'write',
      'pull-requests': 'write',
      issues: 'write',
      statuses: 'write',
      actions: 'write',
    });
    assert.deepEqual(WORKFLOW.jobs.recount.permissions, {
      statuses: 'write',
      'pull-requests': 'read',
      issues: 'read',
    });
  });

  it('runs set -euo pipefail first in every run block, and no expression in one', () => {
    for (const { job, name, step } of steps) {
      if (step.run === undefined) continue;
      assert.match(step.run, /^set -euo pipefail\n/, `${job}: ${name}`);
      assert.doesNotMatch(step.run, /\$\{\{/, `${job}: ${name}`);
    }
  });

  it('runs no npm, and its Node scripts require Node’s built-ins only', () => {
    // The comment's text names CI's own `npm` commands; the shell and the header never run one.
    const heredocs = /node <<'JS'\n([\s\S]*?)\nJS\n/g;
    let scripts = 0;
    for (const { job, name, step } of steps) {
      assert.doesNotMatch(
        (step.run ?? '').replace(heredocs, ''),
        /\bnpm\b|\bnpx\b/,
        `${job}: ${name}`,
      );
      for (const [, script] of (step.run ?? '').matchAll(heredocs)) {
        scripts++;
        for (const [, module] of script.matchAll(/\brequire\(\s*'([^']+)'\s*\)/g))
          assert.match(module, /^node:(?:fs|path|crypto)$/, `${job}: ${name}`);
      }
    }
    assert.equal(scripts, 2);
    const header = TEXT.slice(0, TEXT.indexOf('\njobs:\n'));
    assert.doesNotMatch(header, /\bnpm\b|\bnpx\b/);
    for (const { step } of steps)
      for (const [, name] of (step.run ?? '').matchAll(/\brequire\(\s*"([^"]+)"\s*\)/g))
        assert.equal(name, 'node:fs');
  });

  it('uses GitHub’s own actions at @v4, and no other action', () => {
    for (const { job, step } of steps)
      if (step.uses !== undefined)
        assert.match(
          step.uses,
          /^actions\/(?:checkout|setup-java|setup-node|upload-artifact|download-artifact)@v4$/,
          `${job}: ${step.uses}`,
        );
  });

  it('checks compute out at the head without the token, and keeps the token in apply’s checkout alone', () => {
    const checkouts = steps.filter(({ step }) => step.uses?.startsWith('actions/checkout@'));
    assert.deepEqual(
      checkouts.map(({ job }) => job),
      ['compute', 'apply'],
    );
    assert.deepEqual(checkouts[0].step.with, {
      ref: '${{ steps.pr.outputs.head_sha }}',
      'fetch-depth': 0,
      'persist-credentials': false,
    });
    assert.deepEqual(checkouts[1].step.with, {
      ref: '${{ needs.compute.outputs.head_sha }}',
      'persist-credentials': true,
    });
    assert.deepEqual(
      steps.filter(({ step }) => step.with?.['persist-credentials'] === true).map((s) => s.job),
      ['apply'],
    );
  });

  it('runs none of the kit’s code with the write token: apply has no Node, no Java and no kit file', () => {
    const apply = steps.filter(({ job }) => job === 'apply');
    assert.ok(!apply.some(({ step }) => /^actions\/setup-(?:node|java)@/.test(step.uses ?? '')));
    for (const { name, step } of apply)
      if (step.run !== undefined) assert.doesNotMatch(step.run, /\bnode\b|\.niord\//, name);
    assert.equal(WORKFLOW.jobs.recount.steps.length, 1);
    assert.doesNotMatch(runOf('recount', 'Count and set the status'), /\bnode\b|\.niord\//);
  });

  it("reads the notes with the workflow's own token on the gh-only notes step, and binds GITHUB_TOKEN on the Maven steps only", () => {
    assert.doesNotMatch(TEXT, /secrets\./);
    assert.equal(
      stepEnv(TEXT, { job: 'compute', step: 'Check the release notes' }).GH_TOKEN,
      '${{ github.token }}',
    );
    assert.doesNotMatch(runOf('compute', 'Check the release notes'), /\bnode\b|\bsh be\/mvnw\b/);
    const holders = steps
      .filter(({ step }) => Object.hasOwn(step.env ?? {}, 'GITHUB_TOKEN'))
      .map(({ job, name }) => `${job}: ${name}`);
    const maven = [
      'Fetch the two zips',
      'Make the set-up record of the old version',
      'Set up the new version',
      'List the new release folders',
    ];
    assert.deepEqual(
      holders,
      maven.map((name) => `compute: ${name}`),
    );
    for (const name of maven)
      assert.equal(
        stepEnv(TEXT, { job: 'compute', step: name }).GITHUB_TOKEN,
        '${{ github.token }}',
        name,
      );
  });

  it('commits as github-actions[bot] and pushes plainly before the pending status', () => {
    const run = runOf('apply', 'Commit and push the pins');
    const lines = run.split('\n');
    assert.ok(lines.includes("git config user.name 'github-actions[bot]'"));
    assert.ok(lines.includes(`git config user.email '${BOT}'`));
    assert.doesNotMatch(TEXT, /GIT_AUTHOR_|GIT_COMMITTER_/);
    const pushes = lines.filter((line) => /^\s*git push\b/.test(line));
    assert.deepEqual(pushes, ['git push -q origin "HEAD:refs/heads/$HEAD_REF"']);
    for (const line of TEXT.split('\n').filter((l) => /\bgit push\b/.test(l)))
      assert.doesNotMatch(line, /--force|\s-f\b|\+refs\//, line);
    const push = run.indexOf('git push -q origin');
    const pending = run.indexOf('-f state=pending');
    assert.ok(push > 0 && pending > push, 'the pending status follows the push');
  });

  it("writes the comment's marker one way only, and the template marker once", () => {
    // The template marker as the heredoc writes it, a JavaScript template literal.
    const templateMark = '<!-- niord-base-bump-template: ${name} ${hash} -->';
    const marks = TEXT.match(/<!--[^\n]*?-->/g) ?? [];
    assert.equal(marks.filter((m) => m === templateMark).length, 1, marks.join(', '));
    const identity = marks.filter((m) => m !== templateMark);
    assert.ok(identity.length >= 4, identity.join(', '));
    for (const m of identity) assert.equal(m, MARKER);
    // The comments name the markers in words only.
    assert.equal(TEXT.split('niord-base-bump').length - 1, marks.length);
    // The query that finds the comment, and recount's condition, never take a box's marker for it.
    assert.ok(!templateMark.includes(MARKER));
  });

  it('reads the earlier comment with the query that posts it and recounts it', () => {
    const query = (job, name) =>
      /gh api --paginate "repos\/\$REPO\/issues\/\$PR\/comments" --jq '([^']*)'/.exec(
        runOf(job, name),
      )?.[1];
    const posted = query('apply', 'Post the comment');
    assert.ok(posted?.includes(MARKER), posted);
    assert.equal(query('compute', 'Build the comment'), posted);
    assert.equal(query('recount', 'Count and set the status'), posted);
  });

  it('names no Renovate: the base bump pull request is the Niord base bump app’s or a person’s', () => {
    assert.doesNotMatch(TEXT, /renovate/i);
  });

  it('takes each output from the step that writes it, and hands over one artifact', () => {
    // An output that named another step would be empty, and apply would be skipped or mislead.
    assert.deepEqual(WORKFLOW.jobs.compute.outputs, {
      pr: '${{ steps.pr.outputs.pr }}',
      head_sha: '${{ steps.pr.outputs.head_sha }}',
      head_ref: '${{ steps.pr.outputs.head_ref }}',
      already_pinned: '${{ steps.versions.outputs.already_pinned }}',
      new: '${{ steps.versions.outputs.new }}',
      old: '${{ steps.versions.outputs.old }}',
      notes: '${{ steps.notes.outputs.notes }}',
      update_pins: '${{ steps.setup.outputs.update_pins }}',
      unticked: '${{ steps.comment.outputs.unticked }}',
      security: '${{ steps.comment.outputs.security }}',
      sha256sums: '${{ steps.handover.outputs.sha256sums }}',
    });
    const ids = (job) =>
      Object.fromEntries(WORKFLOW.jobs[job].steps.filter((s) => s.id).map((s) => [s.id, s.name]));
    assert.deepEqual(ids('compute'), {
      pr: 'Which pull request',
      versions: 'Decide the versions',
      zips: 'Fetch the two zips',
      notes: 'Check the release notes',
      record: 'Make the set-up record of the old version',
      setup: 'Set up the new version',
      releases: 'List the new release folders',
      shadows: 'List the shadowed templates',
      comment: 'Build the comment',
      handover: 'Hand over the files',
    });
    assert.deepEqual(ids('apply'), {
      verify: 'Verify the files',
      push: 'Commit and push the pins',
      ci: 'Run CI on the new head',
      comment: 'Post the comment',
    });
    const upload = stepOf('compute', 'actions/upload-artifact@v4');
    assert.equal(upload.with.name, 'base-bump');
    assert.equal(upload.with['retention-days'], 1);
    assert.equal(stepOf('apply', 'actions/download-artifact@v4').with.name, 'base-bump');
    const last = WORKFLOW.jobs.apply.steps.at(-1);
    assert.equal(last.name, 'Set the status');
    assert.equal(last.if, 'always()');
  });
});

describe("base-bump.yml: the jobs' conditions", () => {
  const pullRequest = (ref, extra = {}) => ({
    'github.event_name': 'pull_request',
    'github.head_ref': ref,
    'github.repository': 'SIFA-DK/niord-dk',
    'github.event.pull_request.head.repo.full_name': 'SIFA-DK/niord-dk',
    ...extra,
  });
  const comment = (extra = {}) => ({
    'github.event_name': 'issue_comment',
    'github.event.issue.pull_request': { url: 'https://api.github.com/repos/o/r/pulls/12' },
    'github.event.comment.user.login': 'github-actions[bot]',
    'github.event.comment.body': `${MARKER}\n## Base bump: ${OLD} to ${NEW}\n`,
    'needs.compute.result': 'skipped',
    ...extra,
  });

  it("runs compute on a base-bump/ head of this repository's own, and on a dispatch", () => {
    assert.equal(runs('compute', pullRequest('base-bump/4.0.110')), true);
    assert.equal(
      runs('compute', { 'github.event_name': 'workflow_dispatch', 'inputs.pr': '12' }),
      true,
    );
  });

  it('never runs compute on another branch, a renovate/ one included, a fork’s branch or a comment', () => {
    assert.equal(runs('compute', pullRequest('feature/x')), false);
    assert.equal(runs('compute', pullRequest('renovate/org.niord-niord-parent-4.x')), false);
    assert.equal(
      runs(
        'compute',
        pullRequest('base-bump/x', {
          'github.event.pull_request.head.repo.full_name': 'fork/niord-dk',
        }),
      ),
      false,
    );
    assert.equal(runs('compute', comment()), false);
  });

  it('runs apply only after a compute that succeeded with a version still to pin', () => {
    assert.doesNotMatch(WORKFLOW.jobs.apply.if, STATUS_FUNCTION);
    const after = (pinned, result = 'success') => ({
      'needs.compute.result': result,
      'needs.compute.outputs.already_pinned': pinned,
    });
    assert.equal(runs('apply', after('false')), true);
    assert.equal(runs('apply', after('true')), false);
    assert.equal(runs('apply', after('')), false);
    // A compute that failed after deciding the versions has already_pinned false: it still stops.
    assert.equal(runs('apply', after('false', 'failure')), false);
    assert.equal(runs('apply', after('', 'skipped')), false);
  });

  it("recounts on an edit of the bot's marked comment on a pull request, and no other comment", () => {
    assert.equal(runs('recount', comment()), true);
    assert.equal(runs('recount', comment({ 'github.event.comment.body': 'a comment' })), false);
    assert.equal(runs('recount', comment({ 'github.event.comment.user.login': 'someone' })), false);
    assert.equal(runs('recount', comment({ 'github.event.issue.pull_request': null })), false);
  });

  it('recounts when the branch pins the version already and after a failed compute, never after a re-pin', () => {
    const after = (result, pinned) =>
      pullRequest('base-bump/x', {
        'needs.compute.result': result,
        'needs.compute.outputs.already_pinned': pinned,
      });
    assert.equal(runs('recount', after('success', 'true')), true);
    assert.equal(runs('recount', after('failure', 'false')), true);
    assert.equal(runs('recount', after('failure', '')), true);
    assert.equal(runs('recount', after('success', 'false')), false);
    assert.equal(runs('recount', after('skipped', '')), false);
  });
});

describe("base-bump.yml: the steps' conditions on each path", () => {
  const ALWAYS = [
    'Which pull request',
    'actions/checkout@v4',
    'Set up JDK 25 (Temurin) + GitHub Packages credentials',
    'actions/setup-node@v4',
    'Decide the versions',
  ];
  const compute = (notes) => ({
    'steps.versions.outputs.already_pinned': 'false',
    'steps.notes.outputs.notes': notes,
  });

  it('runs only the versions when the lock pins the version already', () => {
    assert.deepEqual(
      stepsRunning('compute', { 'steps.versions.outputs.already_pinned': 'true' }),
      ALWAYS,
    );
  });

  it('runs every step when the notes confirm the zips', () => {
    const all = steps.filter(({ job }) => job === 'compute').map(({ name }) => name);
    assert.deepEqual(stepsRunning('compute', compute('verified')), all);
  });

  it('stops at the notes when they cannot be read or name other zips', () => {
    assert.deepEqual(stepsRunning('compute', compute(''), ['Check the release notes']), [
      ...ALWAYS,
      'Fetch the two zips',
      'Check the release notes',
    ]);
  });

  const apply = (notes, extra = {}) => ({
    'needs.compute.outputs.notes': notes,
    'steps.verify.outcome': 'success',
    'steps.push.outcome': 'success',
    'steps.push.outputs.pushed': 'true',
    ...extra,
  });
  const APPLY_FIRST = ['actions/checkout@v4', 'actions/download-artifact@v4', 'Verify the files'];

  it('commits, runs CI, comments, labels and sets the status after a verified pin', () => {
    const all = steps.filter(({ job }) => job === 'apply').map(({ name }) => name);
    assert.deepEqual(stepsRunning('apply', apply('verified')), all);
  });

  it('dispatches no CI when there was nothing to push', () => {
    assert.deepEqual(
      stepsRunning('apply', apply('verified', { 'steps.push.outputs.pushed': 'false' })),
      [
        ...APPLY_FIRST,
        'Commit and push the pins',
        'Post the comment',
        'Label the pull request',
        'Set the status',
      ],
    );
  });

  it('only sets the status after a refused push or files that failed their check', () => {
    assert.deepEqual(
      stepsRunning(
        'apply',
        apply('verified', { 'steps.push.outcome': 'failure', 'steps.push.outputs.pushed': '' }),
        ['Commit and push the pins'],
      ),
      [...APPLY_FIRST, 'Commit and push the pins', 'Set the status'],
    );
    assert.deepEqual(
      stepsRunning(
        'apply',
        apply('verified', {
          'steps.verify.outcome': 'failure',
          'steps.push.outcome': 'skipped',
          'steps.push.outputs.pushed': '',
        }),
        ['Verify the files'],
      ),
      [...APPLY_FIRST, 'Set the status'],
    );
  });

  it('still comments and labels when the CI dispatch failed', () => {
    assert.deepEqual(stepsRunning('apply', apply('verified'), ['Run CI on the new head']), [
      ...APPLY_FIRST,
      'Commit and push the pins',
      'Run CI on the new head',
      'Post the comment',
      'Label the pull request',
      'Set the status',
    ]);
  });
});

describe('base-bump.yml: Which pull request', () => {
  const VIEW =
    'pr view 12 --repo o/r --json headRefName,headRefOid,baseRefOid,isCrossRepository,state --jq *';
  const DISPATCH = { EVENT: 'workflow_dispatch', HEAD_SHA: '', HEAD_REF: '', BASE_SHA: '' };
  const view = (ref, cross = 'false', state = 'OPEN') => ({
    out: `${ref}\t${HEAD}\t${BASE}\t${cross}\t${state}\n`,
  });
  const which = (env, answer = { exit: 1 }) =>
    runBash(runOf('compute', 'Which pull request'), {
      env: {
        EVENT: 'pull_request',
        PR: '12',
        HEAD_SHA: HEAD,
        HEAD_REF: 'base-bump/4.0.110',
        BASE_SHA: BASE,
        REPO,
        GH_TOKEN: 'token',
        ...env,
      },
      fakes: { gh: [{ match: VIEW, ...answer }] },
    });

  it("takes a pull request event's values as given, asking gh nothing", () => {
    const result = which({});
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.outputs, {
      pr: '12',
      head_ref: 'base-bump/4.0.110',
      head_sha: HEAD,
      base_sha: BASE,
    });
    assert.deepEqual(result.calls, []);
  });

  it("reads a dispatch's pull request with gh pr view", () => {
    const result = which(DISPATCH, view('base-bump/4.0.110'));
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.outputs, {
      pr: '12',
      head_ref: 'base-bump/4.0.110',
      head_sha: HEAD,
      base_sha: BASE,
    });
    assert.equal(result.calls.length, 1);
    assert.match(result.calls[0], /^gh pr view 12 --repo o\/r --json /);
  });

  for (const [what, env, answer, message] of [
    ['a closed pull request', DISPATCH, view('base-bump/x', 'false', 'CLOSED'), /12 is not open/],
    ['a merged pull request', DISPATCH, view('base-bump/x', 'false', 'MERGED'), /12 is not open/],
    [
      "a fork's pull request",
      DISPATCH,
      view('base-bump/x', 'true'),
      /comes from another repository/,
    ],
    [
      'a branch outside base-bump/',
      DISPATCH,
      view('feature/x'),
      /'feature\/x' is not a base-bump\/ branch/,
    ],
    [
      'a renovate/ branch',
      DISPATCH,
      view('renovate/x'),
      /'renovate\/x' is not a base-bump\/ branch/,
    ],
    [
      "a pull request event's renovate/ branch",
      { HEAD_REF: 'renovate/org.niord-niord-parent-4.x' },
      undefined,
      /'renovate\/org\.niord-niord-parent-4\.x' is not a base-bump\/ branch/,
    ],
    [
      'a pull request number that is not a number',
      { ...DISPATCH, PR: '12; rm -rf /' },
      undefined,
      /is not a number/,
    ],
    ['an empty number', { ...DISPATCH, PR: '' }, undefined, /is not a number/],
  ])
    it(`refuses ${what}`, () => {
      const result = which(env, answer);
      assert.equal(result.status, 1, result.stdout + result.stderr);
      assert.match(result.stdout, message);
      assert.deepEqual(result.outputs, {});
    });
});

describe('base-bump.yml: Decide the versions', () => {
  const pom = (version, parent) =>
    [
      '<project>',
      '  <modelVersion>4.0.0</modelVersion>',
      '  <parent>',
      ...(parent ?? [
        '    <groupId>org.niord</groupId>',
        '    <artifactId>niord-parent</artifactId>',
        `    <version>${version}</version>`,
      ]),
      '  </parent>',
      '  <artifactId>niord-dk</artifactId>',
      '  <version>1.0.0</version>',
      '</project>',
      '',
    ].join('\n');
  const lock = (version) => lockText({ version, sha256: 'e'.repeat(64), packs: { da: DA_PIN } });
  const decide = ({
    parent = NEW,
    parentLines,
    head = OLD,
    base = OLD,
    event = 'pull_request',
  } = {}) =>
    inDir(
      'versions',
      { 'be/pom.xml': pom(parent, parentLines), 'fe/niord-pins.lock.json': lock(head) },
      (dir) =>
        runBash(runOf('compute', 'Decide the versions'), {
          cwd: dir,
          env: { EVENT: event, BASE_SHA: BASE },
          fakes: { git: [{ match: `show ${BASE}:fe/niord-pins.lock.json`, out: lock(base) }] },
        }),
    );

  it('takes NEW from the pom and OLD from the base branch’s lock', () => {
    const result = decide();
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.outputs, {
      new: NEW,
      old: OLD,
      already_pinned: 'false',
    });
    assert.deepEqual(result.calls, [`git show ${BASE}:fe/niord-pins.lock.json`]);
  });

  // The stub's parentVersion reads the whole <parent> block, comments left out: so does this step,
  // or the stub would refuse the lock the workflow wrote.
  for (const [what, parentLines] of [
    [
      'its version before its artifactId',
      [
        `    <version>${NEW}</version>`,
        '    <groupId>org.niord</groupId>',
        '    <artifactId>niord-parent</artifactId>',
      ],
    ],
    [
      'a comment and a relativePath between its artifactId and its version',
      [
        '    <groupId>org.niord</groupId>',
        '    <artifactId>niord-parent</artifactId>',
        '    <!-- <version>4.0.1</version>, the base product version',
        '         the country builds on -->',
        '    <relativePath/>',
        `    <version> ${NEW} </version>`,
      ],
    ],
  ])
    it(`takes NEW from a <parent> with ${what}, as the stub does`, () => {
      const result = decide({ parentLines });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.equal(result.outputs.new, NEW);
    });

  it('refuses a pom without a <parent>', () => {
    const result = inDir(
      'versions',
      {
        'be/pom.xml': `<project>\n  <artifactId>niord-dk</artifactId>\n  <version>${NEW}</version>\n</project>\n`,
        'fe/niord-pins.lock.json': lock(OLD),
      },
      (dir) =>
        runBash(runOf('compute', 'Decide the versions'), {
          cwd: dir,
          env: { EVENT: 'pull_request', BASE_SHA: BASE },
          fakes: { git: [] },
        }),
    );
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /::error::be\/pom\.xml's <parent> is no 4\.0\.<n> version \(got ''\)/,
    );
    assert.deepEqual(result.calls, []);
  });

  it("takes OLD from the base branch, not from the head's lock", () => {
    const result = decide({ head: '4.0.100' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.old, OLD);
    assert.equal(result.outputs.already_pinned, 'false');
  });

  it('recounts only, on a push to a branch whose lock pins NEW already', () => {
    const result = decide({ head: NEW });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.already_pinned, 'true');
    assert.match(result.stdout, /::notice::fe\/niord-pins\.lock\.json pins 4\.0\.110 already/);
  });

  it('pins again on a dispatch, a lock that pins NEW already included', () => {
    const result = decide({ head: NEW, event: 'workflow_dispatch' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.already_pinned, 'false');
  });

  it('refuses a pull request that bumps nothing', () => {
    const result = decide({ parent: OLD });
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /::error::be\/pom\.xml's <parent> is the base branch's version, 4\.0\.97/,
    );
    assert.deepEqual(result.outputs, {});
  });

  for (const parent of ['4.1.0', '4.0.110-SNAPSHOT', '${niord.version}'])
    it(`refuses a parent of ${parent}`, () => {
      const result = decide({ parent });
      assert.equal(result.status, 1);
      assert.match(result.stdout, /::error::be\/pom\.xml's <parent> is no 4\.0\.<n> version/);
    });
});

describe('base-bump.yml: Check the release notes', () => {
  const CALL = `gh release view v${NEW} --repo NiordOrg/niord4 --json body --jq .body`;
  const body = (...lines) =>
    [
      "The base product's frontend sources and the country kit of this version:",
      '',
      ...lines,
      '',
      'Check them with sha256sum -c.',
      '',
    ].join('\n');
  const FE_LINE = `${FE_SHA}  niord-fe-src-${NEW}.zip`;
  const KIT_LINE = `${KIT_SHA}  niord-country-kit-${NEW}.zip`;
  const notes = (answer, env = {}) =>
    runBash(runOf('compute', 'Check the release notes'), {
      env: {
        ...ENV,
        NEW,
        PR: '12',
        FE_SHA256: FE_SHA,
        KIT_SHA256: KIT_SHA,
        GH_TOKEN: 't',
        ...env,
      },
      fakes: {
        gh: [
          {
            match: `release view v${NEW} --repo NiordOrg/niord4 --json body --jq .body`,
            ...answer,
          },
        ],
      },
    });

  it('verifies the downloads against the two lines of the notes', () => {
    const result = notes({ out: body(FE_LINE, KIT_LINE) });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.outputs, { notes: 'verified' });
    assert.deepEqual(result.calls, [CALL]);
  });

  it('reads notes written with CRLF', () => {
    const result = notes({ out: body(FE_LINE, KIT_LINE).replaceAll('\n', '\r\n') });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.notes, 'verified');
  });

  for (const [what, text] of [
    [
      'notes that name another kit zip',
      body(FE_LINE, `${'c'.repeat(64)}  niord-country-kit-${NEW}.zip`),
    ],
    ['notes with one line only', body(FE_LINE)],
    ['notes that give a line twice', body(FE_LINE, FE_LINE, KIT_LINE)],
    ['notes of another version', body(`${FE_SHA}  niord-fe-src-4.0.1100.zip`, KIT_LINE)],
  ])
    it(`refuses ${what}, pinning nothing`, () => {
      const result = notes({ out: text });
      assert.equal(result.status, 1, result.stdout + result.stderr);
      assert.match(
        result.stdout,
        /::error::The registry's zips of 4\.0\.110 are not the ones release v4\.0\.110's notes in NiordOrg\/niord4 name/,
      );
      assert.deepEqual(result.outputs, {});
    });

  // gh 2.x answers "release not found" for a release not made yet.
  for (const [what, err, reason] of [
    [
      'a release not made yet',
      'HTTP 404: Not Found (https://api.github.com/...)\n',
      'HTTP 404: Not Found (https://api.github.com/...)',
    ],
    ["gh's release not found", 'release not found\r\n', 'release not found'],
    [
      'a server error',
      'HTTP 500: Internal Server Error (https://api.github.com/...)\n',
      'HTTP 500: Internal Server Error (https://api.github.com/...)',
    ],
    [
      'a rate limit',
      'HTTP 403: API rate limit exceeded for installation ID 1.\n',
      'HTTP 403: API rate limit exceeded for installation ID 1.',
    ],
    ['a failure that says nothing', '', 'no reason given'],
  ])
    it(`fails on ${what}, pinning nothing, saying why and asking for another run`, () => {
      const result = notes({ err, exit: 1 });
      assert.equal(result.status, 1, result.stdout + result.stderr);
      assert.deepEqual(result.outputs, {});
      assert.ok(
        result.stdout.includes(
          `::error::Release v4.0.110's notes in NiordOrg/niord4 could not be read (${reason}): nothing is pinned. Run this workflow again (Actions, Base bump, Run workflow, pr 12), once the release is made if it is not yet.`,
        ),
        result.stdout,
      );
      assert.deepEqual(result.calls, [CALL]);
    });

  it('refuses downloads that were not hashed, asking gh nothing', () => {
    const result = notes({ out: body(FE_LINE, KIT_LINE) }, { KIT_SHA256: '' });
    assert.equal(result.status, 1);
    assert.match(result.stdout, /::error::The two zips were not hashed\./);
    assert.deepEqual(result.calls, []);
  });
});

describe('base-bump.yml: Write the lock', () => {
  const PACKS = { kl: KL_PIN, da: DA_PIN };
  const oldLock = (kit) =>
    `${JSON.stringify(
      {
        format: 1,
        base: { version: OLD, sha256: 'e'.repeat(64) },
        ...(kit ? { kit: { sha256: 'f'.repeat(64) } } : {}),
        packs: PACKS,
      },
      null,
      2,
    )}\n`;
  const writeLock = (text, env = {}) =>
    inDir('lock', { 'fe/niord-pins.lock.json': text }, (dir) => {
      const result = runBash(runOf('compute', 'Write the lock'), {
        cwd: dir,
        env: { NEW, FE_SHA256: FE_SHA, KIT_SHA256: KIT_SHA, ...env },
      });
      return { ...result, lock: readIn(dir, 'fe/niord-pins.lock.json') };
    });
  const pinned = lockText({ version: NEW, sha256: FE_SHA, kit: { sha256: KIT_SHA }, packs: PACKS });

  for (const kit of [false, true])
    it(`writes the kit's own lock text over a lock ${kit ? 'with' : 'without'} a kit pin`, () => {
      const result = writeLock(oldLock(kit));
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.equal(result.lock, pinned);
      const lock = JSON.parse(result.lock);
      assert.deepEqual(Object.keys(lock), ['format', 'base', 'kit', 'packs']);
      assert.deepEqual(Object.keys(lock.packs), ['da', 'kl']);
    });

  it('writes a lock that pins the version already (a dispatch) with the downloads’ hashes', () => {
    assert.equal(writeLock(pinned).lock, pinned);
    const other = lockText({
      version: NEW,
      sha256: FE_SHA,
      kit: { sha256: 'f'.repeat(64) },
      packs: PACKS,
    });
    const result = writeLock(other);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.lock, pinned);
  });

  it('refuses a lock of another format, leaving it', () => {
    const text = oldLock(true).replace('"format": 1', '"format": 2');
    const result = writeLock(text);
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /::error::fe\/niord-pins\.lock\.json is not a lock file of format 1/,
    );
    assert.equal(result.lock, text);
  });
});

describe('base-bump.yml: Set up the new version', () => {
  const STUB = '#!/usr/bin/env node\n// the stub\n';
  const NEW_STUB = '#!/usr/bin/env node\n// the new stub\n';
  // set-up, faked: it reports whether the record was there, writes the kit's templates when the
  // test gives one, the record and the report, on --update-pins the lock SETUP_PINS_LOCK holds
  // when the test gives one, and exits with the codes SETUP_EXITS lists, one per call. Every
  // other call of node goes to the real one.
  const SETUP = [
    'n=$(( $(cat "$FAKE_BIN/setup.count" 2> /dev/null || echo 0) + 1 ))',
    'echo "$n" > "$FAKE_BIN/setup.count"',
    'if [ -f .niord/build/niord-build-report.json ]; then echo "fake set-up $*: a record"; else echo "fake set-up $*: no record"; fi',
    'if [ -n "${SETUP_TEMPLATE+x}" ]; then',
    '  mkdir -p ../.niord/kit/templates',
    '  printf "%s" "$SETUP_TEMPLATE" > ../.niord/kit/templates/setup.mjs',
    '  printf "the launcher\\n" > ../.niord/kit/templates/start-local.sh',
    'fi',
    'case " $* " in *" --update-pins "*) [ -z "${SETUP_PINS_LOCK+x}" ] || printf "%s" "$SETUP_PINS_LOCK" > niord-pins.lock.json ;; esac',
    'mkdir -p .niord/build && printf "{}\\n" > .niord/build/niord-build-report.json',
    '[ -z "${SETUP_REPORT:-}" ] || printf "%s" "$SETUP_REPORT" > .niord/niord-upgrade-report.json',
    'codes=(${SETUP_EXITS:-0})',
    'exit "${codes[$((n - 1))]:-0}"',
  ].join('\n');
  /** A lock of `version` that pins da at `pin`, or no pack at all. */
  const lockOf = (version, pin) =>
    lockText({ version, sha256: FE_SHA, kit: { sha256: KIT_SHA }, packs: pin ? { da: pin } : {} });
  const GIT_SHOW = `show ${BASE}:fe/niord-pins.lock.json`;
  /**
   * Set-up in a folder holding the stub and a manifest that pins da (`pinned`) or takes it from
   * this country's own home, the branch's lock pinning da at `branchPin` and the base branch's at
   * `basePin`; a refresh writes `movedPin`.
   */
  const setUp = ({
    record = 'made',
    stub = STUB,
    template = STUB,
    report,
    exits = '0',
    pinned = false,
    branchPin = DA_PIN,
    basePin = DA_PIN,
    movedPin,
    baseLock = { out: lockOf(OLD, pinned ? basePin : undefined) },
  } = {}) =>
    inDir(
      'setup',
      {
        'fe/setup.mjs': stub,
        'fe/niord-country.json': `${JSON.stringify({ country: 'xx', packs: { da: pinned ? { pinned: true } : { home: true, publish: true } } }, null, 2)}\n`,
        'fe/niord-pins.lock.json': lockOf(NEW, pinned ? branchPin : undefined),
      },
      (dir) => {
        const env = {
          OLD,
          RECORD: record,
          BASE_SHA: BASE,
          GITHUB_TOKEN: 'token',
          REAL_NODE,
          SETUP_EXITS: exits,
          RUNNER_TEMP: slashes(path.join(dir, 'temp')),
        };
        if (template !== null) env.SETUP_TEMPLATE = template;
        if (report !== undefined) env.SETUP_REPORT = JSON.stringify(report);
        if (movedPin !== undefined) env.SETUP_PINS_LOCK = lockOf(NEW, movedPin);
        const result = runBash(runOf('compute', 'Set up the new version'), {
          cwd: dir,
          env,
          fakes: {
            git: [{ match: GIT_SHOW, ...baseLock }],
            node: [
              { match: 'setup.mjs*', script: SETUP },
              { match: '*', script: 'exec "$REAL_NODE" "$@"' },
            ],
          },
        });
        return {
          ...result,
          setups: result.calls.filter((call) => call.startsWith('node setup.mjs')),
          stub: readIn(dir, 'fe/setup.mjs'),
          launcher: readIn(dir, 'start-local.sh'),
          baseLock: readIn(dir, 'temp/work/base-lock.json'),
        };
      },
    );
  const REFUSED =
    /::warning::node setup\.mjs --update-pins exited with an error: a set-up without it follows, and its report names what is wrong \(a withdrawn pin stops it; a pin it moved stays moved\)\./;

  it('runs set-up once with a record of the old version and no pinned pack, and copies the kit’s launcher', () => {
    const result = setUp();
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs']);
    assert.deepEqual(result.outputs, { status: '0', stub: 'kept', update_pins: '' });
    assert.equal(result.stub, STUB);
    assert.equal(result.launcher, 'the launcher\n');
    assert.ok(result.calls.includes(`git ${GIT_SHOW}`));
    assert.equal(result.baseLock, lockOf(OLD));
  });

  it("moves every pinned pack to its home's newest pack in the one set-up a record allows", () => {
    const result = setUp({ pinned: true, movedPin: DA_PIN3 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs --update-pins']);
    assert.deepEqual(result.outputs, { status: '0', stub: 'kept', update_pins: 'da' });
    assert.doesNotMatch(result.stdout, /::warning::/);
    // The base branch's lock, which Build the comment reads for the bullet's from.
    assert.equal(result.baseLock, lockOf(OLD, DA_PIN));
  });

  it('moves the pins, then compares with the old version by version, without a record', () => {
    const result = setUp({ pinned: true, record: 'missing', movedPin: DA_PIN3 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, [
      'node setup.mjs --update-pins',
      'node setup.mjs --previous 4.0.97',
    ]);
    assert.match(result.stdout, /fake set-up setup\.mjs --previous 4\.0\.97: no record/);
    assert.deepEqual(result.outputs, { status: '0', stub: 'kept', update_pins: 'da' });
  });

  for (const [what, record, setups] of [
    ['with a record', 'made', ['node setup.mjs --update-pins', 'node setup.mjs']],
    [
      'without a record',
      'missing',
      ['node setup.mjs --update-pins', 'node setup.mjs --previous 4.0.97'],
    ],
  ])
    it(`warns when the refresh fails ${what}, and the set-up that follows gives the status`, () => {
      const result = setUp({ pinned: true, record, exits: '1 4' });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.match(result.stdout, REFUSED);
      assert.deepEqual(result.setups, setups);
      assert.deepEqual(result.outputs, { status: '4', stub: 'kept', update_pins: '' });
    });

  it("names a moved pin the branch holds already, measured against the base branch's lock", () => {
    const result = setUp({ pinned: true, branchPin: DA_PIN3, movedPin: DA_PIN3 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.update_pins, 'da');
  });

  it('names no pin when the refresh leaves it where the base branch has it', () => {
    const result = setUp({ pinned: true, movedPin: DA_PIN });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs --update-pins']);
    assert.equal(result.outputs.update_pins, '');
  });

  it("stops when the base branch's lock cannot be read, running no set-up", () => {
    const result = setUp({ pinned: true, baseLock: { err: 'fatal: bad object\n', exit: 128 } });
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /::error::The base branch's fe\/niord-pins\.lock\.json cannot be read\./,
    );
    assert.deepEqual(result.setups, []);
    assert.deepEqual(result.outputs, {});
  });

  it('compares with the old version by version, from no record, at every run without a record', () => {
    const result = setUp({ record: 'missing', template: NEW_STUB });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, [
      'node setup.mjs --previous 4.0.97',
      'node setup.mjs --previous 4.0.97',
    ]);
    assert.equal(
      result.stdout.match(/fake set-up setup\.mjs --previous 4\.0\.97: no record/g).length,
      2,
    );
    assert.doesNotMatch(result.stdout, /: a record/);
  });

  it('copies a stub the kit changed and runs set-up again', () => {
    const result = setUp({ template: NEW_STUB, exits: '1 0' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs', 'node setup.mjs']);
    assert.deepEqual(result.outputs, { status: '0', stub: 'copied', update_pins: '' });
    assert.equal(result.stub, NEW_STUB);
    assert.match(result.stdout, /::notice::The kit's stub changed/);
  });

  it('keeps a stub that differs only in its line endings', () => {
    const result = setUp({ stub: STUB.replaceAll('\n', '\r\n') });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs']);
    assert.equal(result.outputs.stub, 'kept');
  });

  it('stops when set-up left no kit at the root’s .niord/kit/', () => {
    const result = setUp({ template: null, exits: '3' });
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /::error::Set-up of the new version left no kit in \.niord\/kit\/ \(exit 3\): this workflow needs the kit package's layout/,
    );
    assert.deepEqual(result.outputs, {});
  });

  it('copies a changed stub after the refresh and runs set-up again, the pins moved already', () => {
    const result = setUp({ pinned: true, template: NEW_STUB, movedPin: DA_PIN3 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs --update-pins', 'node setup.mjs']);
    assert.deepEqual(result.outputs, { status: '0', stub: 'copied', update_pins: 'da' });
    assert.equal(result.stub, NEW_STUB);
  });

  it('moves no pin for the keys a report finds missing when no pack is pinned', () => {
    const result = setUp({ report: { missingForeign: [{ lang: 'da', keys: ['harbour.title'] }] } });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, ['node setup.mjs']);
    assert.deepEqual(result.outputs, { status: '0', stub: 'kept', update_pins: '' });
  });

  it("gives the last set-up's exit code as the status, failing nothing itself", () => {
    const result = setUp({ pinned: true, template: NEW_STUB, exits: '1 0 4' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.setups, [
      'node setup.mjs --update-pins',
      'node setup.mjs',
      'node setup.mjs',
    ]);
    assert.equal(result.outputs.status, '4');
    assert.equal(setUp({ exits: '2' }).outputs.status, '2');
  });
});

describe('base-bump.yml: Compare the workflow files', () => {
  const compare = (files) =>
    runListing('drift', files, runOf('compute', 'Compare the workflow files'), 'drift.txt');
  const KIT_FILES = {
    '.niord/kit/templates/pack.yml': 'name: Pack\n',
    '.niord/kit/templates/base-bump.yml': 'name: Base bump\n',
  };

  it('lists nothing when the copies equal the templates, line endings aside', () => {
    const result = compare({
      ...KIT_FILES,
      '.github/workflows/pack.yml': 'name: Pack\n',
      '.github/workflows/base-bump.yml': 'name: Base bump\r\n',
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.list, '');
  });

  it('lists a copy that differs, and passes over a workflow the country does not have', () => {
    const result = compare({ ...KIT_FILES, '.github/workflows/base-bump.yml': 'name: Old\n' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.list, 'base-bump.yml\n');
  });
});

describe('base-bump.yml: List the new release folders', () => {
  // The bootstrap zip's root as the real one holds it (be/niord-bootstrap's assembly).
  const listing = (...extra) =>
    [
      'releases/',
      'releases/2026-10-21-templates-from-database/',
      'templates/',
      'templates/aton/',
      'aton-dict.json',
      'domains.json',
      'README.md',
      'releases/2026-10-21-templates-from-database/missing-paths.sql',
      'releases/2026-10-21-templates-from-database/README.md',
      'releases/2026-10-21-templates-from-database/script-resources.json',
      'releases/2026-10-21-templates-from-database/texts.json',
      ...extra.flatMap((folder) => [
        `releases/${folder}/`,
        `releases/${folder}/README.md`,
        `releases/${folder}/texts.json`,
      ]),
      'templates/aton/light-character.ftl',
      '',
    ].join('\n');
  // Maven, faked: dependency:copy touches the zip, or fails for a version MISSING names.
  const MVNW = [
    '#!/bin/sh',
    'for a in "$@"; do',
    '  case "$a" in',
    '    -Dartifact=*) artifact="${a#-Dartifact=}" ;;',
    '    -DoutputDirectory=*) out="${a#-DoutputDirectory=}" ;;',
    '  esac',
    'done',
    'version="$(echo "$artifact" | cut -d: -f3)"',
    'case " $MISSING " in *" $version "*) exit 1 ;; esac',
    'touch "$out/niord-bootstrap-$version.zip"',
    '',
  ].join('\n');
  const releases = (missing = '') =>
    runListing(
      'releases',
      { 'be/mvnw': MVNW },
      runOf('compute', 'List the new release folders'),
      'releases.txt',
      {
        env: { OLD, NEW, MISSING: missing, GITHUB_TOKEN: 'token' },
        fakes: {
          unzip: [
            { match: '-Z1 */niord-bootstrap-4.0.97.zip', out: listing() },
            { match: '-Z1 */niord-bootstrap-4.0.110.zip', out: listing('2026-10-30-harbours') },
          ],
        },
      },
    );

  it('lists the folders new in NEW', () => {
    const result = releases();
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.list, '2026-10-30-harbours\n');
    assert.deepEqual(result.outputs, { bootstrap: 'both' });
    assert.equal(result.calls.length, 2);
  });

  it("lists every folder of NEW when OLD's zip cannot be fetched", () => {
    const result = releases(OLD);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.list, '2026-10-21-templates-from-database\n2026-10-30-harbours\n');
    assert.deepEqual(result.outputs, { bootstrap: 'no-old' });
    assert.match(result.stdout, /::warning::The bootstrap zip of 4\.0\.97 could not be fetched/);
  });

  it("lists none when NEW's zip cannot be fetched, which the comment asks a human to compare", () => {
    const result = releases(NEW);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.list, '');
    assert.deepEqual(result.outputs, { bootstrap: 'no-new' });
    assert.deepEqual(result.calls, []);
  });
});

describe('base-bump.yml: List the shadowed templates', () => {
  // The two bootstraps' templates/, as their zips hold them below the archive root.
  const ZIPS = {
    [OLD]: {
      'messages/a.ftl': 'a of 4.0.97\n',
      'messages/b.ftl': 'b\n',
      'messages/gone.ftl': 'gone\n',
      'tmpl/same.ftl': 'same\n',
      'tmpl/unheld.ftl': 'unheld of 4.0.97\n',
    },
    [NEW]: {
      'messages/a.ftl': 'a of 4.0.110\n',
      'messages/b.ftl': 'b\n',
      'messages/new.ftl': 'new\n',
      'tmpl/same.ftl': 'same\n',
      'tmpl/unheld.ftl': 'unheld of 4.0.110\n',
    },
  };
  // The country's be/bootstrap/templates/: copies, language variants, and files of its own.
  const OWN = [
    'mailinglist/own.ftl',
    'messages/a.ftl',
    'messages/a_kl.ftl',
    'messages/a_kal.ftl',
    'messages/a_kl.js',
    'messages/b.ftl',
    'messages/b_da.ftl',
    'messages/gone.ftl',
    'messages/new.ftl',
    'tmpl/same_da.ftl',
  ];
  // unzip, faked: unpacks zips/<version>/ into the folder -d names, or exits FAIL for a version
  // FAILING names.
  const UNZIP = {
    match: '-q -o */niord-bootstrap-*.zip -d *',
    script: [
      '    version="$(basename "$3" .zip)"; version="${version#niord-bootstrap-}"',
      '    case " $FAILING " in *" $version "*) exit 9 ;; esac',
      '    cp -R "zips/$version/." "$5/"',
    ].join('\n'),
  };
  const shadows = ({ bootstrap = 'both', own = OWN, failing = '' } = {}) => {
    const files = {};
    for (const [version, templates] of Object.entries(ZIPS)) {
      // A file beside templates/, which the country's path never meets.
      files[`zips/${version}/categories.json`] = `[] of ${version}\n`;
      for (const [name, text] of Object.entries(templates))
        files[`zips/${version}/templates/${name}`] = text;
    }
    for (const name of own) files[`be/bootstrap/templates/${name}`] = `${name} of the country\n`;
    return runListing(
      'shadows',
      files,
      runOf('compute', 'List the shadowed templates'),
      'shadowed.txt',
      { env: { OLD, NEW, BOOTSTRAP: bootstrap, FAILING: failing }, fakes: { unzip: [UNZIP] } },
    );
  };
  const rows = (list) =>
    list
      .split('\n')
      .filter(Boolean)
      .map((line) => line.split('\t'));

  it("lists each of the country's templates whose base product file changed, its language variants included", () => {
    const result = shadows();
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(rows(result.list), [
      ['messages/a.ftl', 'messages/a.ftl', 'changed'],
      ['messages/a_kl.ftl', 'messages/a.ftl', 'changed'],
      ['messages/gone.ftl', 'messages/gone.ftl', 'removed'],
      ['messages/new.ftl', 'messages/new.ftl', 'added'],
    ]);
    assert.deepEqual(result.outputs, { shadows: 'listed' });
    assert.equal(result.calls.length, 2);
  });

  it("lists every pair NEW holds, not compared, when OLD's zip could not be fetched", () => {
    const result = shadows({ bootstrap: 'no-old' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(rows(result.list), [
      ['messages/a.ftl', 'messages/a.ftl', 'not-compared'],
      ['messages/a_kl.ftl', 'messages/a.ftl', 'not-compared'],
      ['messages/b.ftl', 'messages/b.ftl', 'not-compared'],
      ['messages/b_da.ftl', 'messages/b.ftl', 'not-compared'],
      ['messages/new.ftl', 'messages/new.ftl', 'not-compared'],
      ['tmpl/same_da.ftl', 'tmpl/same.ftl', 'not-compared'],
    ]);
    assert.deepEqual(result.outputs, { shadows: 'listed' });
    assert.equal(result.calls.length, 1);
  });

  it("asks for a comparison by hand when NEW's zip is not at hand or a zip cannot be unpacked", () => {
    for (const [options, calls] of [
      [{ bootstrap: 'no-new' }, 0],
      [{ failing: OLD }, 2],
      [{ failing: NEW, bootstrap: 'no-old' }, 1],
    ]) {
      const result = shadows(options);
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.equal(result.list, '');
      assert.deepEqual(result.outputs, { shadows: 'by-hand' });
      assert.equal(result.calls.length, calls, JSON.stringify(options));
      if (calls)
        assert.match(result.stdout, /::warning::The bootstrap's templates could not be compared/);
    }
  });

  it('lists nothing and unpacks nothing when the country holds no template', () => {
    const result = shadows({ own: [] });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.list, '');
    assert.deepEqual(result.outputs, { shadows: 'none' });
    assert.deepEqual(result.calls, []);
  });
});

describe('base-bump.yml: Build the comment', () => {
  // The report data's keys in the order upgrade-report.test.mjs's DATA_KEYS freezes: the contract
  // this workflow reads.
  const DATA_KEYS = [
    'format',
    'country',
    'from',
    'to',
    'failed',
    'sections',
    'fixes',
    'todo',
    'look',
    'stubChanged',
    'templatesChanged',
    'templatesUnavailable',
    'otherTemplatesChanged',
    'missingForeign',
    'notChecked',
  ];
  const data = (extra = {}) => ({
    format: 1,
    country: 'xx',
    from: OLD,
    to: NEW,
    failed: 0,
    sections: [],
    fixes: [],
    todo: [],
    look: [],
    stubChanged: false,
    templatesChanged: [],
    templatesUnavailable: null,
    otherTemplatesChanged: [],
    missingForeign: [],
    notChecked: ['what set-up does not check'],
    ...extra,
  });
  const MD = '# Upgrade report: xx, 4.0.97 to 4.0.110\n\n## To do\n\n- Nothing.\n';
  const FULL = data({
    sections: [
      {
        heading: '4.0.105: roleGuard takes its roles from data',
        entries: null,
        actions: ['replace `roleGuard(roles)`\n  by `roleGuard`.', 'run the check once.'],
      },
      { heading: '4.0.106: a renderer hook', entries: null, actions: [] },
    ],
    fixes: [{ version: '4.0.104', summary: 'escapes the bar' }],
    templatesChanged: ['AGENTS.md', 'start-local.sh', 'base-bump.yml'],
  });
  const COMMENTS = 'api --paginate repos/o/r/issues/12/comments --jq *';
  const EARLIER = 'api repos/o/r/issues/comments/555 --jq .body';
  /**
   * The step in a folder of its own. `earlier` is the text of this workflow's earlier comment,
   * 555, which gh gives (none by default); `comments` and `body` override gh's answers to the
   * listing and the read; `baseLock` and `lock` are the base branch's lock Set up the new version
   * left and the final lock; `kit` the kit's templates at NEW, a name → its text, written to
   * .niord/kit/templates/ (KIT_FILES by default, none for `{}`), never the country's copies.
   */
  const build = ({
    report,
    md = MD,
    drift = [],
    releases = [],
    shadowed,
    env = {},
    earlier,
    comments = { out: earlier === undefined ? '' : '555\n' },
    body = { out: earlier },
    baseLock,
    lock,
    kit = KIT_FILES,
  } = {}) =>
    inDir('comment', {}, (dir) => {
      const temp = path.join(dir, 'temp');
      const files = {};
      for (const [name, text] of Object.entries(kit)) files[`.niord/kit/templates/${name}`] = text;
      if (report !== undefined) {
        files['fe/.niord/niord-upgrade-report.json'] = `${JSON.stringify(report, null, 2)}\n`;
        files['fe/.niord/niord-upgrade-report.md'] = md;
      }
      files['temp/work/drift.txt'] = drift.map((line) => `${line}\n`).join('');
      files['temp/work/releases.txt'] = releases.map((line) => `${line}\n`).join('');
      if (shadowed !== undefined)
        files['temp/work/shadowed.txt'] = shadowed.map((row) => `${row.join('\t')}\n`).join('');
      if (baseLock !== undefined) files['temp/work/base-lock.json'] = baseLock;
      if (lock !== undefined) files['fe/niord-pins.lock.json'] = lock;
      write(dir, files);
      const result = runBash(runOf('compute', 'Build the comment'), {
        cwd: dir,
        env: {
          ...ENV,
          NEW,
          OLD,
          PR: '12',
          RECORD: 'made',
          SETUP_STATUS: '0',
          STUB: 'kept',
          UPDATE_PINS: '',
          BOOTSTRAP: 'both',
          FE_SHA256: FE_SHA,
          KIT_SHA256: KIT_SHA,
          REPO,
          GH_TOKEN: 't',
          RUNNER_TEMP: slashes(temp),
          ...env,
        },
        fakes: {
          gh: [
            { match: COMMENTS, ...comments },
            { match: EARLIER, ...body },
          ],
        },
      });
      return {
        ...result,
        comment: readIn(temp, 'base-bump/comment.md') ?? '',
        labels: readIn(temp, 'base-bump/labels.txt'),
      };
    });

  it('builds its fixture from exactly the keys the report data holds', () => {
    assert.deepEqual(Object.keys(data()), DATA_KEYS);
    assert.deepEqual(Object.keys(FULL), DATA_KEYS);
  });

  it('lists each human action as a box, from the report, the workflow files and the bootstrap', () => {
    const result = build({ report: FULL, drift: ['pack.yml'], releases: ['2026-10-30-harbours'] });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const lines = result.comment.split('\n');
    assert.equal(lines[0], MARKER);
    assert.equal(lines[1], '## Base bump: 4.0.97 to 4.0.110');
    assert.deepEqual(boxesOf(result.comment), [
      '- [ ] `country-kit/API-CHANGES.md`, `4.0.105: roleGuard takes its roles from data`: replace `roleGuard(roles)` by `roleGuard`.',
      '- [ ] `country-kit/API-CHANGES.md`, `4.0.105: roleGuard takes its roles from data`: run the check once.',
      `- [ ] Merge \`.niord/kit/templates/AGENTS.md\` into \`AGENTS.md\`: the agent template changed since 4.0.97.${mark('AGENTS.md')}`,
      `- [ ] Copy \`.niord/kit/templates/pack.yml\` over \`.github/workflows/pack.yml\` and push it: a workflow file, which this workflow's token may not push.${mark('pack.yml')}`,
      '- [ ] Decide on `releases/2026-10-30-harbours/`, new in the bootstrap of 4.0.110: whether your bootstrap and each deployment take it (a sysadmin imports it).',
    ]);
    assert.deepEqual(result.outputs, { unticked: '5', security: 'true' });
    assert.equal(result.labels, 'base bump\nsecurity\n');
    assert.ok(
      result.comment.includes(
        `Pinned in \`fe/niord-pins.lock.json\` by this workflow's commit: \`niord-fe-src-4.0.110.zip\` sha256 \`${FE_SHA}\` and \`niord-country-kit-4.0.110.zip\` sha256 \`${KIT_SHA}\`, downloaded from the registry and equal to the two lines of release v4.0.110's notes in NiordOrg/niord4.`,
      ),
    );
    assert.ok(result.comment.includes("\n- `start-local.sh` is the kit's launcher of 4.0.110.\n"));
    assert.ok(
      result.comment.includes(
        '\nTick each box when it is done: the check `niord/upgrade-actions` turns green when none is left.\n',
      ),
    );
    const human = result.comment.indexOf('\n## Human actions\n');
    const ci = result.comment.indexOf('\n## Checked by CI\n');
    assert.ok(human > 0 && ci > human);
    assert.match(result.comment.slice(ci), /Set-up of 4\.0\.110 here: passed/);
    assert.match(result.comment.slice(ci), /`CountryDefaultsChecksTest`/);
    assert.ok(
      result.comment.endsWith(
        `<details><summary>The upgrade report (<code>fe/.niord/niord-upgrade-report.md</code>)</summary>\n\n${MD.trimEnd()}\n\n</details>\n`,
      ),
    );
  });

  it('says there is no human action when none is left', () => {
    const result = build({ report: data() });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), []);
    assert.ok(
      result.comment.includes('\n## Human actions\n\nNo human action: review, merge, deploy.\n'),
    );
    assert.ok(result.comment.includes('\nThe check `niord/upgrade-actions` is green.\n'));
    assert.deepEqual(result.outputs, { unticked: '0', security: 'false' });
    assert.equal(result.labels, 'base bump\n');
  });

  it('asks for each changed template the workflow does not copy, and names a new one', () => {
    const result = build({
      report: data({
        templatesChanged: [
          'image.yml',
          'image-backend-job.yml',
          'image-frontend-job.yml',
          'pack.yml',
          'new-thing.md',
        ],
      }),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const boxes = boxesOf(result.comment);
    assert.equal(boxes.length, 4);
    assert.match(
      boxes[0],
      /^- \[ \] Merge `\.niord\/kit\/templates\/image\.yml` into `\.github\/workflows\/image\.yml` and push it: /,
    );
    assert.match(
      boxes[1],
      /^- \[ \] Merge `\.niord\/kit\/templates\/image-backend-job\.yml` into the backend job of `\.github\/workflows\/image\.yml` and push it: /,
    );
    assert.match(
      boxes[2],
      /^- \[ \] Merge `\.niord\/kit\/templates\/image-frontend-job\.yml` into the frontend job of `\.github\/workflows\/image\.yml` and push it: /,
    );
    ['image.yml', 'image-backend-job.yml', 'image-frontend-job.yml'].forEach((name, i) =>
      assert.ok(boxes[i].endsWith(mark(name)), boxes[i]),
    );
    assert.equal(
      boxes[3],
      `- [ ] Look at \`.niord/kit/templates/new-thing.md\`: it changed since 4.0.97.${mark('new-thing.md')}`,
    );
  });

  it('asks for a comparison of every template when the changed ones could not be listed, and says why', () => {
    const result = build({
      report: data({ templatesChanged: null, templatesUnavailable: 'no set-up record of 4.0.97' }),
      env: { RECORD: 'missing' },
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), [
      `- [ ] Compare each template of \`.niord/kit/templates/\` with the country's copy (\`AGENTS.md\`, \`.github/workflows/image.yml\`): the templates changed since 4.0.97 could not be listed (no set-up record of 4.0.97).${folderMark()}`,
    ]);
    assert.ok(
      result.comment.includes(
        '\n- No set-up record of 4.0.97 could be made (set-up at the base branch failed), so the report compares with 4.0.97 by version.\n',
      ),
    );
  });

  it('names every other changed template in one line above the boxes, never a box', () => {
    const result = build({
      report: data({ otherTemplatesChanged: ['CODEOWNERS', 'be/pom.xml.tmpl'] }),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const line =
      '\nOther templates changed since 4.0.97, in `.niord/kit/templates/`: `CODEOWNERS`, `be/pom.xml.tmpl` (no action: a country that keeps its own copy compares it).\n';
    const at = result.comment.indexOf(line);
    assert.ok(at > 0, result.comment);
    assert.ok(at < result.comment.indexOf('\n## Human actions\n'));
    assert.deepEqual(boxesOf(result.comment), []);
    assert.deepEqual(boxesOf(result.comment), boxesOf(build({ report: data() }).comment));
    assert.deepEqual(result.outputs, { unticked: '0', security: 'false' });
    assert.ok(
      result.comment.includes('\n## Human actions\n\nNo human action: review, merge, deploy.\n'),
    );
    assert.ok(!result.comment.includes('niord-base-bump-template: CODEOWNERS'));
    assert.ok(!result.comment.includes('niord-base-bump-template: be/'));
    const recount = recountWith(result.comment);
    assert.equal(recount.status, 0, recount.stdout + recount.stderr);
    assert.ok(statusCall(recount).includes('state=success'), statusCall(recount));
  });

  it('leaves the boxes of the tracked templates as they were beside the line', () => {
    const result = build({
      report: data({ templatesChanged: ['AGENTS.md'], otherTemplatesChanged: ['CODEOWNERS'] }),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), [
      `- [ ] Merge \`.niord/kit/templates/AGENTS.md\` into \`AGENTS.md\`: the agent template changed since 4.0.97.${mark('AGENTS.md')}`,
    ]);
    assert.equal(result.outputs.unticked, '1');
    const recount = recountWith(result.comment.replace('- [ ] ', '- [x] '));
    assert.equal(recount.status, 0, recount.stdout + recount.stderr);
    assert.ok(statusCall(recount).includes('state=success'), statusCall(recount));
  });

  it('says the other templates are not available when the comparison was not possible', () => {
    const result = build({
      report: data({
        templatesChanged: null,
        templatesUnavailable: 'no set-up record of 4.0.97',
        otherTemplatesChanged: null,
      }),
      env: { RECORD: 'missing' },
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const at = result.comment.indexOf(
      '\nOther templates changed since 4.0.97: not available (no set-up record of 4.0.97).\n',
    );
    assert.ok(at > 0, result.comment);
    assert.ok(at < result.comment.indexOf('\n## Human actions\n'));
    assert.deepEqual(boxesOf(result.comment), [
      `- [ ] Compare each template of \`.niord/kit/templates/\` with the country's copy (\`AGENTS.md\`, \`.github/workflows/image.yml\`): the templates changed since 4.0.97 could not be listed (no set-up record of 4.0.97).${folderMark()}`,
    ]);
    assert.equal(result.outputs.unticked, '1');
  });

  it('writes no such line from a report without the field, or with none changed', () => {
    const without = data();
    delete without.otherTemplatesChanged;
    for (const report of [without, data()]) {
      const result = build({ report });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      const ci = result.comment.indexOf('\n## Checked by CI\n');
      assert.ok(ci > 0);
      assert.doesNotMatch(result.comment.slice(0, ci), /Other templates changed/);
    }
  });

  it('asks for set-up by hand when it wrote no report, or one of another version', () => {
    for (const report of [undefined, data({ to: OLD }), data({ format: 2 })]) {
      const result = build({ report, env: { SETUP_STATUS: '1' } });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.deepEqual(boxesOf(result.comment), [
        "- [ ] Set-up of 4.0.110 wrote no upgrade report (see the Base bump run's log): run `node setup.mjs` in `fe/` on this branch and do what `.niord/niord-upgrade-report.md` asks.",
      ]);
      assert.match(
        result.comment,
        /Set-up of 4\.0\.110 here: failed \(exit 1\): CI fails until the report's To do is done/,
      );
      assert.ok(
        result.comment.includes(
          '\n\nSet-up wrote no upgrade report: see the Base bump run.\n\n</details>\n',
        ),
      );
      assert.equal(result.outputs.unticked, '1');
    }
  });

  it('says what it pinned, the stub, the moved pins and the bootstrap it could not fetch', () => {
    const result = build({
      report: data(),
      releases: ['2026-10-21-templates-from-database', '2026-10-30-harbours'],
      env: { STUB: 'copied', UPDATE_PINS: 'da kl', BOOTSTRAP: 'no-old' },
      baseLock: lockText({ version: OLD, sha256: FE_SHA, packs: { da: DA_PIN, kl: KL_PIN } }),
      lock: lockText({
        version: NEW,
        sha256: FE_SHA,
        kit: { sha256: KIT_SHA },
        packs: { da: DA_PIN3, kl: KL_PIN6 },
      }),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.ok(
      result.comment.includes(
        `Pinned in \`fe/niord-pins.lock.json\` by this workflow's commit: \`niord-fe-src-4.0.110.zip\` sha256 \`${FE_SHA}\` and \`niord-country-kit-4.0.110.zip\` sha256 \`${KIT_SHA}\`, downloaded from the registry and equal to the two lines of release v4.0.110's notes in NiordOrg/niord4.`,
      ),
    );
    assert.ok(
      result.comment.includes(
        "\n- `fe/setup.mjs` is the kit's new stub, `.niord/kit/templates/setup.mjs`.\n",
      ),
    );
    for (const [lang, from, to] of [
      ['da', DA_PIN, DA_PIN3],
      ['kl', KL_PIN, KL_PIN6],
    ])
      assert.ok(
        result.comment.includes(
          `\n- The pinned pack ${lang} moved from \`${from}\` to \`${to}\`, its home's newest pack (\`node setup.mjs --update-pins\`): compare the digest with the home's \`pack-releases.md\`.\n`,
        ),
        result.comment,
      );
    // A line, not a box: set-up checks the new pack in CI.
    assert.equal(boxesOf(result.comment).length, 2);
    assert.ok(boxesOf(result.comment).every((box) => !box.includes('pack-releases')));
    const note = result.comment.indexOf(
      '\nThe bootstrap of 4.0.97 could not be fetched: every release folder of 4.0.110 is listed.\n',
    );
    assert.ok(note > result.comment.indexOf('`releases/2026-10-30-harbours/`'));
    assert.ok(note < result.comment.indexOf('\nTick each box'));
  });

  it('asks for each template the country replaces whose base product file changed, after the release folders', () => {
    const result = build({
      report: data(),
      releases: ['2026-10-30-harbours'],
      shadowed: [
        ['messages/a.ftl', 'messages/a.ftl', 'changed'],
        ['messages/a_kl.ftl', 'messages/a.ftl', 'changed'],
        ['messages/gone.ftl', 'messages/gone.ftl', 'removed'],
        ['messages/new.ftl', 'messages/new.ftl', 'added'],
        ['messages/x_da.ftl', 'messages/x.ftl', 'removed'],
      ],
      env: { SHADOWS: 'listed' },
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const tail =
      "so no change of the base product's reaches it; carry any change into your file and ship it in a release folder of your own.";
    assert.deepEqual(boxesOf(result.comment), [
      '- [ ] Decide on `releases/2026-10-30-harbours/`, new in the bootstrap of 4.0.110: whether your bootstrap and each deployment take it (a sysadmin imports it).',
      `- [ ] Compare \`be/bootstrap/templates/messages/a.ftl\` with \`templates/messages/a.ftl\` of the bootstrap of 4.0.110: the base product changed it since 4.0.97, and your copy replaces it, ${tail}`,
      `- [ ] Compare \`be/bootstrap/templates/messages/a_kl.ftl\` with \`templates/messages/a.ftl\` of the bootstrap of 4.0.110: the base product changed it since 4.0.97, and your language variant replaces it in its language, ${tail}`,
      '- [ ] Look at `be/bootstrap/templates/messages/gone.ftl`: the bootstrap of 4.0.110 no longer has `templates/messages/gone.ftl`, which it replaced, so it is now a template of your own.',
      `- [ ] Compare \`be/bootstrap/templates/messages/new.ftl\` with \`templates/messages/new.ftl\` of the bootstrap of 4.0.110: the base product added it since 4.0.97, and your copy replaces it, ${tail}`,
      '- [ ] Look at `be/bootstrap/templates/messages/x_da.ftl`: the bootstrap of 4.0.110 no longer has `templates/messages/x.ftl`, which it is a language variant of, so it is now a template of your own.',
    ]);
    assert.equal(result.outputs.unticked, '6');
  });

  it('says a template it could not compare is not known to have changed, and asks for a comparison by hand when none could be made', () => {
    const notCompared = build({
      report: data(),
      shadowed: [['tmpl/wreck.ftl', 'tmpl/wreck.ftl', 'not-compared']],
      env: { SHADOWS: 'listed', BOOTSTRAP: 'no-old' },
    });
    assert.equal(notCompared.status, 0, notCompared.stdout + notCompared.stderr);
    assert.deepEqual(boxesOf(notCompared.comment), [
      "- [ ] Compare `be/bootstrap/templates/tmpl/wreck.ftl` with `templates/tmpl/wreck.ftl` of the bootstrap of 4.0.110: the bootstrap of 4.0.97 could not be fetched, so whether it changed is not known, and your copy replaces it, so no change of the base product's reaches it; carry any change into your file and ship it in a release folder of your own.",
    ]);
    const byHand = build({ report: data(), env: { SHADOWS: 'by-hand', BOOTSTRAP: 'no-new' } });
    assert.equal(byHand.status, 0, byHand.stdout + byHand.stderr);
    assert.deepEqual(boxesOf(byHand.comment), [
      "- [ ] Compare the `releases/` folders of the bootstrap of 4.0.110 with 4.0.97's by hand: its zip could not be fetched here.",
      "- [ ] Compare each file of `be/bootstrap/templates/` with the bootstrap of 4.0.110 by hand, the same path and the template a language variant (`x_<ll>.ftl`) is of: the two bootstraps' templates could not be compared here.",
    ]);
  });

  it('asks nothing about the bootstrap templates when the country replaces none that changed', () => {
    for (const env of [{ SHADOWS: 'none' }, { SHADOWS: 'listed' }]) {
      const result = build({ report: data(), shadowed: [], env });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.deepEqual(boxesOf(result.comment), []);
      assert.doesNotMatch(result.comment, /be\/bootstrap\/templates/);
    }
  });

  it('says a pack the base branch did not pin moved from no pin', () => {
    const result = build({
      report: data(),
      env: { UPDATE_PINS: 'da' },
      baseLock: lockText({ version: OLD, sha256: FE_SHA }),
      lock: lockText({ version: NEW, sha256: FE_SHA, packs: { da: DA_PIN3 } }),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.ok(
      result.comment.includes(`\n- The pinned pack da moved from no pin to \`${DA_PIN3}\`, its `),
      result.comment,
    );
  });

  // The earlier comment as GitHub may give it after an edit in the browser: CRLF.
  const crlf = (lines) => `${lines.join('\r\n')}\r\n`;
  // The agent template's box as it was first written, without the marker, and as it is written now.
  const AGENTS_TEXT =
    'Merge `.niord/kit/templates/AGENTS.md` into `AGENTS.md`: the agent template changed since 4.0.97.';
  const AGENTS_ITEM = `${AGENTS_TEXT}${mark('AGENTS.md')}`;
  const ACTION_ITEM =
    '`country-kit/API-CHANGES.md`, `4.0.105: roleGuard takes its roles from data`: replace `roleGuard(roles)` by `roleGuard`.';
  const CHECK_ITEM =
    '`country-kit/API-CHANGES.md`, `4.0.105: roleGuard takes its roles from data`: run the check once.';
  const DRIFT_ITEM = `Copy \`.niord/kit/templates/pack.yml\` over \`.github/workflows/pack.yml\` and push it: a workflow file, which this workflow's token may not push.${mark('pack.yml')}`;
  const decideItem = (version) =>
    `Decide on \`releases/2026-10-30-harbours/\`, new in the bootstrap of ${version}: whether your bootstrap and each deployment take it (a sysadmin imports it).`;

  it('keeps the tick of a box whose text and template did not change, and asks again for one whose text did', () => {
    const earlier = crlf([
      MARKER,
      '## Base bump: 4.0.97 to 4.0.105',
      '',
      '## Human actions',
      '',
      `- [x] ${ACTION_ITEM}`,
      `- [ ] ${CHECK_ITEM}`,
      `- [x] ${AGENTS_ITEM}`,
      `- [x] ${DRIFT_ITEM}`,
      `- [x] ${decideItem('4.0.105')}`,
      '',
      'Tick each box when it is done: the check `niord/upgrade-actions` turns green when none is left.',
    ]);
    const result = build({
      report: FULL,
      drift: ['pack.yml'],
      releases: ['2026-10-30-harbours'],
      earlier,
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), [
      `- [x] ${ACTION_ITEM}`,
      `- [ ] ${CHECK_ITEM}`,
      `- [x] ${AGENTS_ITEM}`,
      // A workflow file still listed differs from the kit's copy again: asked again.
      `- [ ] ${DRIFT_ITEM}`,
      `- [ ] ${decideItem('4.0.110')}`,
    ]);
    assert.deepEqual(result.outputs, { unticked: '3', security: 'true' });
    assert.deepEqual(result.calls, [
      `gh api --paginate repos/o/r/issues/12/comments --jq .[] | select(.user.login == "github-actions[bot]" and (.body | contains("${MARKER}"))) | .id`,
      'gh api repos/o/r/issues/comments/555 --jq .body',
    ]);
  });

  it('carries no tick from outside the first Human actions section', () => {
    const earlier = crlf([
      MARKER,
      '## Base bump: 4.0.97 to 4.0.110',
      '',
      `- [x] ${AGENTS_ITEM}`,
      '',
      '## Human actions',
      '',
      `- [ ] ${ACTION_ITEM}`,
      '',
      '## Checked by CI',
      '',
      `- [x] ${CHECK_ITEM}`,
      '',
      '<details><summary>The upgrade report</summary>',
      '',
      '## Human actions',
      '',
      `- [x] ${decideItem('4.0.110')}`,
      '',
      '</details>',
    ]);
    const result = build({ report: FULL, releases: ['2026-10-30-harbours'], earlier });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.ok(
      boxesOf(result.comment).every((box) => box.startsWith('- [ ] ')),
      result.comment,
    );
    assert.equal(result.outputs.unticked, '4');
  });

  // The kit's image template at 4.0.105, and again changed at 4.0.110.
  const IMAGE_105 = '# image.yml of 4.0.105\n';
  const IMAGE_110 = '# image.yml of 4.0.110, changed again\n';
  const IMAGE_TEXT =
    "Merge `.niord/kit/templates/image.yml` into `.github/workflows/image.yml` and push it: the template changed since 4.0.97, and this workflow's token may not push a workflow file.";
  // The comment of 4.0.105 with both merges ticked, the image template as it was at 4.0.105.
  const IMAGE_BOXES_105 = [
    `- [x] ${IMAGE_TEXT}${mark('image.yml', { 'image.yml': IMAGE_105 })}`,
    `- [x] ${AGENTS_ITEM}`,
  ];
  const TICKED_AT_105 = crlf([
    MARKER,
    '## Base bump: 4.0.97 to 4.0.105',
    '',
    '## Human actions',
    '',
    ...IMAGE_BOXES_105,
    '',
  ]);

  it('asks again for a ticked template box whose template changed at a later version, and counts it', () => {
    // The review's case as the comment written before the markers had it: the image box ticked
    // at 4.0.105, its words the same at 4.0.110 although the template changed again.
    const before = build({
      report: data({ templatesChanged: ['image.yml'] }),
      kit: { ...KIT_FILES, 'image.yml': IMAGE_110 },
      earlier: crlf([
        MARKER,
        '## Base bump: 4.0.97 to 4.0.105',
        '',
        '## Human actions',
        '',
        `- [x] ${IMAGE_TEXT}`,
        '',
      ]),
    });
    assert.equal(before.status, 0, before.stdout + before.stderr);
    // The count first: the review found it 0, the check green with the update outstanding.
    assert.equal(before.outputs.unticked, '1', before.comment);
    assert.deepEqual(boxesOf(before.comment), [
      `- [ ] ${IMAGE_TEXT}${mark('image.yml', { 'image.yml': IMAGE_110 })}`,
    ]);
    // The same with the marker the comment of 4.0.105 carries now.
    const result = build({
      report: data({ templatesChanged: ['image.yml', 'AGENTS.md'] }),
      kit: { ...KIT_FILES, 'image.yml': IMAGE_110 },
      earlier: TICKED_AT_105,
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), [
      `- [ ] ${IMAGE_TEXT}${mark('image.yml', { 'image.yml': IMAGE_110 })}`,
      // The agent template is the same at both versions.
      `- [x] ${AGENTS_ITEM}`,
    ]);
    assert.equal(result.outputs.unticked, '1');
  });

  it('keeps the tick of a template box whose template is the same at the later version', () => {
    const result = build({
      report: data({ templatesChanged: ['image.yml', 'AGENTS.md'] }),
      kit: { ...KIT_FILES, 'image.yml': IMAGE_105 },
      earlier: TICKED_AT_105,
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), IMAGE_BOXES_105);
    assert.equal(result.outputs.unticked, '0');
  });

  it('builds at 4.0.105 the boxes of the earlier comment the two cases above read, unticked', () => {
    const result = build({
      report: data({ to: '4.0.105', templatesChanged: ['image.yml', 'AGENTS.md'] }),
      env: { NEW: '4.0.105' },
      kit: { ...KIT_FILES, 'image.yml': IMAGE_105 },
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(
      boxesOf(result.comment),
      IMAGE_BOXES_105.map((box) => box.replace('- [x] ', '- [ ] ')),
    );
  });

  it('drops every template tick of an earlier comment without markers, and keeps the others', () => {
    const earlier = crlf([
      MARKER,
      '## Base bump: 4.0.97 to 4.0.105',
      '',
      '## Human actions',
      '',
      `- [x] ${ACTION_ITEM}`,
      `- [x] ${AGENTS_TEXT}`,
      `- [x] ${decideItem('4.0.110')}`,
      '',
      'Tick each box when it is done: the check `niord/upgrade-actions` turns green when none is left.',
    ]);
    const result = build({ report: FULL, releases: ['2026-10-30-harbours'], earlier });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), [
      `- [x] ${ACTION_ITEM}`,
      `- [ ] ${CHECK_ITEM}`,
      `- [ ] ${AGENTS_ITEM}`,
      `- [x] ${decideItem('4.0.110')}`,
    ]);
    assert.equal(result.outputs.unticked, '2');
  });

  it('writes one hidden marker at the end of each template box, which no count takes for a box', () => {
    const names = [
      'AGENTS.md',
      'image.yml',
      'image-backend-job.yml',
      'image-frontend-job.yml',
      'ci.yml',
      'pack.yml',
      'base-bump.yml',
    ];
    const result = build({
      report: data({ templatesChanged: names.slice(0, 5) }),
      drift: ['pack.yml', 'base-bump.yml'],
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const { comment } = result;
    const boxes = boxesOf(comment);
    assert.equal(boxes.length, names.length, comment);
    boxes.forEach((box, i) => {
      assert.equal(box.split('<!--').length - 1, 1, box);
      const found = TEMPLATE_MARK_RE.exec(box.slice(box.lastIndexOf(' <!--') + 1));
      assert.deepEqual(found?.slice(1), [names[i], sha256(KIT_FILES[names[i]])], box);
    });
    // Never a line of its own, and the comment's own marker once, its first line.
    assert.ok(
      !comment.split('\n').some((line) => line.startsWith('<!-- niord-base-bump-template')),
    );
    assert.equal(comment.split('\n')[0], MARKER);
    assert.equal(comment.split(MARKER).length - 1, 1);
    assert.equal(result.outputs.unticked, String(boxes.length));
    const unticked = recountWith(comment);
    assert.equal(unticked.status, 0, unticked.stdout + unticked.stderr);
    assert.match(
      statusCall(unticked),
      / -f state=failure .* -f description=Base bump: 7 human action\(s\) unticked -f /,
    );
    const done = recountWith(comment.replaceAll('- [ ] ', '- [x] '));
    assert.equal(done.status, 0, done.stdout + done.stderr);
    assert.match(
      statusCall(done),
      / -f state=success .* -f description=Base bump: no human action left -f /,
    );
  });

  it("keeps the tick of the comparison of every template only while the kit's templates are the same", () => {
    const unlisted = {
      report: data({ templatesChanged: null, templatesUnavailable: 'no set-up record of 4.0.97' }),
      env: { RECORD: 'missing' },
    };
    const first = build(unlisted);
    assert.equal(first.status, 0, first.stdout + first.stderr);
    const earlier = first.comment.replace(
      '- [ ] Compare each template',
      '- [x] Compare each template',
    );
    const same = build({ ...unlisted, earlier });
    assert.equal(same.status, 0, same.stdout + same.stderr);
    assert.equal(boxesOf(same.comment).length, 1);
    assert.match(boxesOf(same.comment)[0], /^- \[x\] Compare each template /);
    assert.ok(boxesOf(same.comment)[0].endsWith(folderMark()), same.comment);
    assert.equal(same.outputs.unticked, '0');
    const changed = { ...KIT_FILES, 'ci.yml': '# ci.yml of 4.0.110, changed\n' };
    const other = build({ ...unlisted, kit: changed, earlier });
    assert.equal(other.status, 0, other.stdout + other.stderr);
    assert.match(boxesOf(other.comment)[0], /^- \[ \] Compare each template /);
    assert.ok(boxesOf(other.comment)[0].endsWith(folderMark(changed)), other.comment);
    assert.equal(other.outputs.unticked, '1');
  });

  it('asks again for a template the kit no longer has, writing no marker', () => {
    const LOOK = 'Look at `.niord/kit/templates/ci.yml`: it changed since 4.0.97.';
    const earlier = crlf([
      MARKER,
      '## Base bump: 4.0.97 to 4.0.105',
      '',
      '## Human actions',
      '',
      `- [x] ${LOOK}`,
      '',
    ]);
    const kit = Object.fromEntries(Object.entries(KIT_FILES).filter(([name]) => name !== 'ci.yml'));
    const result = build({ report: data({ templatesChanged: ['ci.yml'] }), kit, earlier });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(boxesOf(result.comment), [`- [ ] ${LOOK}`]);
    assert.equal(result.outputs.unticked, '1');
  });

  for (const [what, answers, message] of [
    [
      'the comments cannot be listed',
      { comments: { err: 'HTTP 502\n', exit: 1 } },
      /::warning::The pull request's comments could not be read: every box starts unticked\./,
    ],
    [
      'the earlier comment cannot be read',
      { earlier: `${MARKER}\n`, body: { err: 'HTTP 502\n', exit: 1 } },
      /::warning::The earlier Base bump comment could not be read: every box starts unticked\./,
    ],
  ])
    it(`starts every box unticked, warning, when ${what}`, () => {
      const result = build({ report: FULL, ...answers });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.match(result.stdout, message);
      assert.equal(boxesOf(result.comment).length, 3);
      assert.ok(boxesOf(result.comment).every((box) => box.startsWith('- [ ] ')));
      assert.equal(result.outputs.unticked, '3');
    });

  it('asks gh for the comments only, when there is no earlier comment', () => {
    const result = build({ report: data() });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.calls.length, 1);
    assert.ok(result.calls[0].includes(`contains("${MARKER}")`), result.calls[0]);
  });

  it("asks gh nothing when the pull request's number is not a number", () => {
    const result = build({ report: data(), env: { PR: '' } });
    assert.equal(result.status, 1);
    assert.match(result.stdout, /::error::No pull request number \(''\)\./);
    assert.deepEqual(result.calls, []);
  });

  it("asks for the bootstrap's release folders by hand when NEW's zip could not be fetched", () => {
    const result = build({ report: data(), env: { BOOTSTRAP: 'no-new' } });
    assert.deepEqual(boxesOf(result.comment), [
      "- [ ] Compare the `releases/` folders of the bootstrap of 4.0.110 with 4.0.97's by hand: its zip could not be fetched here.",
    ]);
  });

  it('cuts a report too long for a comment, pointing to the artifact', () => {
    const md = `${'a line of the upgrade report, long enough\n'.repeat(1700)}`;
    assert.ok(md.length > 70000);
    const result = build({ report: FULL, md });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.ok(result.comment.length <= 60000, String(result.comment.length));
    assert.ok(
      result.comment.endsWith(
        "\n\n… (cut: the whole report is niord-upgrade-report.md in the run's artifact base-bump)\n\n</details>\n",
      ),
    );
    assert.equal(boxesOf(result.comment).length, 3);
  });
});

/** The files set-up left in fe/ and at the root, which compute hands over. */
const SET_UP_FILES = {
  'fe/niord-pins.lock.json': lockText({ version: NEW, sha256: FE_SHA, kit: { sha256: KIT_SHA } }),
  'fe/setup.mjs': '// the stub\n',
  'start-local.sh': '#!/usr/bin/env bash\n',
  'fe/.niord/niord-upgrade-report.md': '# Upgrade report\n',
  'fe/.niord/niord-upgrade-report.json': '{"format":1}\n',
};

/** compute's hand-over in a folder of its own: `{ dir, temp, folder, sums }` to `fn`. */
function handedOver(fn) {
  return inDir(
    'handover',
    {
      ...SET_UP_FILES,
      'temp/base-bump/comment.md': `${MARKER}\n## Base bump\n`,
      'temp/base-bump/labels.txt': 'base bump\n',
    },
    (dir) => {
      const temp = slashes(path.join(dir, 'temp'));
      const result = runBash(runOf('compute', 'Hand over the files'), {
        cwd: dir,
        env: { RUNNER_TEMP: temp },
      });
      assert.equal(result.status, 0, result.stdout + result.stderr);
      return fn({
        dir,
        temp,
        folder: path.join(dir, 'temp', 'base-bump'),
        sums: result.outputs.sha256sums,
      });
    },
  );
}

describe('base-bump.yml: the hand-over and its check', () => {
  const verify = (temp, env) =>
    runBash(runOf('apply', 'Verify the files'), { env: { RUNNER_TEMP: temp, ...env } });

  it('hands over the files with their SHA256SUMS, and gives the SHA-256 of SHA256SUMS', () => {
    handedOver(({ temp, folder, sums }) => {
      const text = fs.readFileSync(path.join(folder, 'SHA256SUMS'));
      assert.equal(sums, sha256(text));
      assert.deepEqual(
        text
          .toString()
          .trim()
          .split('\n')
          .map((line) => line.slice(66)),
        [
          'niord-pins.lock.json',
          'setup.mjs',
          'start-local.sh',
          'niord-upgrade-report.md',
          'niord-upgrade-report.json',
          'comment.md',
          'labels.txt',
        ],
      );
      const check = runBash(
        'set -euo pipefail\ncd "$RUNNER_TEMP/base-bump"\nsha256sum -c --strict SHA256SUMS\n',
        {
          env: { RUNNER_TEMP: temp },
        },
      );
      assert.equal(check.status, 0, check.stdout + check.stderr);
    });
  });

  it("passes compute's files in apply", () => {
    handedOver(({ temp, sums }) => {
      const result = verify(temp, { NOTES: 'verified', SHA256SUMS: sums });
      assert.equal(result.status, 0, result.stdout + result.stderr);
    });
  });

  it('refuses a changed byte, another SHA256SUMS, a short SHA-256, unverified notes and a missing lock', () => {
    handedOver(({ temp, folder, sums }) => {
      for (const [env, message] of [
        [{ SHA256SUMS: sums.slice(1) }, /::error::compute gave no SHA-256 of SHA256SUMS\./],
        [{ SHA256SUMS: 'f'.repeat(64) }, /SHA256SUMS: FAILED/],
        [{ NOTES: 'maybe' }, /::error::compute did not verify the release notes \('maybe'\)\./],
        [{ NOTES: 'unreadable' }, /::error::compute did not verify the release notes/],
        [{ NOTES: '' }, /::error::compute did not verify the release notes/],
      ]) {
        const result = verify(temp, { NOTES: 'verified', SHA256SUMS: sums, ...env });
        assert.notEqual(result.status, 0, JSON.stringify(env));
        assert.match(result.stdout + result.stderr, message);
      }
      fs.appendFileSync(path.join(folder, 'comment.md'), 'x');
      const changed = verify(temp, { NOTES: 'verified', SHA256SUMS: sums });
      assert.notEqual(changed.status, 0);
      assert.match(changed.stdout + changed.stderr, /comment\.md: FAILED/);
    });
    // SHA256SUMS without the lock, and its own SHA-256: every listed file checks out.
    handedOver(({ temp, folder }) => {
      const file = path.join(folder, 'SHA256SUMS');
      const text = fs
        .readFileSync(file, 'utf8')
        .split('\n')
        .filter((line) => !line.endsWith(' niord-pins.lock.json'))
        .join('\n');
      fs.writeFileSync(file, text);
      fs.rmSync(path.join(folder, 'niord-pins.lock.json'));
      const result = verify(temp, { NOTES: 'verified', SHA256SUMS: sha256(text) });
      assert.equal(result.status, 1, result.stdout + result.stderr);
      assert.match(result.stdout, /::error::SHA256SUMS does not list niord-pins\.lock\.json\./);
    });
  });
});

describe('base-bump.yml: Commit and push the pins, in a real repository', () => {
  const BRANCH = 'base-bump/4.0.110';
  const STATUS = 'api -X POST repos/o/r/statuses/* -f state=pending *';
  let dir;
  let remote;
  let seed;
  let count = 0;
  // Repositories of the test's own: no user or system configuration (no signing, no hooks).
  const gitConfig = () => ({
    GIT_CONFIG_GLOBAL: slashes(path.join(dir, 'gitconfig')),
    GIT_CONFIG_NOSYSTEM: '1',
  });
  const git = (cwd, ...args) => {
    const inherited = Object.fromEntries(
      Object.entries(process.env).filter(([key]) => !key.startsWith('GIT_')),
    );
    const result = spawnSync('git', args, {
      cwd,
      encoding: 'utf8',
      env: {
        ...inherited,
        ...gitConfig(),
        GIT_AUTHOR_NAME: 'Niord test',
        GIT_AUTHOR_EMAIL: 'test@niord.invalid',
        GIT_COMMITTER_NAME: 'Niord test',
        GIT_COMMITTER_EMAIL: 'test@niord.invalid',
      },
    });
    assert.equal(result.status, 0, `git ${args.join(' ')}: ${result.stderr}`);
    return result.stdout.trim();
  };
  /** A new branch on the remote at the seed's commit, checked out detached in a clone of its own. */
  const branchClone = (branch) => {
    git(seed, 'push', '-q', 'origin', `HEAD:refs/heads/${branch}`);
    const work = path.join(dir, `work-${++count}`);
    git(dir, 'clone', '-q', slashes(remote), slashes(work));
    git(work, 'checkout', '-q', '--detach', `origin/${branch}`);
    return work;
  };
  /** apply's step in `work`, with compute's files in a folder of its own. */
  const push = (work, branch, files, env = {}, status = {}) => {
    const temp = path.join(dir, `temp-${++count}`);
    write(path.join(temp, 'base-bump'), files);
    return runBash(runOf('apply', 'Commit and push the pins'), {
      cwd: work,
      env: {
        ...ENV,
        ...gitConfig(),
        // The identity is the step's own, never the environment's.
        GIT_AUTHOR_NAME: undefined,
        GIT_AUTHOR_EMAIL: undefined,
        GIT_COMMITTER_NAME: undefined,
        GIT_COMMITTER_EMAIL: undefined,
        NEW,
        HEAD_REF: branch,
        HEAD_SHA: git(work, 'rev-parse', 'HEAD'),
        UPDATE_PINS: '',
        REPO,
        GH_TOKEN: 'token',
        RUNNER_TEMP: slashes(temp),
        ...env,
      },
      fakes: { gh: [{ match: STATUS, ...status }] },
    });
  };
  const PINS = {
    'niord-pins.lock.json': lockText({ version: NEW, sha256: FE_SHA, kit: { sha256: KIT_SHA } }),
    'setup.mjs': '// the new stub\n',
    'start-local.sh': '#!/usr/bin/env bash\n# the new launcher\n',
  };
  const remoteRef = (branch) => git(remote, 'rev-parse', `refs/heads/${branch}`);

  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-bump-git-'));
    fs.writeFileSync(path.join(dir, 'gitconfig'), '');
    remote = path.join(dir, 'remote.git');
    seed = path.join(dir, 'seed');
    git(dir, 'init', '-q', '--bare', slashes(remote));
    git(dir, 'init', '-q', slashes(seed));
    write(seed, {
      'be/pom.xml': '<project/>\n',
      'fe/niord-pins.lock.json': lockText({ version: OLD, sha256: 'e'.repeat(64) }),
      'fe/setup.mjs': '// the stub\n',
      'start-local.sh': '#!/usr/bin/env bash\n',
      'fe/other.txt': 'other\n',
    });
    git(seed, 'add', '-A');
    // A country's launcher is committed executable (init writes it with mode 755); the step's
    // chmod 755 must not show as a mode change on a runner whose git tracks modes.
    git(seed, 'update-index', '--chmod=+x', 'start-local.sh');
    git(seed, 'commit', '-q', '-m', 'Update the base product to 4.0.110');
    git(seed, 'remote', 'add', 'origin', slashes(remote));
  });
  after(() => fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3 }));

  it('commits the three files as github-actions[bot], pushes them and sets the status pending on the new head', () => {
    const branch = `${BRANCH}-a`;
    const work = branchClone(branch);
    fs.writeFileSync(path.join(work, 'fe', 'other.txt'), 'changed by set-up\n');
    const result = push(work, branch, PINS);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const sha = git(work, 'rev-parse', 'HEAD');
    assert.deepEqual(result.outputs, { pushed: 'true', sha });
    assert.equal(remoteRef(branch), sha);
    assert.equal(
      git(work, 'log', '-1', '--format=%an <%ae>%n%cn <%ce>'),
      `github-actions[bot] <${BOT}>\ngithub-actions[bot] <${BOT}>`,
    );
    assert.equal(
      git(work, 'log', '-1', '--format=%B'),
      'Pin 4.0.110: niord-fe-src and niord-country-kit by their sha256',
    );
    assert.deepEqual(git(work, 'show', '--name-only', '--format=', 'HEAD').split('\n'), [
      'fe/niord-pins.lock.json',
      'fe/setup.mjs',
      'start-local.sh',
    ]);
    assert.equal(git(work, 'show', 'HEAD:fe/setup.mjs'), '// the new stub');
    assert.deepEqual(result.calls, [
      `gh api -X POST repos/o/r/statuses/${sha} -f state=pending -f context=niord/upgrade-actions -f description=Base bump: finishing`,
    ]);
  });

  it('succeeds with the push done when the pending status cannot be set, warning', () => {
    const branch = `${BRANCH}-f`;
    const work = branchClone(branch);
    const result = push(work, branch, PINS, {}, { err: 'HTTP 502: Bad Gateway\n', exit: 1 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const sha = git(work, 'rev-parse', 'HEAD');
    assert.deepEqual(result.outputs, { pushed: 'true', sha });
    assert.equal(remoteRef(branch), sha);
    assert.equal(result.calls.length, 1);
    assert.match(
      result.stdout,
      new RegExp(
        `::warning::The pending status could not be set on ${sha}; the last step sets the final one\\.`,
      ),
    );
  });

  it('names the moved pins in the commit body', () => {
    const branch = `${BRANCH}-b`;
    const work = branchClone(branch);
    const result = push(work, branch, PINS, { UPDATE_PINS: 'da kl' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(
      git(work, 'log', '-1', '--format=%b'),
      "With the pinned packs that differ from the base branch: da kl, each its home's newest pack.",
    );
  });

  it('fails when the branch moved meanwhile, setting no status', () => {
    const branch = `${BRANCH}-c`;
    const work = branchClone(branch);
    const other = branchClone(`${BRANCH}-c`);
    fs.writeFileSync(path.join(other, 'be', 'pom.xml'), '<project>moved</project>\n');
    git(other, 'commit', '-q', '-am', 'The app moved the branch');
    git(other, 'push', '-q', 'origin', `HEAD:refs/heads/${branch}`);
    const moved = remoteRef(branch);
    const result = push(work, branch, PINS);
    assert.notEqual(result.status, 0);
    assert.equal(result.outputs.pushed, undefined);
    assert.equal(remoteRef(branch), moved);
    assert.deepEqual(result.calls, []);
  });

  it('pushes nothing when the branch holds the files already', () => {
    const branch = `${BRANCH}-d`;
    const work = branchClone(branch);
    const head = git(work, 'rev-parse', 'HEAD');
    const result = push(work, branch, {
      'niord-pins.lock.json': fs.readFileSync(
        path.join(work, 'fe', 'niord-pins.lock.json'),
        'utf8',
      ),
      'setup.mjs': fs.readFileSync(path.join(work, 'fe', 'setup.mjs'), 'utf8'),
      'start-local.sh': fs.readFileSync(path.join(work, 'start-local.sh'), 'utf8'),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.outputs, { pushed: 'false', sha: head });
    assert.equal(remoteRef(branch), head);
    assert.deepEqual(result.calls, []);
  });

  it('refuses a branch outside base-bump/, a renovate/ one included, before touching anything', () => {
    const branch = `${BRANCH}-e`;
    const work = branchClone(branch);
    for (const ref of ['main', 'renovate/org.niord-niord-parent-4.x']) {
      const result = push(work, branch, PINS, { HEAD_REF: ref });
      assert.equal(result.status, 1, ref);
      assert.ok(
        result.stdout.includes(`::error::compute gave no base-bump/ branch ('${ref}').`),
        result.stdout,
      );
      assert.equal(git(work, 'status', '--porcelain'), '');
      assert.deepEqual(result.calls, []);
    }
  });
});

describe('base-bump.yml: Run CI on the new head', () => {
  const ci = (current) =>
    runBash(runOf('apply', 'Run CI on the new head'), {
      env: { HEAD_REF: 'base-bump/x', SHA: PUSHED, REPO, GH_TOKEN: 'token' },
      fakes: {
        gh: [
          {
            match:
              'run list --repo o/r --workflow ci.yml --branch base-bump/x --status in_progress --json databaseId --jq .[].databaseId',
            out: '101\n',
          },
          {
            match:
              'run list --repo o/r --workflow ci.yml --branch base-bump/x --status queued --json databaseId --jq .[].databaseId',
            out: '102\n103\n',
          },
          { match: 'run cancel * --repo o/r' },
          {
            match: 'api repos/o/r/git/ref/heads/base-bump/x --jq .object.sha',
            out: `${current}\n`,
          },
          { match: 'workflow run ci.yml --repo o/r --ref base-bump/x' },
        ],
      },
    });
  const CANCELS = [
    'gh run list --repo o/r --workflow ci.yml --branch base-bump/x --status in_progress --json databaseId --jq .[].databaseId',
    'gh run cancel 101 --repo o/r',
    'gh run list --repo o/r --workflow ci.yml --branch base-bump/x --status queued --json databaseId --jq .[].databaseId',
    'gh run cancel 102 --repo o/r',
    'gh run cancel 103 --repo o/r',
    'gh api repos/o/r/git/ref/heads/base-bump/x --jq .object.sha',
  ];

  it("cancels the branch's CI runs and dispatches ci.yml on the pushed head", () => {
    const result = ci(PUSHED);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.calls, [
      ...CANCELS,
      'gh workflow run ci.yml --repo o/r --ref base-bump/x',
    ]);
    assert.deepEqual(result.outputs, { dispatched: 'true' });
  });

  it('dispatches nothing when the branch moved after the push', () => {
    const result = ci(HEAD);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.calls, CANCELS);
    assert.deepEqual(result.outputs, { dispatched: 'false' });
  });
});

describe('base-bump.yml: Post the comment and Label the pull request', () => {
  const post = (ids) =>
    inDir(
      'post',
      { 'base-bump/comment.md': `${MARKER}\n`, 'base-bump/labels.txt': 'base bump\n' },
      (dir) =>
        runBash(runOf('apply', 'Post the comment'), {
          env: { PR: '12', REPO, GH_TOKEN: 'token', RUNNER_TEMP: slashes(dir) },
          fakes: {
            gh: [
              { match: 'api --paginate repos/o/r/issues/12/comments --jq *', out: ids },
              {
                match:
                  'api -X PATCH repos/o/r/issues/comments/555 -F body=@*/base-bump/comment.md --jq .html_url',
                out: `${COMMENT_URL}\n`,
              },
              {
                match:
                  'api -X POST repos/o/r/issues/12/comments -F body=@*/base-bump/comment.md --jq .html_url',
                out: `${COMMENT_URL}\n`,
              },
            ],
          },
        }),
    );

  it("edits the bot's marked comment in place", () => {
    const result = post('555\n556\n');
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.calls.length, 2);
    assert.ok(
      result.calls[0].includes(
        'select(.user.login == "github-actions[bot]" and (.body | contains("<!-- niord-base-bump -->")))',
      ),
    );
    assert.match(result.calls[1], /^gh api -X PATCH repos\/o\/r\/issues\/comments\/555 /);
    assert.deepEqual(result.outputs, { url: COMMENT_URL });
  });

  it('posts a new comment when there is none', () => {
    const result = post('');
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.calls[1], /^gh api -X POST repos\/o\/r\/issues\/12\/comments /);
    assert.deepEqual(result.outputs, { url: COMMENT_URL });
  });

  const label = (labels) =>
    inDir('labels', { 'base-bump/labels.txt': labels }, (dir) =>
      runBash(runOf('apply', 'Label the pull request'), {
        env: { PR: '12', REPO, GH_TOKEN: 'token', RUNNER_TEMP: slashes(dir) },
        fakes: {
          gh: [
            { match: 'label create * --repo o/r' },
            { match: 'api -X POST repos/o/r/issues/12/labels -f labels[]=*' },
          ],
        },
      }),
    );

  it('adds the labels compute named, creating them when they are missing', () => {
    const result = label('base bump\nsecurity\n');
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.calls, [
      'gh label create base bump --repo o/r',
      'gh api -X POST repos/o/r/issues/12/labels -f labels[]=base bump',
      'gh label create security --repo o/r',
      'gh api -X POST repos/o/r/issues/12/labels -f labels[]=security',
    ]);
  });

  it('refuses a label that is not the workflow’s before asking gh', () => {
    const result = label('wontfix\nbase bump\n');
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /::error::labels\.txt names 'wontfix', which is not a label of this workflow\./,
    );
    assert.deepEqual(result.calls, []);
  });
});

describe('base-bump.yml: Set the status', () => {
  const status = (env) => {
    const result = runBash(runOf('apply', 'Set the status'), {
      env: {
        ...ENV,
        HEAD_SHA: HEAD,
        PUSHED: 'true',
        PUSHED_SHA: PUSHED,
        UNTICKED: '0',
        VERIFY: 'success',
        PUSH: 'success',
        CI: 'success',
        COMMENT: 'success',
        COMMENT_URL,
        RUN_URL,
        REPO,
        GH_TOKEN: 'token',
        ...env,
      },
      fakes: { gh: [{ match: 'api -X POST repos/o/r/statuses/* *' }] },
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.calls.length, 1);
    return result.calls[0];
  };
  const call = (sha, state, description, url) =>
    `gh api -X POST repos/o/r/statuses/${sha} -f state=${state} -f context=niord/upgrade-actions -f description=${description} -f target_url=${url}`;
  const NOT_PUSHED = { PUSHED: '', PUSHED_SHA: '' };

  for (const [what, env, expected] of [
    [
      'files that failed their check',
      { VERIFY: 'failure', PUSH: 'skipped', CI: 'skipped', COMMENT: 'skipped', ...NOT_PUSHED },
      call(HEAD, 'failure', 'Base bump: the files from compute failed their check', RUN_URL),
    ],
    [
      'a refused push',
      { PUSH: 'failure', CI: 'skipped', COMMENT: 'skipped', ...NOT_PUSHED },
      call(HEAD, 'failure', 'Base bump: the push was refused; the next run re-pins', RUN_URL),
    ],
    [
      'a CI that could not be dispatched',
      { CI: 'failure' },
      call(PUSHED, 'failure', 'Base bump: CI could not be dispatched on the new head', RUN_URL),
    ],
    [
      'a comment that could not be written',
      { COMMENT: 'failure' },
      call(PUSHED, 'failure', 'Base bump: the comment could not be written', RUN_URL),
    ],
    [
      'no count',
      { UNTICKED: '' },
      call(PUSHED, 'failure', 'Base bump: no count of the human actions', RUN_URL),
    ],
    ['no box left', {}, call(PUSHED, 'success', 'Base bump: no human action left', COMMENT_URL)],
    [
      'three boxes left',
      { UNTICKED: '3' },
      call(PUSHED, 'failure', 'Base bump: 3 human action(s) unticked', COMMENT_URL),
    ],
    [
      'nothing to push',
      { PUSHED: 'false', PUSHED_SHA: HEAD, CI: 'skipped' },
      call(HEAD, 'success', 'Base bump: no human action left', COMMENT_URL),
    ],
  ])
    it(`sets the status for ${what}`, () => {
      assert.equal(status(env), expected);
    });
});

describe('base-bump.yml: recount', () => {
  const COMMENT = [
    MARKER,
    '## Base bump: 4.0.97 to 4.0.110',
    '',
    '## Human actions',
    '',
    '- [ ] one',
    '- [x] two',
    '- [ ] three',
    '',
    '## Checked by CI',
    '',
    '- [ ] not counted',
    '',
    '<details><summary>The upgrade report</summary>',
    '',
    '## Human actions',
    '',
    '- [ ] in the report, not counted either',
    '',
    '</details>',
    '',
  ].join('\r\n');
  const recount = ({ comment = COMMENT, ...options } = {}) => recountWith(comment, options);

  it('counts the unticked boxes of the human actions only, CRLF or not, on the head', () => {
    // compute's first count and every recount are the same program.
    const count = (job, name) => /awk '[^']*'/.exec(runOf(job, name))?.[0];
    assert.ok(count('recount', 'Count and set the status'));
    assert.equal(
      count('compute', 'Build the comment'),
      count('recount', 'Count and set the status'),
    );
    const result = recount();
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(
      statusCall(result),
      `gh api -X POST repos/o/r/statuses/${HEAD} -f state=failure -f context=niord/upgrade-actions -f description=Base bump: 2 human action(s) unticked -f target_url=${COMMENT_URL}`,
    );
  });

  it('turns green when every box is ticked', () => {
    const result = recount({
      comment: COMMENT.replaceAll('- [ ] one', '- [x] one').replace('- [ ] three', '- [x] three'),
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(
      statusCall(result),
      / -f state=success .* -f description=Base bump: no human action left -f target_url=/,
    );
  });

  it('sets the status pending while there is no comment, saying the dispatch makes one', () => {
    const result = recount({ ids: '' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const description =
      'Base bump: no comment yet; run the workflow on this pull request (Run workflow, pr 12)';
    // GitHub refuses a status description of more than 140 characters.
    assert.ok(description.length <= 140);
    assert.equal(
      statusCall(result),
      `gh api -X POST repos/o/r/statuses/${HEAD} -f state=pending -f context=niord/upgrade-actions -f description=${description} -f target_url=${RUN_URL}`,
    );
    assert.match(
      result.stdout,
      /::notice::Pull request 12 has no Base bump comment yet: run this workflow on it \(Actions, Base bump, Run workflow, pr 12\)/,
    );
  });

  it('sets the status failing after a failed compute, reading no comment', () => {
    const result = recount({ env: { COMPUTE: 'failure' } });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.deepEqual(result.calls, [
      'gh pr view 12 --repo o/r --json headRefOid --jq .headRefOid',
      `gh api -X POST repos/o/r/statuses/${HEAD} -f state=failure -f context=niord/upgrade-actions -f description=Base bump failed: see the run -f target_url=${RUN_URL}`,
    ]);
  });

  it('refuses a pull request number that is not a number, asking gh nothing', () => {
    const result = recount({ env: { PR: '' } });
    assert.equal(result.status, 1);
    assert.deepEqual(result.calls, []);
  });
});

describe('base-bump.yml: in the kit package', () => {
  it("is in the kit package, by kit-include.json's templates/**", () => {
    const selects = compileList(readIncludeList(path.join(KIT, 'kit-include.json')));
    assert.ok(selects('templates/base-bump.yml'));
  });
});
