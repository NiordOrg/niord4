/**
 * The release workflows' own bash, run with a fake `gh` first on PATH (workflow-step.mjs): the
 * publish gate, the release notes the tag job writes (one sha256 line for the source zip and one
 * for the kit package), the by-hand release texts and the clean-up of the zips' artifacts, the
 * parser Frontend CI's upgrade test reads the notes with (both lines, and notes without the kit
 * package's line refused), web-publish's zip and deploy of the
 * administration bundle (one en zip, with a fake zip and a stub ./mvnw), the published packages
 * (what publish.yml deploys is what retract-version.yml retracts and the release notes name,
 * niord-local-run and niord-country-kit among them; the local-run zip no image job is given, and
 * the kit package is built and checked before the first deploy; both deploys with a stub
 * ./mvnw), the authorisation, validation and package steps of the retraction (a run refused
 * whole, before any deleting step, without its delete token), the bump job (its shape read
 * from the text, its skip without the app's id or key, its one command, and once against the fake
 * GitHub of github-api-stub.mjs in a worker) and the gate's check of where the bump app's key is;
 * and that the helper removes each run's folder. What runs is the text of the
 * step in the workflow file as it stands, so a change to a step is tested as written. Two of
 * Frontend CI's steps are read, not run: the scripts' lint, and the example's, which sets no
 * ALPINE_IMAGE, so the Dockerfile's default base stands.
 *
 *   npm run test:guards      # from fe/
 *
 * Needs bash: Git's on Windows (workflow-step.mjs finds it), the runner's on Linux.
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { appKeys, readRequestLog, startGitHubStubWorker } from './github-api-stub.mjs';
import { runBash, stepEnv, stepRun } from './workflow-step.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const workflow = (name) => fs.readFileSync(path.join(ROOT, '.github', 'workflows', name), 'utf8');
const PUBLISH = workflow('publish.yml');
const FE_CI = workflow('fe-ci.yml');
const RETRACT = workflow('retract-version.yml');

const REPO = 'NiordOrg/niord4';
const SHA = 'a'.repeat(40);
const MAIN = 'b'.repeat(40);
const ANCESTOR = 'c'.repeat(40);
const MAINTAINER = 'MadsenJensen';
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

// ---------------------------------------------------------------------------------------------
// publish.yml: the gate
// ---------------------------------------------------------------------------------------------

/** The gate's GitHub as a fake gh: everything green and main-only unless the case says otherwise. */
function gateGh({ runs = {}, environments = {}, branchPolicies = {} } = {}) {
  const answers = [
    { match: `api repos/${REPO}/git/ref/heads/main *`, out: `${MAIN}\n` },
    { match: `api repos/${REPO}/compare/${SHA}...${MAIN} *`, out: 'identical\n' },
    { match: `api repos/${REPO}/compare/${ANCESTOR}...${SHA} *`, out: 'ahead\n' },
    { match: `api repos/${REPO}/git/matching-refs/tags/v4.0. *`, out: '' },
    {
      match: `api repos/${REPO}/actions/workflows/be-ci.yml/runs?branch=main*`,
      out: `${ANCESTOR} completed success https://ci.example/be-ci/ancestor\n`,
    },
  ];
  for (const wf of ['be-ci.yml', 'fe-ci.yml', 'info-ci.yml'])
    answers.push({
      match: `api repos/${REPO}/actions/workflows/${wf}/runs?head_sha=${SHA}&event=push*`,
      out: `${runs[wf] ?? `completed success https://ci.example/${wf}`}\n`,
    });
  for (const env of ['publish', 'harbor']) {
    answers.push({
      match: `api repos/${REPO}/environments/${env}/deployment-branch-policies *`,
      out: `${branchPolicies[env] ?? 'branch:main'}\n`,
    });
    answers.push(
      environments[env] === null
        ? {
            match: `api repos/${REPO}/environments/${env} *`,
            err: 'gh: Not Found (HTTP 404)\n',
            exit: 1,
          }
        : {
            match: `api repos/${REPO}/environments/${env} *`,
            out: `${environments[env] ?? 'false true'}\n`,
          },
    );
  }
  answers.push({
    match: `*repos/${REPO}/contents/.github/maintainers?ref=main`,
    out: `# who\n${MAINTAINER}\n`,
  });
  answers.push({ match: 'workflow run be-ci.yml *', out: '' });
  return answers;
}

const GATE = stepRun(PUBLISH, { job: 'gate', step: 'Decide' });

function gate({ env = {}, ...gh } = {}) {
  return runBash(GATE, {
    env: {
      GH_TOKEN: 'fake',
      REPO,
      EVENT: 'workflow_run',
      HEAD_SHA: SHA,
      TRIGGER_NAME: 'Frontend CI',
      TRIGGER_EVENT: 'push',
      TRIGGER_REPO: REPO,
      TRIGGER_BRANCH: 'main',
      TRIGGER_CONCLUSION: 'success',
      TRIGGER_URL: 'https://ci.example/trigger',
      REASON: '',
      ACTOR: MAINTAINER,
      GITHUB_RUN_NUMBER: '77',
      GITHUB_REF: 'refs/heads/main',
      GITHUB_SHA: SHA,
      ...env,
    },
    fakes: { gh: gateGh(gh) },
    // The gate makes a dozen fake gh calls; on a loaded Windows workstation they can pass 60 s
    timeout: 180_000,
  });
}

const show = (result) => `${result.stdout}\n${result.stderr}\ncalls:\n${result.calls.join('\n')}`;

describe('publish.yml: the gate', () => {
  it('waits for Backend CI, Frontend CI and Info CI', () => {
    // \r?: a Windows checkout with core.autocrlf has the workflows with CRLF
    const trigger = /^ {2}workflow_run:\r?\n {4}workflows: \[([^\]]*)\]/m.exec(PUBLISH);
    assert.ok(trigger, 'publish.yml has no workflow_run trigger with a workflows list');
    assert.deepEqual(
      trigger[1].split(',').map((name) => name.trim()),
      ['Backend CI', 'Frontend CI', 'Info CI'],
    );
  });

  it('numbers run 1 4.0.182, after the 4.0.181 SIFA-DK/niord-ng published last', () => {
    const result = gate({ env: { GITHUB_RUN_NUMBER: '1' } });
    assert.equal(result.status, 0, show(result));
    assert.equal(result.outputs.version, '4.0.182', show(result));
  });

  it('publishes a commit whose three CI workflows are green', () => {
    const result = gate();
    assert.equal(result.status, 0, show(result));
    assert.equal(result.outputs.publish, 'true', show(result));
    assert.equal(result.outputs.version, '4.0.258');
    assert.equal(result.outputs.sha, SHA);
    for (const wf of ['be-ci.yml', 'fe-ci.yml', 'info-ci.yml'])
      assert.ok(
        result.calls.some((call) => call.includes(`actions/workflows/${wf}/runs?head_sha=`)),
        `${wf} was not asked`,
      );
    assert.match(result.summary, /Backend CI, Frontend CI and Info CI green/);
  });

  for (const [what, run] of [
    ['failed', 'completed failure https://ci.example/info-ci.yml'],
    ['still running', 'in_progress - https://ci.example/info-ci.yml'],
  ])
    it(`skips a commit whose Info CI ${what}`, () => {
      const result = gate({ runs: { 'info-ci.yml': run } });
      assert.equal(result.status, 0, show(result));
      assert.equal(result.outputs.publish, 'false', show(result));
      assert.match(result.stdout, /::notice::info-ci\.yml /);
    });

  it('publishes when Info CI has no push run for the commit (path-filtered)', () => {
    const result = gate({ runs: { 'info-ci.yml': 'none - -' } });
    assert.equal(result.outputs.publish, 'true', show(result));
    assert.match(result.stdout, /info-ci\.yml: no push run for this commit \(path-filtered\)/);
    assert.ok(!result.calls.some((call) => call.includes('runs?branch=main')), show(result));
  });

  it('publishes a frontend-only commit whose nearest backend ancestor is green', () => {
    const result = gate({ runs: { 'be-ci.yml': 'none - -' } });
    assert.equal(result.outputs.publish, 'true', show(result));
    assert.ok(result.calls.some((call) => call.includes(`compare/${ANCESTOR}...${SHA}`)));
  });

  it('fails, publishing nothing, while harbor allows protected branches instead of main only', () => {
    const result = gate({ environments: { harbor: 'true false' } });
    assert.equal(result.status, 1, show(result));
    assert.notEqual(result.outputs.publish, 'true');
    assert.match(result.stdout, /Environment 'harbor' does not limit deployments/);
  });

  it('fails while publish allows another branch beside main', () => {
    const result = gate({ branchPolicies: { publish: 'branch:main branch:dev' } });
    assert.equal(result.status, 1, show(result));
    assert.notEqual(result.outputs.publish, 'true');
    assert.match(result.stdout, /Environment 'publish' allows 'branch:main branch:dev'/);
  });

  it('fails while an environment does not exist', () => {
    const result = gate({ environments: { harbor: null } });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /Environment 'harbor' does not exist or cannot be read/);
  });

  it('refuses a break-glass dispatch by a login not on the maintainer list', () => {
    const result = gate({
      env: { EVENT: 'workflow_dispatch', ACTOR: 'someone-else', REASON: 'x' },
    });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /'someone-else' is not on \.github\/maintainers/);
    assert.ok(!result.calls.some((call) => call.startsWith('gh workflow run')));
  });

  it('refuses a break-glass dispatch of another ref than main', () => {
    const result = gate({
      env: { EVENT: 'workflow_dispatch', GITHUB_REF: 'refs/heads/x', REASON: 'x' },
    });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /Break-glass publishes only from main \(got refs\/heads\/x\)/);
    assert.deepEqual(result.calls, []);
  });

  it('publishes a break-glass dispatch by a maintainer from main, and starts Backend CI', () => {
    const result = gate({ env: { EVENT: 'workflow_dispatch', REASON: 'prod is down' } });
    assert.equal(result.outputs.publish, 'true', show(result));
    assert.equal(result.outputs.break_glass, 'true');
    assert.ok(result.calls.some((call) => call.startsWith('gh workflow run be-ci.yml')));
  });
});

