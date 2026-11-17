/**
 * The bump script (open-bump-pull-requests.mjs) against a fake GitHub (github-api-stub.mjs) on
 * 127.0.0.1: the app's JWT, checked by the stub with the app's public key; a bump pull request
 * opened, moved in place on its own branch, or replaced when it conflicts; a stale one of the
 * app's closed with a comment and a person's only reported; every skip as a named row; one
 * country's failure leaving the next one done; an app-wide failure exiting 1; the re-run rules for
 * an existing bump branch; a workflow refusal retried once; the refused arguments; the tokens
 * masked and the key never printed; pagination; an idempotent second run; and, over every request
 * the cases made, only the listed routes. The script is imported inside each case, so that with it
 * absent every case fails on its own. Every name is made up.
 *
 *   node --test scripts/open-bump-pull-requests.test.mjs   # from fe/
 *   npm run test:guards                                    # from fe/, with the other guards
 */
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { appKeys, startGitHubStub } from './github-api-stub.mjs';

const script = () => import('./open-bump-pull-requests.mjs');

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPT = 'scripts/open-bump-pull-requests.mjs';

const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]']);
/** fetch, refusing every URL that is not on a loopback host: no test reaches GitHub. */
const guard = (url, init) => {
  const { hostname, origin } = new URL(url);
  if (!LOOPBACK.has(hostname)) throw new Error(`the test refused a request to ${origin}`);
  return fetch(url, init);
};

const APP_ID = 4242;
const SLUG = 'niord-base-bump';
const BOT = `${SLUG}[bot]`;
const VERSION = '4.0.77';
const HEAD = `base-bump/${VERSION}`;
const { privateKey: KEY, publicKey: PUBLIC } = appKeys();
const APP = { id: APP_ID, slug: SLUG, publicKey: PUBLIC };
const OWNERS = [
  '# The owners of what a reviewer has to see changed.',
  '*                   @owner-a',
  '/be/pom.xml         @owner-a @o1/maintainers owner@example.org',
  '',
].join('\n');
const REFUSAL =
  'refusing to allow a GitHub App to create or update workflow `.github/workflows/ci.yml` without `workflows` permission';

/** Every stub a case started, with its seed, for the last case's check of every request; closed after the file. */
const allRuns = [];
const temporary = [];

after(async () => {
  for (const { stub } of allRuns) await stub.close();
  for (const dir of temporary) fs.rmSync(dir, { recursive: true, force: true });
});

/**
 * A country's be/pom.xml in the kit's shape: a comment with a non-ASCII character, an earlier
 * parent commented out ahead of the real one, the parent, and a dependency whose version text
 * equals the parent's and must stay.
 */
function pom(version, { eol = '\n', group = 'org.niord', artifact = 'niord-parent' } = {}) {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    '    <modelVersion>4.0.0</modelVersion>',
    '',
    '    <!-- The pin of this repository (§ 1). An earlier parent, kept for the record:',
    '    <parent>',
    '        <groupId>org.example</groupId>',
    '        <artifactId>old-parent</artifactId>',
    '        <version>1.2.3</version>',
    '    </parent>',
    '    -->',
    '    <parent>',
    `        <groupId>${group}</groupId>`,
    `        <artifactId>${artifact}</artifactId>`,
    `        <version>${version}</version>`,
    '        <relativePath/>',
    '    </parent>',
    '',
    '    <groupId>org.niord.xx</groupId>',
    '    <artifactId>niord-xx</artifactId>',
    '    <dependencies>',
    '        <dependency>',
    '            <groupId>org.example</groupId>',
    '            <artifactId>tool</artifactId>',
    '            <version>4.0.70</version>',
    '        </dependency>',
    '    </dependencies>',
    '</project>',
    '',
  ].join(eol);
}

/** git's blob sha of a text, which GitHub's contents API answers as `sha`. */
const blobSha = (text) => {
  const bytes = Buffer.from(text, 'utf8');
  return crypto
    .createHash('sha1')
    .update(Buffer.concat([Buffer.from(`blob ${bytes.length}\0`), bytes]))
    .digest('hex');
};

/** A country repository's seed: its pom at `version` on the default branch and its owners. */
const country = (version, more = {}) => ({
  ...more,
  files: { 'be/pom.xml': pom(version), '.github/CODEOWNERS': OWNERS, ...more.files },
  collaborators: ['owner-a'],
  teams: ['maintainers'],
});

/** An open bump pull request of the app's on its branch at `at`, made from the default branch. */
const appPull = (number, at, more = {}) => ({
  branches: { [`base-bump/${at}`]: { files: { 'be/pom.xml': pom(at) } } },
  pulls: [{ number, head: `base-bump/${at}`, author: 'app', mergeable: true, ...more }],
});

async function github(seed) {
  const stub = await startGitHubStub({ app: APP, ...seed });
  allRuns.push({ seed, stub });
  return stub;
}

/** The script's run() against the stub, with the log and the sleeps collected. */
async function bump(stub, options = {}) {
  const { run } = await script();
  const lines = [];
  const sleeps = [];
  const outcome = await run({
    api: stub.url,
    appId: APP_ID,
    key: KEY,
    version: VERSION,
    baseRepo: 'o9/base',
    runUrl: 'https://ci.example/run/1',
    log: (line) => lines.push(line),
    fetch: guard,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    now: () => Date.now(),
    ...options,
  });
  const result = (repository) => outcome.rows.find((row) => row.repository === repository)?.result;
  return { ...outcome, lines, sleeps, result };
}

/** The requests to one repository's routes, as 'METHOD path', from the log index `from`. */
const calls = (stub, repository, from = 0) =>
  stub.requests
    .slice(from)
    .filter((entry) => entry.path.startsWith(`/repos/${repository}/`))
    .map((entry) => `${entry.method} ${entry.path}`);

const entries = (stub, method, pathname) =>
  stub.requests.filter((entry) => entry.method === method && entry.path === pathname);

