import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { requireFromBase } from './test-fixtures/base-modules.mjs';
import { runBash, stepEnv, stepRun } from '../fe/scripts/workflow-step.mjs';

const yaml = requireFromBase('js-yaml');

const KIT = path.dirname(fileURLToPath(import.meta.url));
const TEXT = fs.readFileSync(path.join(KIT, 'templates', 'pack.yml'), 'utf8');
const WORKFLOW = yaml.load(TEXT);
const BEFORE = 'a'.repeat(40);
const SHA = 'b'.repeat(40);

/** Every step of every job: `{ job, name, step }`. */
const steps = Object.entries(WORKFLOW.jobs).flatMap(([job, { steps: list }]) =>
  list.map((step) => ({ job, name: step.name ?? step.uses, step })),
);

/**
 * An `if:` evaluated against a run's context: `==`, `!=`, `&&`, `||` and parentheses over
 * contexts and single-quoted strings, the subset the template's conditions use.
 */
function evaluate(condition, context, label) {
  const expression = condition.replace(
    /\b(?:github|inputs|needs|steps)(?:\.[A-Za-z_][\w-]*)+/g,
    (name) => JSON.stringify(context[name] ?? ''),
  );
  assert.match(expression, /^[\s"'\w./=!&|()-]*$/, `${label}: ${expression}`);
  return Function(`return (${expression});`)();
}

/** A job's `if:` against a run's context. */
function runs(job, context) {
  const condition = WORKFLOW.jobs[job].if;
  assert.ok(condition, `${job} has no if:`);
  return evaluate(condition, context, job);
}

/**
 * A step's run as YAML reads it, for an exact comparison: stepRun also takes the comment above
 * the next step into a `run: |` block.
 */
const runOf = (job, name) => {
  const found = steps.find((s) => s.job === job && s.name === name);
  assert.ok(found, `${job}: ${name}`);
  return found.step.run;
};

/** Whether a step of `job`, by its name (or its `uses:`), runs in a context: true with no `if:`. */
function stepRuns(job, name, context) {
  const found = steps.filter((s) => s.job === job && s.name === name);
  assert.equal(found.length, 1, `${job}: ${name}`);
  const condition = found[0].step.if;
  return condition === undefined ? true : evaluate(condition, context, `${job}: ${name}`);
}

describe("pack.yml: the template's shape", () => {
  it('is valid YAML with the three jobs, publish and withdraw after validate', () => {
    assert.deepEqual(Object.keys(WORKFLOW.jobs), ['validate', 'publish', 'withdraw']);
    assert.equal(WORKFLOW.jobs.publish.needs, 'validate');
    assert.equal(WORKFLOW.jobs.withdraw.needs, 'validate');
    assert.deepEqual(WORKFLOW.permissions, { contents: 'read' });
    // The archive leaves the reviewers' notes out: a change to them alone publishes nothing.
    assert.deepEqual(WORKFLOW.on.push, {
      branches: ['main'],
      paths: ['fe/packs/**', '!fe/packs/*/status/unaccepted.json'],
    });
    assert.deepEqual(Object.keys(WORKFLOW.on.workflow_dispatch.inputs), [
      'lang',
      'action',
      'tag',
      'reason',
    ]);
    assert.deepEqual(WORKFLOW.on.workflow_dispatch.inputs.action.options, ['publish', 'withdraw']);
  });

  it('runs one at a time and queues every pending run, so a later run never cancels a queued withdrawal', () => {
    // GitHub's default queue keeps one pending run per group and cancels it when a third arrives.
    assert.deepEqual(WORKFLOW.concurrency, {
      group: 'pack',
      'cancel-in-progress': false,
      queue: 'max',
    });
  });

  it('gives validate contents: read and packages: read, and each token job packages: write alone', () => {
    assert.deepEqual(WORKFLOW.jobs.validate.permissions, { contents: 'read', packages: 'read' });
    assert.deepEqual(WORKFLOW.jobs.publish.permissions, { packages: 'write' });
    assert.deepEqual(WORKFLOW.jobs.withdraw.permissions, { packages: 'write' });
  });

  it('checks out without keeping the token, and never in the token jobs', () => {
    const checkout = WORKFLOW.jobs.validate.steps.find((s) =>
      s.uses?.startsWith('actions/checkout@'),
    );
    assert.deepEqual(checkout.with, { 'persist-credentials': false, 'fetch-depth': 0 });
    for (const job of ['publish', 'withdraw'])
      assert.ok(
        !WORKFLOW.jobs[job].steps.some((s) => s.uses?.startsWith('actions/checkout@')),
        job,
      );
  });

  it('binds GITHUB_TOKEN on the two Maven steps only, and PACK_TOKEN on the two pushes only', () => {
    const holders = (name) =>
      steps
        .filter(({ step }) => Object.hasOwn(step.env ?? {}, name))
        .map(({ job, name: n }) => `${job}: ${n}`);
    assert.deepEqual(holders('GITHUB_TOKEN'), [
      'validate: Set up the workspace',
      'validate: Fetch the latest base product catalog',
    ]);
    assert.deepEqual(holders('PACK_TOKEN'), [
      'publish: Push the archive',
      'withdraw: Add the tag to the withdrawn marker',
    ]);
    for (const job of Object.values(WORKFLOW.jobs)) assert.equal(job.env, undefined);
    assert.equal(WORKFLOW.env, undefined);
    // As written: the token is the run's own, never a secret.
    for (const [job, step, name] of [
      ['validate', 'Set up the workspace', 'GITHUB_TOKEN'],
      ['validate', 'Fetch the latest base product catalog', 'GITHUB_TOKEN'],
      ['publish', 'Push the archive', 'PACK_TOKEN'],
      ['withdraw', 'Add the tag to the withdrawn marker', 'PACK_TOKEN'],
    ])
      assert.equal(stepEnv(TEXT, { job, step })[name], '${{ github.token }}', step);
    assert.doesNotMatch(TEXT, /secrets\./);
  });

  it('runs Maven through set-up and the catalog fetch, and the archive with no token', () => {
    assert.equal(runOf('validate', 'Set up the workspace'), 'set -euo pipefail\nnode setup.mjs\n');
    assert.equal(
      runOf('validate', 'Fetch the latest base product catalog'),
      'set -euo pipefail\nnode ../.niord/kit/pack.mjs latest-catalog --out .niord/latest-catalog.json\n',
    );
    const archive = stepRun(TEXT, { job: 'validate', step: 'Archive the pack' });
    assert.match(
      archive,
      /^node \.\.\/\.niord\/kit\/pack\.mjs validate "\$PACK_LANG" --catalog \.niord\/latest-catalog\.json --out "\$RUNNER_TEMP\/pack"$/m,
    );
    assert.match(archive, /^sha256sum -c "niord-lang-\$PACK_LANG\.zip\.sha256"$/m);
    assert.match(
      stepRun(TEXT, { job: 'validate', step: 'Hand over the OCI client' }),
      /cp \.\.\/\.niord\/kit\/lib\/oci\.mjs "\$RUNNER_TEMP\/pack\/oci\.mjs"/,
    );
    const upload = WORKFLOW.jobs.validate.steps.find((s) =>
      s.uses?.startsWith('actions/upload-artifact@'),
    );
    assert.equal(upload.with.name, 'pack');
    assert.equal(upload.with['retention-days'], 1);
    assert.equal(upload.with['compression-level'], 0);
    // Each output from the step that writes it: one that named another step would be empty, and
    // the publish job would be skipped without a word.
    assert.deepEqual(WORKFLOW.jobs.validate.outputs, {
      lang: '${{ steps.which.outputs.lang }}',
      publish: '${{ steps.archive.outputs.publish }}',
      sha256: '${{ steps.archive.outputs.sha256 }}',
      oci_sha256: '${{ steps.client.outputs.sha256 }}',
    });
    const ids = Object.fromEntries(
      WORKFLOW.jobs.validate.steps.filter((s) => s.id).map((s) => [s.id, s.name]),
    );
    assert.deepEqual(ids, {
      which: 'Which pack',
      archive: 'Archive the pack',
      client: 'Hand over the OCI client',
    });
  });

  it('runs no npm, no expression in a run block, and set -euo pipefail first in every one, one-liners included', () => {
    assert.doesNotMatch(TEXT, /\bnpm\b|\bnpx\b/);
    for (const { job, name, step } of steps) {
      if (step.run === undefined) continue;
      assert.doesNotMatch(step.run, /\$\{\{/, `${job}: ${name}`);
      assert.match(step.run, /^set -euo pipefail\n/, `${job}: ${name}`);
    }
  });

  it('pushes only the verified files: the token jobs check both SHA-256s before running the client', () => {
    const verify = stepRun(TEXT, { job: 'publish', step: 'Verify the archive and the client' });
    assert.match(verify, /echo "\$SHA256 {2}pack\/niord-lang-\$PACK_LANG\.zip" \| sha256sum -c -/);
    assert.match(verify, /echo "\$OCI_SHA256 {2}pack\/oci\.mjs" \| sha256sum -c -/);
    assert.equal(
      runOf('publish', 'Push the archive'),
      'set -euo pipefail\nnode pack/oci.mjs publish --archive "pack/niord-lang-$PACK_LANG.zip" --sha256 "$SHA256"\n',
    );
    assert.match(
      stepRun(TEXT, { job: 'withdraw', step: 'Verify the client' }),
      /echo "\$OCI_SHA256 {2}pack\/oci\.mjs" \| sha256sum -c -/,
    );
    assert.equal(
      runOf('withdraw', 'Add the tag to the withdrawn marker'),
      'set -euo pipefail\nnode pack/oci.mjs withdraw\n',
    );
    // The step that holds the token runs nothing but the verified client.
    for (const [job, step] of [
      ['publish', 'Push the archive'],
      ['withdraw', 'Add the tag to the withdrawn marker'],
    ])
      assert.equal(
        WORKFLOW.jobs[job].steps.findIndex((s) => s.name === step),
        WORKFLOW.jobs[job].steps.length - 1,
      );
  });
});

describe("pack.yml: the jobs' conditions", () => {
  const MAIN = 'refs/heads/main';
  const context = (extra) => ({
    'github.ref': MAIN,
    'needs.validate.outputs.publish': 'true',
    ...extra,
  });

  it('publishes on main, for a pack marked publish, on a push or a dispatch with action publish', () => {
    assert.equal(runs('publish', context({ 'github.event_name': 'push' })), true);
    assert.equal(
      runs(
        'publish',
        context({ 'github.event_name': 'workflow_dispatch', 'inputs.action': 'publish' }),
      ),
      true,
    );
  });

  it('never publishes from another branch, an unpublished pack, or a withdrawal', () => {
    assert.equal(
      runs(
        'publish',
        context({
          'github.event_name': 'workflow_dispatch',
          'inputs.action': 'publish',
          'github.ref': 'refs/heads/feature',
        }),
      ),
      false,
    );
    assert.equal(
      runs(
        'publish',
        context({ 'github.event_name': 'push', 'needs.validate.outputs.publish': 'false' }),
      ),
      false,
    );
    assert.equal(
      runs(
        'publish',
        context({ 'github.event_name': 'push', 'needs.validate.outputs.publish': '' }),
      ),
      false,
    );
    assert.equal(
      runs(
        'publish',
        context({ 'github.event_name': 'workflow_dispatch', 'inputs.action': 'withdraw' }),
      ),
      false,
    );
  });

  it('withdraws only by a dispatch with action withdraw, on main', () => {
    assert.equal(
      runs(
        'withdraw',
        context({ 'github.event_name': 'workflow_dispatch', 'inputs.action': 'withdraw' }),
      ),
      true,
    );
    assert.equal(
      runs(
        'withdraw',
        context({
          'github.event_name': 'workflow_dispatch',
          'inputs.action': 'withdraw',
          'github.ref': 'refs/heads/x',
        }),
      ),
      false,
    );
    assert.equal(
      runs(
        'withdraw',
        context({ 'github.event_name': 'workflow_dispatch', 'inputs.action': 'publish' }),
      ),
      false,
    );
    assert.equal(runs('withdraw', context({ 'github.event_name': 'push' })), false);
  });

  /** Which of validate's steps run in a context, by name. */
  const validateSteps = (extra) =>
    steps
      .filter(({ job }) => job === 'validate')
      .filter(({ name }) => stepRuns('validate', name, extra))
      .map(({ name }) => name);
  const ALWAYS = [
    'actions/checkout@v4',
    'Set up JDK 25 (Temurin) + GitHub Packages credentials',
    'actions/setup-node@v4',
    'Which pack',
  ];

  it('runs every validate step for a publish, on a push or a dispatch, once Which pack named one', () => {
    const all = [
      ...ALWAYS,
      'Set up the workspace',
      'Fetch the latest base product catalog',
      'Archive the pack',
      'Hand over the OCI client',
      'actions/upload-artifact@v4',
    ];
    assert.deepEqual(
      validateSteps({ 'github.event_name': 'push', 'steps.which.outputs.lang': 'da' }),
      all,
    );
    assert.deepEqual(
      validateSteps({
        'github.event_name': 'workflow_dispatch',
        'inputs.action': 'publish',
        'steps.which.outputs.lang': 'da',
      }),
      all,
    );
  });

  it('runs set-up and hands the client over for a withdrawal, but fetches and archives nothing', () => {
    assert.deepEqual(
      validateSteps({
        'github.event_name': 'workflow_dispatch',
        'inputs.action': 'withdraw',
        'steps.which.outputs.lang': 'da',
      }),
      [...ALWAYS, 'Set up the workspace', 'Hand over the OCI client', 'actions/upload-artifact@v4'],
    );
  });

  it('runs nothing after Which pack when it named no pack', () => {
    assert.deepEqual(
      validateSteps({ 'github.event_name': 'push', 'steps.which.outputs.lang': '' }),
      ALWAYS,
    );
  });
});

describe('pack.yml: Which pack', () => {
  const script = stepRun(TEXT, { job: 'validate', step: 'Which pack' });
  /**
   * The step in a fe/ holding `packs`, with git finding the previous commit and answering `diff`,
   * the reviewers' notes left out, with `changed`.
   */
  const which = (env, { packs = ['da', 'kl'], changed = '' } = {}) => {
    const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-pack-'));
    try {
      for (const lang of packs) fs.mkdirSync(path.join(fe, 'packs', lang), { recursive: true });
      return runBash(script, {
        cwd: fe,
        env: { GITHUB_SHA: SHA, BEFORE, EVENT: 'push', PACK_LANG: '', ...env },
        fakes: {
          git: [
            { match: `cat-file -e ${BEFORE}^{commit}` },
            {
              match: `diff --name-only --relative ${BEFORE} ${SHA} -- packs/ :(exclude)packs/*/status/unaccepted.json`,
              out: changed,
            },
          ],
        },
      });
    } finally {
      fs.rmSync(fe, { recursive: true, force: true });
    }
  };

  it('takes the dispatch’s language when its folder exists, asking git nothing', () => {
    const result = which({ EVENT: 'workflow_dispatch', PACK_LANG: 'da' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.lang, 'da');
    assert.deepEqual(result.calls, []);
  });

  for (const [what, lang, message] of [
    ['a value that is not a language code', 'da; rm -rf /', /not a language code/],
    ['an upper-case code', 'DA', /not a language code/],
    ['a language without its folder', 'nb', /packs\/nb\/ does not exist/],
  ])
    it(`refuses a dispatch with ${what}`, () => {
      const result = which({ EVENT: 'workflow_dispatch', PACK_LANG: lang });
      assert.equal(result.status, 1);
      assert.match(result.stdout, message);
      assert.equal(result.outputs.lang, undefined);
    });

  it('takes the one pack a push changed', () => {
    const result = which({}, { changed: 'packs/da/i18n/admin/da.json\npacks/da/pack.json\n' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.lang, 'da');
  });

  it('publishes nothing when a push changed no pack folder', () => {
    const result = which({}, { changed: 'packs/README.md\n' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.lang, '');
    assert.match(result.stdout, /::notice::No pack folder changed/);
  });

  it('publishes nothing when a push removed the pack folder it changed', () => {
    const result = which({}, { changed: 'packs/nb/pack.json\npacks/nb/i18n/admin/nb.json\n' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.lang, '');
    assert.match(result.stdout, /::notice::packs\/nb\/ was removed: nothing to publish/);
  });

  it('refuses a push that changed two packs: one pack per change', () => {
    const result = which({}, { changed: 'packs/da/pack.json\npacks/kl/pack.json\n' });
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /One pack per change: this push changed 2 packs; dispatch publish for each/,
    );
  });

  it('refuses a push without a previous commit, asking for a dispatch', () => {
    for (const before of ['0'.repeat(40), '', 'HEAD~1']) {
      const result = which({ BEFORE: before });
      assert.equal(result.status, 1, before);
      assert.match(result.stdout, /no previous commit to compare with: dispatch publish/);
      assert.deepEqual(result.calls, []);
    }
  });

  it('refuses a changed folder that is not a language code', () => {
    const result = which({}, { changed: 'packs/Da/pack.json\n' });
    assert.equal(result.status, 1);
    assert.match(result.stdout, /not a language code/);
  });
});

describe('pack.yml: Which pack against a real git history', () => {
  const script = stepRun(TEXT, { job: 'validate', step: 'Which pack' });
  let dir;
  let repo;
  const commits = {};
  // A repository of the test's own: no user or system configuration (no signing, no hooks), and
  // an identity of its own.
  const gitEnv = () => ({
    GIT_CONFIG_GLOBAL: path.join(dir, 'gitconfig'),
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_AUTHOR_NAME: 'Niord test',
    GIT_AUTHOR_EMAIL: 'test@niord.invalid',
    GIT_COMMITTER_NAME: 'Niord test',
    GIT_COMMITTER_EMAIL: 'test@niord.invalid',
  });
  const git = (...args) => {
    const inherited = Object.fromEntries(
      Object.entries(process.env).filter(([key]) => !key.startsWith('GIT_')),
    );
    const result = spawnSync('git', args, {
      cwd: repo,
      encoding: 'utf8',
      env: { ...inherited, ...gitEnv() },
    });
    assert.equal(result.status, 0, `git ${args.join(' ')}: ${result.stderr}`);
    return result.stdout.trim();
  };
  const commit = (files, message) => {
    for (const [relative, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(repo, relative)), { recursive: true });
      fs.writeFileSync(path.join(repo, relative), text);
    }
    git('add', '-A');
    git('commit', '-q', '-m', message);
    return git('rev-parse', 'HEAD');
  };
  /** The step in the repository's fe/ for a push from `before` to `sha`. */
  const which = (before, sha) =>
    runBash(script, {
      cwd: path.join(repo, 'fe'),
      env: { ...gitEnv(), GITHUB_SHA: sha, BEFORE: before, EVENT: 'push', PACK_LANG: '' },
    });

  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-pack-git-'));
    repo = path.join(dir, 'repo');
    fs.mkdirSync(repo);
    fs.writeFileSync(path.join(dir, 'gitconfig'), '');
    git('init', '-q');
    commits.first = commit(
      {
        'fe/packs/da/pack.json': '{"lang":"da"}\n',
        'fe/packs/da/status/unaccepted.json': '{}\n',
        'fe/packs/kl/pack.json': '{"lang":"kl"}\n',
      },
      'the packs',
    );
    commits.notes = commit(
      { 'fe/packs/da/status/unaccepted.json': '{"admin":["a.key"]}\n' },
      'a reviewers note',
    );
    commits.both = commit(
      {
        'fe/packs/da/status/unaccepted.json': '{"admin":[]}\n',
        'fe/packs/da/pack.json': '{"lang":"da","label":"Dansk"}\n',
      },
      'a note and the pack',
    );
  });
  after(() => fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3 }));

  it("publishes nothing for a change to a pack's status/unaccepted.json alone", () => {
    const result = which(commits.first, commits.notes);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.lang, '');
    assert.match(result.stdout, /::notice::No pack folder changed: nothing to publish/);
  });

  it('takes the pack when the same push changed the pack itself too', () => {
    const result = which(commits.notes, commits.both);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.outputs.lang, 'da');
  });

  it('refuses a previous commit no ref reaches (a force-push), asking for a dispatch', () => {
    const result = which('c'.repeat(40), commits.both);
    assert.equal(result.status, 1, result.stdout + result.stderr);
    assert.match(
      result.stdout,
      /^::error::The push's previous commit is not in this history \(a force-push\?\): dispatch publish for the pack\.$/m,
    );
    assert.equal(result.outputs.lang, undefined);
  });
});