// ---------------------------------------------------------------------------------------------
// publish.yml's release notes and fe-ci.yml's reading of them
// ---------------------------------------------------------------------------------------------

const VERSION = '4.0.12';
const ZIP_NAME = `niord-fe-src-${VERSION}.zip`;
const ZIP = Buffer.from('the base product frontend sources');
const ZIP_SHA = sha256(ZIP);
const KIT_NAME = `niord-country-kit-${VERSION}.zip`;
const KIT = Buffer.from('the country kit package');
const KIT_SHA = sha256(KIT);
const RELEASE = stepRun(PUBLISH, { job: 'tag', step: 'Release v' });

/**
 * Runs the tag job's release step on a source zip of ZIP's bytes and a kit package of KIT's, with
 * the build job's sha256 of each given as `feSrc` and `kit`; `created` false makes the release
 * fail. Returns the result and the notes.
 */
function release({ feSrc = ZIP_SHA, kit = KIT_SHA, created = true } = {}) {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-release-'));
  try {
    fs.mkdirSync(path.join(cwd, 'fe-src'));
    fs.writeFileSync(path.join(cwd, 'fe-src', ZIP_NAME), ZIP);
    fs.mkdirSync(path.join(cwd, 'kit'));
    fs.writeFileSync(path.join(cwd, 'kit', KIT_NAME), KIT);
    const result = runBash(RELEASE, {
      cwd,
      env: {
        GH_TOKEN: 'fake',
        REPO,
        VERSION,
        FE_SRC_SHA256: feSrc,
        KIT_SHA256: kit,
        GITHUB_RUN_ID: '4242',
      },
      fakes: {
        gh: [
          created
            ? { match: 'release create *', out: '' }
            : { match: 'release create *', err: 'HTTP 502\n', exit: 1 },
        ],
      },
    });
    const notesFile = path.join(cwd, 'notes.md');
    const notes = fs.existsSync(notesFile) ? fs.readFileSync(notesFile, 'utf8') : undefined;
    return { result, notes };
  } finally {
    fs.rmSync(cwd, { recursive: true, force: true });
  }
}

const PREVIOUS = stepRun(FE_CI, { job: 'example', step: 'The previous published zip' });

/**
 * Runs Frontend CI's step that finds the previous zip: the latest release is `tag`, its notes
 * `notes`, and its assets the bytes `zip` and `kit` (each none when undefined): the fake download
 * copies the asset its --pattern names, and fails when the release has none. `keep` keeps the
 * run's folder, where the step leaves the zips, for the caller to read and remove.
 */
function previous({ tag = `v${VERSION}`, notes, zip = ZIP, kit, keep = false } = {}) {
  const source = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-asset-'));
  try {
    fs.writeFileSync(path.join(source, 'notes'), notes ?? '');
    if (zip) fs.writeFileSync(path.join(source, ZIP_NAME), zip);
    if (kit) fs.writeFileSync(path.join(source, KIT_NAME), kit);
    const download = [
      '    while [ $# -gt 0 ]; do',
      '      [ "$1" = --dir ] && dir="$2"',
      '      [ "$1" = --pattern ] && pattern="$2"',
      '      shift',
      '    done',
      '    mkdir -p "$dir"',
      '    cp "$ASSETS/$pattern" "$dir/" 2> /dev/null || exit 1',
    ].join('\n');
    return runBash(PREVIOUS, {
      keep,
      env: { GH_TOKEN: 'fake', REPO, ASSETS: source.replaceAll('\\', '/') },
      fakes: {
        gh: [
          { match: `api repos/${REPO}/releases?per_page=20 *`, out: tag ? `${tag}\n` : '\n' },
          { match: `release download ${tag} *`, script: download },
          {
            match: `api repos/${REPO}/releases/tags/${tag} *`,
            script: '    cat "$ASSETS/notes"',
          },
        ],
      },
    });
  } finally {
    fs.rmSync(source, { recursive: true, force: true });
  }
}

/**
 * Runs the by-hand `gh release create …` line of a failure summary under bash, with a fake gh
 * that writes the --notes argument it was given to a file; returns the line and those notes.
 */