const decoded = (entry) => Buffer.from(entry.body.content, 'base64').toString('utf8');

const warnings = (lines) => lines.filter((line) => line.startsWith('::warning::'));

/** The CLI, started asynchronously so that the in-process stub keeps answering. */
function cli(args, env) {
  const inherited = Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) =>
        !/^(GITHUB_|RUNNER_|ACTIONS_|GH_|NIORD_BUMP_)/i.test(key) &&
        !/^(NODE_TEST_CONTEXT|NODE_OPTIONS|NODE_USE_ENV_PROXY)$/i.test(key),
    ),
  );
  return new Promise((resolve) => {
    execFile(
      process.execPath,
      [SCRIPT, ...args],
      { cwd: FE, env: { ...inherited, ...env }, timeout: 60_000 },
      (error, stdout, stderr) =>
        resolve({
          code: error ? (typeof error.code === 'number' ? error.code : -1) : 0,
          stdout,
          stderr,
        }),
    );
  });
}

function summaryFile() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-bump-'));
  temporary.push(dir);
  const file = path.join(dir, 'summary.md');
  fs.writeFileSync(file, '');
  return file;
}

/** The pull request an opened row names. */
const openedNumber = (text) => {
  const match = /^opened #(\d+)/.exec(text ?? '');
  assert.ok(match, `expected an 'opened #<m>' row, got '${text}'`);
  return Number(match[1]);
};