describe("pack.yml: the publish job's checks", () => {
  const script = stepRun(TEXT, { job: 'publish', step: 'Verify the archive and the client' });
  const ARCHIVE = Buffer.from('the archive\n');
  const CLIENT = Buffer.from('the client\n');
  const hash = (data) => createHash('sha256').update(data).digest('hex');
  /** The step beside a downloaded pack/niord-lang-da.zip and pack/oci.mjs. */
  const verify = (env) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-publish-'));
    try {
      fs.mkdirSync(path.join(dir, 'pack'));
      fs.writeFileSync(path.join(dir, 'pack', 'niord-lang-da.zip'), ARCHIVE);
      fs.writeFileSync(path.join(dir, 'pack', 'oci.mjs'), CLIENT);
      return runBash(script, {
        cwd: dir,
        env: { PACK_LANG: 'da', SHA256: hash(ARCHIVE), OCI_SHA256: hash(CLIENT), ...env },
      });
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  };

  it('passes the archive and the client of the SHA-256s validate gave', () => {
    const result = verify({});
    assert.equal(result.status, 0, result.stdout + result.stderr);
  });

  for (const [what, env, message] of [
    ['an archive of another SHA-256', { SHA256: 'f'.repeat(64) }, /niord-lang-da\.zip: FAILED/],
    ['a client of another SHA-256', { OCI_SHA256: 'f'.repeat(64) }, /oci\.mjs: FAILED/],
    ['no language', { PACK_LANG: '' }, /::error::validate gave no language code\./],
    [
      'an archive SHA-256 of 63 digits',
      { SHA256: 'f'.repeat(63) },
      /::error::validate gave no archive SHA-256\./,
    ],
    [
      'a client SHA-256 of 63 digits',
      { OCI_SHA256: 'f'.repeat(63) },
      /::error::validate gave no client SHA-256\./,
    ],
  ])
    it(`refuses ${what}`, () => {
      const result = verify(env);
      assert.notEqual(result.status, 0);
      assert.match(result.stdout + result.stderr, message);
    });
});