function byHandRelease(summary) {
  const line = summary
    .replaceAll('\r', '')
    .split('\n')
    .find((text) => text.startsWith('gh release create '));
  assert.ok(line, summary);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-by-hand-'));
  try {
    const notesFile = path.join(tmp, 'notes');
    const result = runBash(`${line}\n`, {
      env: { NOTES_OUT: notesFile.replaceAll('\\', '/') },
      fakes: {
        gh: [
          {
            match: 'release create *',
            script: [
              '    while [ $# -gt 0 ]; do',
              '      [ "$1" = --notes ] && printf \'%s\' "$2" > "$NOTES_OUT"',
              '      shift',
              '    done',
            ].join('\n'),
          },
        ],
      },
    });
    assert.equal(result.status, 0, show(result));
    return { line, notes: fs.readFileSync(notesFile, 'utf8') };
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

/** The by-hand commands' assertions: both downloads, both files and two notes lines. */
function assertByHand(summary, { feSrc, kit }) {
  assert.match(summary, / -n niord-fe-src -D fe-src$/m, summary);
  assert.match(summary, / -n niord-country-kit -D kit$/m, summary);
  const { line, notes } = byHandRelease(summary);
  assert.match(
    line,
    new RegExp(`^gh release create v${VERSION} fe-src/${ZIP_NAME} kit/${KIT_NAME} `),
    line,
  );
  assert.match(line, / --verify-tag /, line);
  assert.equal(notes, `${feSrc}  ${ZIP_NAME}\n${kit}  ${KIT_NAME}`, line);
}

describe("publish.yml's release notes", () => {
  it("gives the tag and release steps the build job's sha256 of each zip", () => {
    for (const step of ['Tag v', 'Release v']) {
      const env = stepEnv(PUBLISH, { job: 'tag', step });
      assert.equal(env.FE_SRC_SHA256, '${{ needs.build.outputs.fe_src_sha256 }}', step);
      assert.equal(env.KIT_SHA256, '${{ needs.build.outputs.kit_sha256 }}', step);
    }
  });

  it("records each zip on a line of its own in sha256sum's form, and makes the release on the tag", () => {
    const { result, notes } = release();
    assert.equal(result.status, 0, show(result));
    const lines = notes.split('\n');
    assert.match(lines[0], /^The base product's frontend sources for 4\.0\.12, /);
    assert.match(lines[0], /org\.niord:niord-country-kit:4\.0\.12:zip/);
    assert.equal(lines[1], '');
    assert.equal(lines[2], `${ZIP_SHA}  ${ZIP_NAME}`);
    assert.equal(lines[3], `${KIT_SHA}  ${KIT_NAME}`);
    assert.equal(lines[4], '');
    assert.match(lines[5], /^The same version's Maven packages in that registry: /);
    assert.equal(lines.slice(6).join(''), '');
    // Frontend CI's upgrade test and a country's pins read exactly one line of this shape per zip
    const fingerprints = lines.filter((line) => /^[0-9a-f]{64} {2}/.test(line));
    assert.deepEqual(fingerprints, [`${ZIP_SHA}  ${ZIP_NAME}`, `${KIT_SHA}  ${KIT_NAME}`], notes);
    const create = result.calls.find((call) => call.startsWith('gh release create'));
    assert.ok(create, show(result));
    assert.match(create, / --verify-tag /);
    assert.match(create, / --notes-file notes\.md$/);
    assert.match(
      create,
      new RegExp(`^gh release create v${VERSION} fe-src/${ZIP_NAME} kit/${KIT_NAME} `),
    );
    assert.match(result.summary, new RegExp(`${ZIP_SHA}[\\s\\S]*${KIT_SHA}`), result.summary);
  });

  it('refuses a zip that is not the one the build job deployed, making no release', () => {
    const { result } = release({ feSrc: 'f'.repeat(64) });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /::error::fe-src\/niord-fe-src-4\.0\.12\.zip has sha256 /);
    assert.ok(!result.calls.some((call) => call.startsWith('gh release create')));
  });

  it('refuses a kit package that is not the one the build job deployed, making no release', () => {
    const { result } = release({ kit: 'f'.repeat(64) });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /::error::kit\/niord-country-kit-4\.0\.12\.zip has sha256 /);
    assert.ok(!result.calls.some((call) => call.startsWith('gh release create')));
  });

  it('gives, when the release cannot be made, the by-hand commands for both zips and both lines', () => {
    const { result } = release({ created: false });
    assert.equal(result.status, 1, show(result));
    assert.match(result.summary, new RegExp(`sha256sum fe-src/${ZIP_NAME} .*${ZIP_SHA}`));
    assert.match(result.summary, new RegExp(`sha256sum kit/${KIT_NAME} .*${KIT_SHA}`));
    assertByHand(result.summary, { feSrc: ZIP_SHA, kit: KIT_SHA });
  });

  it('gives, when the tag is refused, the by-hand release of both zips with both lines', () => {
    const result = runBash(stepRun(PUBLISH, { job: 'tag', step: 'Tag v' }), {
      env: {
        VERSION,
        SHA,
        FE_SRC_SHA256: ZIP_SHA,
        KIT_SHA256: KIT_SHA,
        GITHUB_RUN_ID: '4242',
        GITHUB_REPOSITORY: REPO,
      },
      fakes: {
        git: [
          { match: 'tag *', out: '' },
          {
            match: 'push *',
            err: '! [remote rejected] refusing to allow a GitHub App to create or update workflow `.github/workflows/x.yml` without `workflows` permission\n',
            exit: 1,
          },
        ],
      },
    });
    assert.equal(result.status, 1, show(result));
    assert.match(result.summary, /org\.niord\.web:niord-fe-src:4\.0\.12:zip/);
    assert.match(result.summary, /org\.niord:niord-country-kit:4\.0\.12:zip/);
    assert.match(result.summary, new RegExp(`^sha256sum fe-src/${ZIP_NAME}$`, 'm'));
    assert.match(result.summary, new RegExp(`^sha256sum kit/${KIT_NAME}$`, 'm'));
    assertByHand(result.summary, { feSrc: ZIP_SHA, kit: KIT_SHA });
  });
});

describe("publish.yml's clean-up of the zips' artifacts", () => {
  it('deletes niord-fe-src and niord-country-kit once the release exists, and no other artifact', () => {
    const artifacts = [
      [11, 'niord-fe-src'],
      [12, 'niord-country-kit'],
      [13, 'web-bundles'],
    ];
    // The list as gh's --jq selects it: an artifact is printed when the filter names it.
    const list = artifacts
      .map(([id, name]) => `    case "$*" in *'.name == "${name}"'*) echo "${id} ${name}" ;; esac`)
      .join('\n');
    const result = runBash(stepRun(PUBLISH, { job: 'tag', step: 'Delete niord-fe-src' }), {
      env: { GH_TOKEN: 'fake', REPO, RUN_ID: '4242' },
      fakes: {
        gh: [
          {
            match: `api repos/${REPO}/actions/runs/4242/artifacts?per_page=100 --jq *`,
            script: list,
          },
          { match: `api -X DELETE repos/${REPO}/actions/artifacts/*`, out: '' },
        ],
      },
    });
    assert.equal(result.status, 0, show(result));
    const deleted = result.calls.filter((call) => call.includes('-X DELETE'));
    assert.deepEqual(
      deleted,
      [11, 12].map((id) => `gh api -X DELETE repos/${REPO}/actions/artifacts/${id}`),
      show(result),
    );
    assert.match(result.stdout, /Deleted niord-fe-src \(artifact 11\)/);
    assert.match(result.stdout, /Deleted niord-country-kit \(artifact 12\)/);
  });
});

describe("fe-ci.yml's previous published zip", () => {
  const ZIP_LINE = `${ZIP_SHA}  ${ZIP_NAME}`;
  const KIT_LINE = `${KIT_SHA}  ${KIT_NAME}`;

  it('reads the notes the tag job writes, and hands on the verified zip and kit package', () => {
    const { notes } = release();
    const result = previous({ notes, kit: KIT, keep: true });
    try {
      assert.equal(result.status, 0, show(result));
      assert.match(result.outputs.zip, new RegExp(`/previous/${ZIP_NAME.replaceAll('.', '[.]')}$`));
      assert.equal(fs.readFileSync(result.outputs.zip).equals(ZIP), true);
      assert.match(result.outputs.kit, new RegExp(`/previous/${KIT_NAME.replaceAll('.', '[.]')}$`));
      assert.equal(fs.readFileSync(result.outputs.kit).equals(KIT), true);
    } finally {
      fs.rmSync(result.dir, { recursive: true, force: true });
    }
  });

  it('fails a release whose notes name no kit package', () => {
    const result = previous({ notes: `intro\n\n${ZIP_LINE}\n` });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /::error::v4\.0\.12 names no niord-country-kit line in its notes/);
    assert.equal(result.outputs.zip, undefined);
    assert.equal(result.outputs.kit, undefined);
    assert.ok(!result.calls.some((call) => call.includes(`--pattern ${KIT_NAME}`)), show(result));
  });

  it('fails on notes that name the kit package without the release having it', () => {
    const result = previous({ notes: `${ZIP_LINE}\n${KIT_LINE}\n` });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /has no asset niord-country-kit-4\.0\.12\.zip/);
    assert.equal(result.outputs.kit, undefined);
  });

  it('fails on notes that name the kit package on two lines', () => {
    const result = previous({ notes: `${ZIP_LINE}\n${KIT_LINE}\n${KIT_LINE}\n`, kit: KIT });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /::error::.*niord-country-kit-4\.0\.12\.zip.*exactly one line/);
    assert.equal(result.outputs.kit, undefined);
  });

  it('fails on a kit package whose hash is not the one the notes record', () => {
    const result = previous({
      notes: `${ZIP_LINE}\n${'0'.repeat(64)}  ${KIT_NAME}\n`,
      kit: KIT,
    });
    assert.equal(result.status, 1, show(result));
    assert.match(
      result.stdout,
      /::error::niord-country-kit-4\.0\.12\.zip of v4\.0\.12 has sha256 [0-9a-f]{64}, but its release notes record 0{64}: refusing an unverified previous kit/,
    );
    assert.equal(result.outputs.kit, undefined);
  });

  it('skips the upgrade test when there is no release yet', () => {
    const result = previous({ tag: '' });
    assert.equal(result.status, 0, show(result));
    assert.equal(result.outputs.zip, '');
    assert.equal(result.outputs.kit, '');
    assert.match(result.summary, /Upgrade test: skipped/);
  });

  it('gives the harness --upgrade-kit-from only when the step found a kit package', () => {
    const step = { job: 'example', step: 'Set up, build, test and lint the example' };
    const env = stepEnv(FE_CI, step);
    assert.equal(env.UPGRADE_FROM, '${{ steps.previous.outputs.zip }}');
    assert.equal(env.UPGRADE_KIT_FROM, '${{ steps.previous.outputs.kit }}');
    const harness = (values) =>
      runBash(stepRun(FE_CI, step), {
        env: values,
        fakes: { node: [{ match: '*', out: '' }] },
      });
    const both = harness({ UPGRADE_FROM: '/p/zip.zip', UPGRADE_KIT_FROM: '/p/kit.zip' });
    assert.equal(both.status, 0, show(both));
    assert.match(both.calls[0], / --upgrade-from \/p\/zip\.zip --upgrade-kit-from \/p\/kit\.zip$/);
    const zipOnly = harness({ UPGRADE_FROM: '/p/zip.zip', UPGRADE_KIT_FROM: '' });
    assert.match(zipOnly.calls[0], / --upgrade-from \/p\/zip\.zip$/);
    const none = harness({ UPGRADE_FROM: '', UPGRADE_KIT_FROM: '' });
    assert.match(none.calls[0], / --with-deps$/);
  });

  it('fails on a latest release that is not a v4.0.<run>', () => {
    const result = previous({ tag: 'v5.1.0', notes: `${ZIP_SHA}  niord-fe-src-5.1.0.zip\n` });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /The latest release is 'v5\.1\.0'/);
  });

  it('fails on notes that name the zip on two lines', () => {
    const line = `${ZIP_SHA}  ${ZIP_NAME}`;
    const result = previous({ notes: `${line}\n${line}\n` });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /exactly one line/);
  });

  it('fails on a zip whose hash is not the one the notes record', () => {
    const result = previous({ notes: `${'0'.repeat(64)}  ${ZIP_NAME}\n` });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /but its release notes record 0{64}/);
  });

  it("reads the version's dots as dots: a look-alike name is no line for the zip", () => {
    const result = previous({ notes: `intro\n\n${ZIP_SHA}  niord-fe-src-4x0x12.zip\n` });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /exactly one line/);
    assert.equal(result.outputs.zip, undefined);
  });
});