describe('open-bump-pull-requests', () => {
  it('opens a bump pull request: ref read, ref create, the pom on base-bump/4.0.77, the pull request, the code owners asked', async () => {
    const { appJwt, codeOwners, compareVersions, moveParent } = await script();

    // The JWT: RS256 over iat a minute back, exp ten minutes after iat, iss the app id.
    const now = 1_800_000_000_000;
    const [header, payload, signature] = appJwt({ appId: APP_ID, key: KEY, now }).split('.');
    const json = (part) => JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
    assert.deepEqual(json(header), { alg: 'RS256', typ: 'JWT' });
    assert.deepEqual(json(payload), { iat: now / 1000 - 60, exp: now / 1000 + 540, iss: '4242' });
    assert.ok(
      crypto.verify(
        'sha256',
        Buffer.from(`${header}.${payload}`),
        PUBLIC,
        Buffer.from(signature, 'base64url'),
      ),
    );

    // The pom: only the parent's version text moves, whatever the line ends.
    for (const eol of ['\n', '\r\n'])
      assert.deepEqual(moveParent(pom('4.0.70', { eol }), VERSION), {
        from: '4.0.70',
        text: pom(VERSION, { eol }),
      });
    assert.equal(compareVersions('4.0.9', '4.0.10'), -1);
    assert.equal(compareVersions('4.0.77', '4.0.77'), 0);
    assert.equal(compareVersions('4.0.100', '4.0.99'), 1);

    // The code owners: the last line matching /be/pom.xml or *, users and teams, emails ignored.
    assert.deepEqual(codeOwners(OWNERS), {
      reviewers: ['owner-a'],
      team_reviewers: ['maintainers'],
    });
    assert.deepEqual(codeOwners('/be/pom.xml @owner-a\n* @person-b\n'), {
      reviewers: ['person-b'],
      team_reviewers: [],
    });
    assert.deepEqual(codeOwners('/fe/ @owner-a\n'), { reviewers: [], team_reviewers: [] });

    const crlf = pom('4.0.70', { eol: '\r\n' });
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: {
        'o1/country-a': country('4.0.70'),
        'o1/country-b': country('4.0.70', { files: { 'be/pom.xml': crlf } }),
      },
    });
    const mainSha = stub.state.repositories['o1/country-a'].refs.main;
    const outcome = await bump(stub);

    assert.equal(outcome.exitCode, 0);
    const m = openedNumber(outcome.result('o1/country-a'));
    assert.equal(outcome.result('o1/country-a'), `opened #${m}`);
    assert.equal(outcome.rows.find((row) => row.repository === 'o1/country-a').was, '4.0.70');
    const own = calls(stub, 'o1/country-a').filter(
      (call) =>
        call !== 'GET /repos/o1/country-a/contents/be/pom.xml' &&
        call !== 'GET /repos/o1/country-a/pulls',
    );
    assert.deepEqual(own, [
      'GET /repos/o1/country-a/git/ref/heads/main',
      'POST /repos/o1/country-a/git/refs',
      'PUT /repos/o1/country-a/contents/be/pom.xml',
      'POST /repos/o1/country-a/pulls',
      'GET /repos/o1/country-a/contents/.github/CODEOWNERS',
      `POST /repos/o1/country-a/pulls/${m}/requested_reviewers`,
    ]);
    assert.deepEqual(entries(stub, 'POST', '/repos/o1/country-a/git/refs')[0].body, {
      ref: `refs/heads/${HEAD}`,
      sha: mainSha,
    });
    const [put] = entries(stub, 'PUT', '/repos/o1/country-a/contents/be/pom.xml');
    assert.equal(put.body.branch, HEAD);
    assert.equal(put.body.sha, blobSha(pom('4.0.70')));
    assert.equal(decoded(put), pom(VERSION));
    const [pull] = entries(stub, 'POST', '/repos/o1/country-a/pulls');
    assert.equal(pull.body.title, 'Base bump to 4.0.77');
    assert.equal(pull.body.head, HEAD);
    assert.equal(pull.body.base, 'main');
    assert.match(pull.body.body, /v4\.0\.77/);
    assert.match(pull.body.body, /o9\/base/);
    assert.match(pull.body.body, /https:\/\/ci\.example\/run\/1/);
    assert.match(pull.body.body, /https:\/\/github\.com\/apps\/niord-base-bump/);
    assert.equal(
      entries(stub, 'GET', '/repos/o1/country-a/contents/.github/CODEOWNERS')[0].query.ref,
      'main',
    );
    assert.deepEqual(
      entries(stub, 'POST', `/repos/o1/country-a/pulls/${m}/requested_reviewers`)[0].body,
      {
        reviewers: ['owner-a'],
        team_reviewers: ['maintainers'],
      },
    );
    assert.equal(stub.fileAt('o1/country-a', HEAD, 'be/pom.xml'), pom(VERSION));
    assert.equal(stub.fileAt('o1/country-a', 'main', 'be/pom.xml'), pom('4.0.70'));

    // CRLF and the non-ASCII byte kept, byte for byte.
    const [putCrlf] = entries(stub, 'PUT', '/repos/o1/country-b/contents/be/pom.xml');
    assert.equal(decoded(putCrlf), pom(VERSION, { eol: '\r\n' }));
    assert.equal(stub.fileAt('o1/country-b', HEAD, 'be/pom.xml'), pom(VERSION, { eol: '\r\n' }));
    assert.match(outcome.result('o1/country-b'), /^opened #\d+$/);

    const jwt = stub.requests.filter((entry) => entry.path.startsWith('/app'));
    assert.ok(jwt.length >= 3);
    for (const entry of jwt) assert.equal(entry.auth, 'jwt', `${entry.method} ${entry.path}`);
    assert.ok(!stub.requests.some((entry) => entry.auth === 'invalid'));
  });

  it('moves an open app pull request in place on its own branch', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: { 'o1/country-a': country('4.0.69', appPull(11, '4.0.70')) },
    });
    const branchPom = stub.fileAt('o1/country-a', 'base-bump/4.0.70', 'be/pom.xml');
    const outcome = await bump(stub);

    assert.equal(outcome.result('o1/country-a'), 'moved #11 from 4.0.70');
    assert.equal(outcome.exitCode, 0);
    assert.deepEqual(outcome.sleeps, []);
    assert.equal(entries(stub, 'GET', '/repos/o1/country-a/pulls/11').length, 1);
    const [put] = entries(stub, 'PUT', '/repos/o1/country-a/contents/be/pom.xml');
    assert.equal(put.body.branch, 'base-bump/4.0.70');
    assert.equal(put.body.sha, blobSha(branchPom));
    assert.equal(decoded(put), pom(VERSION));
    const [patch] = entries(stub, 'PATCH', '/repos/o1/country-a/pulls/11');
    assert.equal(patch.body.title, 'Base bump to 4.0.77');
    assert.match(patch.body.body, /4\.0\.77/);
    assert.equal(patch.body.state, undefined);
    assert.equal(entries(stub, 'POST', '/repos/o1/country-a/git/refs').length, 0);
    assert.equal(entries(stub, 'POST', '/repos/o1/country-a/pulls').length, 0);
    assert.equal(stub.fileAt('o1/country-a', 'base-bump/4.0.70', 'be/pom.xml'), pom(VERSION));
    assert.equal(stub.fileAt('o1/country-a', 'main', 'be/pom.xml'), pom('4.0.69'));

    // The PUT landed and the PATCH failed: the next run finds the pom moved and sets the title once.
    const torn = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: { 'o1/country-a': country('4.0.69', appPull(11, '4.0.70')) },
      failures: [{ method: 'PATCH', path: '/repos/o1/country-a/pulls/11', status: 500 }],
    });
    const first = await bump(torn);
    assert.match(first.result('o1/country-a'), /^failed: 500 PATCH /);
    assert.equal(torn.fileAt('o1/country-a', 'base-bump/4.0.70', 'be/pom.xml'), pom(VERSION));
    const second = await bump(torn);
    assert.equal(second.result('o1/country-a'), '#11 at 4.0.77 already; title refreshed');
    const patches = entries(torn, 'PATCH', '/repos/o1/country-a/pulls/11');
    assert.deepEqual(
      patches.map((entry) => entry.status),
      [500, 200],
    );
    assert.equal(patches[1].body.title, 'Base bump to 4.0.77');
    assert.match(patches[1].body.body, /4\.0\.77/);
    assert.equal(entries(torn, 'PUT', '/repos/o1/country-a/contents/be/pom.xml').length, 1);
    const third = await bump(torn);
    assert.equal(third.result('o1/country-a'), '#11 at 4.0.77 already');
    assert.equal(entries(torn, 'PATCH', '/repos/o1/country-a/pulls/11').length, 2);
  });

  it('reads a null mergeable three times, then moves in place', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: {
        'o1/country-a': country('4.0.69', appPull(11, '4.0.70', { mergeable: [null, null, null] })),
      },
    });
    const outcome = await bump(stub);

    assert.equal(outcome.result('o1/country-a'), 'moved #11 from 4.0.70 (mergeability unknown)');
    assert.equal(entries(stub, 'GET', '/repos/o1/country-a/pulls/11').length, 3);
    assert.deepEqual(outcome.sleeps, [2000, 2000]);
    assert.equal(entries(stub, 'PUT', '/repos/o1/country-a/contents/be/pom.xml').length, 1);
    assert.equal(entries(stub, 'PATCH', '/repos/o1/country-a/pulls/11').length, 1);
  });

  it('replaces a conflicting app pull request and closes the old one with a comment, its branch kept', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: {
        'o1/country-a': country('4.0.69', appPull(11, '4.0.70', { mergeable: false })),
      },
    });
    const outcome = await bump(stub);

    const match = /^replaced #11 by #(\d+) \(conflict with main\)$/.exec(
      outcome.result('o1/country-a'),
    );
    assert.ok(match, outcome.result('o1/country-a'));
    const m = Number(match[1]);
    const log = calls(stub, 'o1/country-a');
    const created = log.indexOf('POST /repos/o1/country-a/pulls');
    const comment = log.indexOf('POST /repos/o1/country-a/issues/11/comments');
    const close = log.indexOf('PATCH /repos/o1/country-a/pulls/11');
    assert.ok(created >= 0 && created < comment && comment < close, log.join('\n'));
    const [put] = entries(stub, 'PUT', '/repos/o1/country-a/contents/be/pom.xml');
    assert.equal(put.body.branch, HEAD);
    assert.equal(put.body.sha, blobSha(pom('4.0.69')));
    assert.match(entries(stub, 'POST', '/repos/o1/country-a/pulls')[0].body.body, /Replaces #11/);
    const [said] = entries(stub, 'POST', '/repos/o1/country-a/issues/11/comments');
    assert.match(said.body.body, new RegExp(`^Replaced by #${m}: `));
    assert.match(said.body.body, /base-bump\/4\.0\.70/);
    assert.deepEqual(entries(stub, 'PATCH', '/repos/o1/country-a/pulls/11')[0].body, {
      state: 'closed',
    });
    assert.ok(stub.state.repositories['o1/country-a'].refs['base-bump/4.0.70']);
    assert.equal(stub.fileAt('o1/country-a', 'base-bump/4.0.70', 'be/pom.xml'), pom('4.0.70'));
  });

  it('closes a stale app pull request behind main with a comment, and only reports a hand-made one', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: {
        'o1/country-a': country(VERSION, appPull(9, '4.0.70')),
        'o1/country-b': country(VERSION, {
          branches: { 'base-bump/4.0.70': { files: { 'be/pom.xml': pom('4.0.70') } } },
          pulls: [{ number: 5, head: 'base-bump/4.0.70', author: 'person-b' }],
        }),
      },
    });
    const outcome = await bump(stub);

    assert.equal(outcome.result('o1/country-a'), 'at 4.0.77 already; closed #9 (behind main)');
    const log = calls(stub, 'o1/country-a');
    const comment = log.indexOf('POST /repos/o1/country-a/issues/9/comments');
    assert.ok(comment >= 0 && comment < log.indexOf('PATCH /repos/o1/country-a/pulls/9'));
    const [said] = entries(stub, 'POST', '/repos/o1/country-a/issues/9/comments');
    assert.match(said.body.body, /^main is at 4\.0\.77/);
    assert.match(said.body.body, /base-bump\/4\.0\.70/);
    assert.deepEqual(entries(stub, 'PATCH', '/repos/o1/country-a/pulls/9')[0].body, {
      state: 'closed',
    });

    assert.equal(
      outcome.result('o1/country-b'),
      'at 4.0.77 already; #5 made by hand is open: not touched',
    );
    assert.equal(entries(stub, 'POST', '/repos/o1/country-b/issues/5/comments').length, 0);
    assert.equal(entries(stub, 'PATCH', '/repos/o1/country-b/pulls/5').length, 0);
    for (const repository of ['o1/country-a', 'o1/country-b'])
      assert.ok(!calls(stub, repository).some((call) => /^(PUT|POST \S+\/git\/refs)/.test(call)));
  });

  it('skips with a named row: at the version or past it, no be/pom.xml, another parent, a hand-made pull request, archived, a suspended installation', async () => {
    const { moveParent } = await script();
    assert.deepEqual(moveParent('<project>\n</project>\n', VERSION), { reason: 'no <parent>' });
    assert.deepEqual(moveParent(pom('1.0', { group: 'org.example', artifact: 'other' }), VERSION), {
      reason: 'parent',
      parent: 'org.example:other',
    });
    assert.deepEqual(moveParent(pom('${revision}'), VERSION), {
      reason: 'version',
      from: '${revision}',
    });

    const stub = await github({
      installations: [
        {
          id: 1,
          account: 'o1',
          repositories: [
            'o1/country-a',
            'o1/country-b',
            'o1/tools',
            'o1/other',
            'o1/bare',
            'o1/odd',
            'o1/country-c',
            'o1/old',
          ],
        },
        { id: 2, account: 'o2', suspended: true, repositories: ['o2/country-d'] },
      ],
      repositories: {
        'o1/country-a': country(VERSION),
        'o1/country-b': country('4.0.78'),
        'o1/tools': { files: { 'README.md': '# Tools\n' } },
        'o1/other': country('1.0', {
          files: { 'be/pom.xml': pom('1.0', { group: 'org.example', artifact: 'other' }) },
        }),
        'o1/bare': country('', { files: { 'be/pom.xml': '<project>\n</project>\n' } }),
        'o1/odd': country('${revision}'),
        'o1/country-c': country('4.0.70', {
          branches: { 'base-bump/4.0.71': { files: { 'be/pom.xml': pom('4.0.71') } } },
          pulls: [{ number: 4, head: 'base-bump/4.0.71', author: 'person-b' }],
        }),
        'o1/old': country('4.0.70', { archived: true }),
        'o2/country-d': country('4.0.70'),
      },
    });
    const outcome = await bump(stub);

    assert.equal(outcome.exitCode, 0);
    assert.equal(outcome.result('o1/country-a'), 'at 4.0.77 already');
    assert.equal(outcome.result('o1/country-b'), 'at 4.0.78 already');
    assert.equal(outcome.result('o1/tools'), 'not a country (no be/pom.xml)');
    assert.equal(outcome.result('o1/other'), 'not a country (parent org.example:other)');
    assert.equal(outcome.result('o1/bare'), 'not a country (no <parent>)');
    assert.equal(outcome.result('o1/odd'), "unreadable parent version '${revision}'");
    assert.equal(outcome.result('o1/country-c'), '#4 made by hand is open: not touched');
    assert.equal(outcome.result('o1/old'), 'archived');
    assert.equal(outcome.result('o2 (installation)'), 'installation suspended');
    assert.equal(outcome.rows.length, 9);
    assert.ok(warnings(outcome.lines).some((line) => line.includes('o1/odd')));
    assert.equal(calls(stub, 'o1/old').length, 0);
    assert.equal(entries(stub, 'POST', '/app/installations/2/access_tokens').length, 0);
    assert.ok(!stub.state.tokens.some((token) => token.installation === 2));
    const writes = stub.requests.filter((entry) => entry.method !== 'GET');
    for (const entry of writes)
      assert.match(
        `${entry.method} ${entry.path}`,
        /^POST \/app\/installations\/1\/access_tokens$/,
      );
  });

  it('reports an installation that lacks pull_requests: write and goes on with the others', async () => {
    const stub = await github({
      installations: [
        {
          id: 1,
          account: 'o2',
          permissions: { metadata: 'read', contents: 'write', pull_requests: 'read' },
          repositories: ['o2/country-a'],
        },
        { id: 2, account: 'o3', repositories: ['o3/country-b'] },
      ],
      repositories: { 'o2/country-a': country('4.0.70'), 'o3/country-b': country('4.0.70') },
    });
    const outcome = await bump(stub);

    const text = 'not done: the installation on o2 lacks pull_requests: write';
    assert.equal(outcome.result('o2 (installation)'), text);
    assert.ok(warnings(outcome.lines).some((line) => line.includes(text)));
    assert.equal(outcome.result('o2/country-a'), undefined);
    assert.equal(stub.requests.filter((entry) => entry.auth === 'installation:1').length, 0);
    assert.match(outcome.result('o3/country-b'), /^opened #\d+$/);
    assert.equal(outcome.exitCode, 0);
  });

  it('a 500 in one country leaves the next one done, exit 0', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: { 'o1/country-a': country('4.0.70'), 'o1/country-b': country('4.0.70') },
      failures: [
        {
          method: 'GET',
          path: '/repos/o1/country-a/contents/be/pom.xml',
          status: 500,
          times: Infinity,
        },
      ],
    });
    const outcome = await bump(stub);

    assert.match(
      outcome.result('o1/country-a'),
      /^failed: 500 GET \/repos\/o1\/country-a\/contents\/be\/pom\.xml: /,
    );
    assert.ok(
      warnings(outcome.lines).some((line) => line.includes('o1/country-a') && line.includes('500')),
    );
    assert.match(outcome.result('o1/country-b'), /^opened #\d+$/);
    assert.equal(outcome.exitCode, 0);
    assert.ok(!outcome.lines.some((line) => line.startsWith('::error::')));
  });

  it('a 401 on the installations exits 1 with ::error', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: { 'o1/country-a': country('4.0.70') },
      failures: [
        { method: 'GET', path: '/app/installations', status: 401, message: 'Bad credentials' },
      ],
    });
    const outcome = await bump(stub);

    assert.equal(outcome.exitCode, 1);
    assert.ok(outcome.lines.some((line) => line.startsWith('::error::') && line.includes('401')));
    assert.match(outcome.summary, /^## Bump pull requests for 4\.0\.77\n\nNot done: /);
    assert.equal(stub.state.tokens.length, 0);
    assert.equal(stub.requests.filter((entry) => entry.path.endsWith('/access_tokens')).length, 0);

    const other = appKeys().privateKey;
    const from = stub.requests.length;
    const signedWrong = await bump(stub, { key: other });
    assert.equal(signedWrong.exitCode, 1);
    assert.ok(
      signedWrong.lines.some((line) => line.startsWith('::error::') && line.includes('401')),
    );
    assert.deepEqual(
      stub.requests
        .slice(from)
        .map((entry) => `${entry.method} ${entry.path} ${entry.status} ${entry.auth}`),
      ['GET /app 401 invalid'],
    );
  });

  it('an existing base-bump/4.0.77 whose pom is at the version opens the pull request with no PUT', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a'] }],
      repositories: {
        'o1/country-a': country('4.0.70', {
          branches: { [HEAD]: { files: { 'be/pom.xml': pom(VERSION) } } },
        }),
      },
    });
    const outcome = await bump(stub);

    assert.match(outcome.result('o1/country-a'), /^opened #\d+$/);
    assert.deepEqual(
      entries(stub, 'POST', '/repos/o1/country-a/git/refs').map((entry) => entry.status),
      [422],
    );
    assert.equal(entries(stub, 'GET', `/repos/o1/country-a/git/ref/heads/${HEAD}`).length, 1);
    assert.ok(
      entries(stub, 'GET', '/repos/o1/country-a/contents/be/pom.xml').some(
        (entry) => entry.query.ref === HEAD,
      ),
    );
    assert.equal(entries(stub, 'PUT', '/repos/o1/country-a/contents/be/pom.xml').length, 0);
    assert.equal(entries(stub, 'POST', '/repos/o1/country-a/pulls')[0].body.head, HEAD);
  });

  it('an existing base-bump/4.0.77 at or behind main is taken over with its own blob sha', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: {
        'o1/country-a': country('4.0.70', { branches: { [HEAD]: {} } }),
        'o1/country-b': country('4.0.70', { mainCommits: 1, branches: { [HEAD]: { behind: 1 } } }),
      },
    });
    const before = {};
    for (const repository of ['o1/country-a', 'o1/country-b']) {
      const { refs } = stub.state.repositories[repository];
      before[repository] = {
        main: refs.main,
        branch: refs[HEAD],
        pom: stub.fileAt(repository, HEAD, 'be/pom.xml'),
      };
    }
    assert.equal(before['o1/country-a'].main, before['o1/country-a'].branch);
    assert.notEqual(before['o1/country-b'].main, before['o1/country-b'].branch);
    const outcome = await bump(stub);

    for (const repository of ['o1/country-a', 'o1/country-b']) {
      const { main, branch, pom: branchPom } = before[repository];
      assert.match(outcome.result(repository), /^opened #\d+$/, repository);
      assert.equal(
        entries(stub, 'GET', `/repos/${repository}/compare/${main}...${branch}`).length,
        1,
        repository,
      );
      const [put] = entries(stub, 'PUT', `/repos/${repository}/contents/be/pom.xml`);
      assert.equal(put.body.branch, HEAD);
      assert.equal(put.body.sha, blobSha(branchPom));
      assert.equal(decoded(put), pom(VERSION));
      assert.equal(entries(stub, 'POST', `/repos/${repository}/pulls`).length, 1);
    }
  });

  it('a foreign base-bump/4.0.77 ahead of main is skipped, never forced', async () => {
    const foreign = {
      files: {
        'be/pom.xml': pom('4.0.70').replace('niord-xx</artifactId>', 'niord-yy</artifactId>'),
      },
    };
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: {
        'o1/country-a': country('4.0.70', { branches: { [HEAD]: foreign } }),
        'o1/country-b': country('4.0.70', {
          mainCommits: 1,
          branches: { [HEAD]: { behind: 1, ...foreign } },
        }),
      },
    });
    const outcome = await bump(stub);

    for (const repository of ['o1/country-a', 'o1/country-b']) {
      assert.equal(
        outcome.result(repository),
        `a branch ${HEAD} exists that the step did not make: not touched`,
      );
      assert.ok(calls(stub, repository).some((call) => call.includes('/compare/')));
      assert.ok(!calls(stub, repository).some((call) => /^(PUT|PATCH) /.test(call)));
      assert.equal(entries(stub, 'POST', `/repos/${repository}/pulls`).length, 0);
      assert.ok(warnings(outcome.lines).some((line) => line.includes(repository)));
    }
    assert.equal(outcome.exitCode, 0);
  });

  it('a workflow refusal on the branch is retried once after re-reading main, then has its own row, exit 0', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: { 'o1/country-a': country('4.0.70'), 'o1/country-b': country('4.0.70') },
      failures: [
        {
          method: 'POST',
          path: '/repos/o1/country-a/git/refs',
          status: 422,
          message: REFUSAL,
          times: 2,
        },
        {
          method: 'POST',
          path: '/repos/o1/country-b/git/refs',
          status: 422,
          message: REFUSAL,
          times: 1,
        },
      ],
    });
    const outcome = await bump(stub);

    const text = `GitHub refused the branch: main changed workflow files meanwhile; the next publish opens it, or push ${HEAD} by hand`;
    assert.equal(outcome.result('o1/country-a'), text);
    assert.equal(entries(stub, 'GET', '/repos/o1/country-a/git/ref/heads/main').length, 2);
    assert.equal(entries(stub, 'POST', '/repos/o1/country-a/git/refs').length, 2);
    assert.equal(entries(stub, 'PUT', '/repos/o1/country-a/contents/be/pom.xml').length, 0);
    assert.ok(warnings(outcome.lines).some((line) => line.includes(text)));
    assert.equal(outcome.exitCode, 0);

    assert.match(outcome.result('o1/country-b'), /^opened #\d+$/);
    assert.equal(entries(stub, 'GET', '/repos/o1/country-b/git/ref/heads/main').length, 2);
    assert.deepEqual(
      entries(stub, 'POST', '/repos/o1/country-b/git/refs').map((entry) => entry.status),
      [422, 201],
    );
  });

  it('refuses --version 4.0.80-alpha.3 and 5.0.1, a --key argument and an http API off loopback before any call', async () => {
    const { main, parseArgs, apiBase, VERSION_PATTERN } = await script();
    const stub = await github({ installations: [], repositories: {} });
    const env = { NIORD_BUMP_APP_ID: String(APP_ID), NIORD_BUMP_APP_PRIVATE_KEY: KEY };
    const attempt = async (argv, extra = {}) => {
      let out = '';
      const code = await main(
        argv,
        { ...env, ...extra },
        { fetch: guard, stdout: { write: (text) => (out += text) } },
      );
      return { code, out };
    };
    const refused = async (argv, rule, extra) => {
      const { code, out } = await attempt(argv, extra);
      assert.equal(code, 1, argv.join(' '));
      assert.match(out, /^::error::/m, argv.join(' '));
      assert.match(out, rule, argv.join(' '));
      return out;
    };

    assert.ok(VERSION_PATTERN.test('4.0.77') && !VERSION_PATTERN.test('4.0.77 '));
    await refused(['--version', '4.0.80-alpha.3', '--api', stub.url], /4\.0\.<n>/);
    await refused(['--version', '5.0.1', '--api', stub.url], /4\.0\.<n>/);
    await refused(['--api', stub.url], /--version/);
    const keyArg = await refused(
      ['--version', VERSION, '--api', stub.url, '--key', 'secret-x'],
      /NIORD_BUMP_APP_PRIVATE_KEY/,
    );
    assert.ok(!keyArg.includes('secret-x'));
    const other = await refused(['--version', VERSION, '--token=secret-y'], /--version/);
    assert.ok(!other.includes('secret-y'));
    await refused(['--version', VERSION, '--api', 'http://example.org'], /https/);
    await refused(['--version', VERSION], /https/, { GITHUB_API_URL: 'http://example.org' });
    await refused(
      ['--version', VERSION, '--api', 'https://user:secret-z@example.org'],
      /credentials/,
    );
    await refused(['--version', VERSION, '--api', stub.url], /NIORD_BUMP_APP_ID/, {
      NIORD_BUMP_APP_ID: '',
    });
    await refused(['--version', VERSION, '--api', stub.url], /NIORD_BUMP_APP_ID/, {
      NIORD_BUMP_APP_ID: 'app 1',
    });
    await refused(['--version', VERSION, '--api', stub.url], /NIORD_BUMP_APP_PRIVATE_KEY/, {
      NIORD_BUMP_APP_PRIVATE_KEY: '',
    });
    const unreadable = await refused(
      ['--version', VERSION, '--api', stub.url],
      /NIORD_BUMP_APP_PRIVATE_KEY/,
      {
        NIORD_BUMP_APP_PRIVATE_KEY: 'secret-key-text',
      },
    );
    assert.ok(!unreadable.includes('secret-key-text'));
    assert.deepEqual(stub.requests, []);

    // The pure rules behind them.
    assert.throws(() => parseArgs(['--version', '4.0.80-alpha.3']), /4\.0\.<n>/);
    assert.deepEqual(parseArgs(['--version', VERSION, '--api', stub.url]), {
      version: VERSION,
      api: stub.url,
    });
    assert.equal(apiBase(undefined), 'https://api.github.com');
    assert.equal(apiBase('https://ghe.example/api/v3/'), 'https://ghe.example/api/v3');
    assert.equal(apiBase('http://127.0.0.1:8123'), 'http://127.0.0.1:8123');
    assert.equal(apiBase('http://[::1]:8123/'), 'http://[::1]:8123');
    assert.throws(() => apiBase('https://api.github.com/?x=1'));

    const { code, stdout, stderr } = await cli(['--version', '5.0.1'], {
      ...env,
      GITHUB_API_URL: stub.url,
    });
    assert.equal(code, 1);
    assert.match(stdout + stderr, /^::error::.*4\.0\.<n>/m);
    assert.deepEqual(stub.requests, []);
  });

  it('masks every installation token and never prints the key or a token', async () => {
    const stub = await github({
      installations: [
        { id: 1, account: 'o1', repositories: ['o1/country-a'] },
        { id: 2, account: 'o2', repositories: ['o2/country-b'] },
      ],
      repositories: { 'o1/country-a': country('4.0.70'), 'o2/country-b': country('4.0.70') },
    });
    const summary = summaryFile();
    const { code, stdout, stderr } = await cli(['--version', VERSION], {
      NIORD_BUMP_APP_ID: String(APP_ID),
      NIORD_BUMP_APP_PRIVATE_KEY: KEY,
      GITHUB_API_URL: stub.url,
      GITHUB_STEP_SUMMARY: summary,
    });
    assert.equal(code, 0, stdout + stderr);
    const written = fs.readFileSync(summary, 'utf8');
    const lines = stdout.split(/\r?\n/);

    const tokens = stub.state.tokens.map((entry) => entry.token);
    assert.equal(tokens.length, 2);
    for (const [index, token] of tokens.entries()) {
      assert.deepEqual(
        lines.filter((line) => line.includes(token)),
        [`::add-mask::${token}`],
      );
      const mask = lines.indexOf(`::add-mask::${token}`);
      const account = `o${index + 1}`;
      const about = lines.findIndex(
        (line) => line.includes(`${account}/`) || line.includes(`${account} (`),
      );
      assert.ok(about === -1 || mask < about, `the mask of ${account}'s token comes first`);
      assert.ok(!stderr.includes(token));
      assert.ok(!written.includes(token));
    }
    const pemBody = KEY.split(/\r?\n/).filter((line) => line && !line.startsWith('-----'));
    assert.ok(pemBody.length > 10);
    const unmasked = lines.filter((line) => !line.startsWith('::add-mask::')).join('\n');
    for (const text of [unmasked, stderr, written]) {
      for (const line of pemBody) assert.ok(!text.includes(line), 'a line of the key was printed');
      assert.ok(!text.includes('eyJ'), 'a JWT was printed');
      assert.ok(!text.includes('PRIVATE KEY'));
    }
    assert.match(
      written,
      /^## Bump pull requests for 4\.0\.77\n\n\| Repository \| Was \| Result \|\n\|---\|---\|---\|\n/,
    );
    assert.match(written, /^\| o1\/country-a \| 4\.0\.70 \| opened #\d+ \|$/m);
    assert.match(written, /^\| o2\/country-b \| 4\.0\.70 \| opened #\d+ \|$/m);
    assert.ok(stdout.includes('## Bump pull requests for 4.0.77'));
    assert.match(stdout, /^\| o1\/country-a \| 4\.0\.70 \| opened #\d+ \|$/m);

    const { summaryTable } = await script();
    assert.equal(
      summaryTable(VERSION, [{ repository: 'o1/a|b', was: '', result: 'x | y' }]),
      '## Bump pull requests for 4.0.77\n\n| Repository | Was | Result |\n|---|---|---|\n| o1/a\\|b |  | x \\| y |\n',
    );
  });

  it('pages through 101 installations and 101 repositories', async () => {
    const names = Array.from(
      { length: 101 },
      (_, i) => `o1/repo-${String(i + 1).padStart(3, '0')}`,
    );
    const installations = Array.from({ length: 101 }, (_, i) => ({
      id: i + 1,
      account: `o${i + 1}`,
      repositories: i === 0 ? names : [],
    }));
    const repositories = Object.fromEntries(
      names.map((name) => [name, { files: { 'README.md': '# Tools\n' } }]),
    );
    const stub = await github({ installations, repositories });
    const outcome = await bump(stub);

    assert.equal(outcome.exitCode, 0);
    const pages = stub.requests.filter(
      (entry) => entry.method === 'GET' && entry.path === '/app/installations',
    );
    assert.deepEqual(
      pages.map((entry) => [entry.query.per_page, entry.query.page]),
      [
        ['100', '1'],
        ['100', '2'],
      ],
    );
    assert.equal(stub.state.tokens.length, 101);
    const listed = stub.requests.filter(
      (entry) => entry.path === '/installation/repositories' && entry.auth === 'installation:1',
    );
    assert.deepEqual(
      listed.map((entry) => entry.query.page),
      ['1', '2'],
    );
    const rows = outcome.rows.filter((row) => row.repository.startsWith('o1/repo-'));
    assert.equal(rows.length, 101);
    for (const row of rows) assert.equal(row.result, 'not a country (no be/pom.xml)');
  });

  it('a second run over the same state is idempotent', async () => {
    const stub = await github({
      installations: [{ id: 1, account: 'o1', repositories: ['o1/country-a', 'o1/country-b'] }],
      repositories: {
        'o1/country-a': country('4.0.70'),
        'o1/country-b': country('4.0.69', appPull(11, '4.0.70')),
      },
    });
    const first = await bump(stub);
    const m = openedNumber(first.result('o1/country-a'));
    assert.equal(first.result('o1/country-b'), 'moved #11 from 4.0.70');

    const from = stub.requests.length;
    const second = await bump(stub);
    assert.equal(second.result('o1/country-a'), `#${m} at 4.0.77 already`);
    assert.equal(second.result('o1/country-b'), '#11 at 4.0.77 already');
    assert.equal(second.exitCode, 0);
    const writes = stub.requests
      .slice(from)
      .filter((entry) => entry.method !== 'GET' && !entry.path.endsWith('/access_tokens'));
    assert.deepEqual(writes, []);
  });

  it("writes only the listed routes: no DELETE, no write to the default branch, no .github/workflows path, no close but the app's own", async () => {
    const stub = await github({
      installations: [
        {
          id: 1,
          account: 'o1',
          repositories: [
            'o1/country-a',
            'o1/country-b',
            'o1/country-c',
            'o1/country-d',
            'o1/country-e',
          ],
        },
        { id: 2, account: 'o2', repositories: ['o2/country-f'] },
      ],
      repositories: {
        'o1/country-a': country('4.0.70'),
        'o1/country-b': country('4.0.69', appPull(11, '4.0.70')),
        'o1/country-c': country('4.0.69', appPull(12, '4.0.70', { mergeable: false })),
        'o1/country-d': country(VERSION, appPull(13, '4.0.70')),
        'o1/country-e': country('4.0.70', {
          defaultBranch: 'trunk',
          branches: { 'base-bump/4.0.71': { files: { 'be/pom.xml': pom('4.0.71') } } },
          pulls: [{ number: 14, head: 'base-bump/4.0.71', author: 'person-b' }],
        }),
        'o2/country-f': country(VERSION, {
          branches: { 'base-bump/4.0.70': { files: { 'be/pom.xml': pom('4.0.70') } } },
          pulls: [{ number: 15, head: 'base-bump/4.0.70', author: 'person-b' }],
        }),
      },
    });
    const outcome = await bump(stub);
    assert.match(outcome.result('o1/country-a'), /^opened #\d+$/);
    assert.equal(outcome.result('o1/country-b'), 'moved #11 from 4.0.70');
    assert.match(outcome.result('o1/country-c'), /^replaced #12 by #\d+ \(conflict with main\)$/);
    assert.equal(outcome.result('o1/country-d'), 'at 4.0.77 already; closed #13 (behind main)');
    assert.equal(outcome.result('o1/country-e'), '#14 made by hand is open: not touched');
    assert.equal(
      outcome.result('o2/country-f'),
      'at 4.0.77 already; #15 made by hand is open: not touched',
    );

    const owner = '[^/]+/[^/]+';
    const ROUTES = [
      /^GET \/app$/,
      /^GET \/app\/installations$/,
      /^POST \/app\/installations\/\d+\/access_tokens$/,
      /^GET \/installation\/repositories$/,
      new RegExp(`^GET /repos/${owner}/contents/.+$`),
      new RegExp(`^PUT /repos/${owner}/contents/.+$`),
      new RegExp(`^GET /repos/${owner}/git/ref/heads/.+$`),
      new RegExp(`^POST /repos/${owner}/git/refs$`),
      new RegExp(`^GET /repos/${owner}/compare/[^/]+\\.\\.\\.[^/]+$`),
      new RegExp(`^GET /repos/${owner}/pulls$`),
      new RegExp(`^GET /repos/${owner}/pulls/\\d+$`),
      new RegExp(`^POST /repos/${owner}/pulls$`),
      new RegExp(`^PATCH /repos/${owner}/pulls/\\d+$`),
      new RegExp(`^POST /repos/${owner}/issues/\\d+/comments$`),
      new RegExp(`^POST /repos/${owner}/pulls/\\d+/requested_reviewers$`),
    ];
    const authorOf = (pull) => pull.user?.login ?? (pull.author === 'app' ? BOT : pull.author);
    let checked = 0;
    for (const { seed, stub: run } of allRuns) {
      const defaults = (repository) => seed.repositories?.[repository]?.defaultBranch ?? 'main';
      const appPulls = new Set();
      for (const [repository, state] of Object.entries(run.state.repositories ?? {}))
        for (const pull of Object.values(state.pulls ?? {}))
          if (authorOf(pull) === BOT) appPulls.add(`${repository}#${pull.number}`);
      for (const entry of run.requests) {
        checked++;
        const call = `${entry.method} ${entry.path}`;
        assert.ok(['GET', 'POST', 'PUT', 'PATCH'].includes(entry.method), call);
        assert.ok(!entry.unknown, `an unknown route: ${call}`);
        assert.ok(
          ROUTES.some((route) => route.test(call)),
          `not a listed route: ${call}`,
        );
        assert.ok(!entry.path.includes('.github/workflows'), call);
        assert.ok(!String(entry.query?.ref ?? '').includes('.github/workflows'), call);
        assert.ok(!String(entry.body?.path ?? '').includes('.github/workflows'), call);
        const repository = /^\/repos\/([^/]+\/[^/]+)\//.exec(entry.path)?.[1];
        if (entry.method === 'PUT') {
          assert.ok(entry.path.endsWith('/contents/be/pom.xml'), call);
          assert.ok(entry.body?.branch, `${call} names its branch`);
          assert.notEqual(
            entry.body.branch,
            defaults(repository),
            `${call} writes the default branch`,
          );
        }
        if (entry.method === 'POST' && entry.path.endsWith('/git/refs'))
          assert.ok(entry.body.ref.startsWith('refs/heads/base-bump/'), call);
        const number = /\/(?:pulls|issues)\/(\d+)(?:\/comments)?$/.exec(entry.path)?.[1];
        if (
          entry.method === 'PATCH' ||
          (entry.method === 'POST' && entry.path.endsWith('/comments'))
        )
          assert.ok(
            appPulls.has(`${repository}#${number}`),
            `${call} touches a pull request the app did not make`,
          );
      }
    }
    assert.ok(
      allRuns.length >= 17 && checked > 500,
      `${allRuns.length} stubs, ${checked} requests`,
    );
  });
});