describe('pack.yml: the withdrawal’s checks', () => {
  const script = stepRun(TEXT, { job: 'withdraw', step: 'Verify the client' });
  const CLIENT = Buffer.from('the client\n');
  const CLIENT_SHA = createHash('sha256').update(CLIENT).digest('hex');
  /** The step beside a downloaded pack/oci.mjs. */
  const verify = (env) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-withdraw-'));
    try {
      fs.mkdirSync(path.join(dir, 'pack'));
      fs.writeFileSync(path.join(dir, 'pack', 'oci.mjs'), CLIENT);
      return runBash(script, {
        cwd: dir,
        env: { OCI_SHA256: CLIENT_SHA, PACK_TAG: '3', PACK_REASON: 'a wrong text', ...env },
      });
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  };

  it('passes the verified client with a tag and a reason', () => {
    const result = verify({});
    assert.equal(result.status, 0, result.stdout + result.stderr);
  });

  for (const [what, env, message] of [
    ['a client of another SHA-256', { OCI_SHA256: 'f'.repeat(64) }, /FAILED/],
    ['a tag that is not digits', { PACK_TAG: 'latest' }, /digits only/],
    ['an empty tag', { PACK_TAG: '' }, /digits only/],
    ['an empty reason', { PACK_REASON: '  ' }, /gives its reason/],
  ])
    it(`refuses ${what}`, () => {
      const result = verify(env);
      assert.notEqual(result.status, 0);
      assert.match(result.stdout + result.stderr, message);
    });
});