// ---------------------------------------------------------------------------------------------
// fe-ci.yml's steps: the scripts' lint, and the example's base image
// ---------------------------------------------------------------------------------------------

describe("fe-ci.yml's steps", () => {
  it('lints the scripts in Checks + builds', () => {
    assert.equal(
      stepRun(FE_CI, { job: 'checks', step: 'Lint the scripts' }),
      'npm run lint:scripts\n',
    );
  });

  // A public mirror's anonymous limit is per address as Docker Hub's is, and the runners share addresses.
  it("sets no ALPINE_IMAGE for the example, so the Dockerfile's default base stands", () => {
    const dockerfile = fs.readFileSync(
      path.join(ROOT, 'fe', 'ui-apps', 'info', 'Dockerfile'),
      'utf8',
    );
    assert.match(
      dockerfile.replaceAll('\r', ''),
      /^ARG ALPINE_IMAGE=alpine:\S+$/m,
      'the Dockerfile has no ARG ALPINE_IMAGE=alpine:<tag>',
    );
    const env = stepEnv(FE_CI, {
      job: 'example',
      step: 'Set up, build, test and lint the example',
    });
    assert.equal(env.ALPINE_IMAGE, undefined, 'the example step sets ALPINE_IMAGE');
    // Nor the job's or the workflow's env.
    assert.doesNotMatch(FE_CI.replaceAll('\r', ''), /^\s*ALPINE_IMAGE\s*:/m);
  });
});

// ---------------------------------------------------------------------------------------------
// publish.yml's administration bundle
// ---------------------------------------------------------------------------------------------

/** The deploy's `-D<name>=` argument split at its commas, or undefined when it is not given. */
const listArgument = (args, name) =>
  args
    .find((arg) => arg.startsWith(`-D${name}=`))
    ?.slice(name.length + 3)
    .split(',');

/**
 * Writes `be/mvnw` under `tmp`: a stub that records its arguments one per line in $MVNW_ARGS and
 * prints the upload line Maven prints for `file`, the path under the registry.
 */
function mvnwStub(tmp, file) {
  fs.mkdirSync(path.join(tmp, 'be'), { recursive: true });
  const mvnw = path.join(tmp, 'be', 'mvnw');
  fs.writeFileSync(
    mvnw,
    [
      '#!/usr/bin/env bash',
      'printf \'%s\\n\' "$@" > "$MVNW_ARGS"',
      `echo "Uploaded to github: https://maven.pkg.github.com/NiordOrg/niord/${file} (1 B at 1 B/s)"`,
      '',
    ].join('\n'),
    { mode: 0o755 },
  );
  fs.chmodSync(mvnw, 0o755);
}

/** The arguments the ./mvnw stub recorded in `file`, or none when it was not run. */
const mvnwArgs = (file) =>
  fs.existsSync(file)
    ? fs.readFileSync(file, 'utf8').replaceAll('\r', '').split('\n').filter(Boolean)
    : [];

/**
 * Runs web-publish's zip step and then its deploy step in `tmp`, a folder the caller makes and
 * removes, given the en bundle alone, as the web job hands it on. zip is a fake that writes what
 * it was run in and on (Git Bash on Windows has no zip; on Linux the fake comes first on PATH as
 * well), and ./mvnw a stub (mvnwStub). Returns both results and the deploy's arguments.
 */
function webPublish(tmp) {
  const bundle = path.join(tmp, 'fe', 'web', 'niord-app');
  fs.mkdirSync(bundle, { recursive: true });
  fs.writeFileSync(path.join(bundle, 'index.html'), '<!doctype html>\n');
  mvnwStub(tmp, `org/niord/web/niord-web-admin/${VERSION}/niord-web-admin-${VERSION}-en.zip`);
  const zip = runBash(stepRun(PUBLISH, { job: 'web-publish', step: 'Zip the bundle' }), {
    cwd: path.join(tmp, 'fe'),
    env: { VERSION },
    fakes: {
      zip: [
        {
          match: '-qr -X *',
          script: [
            '    printf \'zip of %s\\n\' "$(basename "$PWD")" > "$3"',
            '    ls -A >> "$3"',
          ].join('\n'),
        },
      ],
    },
  });
  const argsFile = path.join(tmp, 'mvnw-args');
  const deploy = runBash(stepRun(PUBLISH, { job: 'web-publish', step: 'Deploy niord-web-admin' }), {
    cwd: path.join(tmp, 'be'),
    env: {
      GITHUB_WORKSPACE: tmp.replaceAll('\\', '/'),
      VERSION,
      MVNW_ARGS: argsFile.replaceAll('\\', '/'),
    },
  });
  return { zip, deploy, args: mvnwArgs(argsFile) };
}

describe("publish.yml's administration bundle", () => {
  it('zips the en bundle from inside it and deploys that one zip under the classifier en', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-web-admin-'));
    try {
      const { zip, deploy, args } = webPublish(tmp);
      assert.equal(zip.status, 0, show(zip));
      assert.equal(deploy.status, 0, show(deploy));
      const pkg = `${tmp.replaceAll('\\', '/')}/fe/pkg`;
      assert.deepEqual(
        listArgument(args, 'files'),
        [`${pkg}/niord-web-admin-en.zip`],
        args.join('\n'),
      );
      assert.deepEqual(listArgument(args, 'classifiers'), ['en'], args.join('\n'));
      assert.deepEqual(listArgument(args, 'types'), ['zip'], args.join('\n'));
      const zipFile = path.join(tmp, 'fe', 'pkg', 'niord-web-admin-en.zip');
      assert.ok(fs.existsSync(zipFile), show(zip));
      const listing = fs.readFileSync(zipFile, 'utf8').replaceAll('\r', '').split('\n');
      assert.equal(listing[0], 'zip of niord-app');
      assert.ok(listing.includes('index.html'), listing.join('\n'));
      const pom = fs.readFileSync(path.join(tmp, 'fe', 'pkg', 'niord-web-admin.pom'), 'utf8');
      const description = /<description>(.*)<\/description>/.exec(pom)?.[1];
      assert.ok(description, pom);
      assert.match(description, /classifier en\b/);
      assert.doesNotMatch(description, /\b(dk|gl)\b/);
      assert.match(
        deploy.summary,
        new RegExp(`niord-web-admin-${VERSION.replaceAll('.', '[.]')}-en[.]zip`),
      );
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  });

  it('is built by the web job from the generic build alone', () => {
    const build = stepRun(PUBLISH, { job: 'web', step: 'Build the' });
    assert.ok(build.includes('npm run -s build -- --output-path out/niord-app'), build);
    assert.doesNotMatch(build, /build:(dk|gl)|niord-(dk|gl)/);
  });
});

// ---------------------------------------------------------------------------------------------
// The published packages: publish.yml's deploys, retract-version.yml's list, the release notes
// ---------------------------------------------------------------------------------------------

const BE_CI = workflow('be-ci.yml');
const BE = path.join(ROOT, 'be');

/** A workflow's text without its comment lines. */
const code = (text) =>
  text
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('#'))
    .join('\n');

/** The text of the job `name`: from its line under jobs: to the next job's. */
function jobText(text, name) {
  const from = text.search(new RegExp(`^ {2}${name}:\\r?$`, 'm'));
  assert.ok(from >= 0, `the workflow has no job ${name}`);
  const next = text.slice(from + 1).search(/^ {2}[a-z-]+:\r?$/m);
  return next < 0 ? text.slice(from) : text.slice(from, from + 1 + next);
}

/** The coordinates of the pom `file`: its own groupId, else its parent's. */
function pomCoordinates(file) {
  const pom = fs.readFileSync(file, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const parent = /<parent>([\s\S]*?)<\/parent>/.exec(pom)?.[1] ?? '';
  const own = pom.replace(/<parent>[\s\S]*?<\/parent>/, '');
  const artifact = /<artifactId>([^<]+)<\/artifactId>/.exec(own);
  assert.ok(artifact, `${file} has no artifactId`);
  const group =
    /<groupId>([^<]+)<\/groupId>/.exec(own.slice(0, artifact.index)) ??
    /<groupId>([^<]+)<\/groupId>/.exec(parent);
  assert.ok(group, `${file} has no groupId, nor has its parent`);
  return { group: group[1].trim(), artifact: artifact[1].trim() };
}

/** The reactor's poms, be/pom.xml and be/<module>/pom.xml, by artifactId. */
const MODULES = new Map(
  ['pom.xml', ...fs.readdirSync(BE).map((dir) => path.join(dir, 'pom.xml'))]
    .filter((file) => fs.existsSync(path.join(BE, file)))
    .map((file) => pomCoordinates(path.join(BE, file)))
    .map((coordinates) => [coordinates.artifact, coordinates]),
);

const key = ({ group, artifact }) => `${group}.${artifact}`;

/**
 * What publish.yml deploys: the modules of the build job's `-pl` list, by their poms'
 * coordinates, and every `deploy:deploy-file` invocation's -DgroupId and -DartifactId.
 */
function publishedPackages() {
  const lists = [...code(jobText(PUBLISH, 'build')).matchAll(/ -pl (:[\w-]+(?:,:[\w-]+)*)/g)];
  assert.equal(lists.length, 1, 'the build job deploys one -pl list');
  const packages = lists[0][1].split(',').map((module) => {
    const coordinates = MODULES.get(module.slice(1));
    assert.ok(coordinates, `-pl names ${module}, which is no module of be/`);
    return coordinates;
  });
  const deploys = code(PUBLISH).split('deploy:deploy-file').slice(1);
  assert.ok(deploys.length >= 5, `${deploys.length} deploy-file invocations`);
  for (const invocation of deploys) {
    const group = /-DgroupId=([\w.-]+)/.exec(invocation)?.[1];
    const artifact = /-DartifactId=([\w-]+)/.exec(invocation)?.[1];
    assert.ok(group && artifact, `a deploy-file without coordinates: ${invocation.slice(0, 300)}`);
    packages.push({ group, artifact });
  }
  return packages;
}

/** The packages retract-version.yml's Packages step deletes the version from. */
function retractedPackages() {
  const loop = /for pkg in ([^;\n]+); do/.exec(
    stepRun(RETRACT, { job: 'retract', step: 'GitHub Packages' }),
  );
  assert.ok(loop, 'retract-version.yml has no `for pkg in …; do` loop');
  return loop[1].trim().split(/\s+/);
}

/** The `path:` values of every actions/upload-artifact step of a workflow. */
function uploadPaths(text) {
  const lines = code(text).replaceAll('\r', '').split('\n');
  const paths = [];
  lines.forEach((line, i) => {
    if (!line.includes('actions/upload-artifact')) return;
    for (let j = i + 1; j < lines.length && !/^\s*- |^ {2}[a-z-]+:$/.test(lines[j]); j++) {
      const value = /^\s*path:\s*(.*)$/.exec(lines[j]);
      if (value) paths.push(value[1]);
    }
  });
  return paths;
}

describe('the published packages', () => {
  it("are the same in publish.yml's deploys and retract-version.yml's list, niord-local-run and niord-country-kit among them", () => {
    const published = publishedPackages().map(key).sort();
    assert.equal(new Set(published).size, published.length, `deployed twice: ${published}`);
    assert.deepEqual(retractedPackages().sort(), published);
    assert.ok(published.includes('org.niord.niord-local-run'), published.join('\n'));
    assert.ok(published.includes('org.niord.niord-country-kit'), published.join('\n'));
  });

  it('are each named in the release notes, as group:artifact, on one line of their own', () => {
    const { result, notes } = release();
    assert.equal(result.status, 0, show(result));
    const line = notes
      .split('\n')
      .find((text) => text.startsWith("The same version's Maven packages in that registry: "));
    assert.ok(line, notes);
    const named = [...line.matchAll(/\borg\.niord[\w.]*:[\w-]+/g)].map((m) => m[0]).sort();
    const published = publishedPackages().map(({ group, artifact }) => `${group}:${artifact}`);
    assert.deepEqual(named, published.sort(), line);
  });

  it('never hand the local-run zip to an image: no image job, artifact or no-sample check names it', () => {
    for (const job of ['harbor', 'web', 'web-publish'])
      assert.doesNotMatch(jobText(PUBLISH, job), /local-run/, `the ${job} job`);
    const paths = uploadPaths(PUBLISH);
    assert.ok(paths.length >= 4, `the upload-artifact paths: ${paths}`);
    for (const value of paths) assert.doesNotMatch(value, /local-run/);
    for (const [name, text, least] of [
      ['publish.yml', PUBLISH, 2],
      ['be-ci.yml', BE_CI, 1],
    ]) {
      const calls = code(text)
        .split('\n')
        .filter((line) => line.includes('check-no-sample.sh'));
      assert.ok(calls.length >= least, `${name}'s check-no-sample calls: ${calls}`);
      for (const call of calls) assert.doesNotMatch(call, /local-run/, `${name}: ${call}`);
    }
  });

  it('build the local-run zip before the first deploy, without the publishing token', () => {
    const build = jobText(PUBLISH, 'build');
    const step = stepRun(PUBLISH, { job: 'build', step: 'Build the local-run zip' });
    assert.match(step, /scripts\/local-run-zip\.sh "\$RUNNER_TEMP\/niord-local-run\.zip"/);
    const env = stepEnv(PUBLISH, { job: 'build', step: 'Build the local-run zip' });
    assert.deepEqual(Object.keys(env), [], 'no env, so no token');
    const at = build.indexOf('- name: Build the local-run zip');
    const deploy = build.indexOf('- name: Deploy ');
    assert.ok(at >= 0 && deploy > at, 'built before the first deploy step');
  });

  it('deploy the local-run zip with deploy-file beside the image tools', () => {
    const where = { job: 'build', step: 'Deploy niord-local-run' };
    deployFile(where, { artifact: 'niord-local-run', file: 'niord-local-run.zip' });
    const env = stepEnv(PUBLISH, where);
    assert.deepEqual(Object.keys(env).sort(), ['NIORD_PUBLISH_TOKEN', 'NIORD_PUBLISH_USER']);
  });

  it('build and check the kit package before setup-java and the first deploy, without the publishing token', () => {
    const build = jobText(PUBLISH, 'build').replaceAll('\r', '');
    const name = 'Build and check the kit package';
    // Each line trimmed: the run block keeps its indent relative to the comments that follow it.
    const step = stepRun(PUBLISH, { job: 'build', step: name })
      .split('\n')
      .map((line) => line.trim())
      .join('\n');
    const out = '"$RUNNER_TEMP/niord-country-kit-$VERSION.zip"';
    assert.ok(step.includes(`out=${out}`), step);
    assert.match(
      step,
      /^node \.\.\/country-kit\/source-zip\.mjs build --package kit --version "\$VERSION" --out "\$out"$/m,
    );
    for (const command of ['verify', 'guard'])
      assert.match(
        step,
        new RegExp(
          `^node \\.\\./country-kit/source-zip\\.mjs ${command} --package kit "\\$out"$`,
          'm',
        ),
      );
    assert.match(step, /^echo "sha256=\$sha" >> "\$GITHUB_OUTPUT"$/m);
    assert.deepEqual(Object.keys(stepEnv(PUBLISH, { job: 'build', step: name })), [], 'no env');
    const at = build.indexOf(`- name: ${name}`);
    assert.ok(at >= 0, `the build job has no step ${name}`);
    const block = build.slice(at, build.indexOf('\n      - ', at + 1));
    assert.match(block, /^ {8}id: kit$/m, block);
    assert.match(block, /^ {8}working-directory: fe$/m, block);
    const setupJava = build.indexOf('uses: actions/setup-java');
    const deploy = build.indexOf('- name: Deploy ');
    assert.ok(at >= 0 && setupJava > at && deploy > at, 'built before setup-java and the deploys');
    assert.match(build, /^ {6}kit_sha256: \$\{\{ steps\.kit\.outputs\.sha256 \}\}$/m);
    assert.match(build, /^ {6}fe_src_sha256: \$\{\{ steps\.fe-src\.outputs\.sha256 \}\}$/m);
  });

  it('deploy the kit package with deploy-file, its sha256 in the summary', () => {
    const where = { job: 'build', step: 'Deploy niord-country-kit' };
    const { deploy } = deployFile(where, {
      artifact: 'niord-country-kit',
      file: `niord-country-kit-${VERSION}.zip`,
      env: { KIT_SHA256: KIT_SHA },
    });
    assert.ok(deploy.summary.includes(`${KIT_SHA}  ${KIT_NAME}`), deploy.summary);
    const env = stepEnv(PUBLISH, where);
    assert.deepEqual(Object.keys(env).sort(), [
      'KIT_SHA256',
      'NIORD_PUBLISH_TOKEN',
      'NIORD_PUBLISH_USER',
    ]);
    assert.equal(env.KIT_SHA256, '${{ steps.kit.outputs.sha256 }}');
  });

  it('hand the kit package to the tag job as the artifact niord-country-kit, which it fetches into kit/', () => {
    assert.ok(
      uploadPaths(PUBLISH).includes('${{ runner.temp }}/niord-country-kit-*.zip'),
      uploadPaths(PUBLISH).join('\n'),
    );
    const hand = jobText(PUBLISH, 'build').replaceAll('\r', '');
    const at = hand.indexOf('- name: Hand the kit package to the tag job');
    assert.ok(at >= 0, 'no step hands the kit package on');
    assert.match(hand.slice(at), /^ {10}name: niord-country-kit$/m);
    const tag = jobText(PUBLISH, 'tag').replaceAll('\r', '');
    const fetch = tag.indexOf('- name: Fetch the kit package');
    assert.ok(fetch >= 0, 'the tag job does not fetch the kit package');
    assert.match(tag.slice(fetch), /^ {10}name: niord-country-kit\n {10}path: kit$/m);
  });
});

/**
 * Runs the build job's deploy step `where` with a stub ./mvnw (mvnwStub) in a folder of its own,
 * the zip `file` under RUNNER_TEMP, and checks the deploy-file arguments for org.niord:`artifact`
 * and the upload line in the summary. Returns the result and the arguments.
 */
function deployFile(where, { artifact, file, env = {} }) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), `${artifact}-`));
  try {
    mvnwStub(tmp, `org/niord/${artifact}/${VERSION}/${artifact}-${VERSION}.zip`);
    const runnerTemp = path.join(tmp, 'runner-temp');
    fs.mkdirSync(runnerTemp);
    const argsFile = path.join(tmp, 'mvnw-args');
    const deploy = runBash(stepRun(PUBLISH, where), {
      cwd: path.join(tmp, 'be'),
      env: {
        VERSION,
        RUNNER_TEMP: runnerTemp.replaceAll('\\', '/'),
        MVNW_ARGS: argsFile.replaceAll('\\', '/'),
        ...env,
      },
    });
    assert.equal(deploy.status, 0, show(deploy));
    const args = mvnwArgs(argsFile);
    for (const arg of [
      '-N',
      'deploy:deploy-file',
      '-Durl=https://maven.pkg.github.com/NiordOrg/niord',
      '-DrepositoryId=github',
      '-DgroupId=org.niord',
      `-DartifactId=${artifact}`,
      `-Dversion=${VERSION}`,
      '-Dpackaging=zip',
      '-DgeneratePom=true',
      `-Dfile=${runnerTemp.replaceAll('\\', '/')}/${file}`,
    ])
      assert.ok(args.includes(arg), `${arg} missing from:\n${args.join('\n')}`);
    assert.match(
      deploy.summary,
      new RegExp(`org/niord/${artifact}/${VERSION.replaceAll('.', '[.]')}/${artifact}-`),
    );
    return { deploy, args };
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------------------------
// retract-version.yml
// ---------------------------------------------------------------------------------------------

const AUTHORISE = stepRun(RETRACT, { job: 'authorise', step: 'Maintainer, main' });
const VALIDATE = { job: 'retract', step: 'Validate input' };
const PACKAGES = stepRun(RETRACT, { job: 'retract', step: 'GitHub Packages of NiordOrg/niord' });

/** The validation step's bash for 4.0.140, `hasToken` the true or false its env hands it. */
const validate = (hasToken) =>
  runBash(stepRun(RETRACT, VALIDATE), {
    env: { VERSION: '4.0.140', REASON: 'a bad build', HAS_DELETE_TOKEN: hasToken },
  });

const NO_TOKEN =
  '::error::The retract environment holds no NIORD_DELETE_TOKEN; nothing was retracted.';

/**
 * The retract job's steps in order, each as `{ name, id, if, uses }` from its own keys (the
 * name of a `uses:` step without one is its action).
 */
function retractSteps() {
  const steps = [];
  for (const line of code(jobText(RETRACT, 'retract')).replaceAll('\r', '').split('\n')) {
    const item = /^ {6}- ([a-z-]+):\s*(.*)$/.exec(line);
    const key = item ?? /^ {8}([a-z-]+):\s*(.*)$/.exec(line);
    if (item) steps.push({});
    if (key && steps.length) steps.at(-1)[key[1]] = key[2].trim();
  }
  return steps.map((step) => ({ ...step, name: step.name ?? step.uses }));
}

function authorise({ actor = MAINTAINER, environment = 'false true' } = {}) {
  return runBash(AUTHORISE, {
    env: { GH_TOKEN: 'fake', REPO, ACTOR: actor, GITHUB_REF: 'refs/heads/main' },
    fakes: {
      gh: [
        { match: `*repos/${REPO}/contents/.github/maintainers?ref=main`, out: `${MAINTAINER}\n` },
        {
          match: `api repos/${REPO}/environments/retract/deployment-branch-policies *`,
          out: 'branch:main\n',
        },
        environment === null
          ? {
              match: `api repos/${REPO}/environments/retract *`,
              err: 'gh: Not Found (HTTP 404)\n',
              exit: 1,
            }
          : { match: `api repos/${REPO}/environments/retract *`, out: `${environment}\n` },
      ],
    },
  });
}

const PACKAGE_BASE = 'orgs/NiordOrg/packages/maven';

/**
 * The registry as a fake gh: each package exists with 150 versions over two pages, 4.0.1 to
 * 4.0.150, ids 1001 to 1150. Asked without --paginate, gh returns the first page only, as JSON
 * when no --jq shapes it. `fail` answers the versions with an error instead; `missing` names
 * packages that do not exist.
 */
function registryGh({ fail, missing = [] } = {}) {
  const versions = Array.from({ length: 150 }, (_, i) => ({ id: 1001 + i, name: `4.0.${i + 1}` }));
  const lines = (list) => list.map((v) => `${v.id} ${v.name}\n`).join('');
  const answers = [];
  for (const pkg of missing)
    answers.push({
      match: `api ${PACKAGE_BASE}/${pkg}*`,
      err: 'gh: Package not found. (HTTP 404)\n',
      exit: 1,
    });
  answers.push(
    { match: `api -X DELETE ${PACKAGE_BASE}/*`, out: '' },
    fail
      ? { match: `api --paginate ${PACKAGE_BASE}/*/versions?per_page=100*`, err: fail, exit: 1 }
      : {
          match: `api --paginate ${PACKAGE_BASE}/*/versions?per_page=100 --jq *`,
          out: lines(versions),
        },
    {
      match: `api ${PACKAGE_BASE}/*/versions?per_page=100 --jq *`,
      out: lines(versions.slice(0, 100)),
    },
    {
      match: `api ${PACKAGE_BASE}/*/versions?per_page=100`,
      out: JSON.stringify(versions.slice(0, 100)),
    },
    { match: `api ${PACKAGE_BASE}/*`, out: '{}\n' },
  );
  return answers;
}

/**
 * Runs the packages step for `version` on registryGh(gh). Eight packages make some 30 fake gh
 * calls, each a bash start: on a Windows workstation under a full suite's load that passes
 * runBash's default 60 s, so the run gets three minutes.
 */
const packages = (version, gh) =>
  runBash(PACKAGES, {
    env: { GH_TOKEN: 'fake', VERSION: version },
    fakes: { gh: registryGh(gh) },
    timeout: 180_000,
  });

describe('retract-version.yml', () => {
  it('lets a maintainer retract from main while retract allows main only', () => {
    const result = authorise();
    assert.equal(result.status, 0, show(result));
  });

  it('refuses a login not on the maintainer list', () => {
    const result = authorise({ actor: 'someone-else' });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /'someone-else' is not on \.github\/maintainers/);
  });

  it('refuses while the retract environment does not exist', () => {
    const result = authorise({ environment: null });
    assert.equal(result.status, 1, show(result));
    assert.match(result.stdout, /Environment 'retract' does not exist or cannot be read/);
  });

  it('refuses the whole run, before anything is deleted, while retract holds no NIORD_DELETE_TOKEN', () => {
    const result = validate('false');
    assert.equal(result.status, 1, show(result));
    assert.deepEqual(outputLines(result.stdout), [NO_TOKEN]);
    assert.equal(result.summary, '');
  });

  it('goes on to the deleting steps while the token is there', () => {
    const result = validate('true');
    assert.equal(result.status, 0, show(result));
    assert.equal(result.stdout, '');
    assert.match(result.summary, /^## Retract 4\.0\.140\n/);
  });

  it("checks the token's presence only, through env, in the first step, which every deleting step needs", () => {
    assert.equal(
      stepEnv(RETRACT, VALIDATE).HAS_DELETE_TOKEN,
      "${{ secrets.NIORD_DELETE_TOKEN != '' }}",
    );
    assert.ok(!stepRun(RETRACT, VALIDATE).includes('${{'));
    const steps = retractSteps();
    assert.equal(steps[0]?.id, 'validate', JSON.stringify(steps));
    // A step without if: runs only when every step before it succeeded; one with this if: runs
    // after the validation succeeded, a failed Packages step (a token the registry refuses) or not.
    const afterValidation = "${{ !cancelled() && steps.validate.outcome == 'success' }}";
    for (const step of steps.slice(1))
      assert.ok(
        step.if === undefined || step.if === afterValidation,
        `${step.name}: if: ${step.if}`,
      );
    for (const deleting of ['GitHub Packages of NiordOrg/niord', 'GitHub release', 'Tag'])
      assert.ok(
        steps.slice(1).some((step) => step.name === deleting),
        `no step ${deleting} after the validation`,
      );
    assert.equal(steps.find((step) => step.name === 'GitHub release')?.if, afterValidation);
    assert.equal(steps.find((step) => step.name === 'Tag')?.if, afterValidation);
  });

  it('finds a version on the second page of a package and deletes it', () => {
    const result = packages('4.0.140');
    assert.equal(result.status, 0, show(result));
    assert.ok(
      result.calls.includes(
        `gh api -X DELETE ${PACKAGE_BASE}/org.niord.web.niord-fe-src/versions/1140`,
      ),
      show(result),
    );
    assert.match(result.summary, /org\.niord\.web\.niord-fe-src 4\.0\.140 \(id 1140\): deleted/);
    assert.match(result.summary, /org\.niord\.niord-country-kit 4\.0\.140 \(id 1140\): deleted/);
  });

  it('fails when the registry cannot be read, rather than reporting the version absent', () => {
    const result = packages('4.0.140', { fail: 'gh: Bad credentials (HTTP 401)\n' });
    assert.notEqual(result.status, 0, show(result));
    assert.ok(!result.calls.some((call) => call.includes('-X DELETE')), show(result));
    assert.doesNotMatch(result.summary, /not present/);
  });

  it('reports a package that does not exist yet as not present, and goes on', () => {
    const result = packages('4.0.140', { missing: ['org.niord.niord-bootstrap'] });
    assert.equal(result.status, 0, show(result));
    assert.match(result.summary, /org\.niord\.niord-bootstrap 4\.0\.140: not present/);
    assert.ok(
      result.calls.includes(
        `gh api -X DELETE ${PACKAGE_BASE}/org.niord.web.niord-fe-src/versions/1140`,
      ),
      show(result),
    );
  });
});

// ---------------------------------------------------------------------------------------------
// publish.yml: the bump job and the gate's check of the bump app's key
// ---------------------------------------------------------------------------------------------

const BUMP = { job: 'bump', step: 'Open or move the bump pull request in each country' };
const KEY_CHECK = { job: 'gate', step: 'Check where the bump app key is' };

/** A text's code lines: no comment line and no blank one, and no CR. */
const lines = (text) =>
  code(text.replaceAll('\r', ''))
    .split('\n')
    .filter((line) => line.trim());

/** A run's output as lines, without CR and without blank ones. */
const outputLines = (text) => text.replaceAll('\r', '').split('\n').filter(Boolean);

/** The app's key as the fake node sees it: two lines, and not shaped like a key file. */
const FAKE_KEY = 'not-a-key line one\nnot-a-key line two\n';
const NOTICE =
  '::notice::No bump pull requests: the publish environment has no NIORD_BUMP_APP_ID or NIORD_BUMP_APP_PRIVATE_KEY (the Niord base bump app). A country pushes base-bump/4.0.77 by hand.';
const SKIPPED =
  '## Bump pull requests for 4.0.77\n\nNot opened: the Niord base bump app is not set up on the publish environment.\n';
const WARNING =
  "::warning::NIORD_BUMP_APP_PRIVATE_KEY is a repository secret: any branch's copy of a workflow can read it; move it to Settings > Environments > publish and delete the repository secret";

/**
 * The bump step's own bash for 4.0.77, the app's id and key set unless `env` says otherwise:
 * runBash hands on this process's environment, which may hold either.
 */
function bumpStep(env = {}, options = {}) {
  return runBash(stepRun(PUBLISH, BUMP), {
    env: {
      VERSION: '4.0.77',
      NIORD_BUMP_APP_ID: '4242',
      NIORD_BUMP_APP_PRIVATE_KEY: FAKE_KEY,
      BASE_REPO: 'o0/base',
      RUN_URL: 'https://ci.example/run/1',
      ...env,
    },
    ...options,
  });
}

describe("publish.yml: the gate's check of the bump app's key", () => {
  it('warns while the bump app key resolves in the gate, a job with no environment', () => {
    const result = runBash(stepRun(PUBLISH, KEY_CHECK), { env: { REPO_LEVEL: 'true' } });
    assert.equal(result.status, 0, show(result));
    assert.deepEqual(
      outputLines(result.stdout).filter((line) => line.startsWith('::warning::')),
      [WARNING],
    );
    assert.equal(result.summary, '');
  });

  it('says nothing while it does not', () => {
    const result = runBash(stepRun(PUBLISH, KEY_CHECK), { env: { REPO_LEVEL: 'false' } });
    assert.equal(result.status, 0, show(result));
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    assert.equal(result.summary, '');
  });

  it('hands the step the boolean only, in a job that names no environment', () => {
    assert.deepEqual(stepEnv(PUBLISH, KEY_CHECK), {
      REPO_LEVEL: "${{ secrets.NIORD_BUMP_APP_PRIVATE_KEY != '' }}",
    });
    assert.ok(!stepRun(PUBLISH, KEY_CHECK).includes('${{'));
    assert.doesNotMatch(code(jobText(PUBLISH, 'gate')), /^ {4}environment:/m);
    const decide = Object.keys(stepEnv(PUBLISH, { job: 'gate', step: 'Decide' }));
    assert.deepEqual(
      decide.filter((name) => /BUMP/.test(name)),
      [],
    );
  });
});

describe("publish.yml's bump job", () => {
  it('runs after the gate and the tag, on the publish environment, with contents: read, continue-on-error and a concurrency group of its own', () => {
    const job = lines(jobText(PUBLISH, 'bump'));
    const at = job.indexOf('    steps:');
    assert.ok(at > 0, job.join('\n'));
    assert.deepEqual(job.slice(0, at), [
      '  bump:',
      '    name: Open the bump pull requests',
      '    needs: [ gate, tag ]',
      "    if: needs.gate.outputs.publish == 'true'",
      '    runs-on: ubuntu-latest',
      '    timeout-minutes: 10',
      '    continue-on-error: true',
      '    environment: publish',
      '    permissions:',
      '      contents: read',
      '    concurrency:',
      '      group: niord-base-bump',
      '      cancel-in-progress: false',
      '    env:',
      '      VERSION: ${{ needs.gate.outputs.version }}',
    ]);
  });

  it('checks out fe/scripts at the published commit without a git credential, and sets up Node without npm', () => {
    const job = lines(jobText(PUBLISH, 'bump'));
    const at = job.indexOf('    steps:');
    const step = job.findIndex((line) => line.startsWith('      - name: Open or move'));
    assert.ok(at > 0 && step > at, job.join('\n'));
    assert.deepEqual(job.slice(at + 1, step), [
      '      - uses: actions/checkout@v4',
      '        with:',
      '          ref: ${{ needs.gate.outputs.sha }}',
      '          sparse-checkout: fe/scripts',
      '          persist-credentials: false',
      '      - uses: actions/setup-node@v4',
      '        with:',
      '          node-version-file: fe/.nvmrc',
    ]);
    assert.equal(job.filter((line) => /^\s*(- )?uses:/.test(line)).length, 2, job.join('\n'));
    assert.deepEqual(
      job.filter((line) => /npm|cache/.test(line)),
      [],
    );
  });

  it("is the last job, and holds no token but the app's key and id", () => {
    const all = PUBLISH.replaceAll('\r', '').split('\n');
    const jobs = all.findIndex((line) => line === 'jobs:');
    assert.ok(jobs >= 0);
    const names = all
      .slice(jobs + 1)
      .map((line) => /^ {2}([a-z-]+):$/.exec(line)?.[1])
      .filter(Boolean);
    assert.deepEqual(names.slice(-2), ['tag', 'bump'], names.join(', '));
    const bump = code(jobText(PUBLISH, 'bump'));
    assert.doesNotMatch(bump, /github\.token|GITHUB_TOKEN|GH_TOKEN|persist-credentials: true/);
    const codeLines = lines(PUBLISH);
    assert.deepEqual(
      codeLines
        .filter((line) => line.includes('secrets.NIORD_BUMP_APP_PRIVATE_KEY'))
        .map((line) => line.trim()),
      [
        "REPO_LEVEL: ${{ secrets.NIORD_BUMP_APP_PRIVATE_KEY != '' }}",
        'NIORD_BUMP_APP_PRIVATE_KEY: ${{ secrets.NIORD_BUMP_APP_PRIVATE_KEY }}',
      ],
    );
    assert.deepEqual(
      codeLines
        .filter((line) => line.includes('vars.NIORD_BUMP_APP_ID'))
        .map((line) => line.trim()),
      ['NIORD_BUMP_APP_ID: ${{ vars.NIORD_BUMP_APP_ID }}'],
    );
    assert.equal(lines(bump).filter((line) => line.includes('secrets.')).length, 1, bump);
  });

  it('passes the key and the id by env only; its command carries neither --api nor the key', () => {
    assert.deepEqual(stepEnv(PUBLISH, BUMP), {
      NIORD_BUMP_APP_ID: '${{ vars.NIORD_BUMP_APP_ID }}',
      NIORD_BUMP_APP_PRIVATE_KEY: '${{ secrets.NIORD_BUMP_APP_PRIVATE_KEY }}',
      BASE_REPO: '${{ github.repository }}',
      RUN_URL:
        '${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}',
    });
    const run = stepRun(PUBLISH, BUMP);
    assert.equal(run.split('\n')[0], 'set -euo pipefail');
    assert.ok(!run.includes('${{'), run);
    assert.ok(!run.includes('--api'), run);
    assert.deepEqual(
      run
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.startsWith('node ')),
      ['node fe/scripts/open-bump-pull-requests.mjs --version "$VERSION"'],
    );
    const expansions = [...run.matchAll(/\$\{?NIORD_BUMP_APP_PRIVATE_KEY/g)];
    assert.equal(expansions.length, 1, run);
    const test = '[ -z "${NIORD_BUMP_APP_PRIVATE_KEY:-}" ]';
    assert.ok(run.includes(test), run);
    assert.equal(expansions[0].index, run.indexOf(test) + '[ -z "'.length, run);
  });
});

describe("publish.yml's bump step", () => {
  const SCRIPT_CALL = 'node fe/scripts/open-bump-pull-requests.mjs --version 4.0.77';

  it('skips with a notice and a summary line, calling no node, while the app id or the key is unset', () => {
    for (const [id, appKey] of [
      ['', FAKE_KEY],
      ['4242', ''],
      ['', ''],
    ]) {
      const result = bumpStep(
        { NIORD_BUMP_APP_ID: id, NIORD_BUMP_APP_PRIVATE_KEY: appKey },
        { fakes: { node: [{ match: '*', exit: 0 }] } },
      );
      const which = `id=${JSON.stringify(id)} key=${appKey ? 'set' : 'unset'}\n${show(result)}`;
      assert.equal(result.status, 0, which);
      assert.deepEqual(outputLines(result.stdout), [NOTICE], which);
      assert.equal(result.summary, SKIPPED, which);
      assert.deepEqual(result.calls, [], which);
    }
  });

  it('runs the script once with --version alone, the key and the id in its environment', () => {
    const result = bumpStep(
      {},
      {
        keep: true,
        fakes: {
          node: [
            {
              match: '*',
              script:
                '    printf "%s" "$NIORD_BUMP_APP_PRIVATE_KEY" > "$FAKE_BIN/node.key"; printf "%s" "$NIORD_BUMP_APP_ID" > "$FAKE_BIN/node.id"',
            },
          ],
        },
      },
    );
    try {
      assert.equal(result.status, 0, show(result));
      assert.deepEqual(result.calls, [SCRIPT_CALL]);
      assert.equal(fs.readFileSync(path.join(result.dir, 'bin', 'node.key'), 'utf8'), FAKE_KEY);
      assert.equal(fs.readFileSync(path.join(result.dir, 'bin', 'node.id'), 'utf8'), '4242');
      assert.ok(!result.stdout.includes('not-a-key'), show(result));
      assert.ok(!result.stderr.includes('not-a-key'), show(result));
    } finally {
      fs.rmSync(result.dir, { recursive: true, force: true });
    }
  });

  it('fails when the script fails', () => {
    // The job's continue-on-error is what keeps the run green.
    const result = bumpStep({}, { fakes: { node: [{ match: '*', exit: 1 }] } });
    assert.equal(result.status, 1, show(result));
    assert.deepEqual(result.calls, [SCRIPT_CALL]);
  });

  it('opens the bump pull request on a fake GitHub in a worker: the summary table, and the PUT on base-bump/4.0.77', async () => {
    const { privateKey, publicKey } = appKeys();
    const pom = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<project xmlns="http://maven.apache.org/POM/4.0.0">',
      '    <modelVersion>4.0.0</modelVersion>',
      '    <parent>',
      '        <groupId>org.niord</groupId>',
      '        <artifactId>niord-parent</artifactId>',
      '        <version>4.0.70</version>',
      '        <relativePath/>',
      '    </parent>',
      '    <artifactId>niord-xx</artifactId>',
      '</project>',
      '',
    ].join('\n');
    const seed = {
      app: { id: 4242, slug: 'niord-base-bump', publicKey },
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: {
        'o1/country-a': {
          files: { 'be/pom.xml': pom, '.github/CODEOWNERS': '/be/pom.xml @owner-a\n' },
          collaborators: ['owner-a'],
        },
      },
    };
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-bump-step-'));
    const logFile = path.join(tmp, 'requests.jsonl');
    const stub = await startGitHubStubWorker(seed, { logFile });
    try {
      // The real node behind a fake that logs the call, whichever node is first on PATH. runBash
      // drops GITHUB_*, so the API base the runner would set is passed here; no proxy and no
      // node option of this process sits between the script and the loopback stub.
      const result = bumpStep(
        {
          NIORD_BUMP_APP_PRIVATE_KEY: privateKey,
          GITHUB_API_URL: stub.url,
          NODE_OPTIONS: '',
          NODE_USE_ENV_PROXY: '',
          NODE_TEST_CONTEXT: '',
        },
        {
          cwd: ROOT,
          timeout: 120_000,
          fakes: {
            node: [
              { match: '*', script: `    exec "${process.execPath.replaceAll('\\', '/')}" "$@"` },
            ],
          },
        },
      );
      assert.equal(result.status, 0, show(result));
      assert.deepEqual(result.calls, [SCRIPT_CALL]);
      assert.equal(
        result.summary,
        '## Bump pull requests for 4.0.77\n\n| Repository | Was | Result |\n|---|---|---|\n| o1/country-a | 4.0.70 | opened #1 |\n\n',
      );

      const log = readRequestLog(logFile);
      const puts = log.filter((entry) => entry.method === 'PUT');
      assert.equal(puts.length, 1, JSON.stringify(log, null, 2));
      const [put] = puts;
      assert.equal(put.path, '/repos/o1/country-a/contents/be/pom.xml');
      assert.equal(put.body.branch, 'base-bump/4.0.77');
      assert.equal(put.auth, 'installation:1');
      assert.ok(put.status < 300, JSON.stringify(put));
      assert.equal(
        Buffer.from(put.body.content, 'base64').toString('utf8'),
        pom.replace('<version>4.0.70</version>', '<version>4.0.77</version>'),
      );
      assert.deepEqual(
        log.filter((entry) => entry.unknown || ['invalid', 'none'].includes(entry.auth)),
        [],
      );

      const body = privateKey
        .split('\n')
        .filter((line) => line.trim() && !line.startsWith('-----'));
      assert.ok(body.length > 0);
      for (const line of body)
        for (const text of [result.stdout, result.stderr, result.summary])
          assert.ok(!text.includes(line.trim()), 'a line of the key reached the output');
    } finally {
      await stub.close();
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------------------------
// workflow-step.mjs: the run's own folder
// ---------------------------------------------------------------------------------------------

describe("workflow-step.mjs's run folder", () => {
  /** The folder a run used: the one its GITHUB_OUTPUT file is in, as the step saw it. */
  const folderOf = (result) => path.dirname(result.stdout.trim());
  const SHOW_FOLDER = 'printf "%s\\n" "$GITHUB_OUTPUT"\n';

  /** runBash with NIORD_KEEP_STEP_DIR set to `variable` (unset when undefined) for the call. */
  function runWith(variable, script, options) {
    const before = process.env.NIORD_KEEP_STEP_DIR;
    const set = (value) => {
      if (value === undefined) delete process.env.NIORD_KEEP_STEP_DIR;
      else process.env.NIORD_KEEP_STEP_DIR = value;
    };
    set(variable);
    try {
      return runBash(script, options);
    } finally {
      set(before);
    }
  }

  it('is removed when the run is done, and not handed back', () => {
    const result = runWith(undefined, SHOW_FOLDER);
    assert.equal(result.status, 0, show(result));
    assert.match(path.basename(folderOf(result)), /^niord-step-/);
    assert.equal(fs.existsSync(folderOf(result)), false, folderOf(result));
    assert.equal(result.dir, undefined);
  });

  it('is removed when the step fails as well', () => {
    const result = runWith(undefined, `${SHOW_FOLDER}exit 3\n`);
    assert.equal(result.status, 3, show(result));
    assert.equal(fs.existsSync(folderOf(result)), false, folderOf(result));
  });

  it('is kept and handed back with keep, or while NIORD_KEEP_STEP_DIR is set', () => {
    for (const [options, variable] of [
      [{ keep: true }, undefined],
      [{}, '1'],
    ]) {
      const result = runWith(variable, SHOW_FOLDER, options);
      try {
        assert.equal(result.status, 0, show(result));
        assert.equal(result.dir, folderOf(result));
        assert.ok(fs.existsSync(path.join(result.dir, 'step.sh')), result.dir);
      } finally {
        fs.rmSync(folderOf(result), { recursive: true, force: true });
      }
    }
  });
});
