/**
 * Opens or moves the bump pull request in every country repository that installed the Niord base
 * bump app, after the base product published a version. The script signs a JSON web token as the
 * app, lists the app's installations, mints one installation token each and walks the
 * repositories each installation selected. A repository whose be/pom.xml has the base product's
 * parent (org.niord:niord-parent) is a country:
 *
 * - its default branch at the version or past it: nothing to bump; an open bump pull request of
 *   the app's whose branch is at or below the default branch is commented on and closed;
 * - an open bump pull request of the app's: moved in place, the new <parent> committed on its own
 *   branch and its title and body rewritten; one that conflicts with the default branch is
 *   replaced by a fresh one and then commented on and closed, its branch kept;
 * - an open bump pull request a person made: reported, not touched;
 * - none: a branch base-bump/<version> from the default branch's head, the new <parent> committed
 *   on it, the pull request opened and the code owners of /be/pom.xml asked for a review.
 *
 * Every repository the installations select gets a row in the summary, the ones that are not
 * countries included. One repository's or one installation's failure is its row and a warning,
 * and the next one is done.
 *
 *   node scripts/open-bump-pull-requests.mjs --version 4.0.<n> [--api <base>]   # from fe/
 *
 * The environment: NIORD_BUMP_APP_ID (the app's id) and NIORD_BUMP_APP_PRIVATE_KEY (its .pem,
 * whole), read from the environment only and never printed; GITHUB_API_URL, the API base the
 * runner sets (stated from knowledge), https://api.github.com when unset, and `--api` before it,
 * for the tests (https always; http only on a loopback host); GITHUB_STEP_SUMMARY, the file the
 * summary table is appended to when set (it is printed to stdout always); BASE_REPO and RUN_URL,
 * the base product's repository and the publish run, named in the pull request's body.
 *
 * Exit 0 when the app worked, whatever each country's row says; 1 when the app as a whole failed
 * (an argument refused, the id or the key missing or unreadable, the app or its installations not
 * readable), with ::error.
 *
 * The writes it may make: a branch base-bump/<version>; be/pom.xml on that branch or on the
 * branch of the app's own open bump pull request; the app's own pull requests opened, retitled,
 * commented on and closed; review requests on the pull requests it opened. Never a delete, a
 * merge, an approval or a label, never a write to a default branch or to a workflow file, never a
 * person's pull request. The calls run one after another.
 *
 * GitHub's REST API shapes and rules the script relies on are stated from knowledge, and each is
 * marked so where it is used; the first live run is their proof.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const VERSION_PATTERN = /^4\.0\.[0-9]+$/;

const DEFAULT_API = 'https://api.github.com';
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]']);
const POM = 'be/pom.xml';
const CODEOWNERS = '.github/CODEOWNERS';
const PER_PAGE = 100;
const TIMEOUT_MS = 30_000;
const MERGEABLE_READS = 3;
const MERGEABLE_WAIT_MS = 2000;
// GitHub's refusal of a commit or a branch that would change a workflow file, for an app without
// the workflows permission, stated from knowledge; matched by its text, since its status is not.
const WORKFLOW_REFUSAL = 'refusing to allow a GitHub App to create or update workflow';

/** A refused request: its status, method, path and GitHub's message. */
class GitHubError extends Error {
  constructor({ status, method, path, message }) {
    super(message);
    this.status = status;
    this.method = method;
    this.path = path;
  }
}

/** A repository's or an installation's row that ends its walk, with a warning when `warn`. */
class Row extends Error {
  constructor(result, { warn = false } = {}) {
    super(result);
    this.warn = warn;
  }
}

/**
 * The arguments: `--version 4.0.<n>`, required, and `--api <url>`. Anything else is refused, and
 * no refusal echoes a value: an argument is no place for a credential.
 */
export function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const name = argv[i];
    if (name === '--key' || name.startsWith('--key='))
      throw new Error(
        'a --key argument is refused: the key is read from NIORD_BUMP_APP_PRIVATE_KEY only',
      );
    if (name !== '--version' && name !== '--api')
      throw new Error(
        'an argument the script does not take: only --version 4.0.<n> and --api <url>',
      );
    const field = name.slice(2);
    if (args[field] !== undefined) throw new Error(`${name} is given twice`);
    if (i + 1 >= argv.length) throw new Error(`${name} needs a value`);
    args[field] = argv[++i];
  }
  if (args.version === undefined) throw new Error('--version 4.0.<n> is required');
  if (!VERSION_PATTERN.test(args.version))
    throw new Error(
      '--version must be 4.0.<n>, a release of the base product: a preview or another major version gets no bump',
    );
  return args;
}

/**
 * The API base: `text` (`--api`, else GITHUB_API_URL), else https://api.github.com. https always,
 * http only on a loopback host; no credentials, query or fragment; a trailing slash dropped, so an
 * Enterprise base keeps its /api/v3.
 */
export function apiBase(text) {
  const raw = text?.trim() ? text.trim() : DEFAULT_API;
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error('the GitHub API base (--api or GITHUB_API_URL) is not a URL');
  }
  if (url.username || url.password)
    throw new Error('the GitHub API base (--api or GITHUB_API_URL) must carry no credentials');
  if (url.search || url.hash || raw.includes('?') || raw.includes('#'))
    throw new Error(
      'the GitHub API base (--api or GITHUB_API_URL) must carry no query or fragment',
    );
  const loopback = LOOPBACK.has(url.hostname);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback))
    throw new Error(
      'the GitHub API base (--api or GITHUB_API_URL) must be https; http only on a loopback host (127.0.0.1, localhost, ::1)',
    );
  return `${url.origin}${url.pathname.replace(/\/+$/, '')}`;
}

/**
 * The app's JSON web token. GitHub's rules, stated from knowledge: RS256 with the app's private
 * key (the PKCS#1 .pem GitHub generates, which createPrivateKey reads); iss the app's id (its
 * client id is accepted too); exp at most ten minutes ahead. iat a minute back allows for the
 * clocks' skew, and exp nine minutes ahead stays inside the ten.
 */
export function appJwt({ appId, key, now = Date.now() }) {
  const part = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const iat = Math.floor(now / 1000) - 60;
  const signed = `${part({ alg: 'RS256', typ: 'JWT' })}.${part({ iat, exp: iat + 600, iss: String(appId) })}`;
  const privateKey = typeof key === 'string' ? crypto.createPrivateKey(key) : key;
  return `${signed}.${crypto.sign('sha256', Buffer.from(signed), privateKey).toString('base64url')}`;
}

/** A workflow command's data with % CR LF escaped, so that a message stays on its one line. */
const escapeData = (text) =>
  String(text).replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');
const command = (name, text) => `::${name}::${escapeData(text)}`;

/** What a failure says, without a credential: the status, the call and GitHub's message. */
const describe = (error) =>
  error instanceof GitHubError
    ? `${error.status} ${error.method} ${error.path}: ${error.message}`
    : error.message;

/** A branch name as a path's tail: each of its segments escaped, its slashes kept. */
const branchPath = (name) => name.split('/').map(encodeURIComponent).join('/');

const refusedAsWorkflow = (error) =>
  error instanceof GitHubError &&
  error.status >= 400 &&
  error.status < 500 &&
  error.message.includes(WORKFLOW_REFUSAL);

/**
 * A client of the API at `api`: every request with GitHub's media type and API version, a User
 * agent, and `Bearer <credential()>` (the app's JWT and an installation token alike, stated from
 * knowledge); JSON bodies; no redirect followed, so that a credential never reaches another host;
 * a timeout, so that a stuck call cannot use up the job.
 */
function client(api, fetch, credential) {
  async function request(method, route, { query, body, accept = [] } = {}) {
    const search = query ? `?${new URLSearchParams(query)}` : '';
    const response = await fetch(`${api}${route}${search}`, {
      method,
      headers: {
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'niord-base-bump',
        Authorization: `Bearer ${credential()}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const text = await response.text();
    let payload;
    try {
      payload = text ? JSON.parse(text) : null;
    } catch {
      payload = null;
    }
    if (response.ok || accept.includes(response.status))
      return { status: response.status, payload };
    // GitHub's error shape, stated from knowledge: { message, errors?: [{ message? }] }.
    const details = (payload?.errors ?? [])
      .map((error) => (typeof error === 'string' ? error : error?.message))
      .filter(Boolean);
    const message = [payload?.message ?? response.statusText, ...details].join(': ');
    throw new GitHubError({ status: response.status, method, path: route, message });
  }

  return {
    request,
    /** The body of a 2xx answer. */
    async json(method, route, options) {
      return (await request(method, route, options)).payload;
    },
    /** Every item of a list, page by page (per_page 100), until a page comes back short. */
    async paginate(route, query, items = (page) => page) {
      const all = [];
      for (let page = 1; ; page++) {
        const list = items(
          (await request('GET', route, { query: { ...query, per_page: PER_PAGE, page } })).payload,
        );
        if (!Array.isArray(list)) throw new Error(`GET ${route} answered no list`);
        all.push(...list);
        if (list.length < PER_PAGE) return all;
      }
    },
  };
}

/**
 * An installation token, masked in the log the moment it is minted, before anything else is
 * printed. GitHub's answer, stated from knowledge: { token, expires_at, permissions }, the
 * permissions by their names (contents, pull_requests, …) with 'read' or 'write'; ::add-mask::
 * is the runner's command that hides a value in the rest of the job's log.
 */
export async function installationToken(app, id, log) {
  const payload = await app.json('POST', `/app/installations/${id}/access_tokens`);
  if (typeof payload?.token !== 'string' || !payload.token)
    throw new Error(`POST /app/installations/${id}/access_tokens answered no token`);
  log(`::add-mask::${payload.token}`);
  return { token: payload.token, permissions: payload.permissions ?? {} };
}

/**
 * The pom's parent moved to `version`. XML comments are blanked to spaces of the same length
 * (line ends kept) before the first <parent>…</parent> is looked for, so a parent inside a comment
 * is never read. Returns { from, text }, where text is the pom with only the parent version's
 * characters replaced and every other byte kept; or { reason: 'no <parent>' }, { reason:
 * 'parent', parent: '<groupId>:<artifactId>' } for another parent, { reason: 'version', from } for
 * a version that is not 4.0.<n>.
 */
export function moveParent(pom, version) {
  const blanked = pom.replace(/<!--[\s\S]*?-->/g, (comment) => comment.replace(/[^\r\n]/g, ' '));
  const parent = /<parent>([\s\S]*?)<\/parent>/.exec(blanked);
  if (!parent) return { reason: 'no <parent>' };
  const start = parent.index + '<parent>'.length;
  const field = (name) => {
    const match = new RegExp(`<${name}>([^<]*)</${name}>`).exec(parent[1]);
    if (!match) return undefined;
    const value = match[1].trim();
    return { value, at: start + match.index + name.length + 2 + match[1].indexOf(value) };
  };
  const group = field('groupId');
  const artifact = field('artifactId');
  if (group?.value !== 'org.niord' || artifact?.value !== 'niord-parent')
    return { reason: 'parent', parent: `${group?.value ?? '?'}:${artifact?.value ?? '?'}` };
  const current = field('version');
  if (!current || !VERSION_PATTERN.test(current.value))
    return { reason: 'version', from: current?.value ?? '' };
  return {
    from: current.value,
    text: pom.slice(0, current.at) + version + pom.slice(current.at + current.value.length),
  };
}

/** Two 4.0.<n> versions compared segment by segment as numbers: -1, 0 or 1. */
export function compareVersions(a, b) {
  const left = a.split('.').map(Number);
  const right = b.split('.').map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const difference = (left[i] ?? 0) - (right[i] ?? 0);
    if (difference) return Math.sign(difference);
  }
  return 0;
}

/**
 * The bump pull request's body: what it moves, what the Base bump workflow then does, how a newer
 * release moves it, and what the app never does.
 */
export function prBody({ version, from, baseRepo, runUrl, replaces, app = 'niord-base-bump' }) {
  const release = baseRepo ? `release v${version} in ${baseRepo}` : `release v${version}`;
  return [
    `The base product published ${version} (${release}). This pull request moves only the \`<parent>\` of \`be/pom.xml\`, from ${from} to ${version}.`,
    ...(replaces
      ? [
          `Replaces #${replaces}, whose branch conflicts with the default branch, so that its workflows no longer start.`,
        ]
      : []),
    "The Base bump workflow runs on it: it checks the two zips against the release notes, pins `fe/niord-pins.lock.json`, moves every pinned pack to its home's newest pack, copies the stub and the launcher, commits as github-actions[bot], dispatches CI, and posts one comment with the upgrade report and its `## Human actions`. The check `niord/upgrade-actions` is green when no box is left: green means no task beyond the review, the merge and the deploy.",
    'A newer release moves this pull request on its own branch, and your commits stay. Each move posts the comment again: a box whose text did not change keeps its tick, and a box the new version adds starts unticked. If this branch comes to conflict with the default branch, the next release replaces this pull request with a fresh one and closes this one with a comment.',
    'The app never merges, approves, labels or deletes a branch, and touches no other branch or pull request.',
    `${runUrl ? `Opened by the publish run ${runUrl} with` : 'Opened by'} the Niord base bump app, https://github.com/apps/${app}.`,
  ].join('\n\n');
}

/**
 * The review request for /be/pom.xml from a CODEOWNERS text: the owners of the last line whose
 * pattern is /be/pom.xml, be/pom.xml or * (the last matching line wins, GitHub's rule, stated
 * from knowledge; other patterns are not matched). '@user' is a reviewer, '@org/team' a team
 * reviewer by its slug (team_reviewers takes slugs, stated from knowledge), an email is ignored.
 */
export function codeOwners(text) {
  let owners = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/(^|\s)#.*$/, '').trim();
    if (!line) continue;
    const [pattern, ...rest] = line.split(/\s+/);
    if (['/be/pom.xml', 'be/pom.xml', '*'].includes(pattern)) owners = rest;
  }
  const reviewers = [];
  const teams = [];
  for (const owner of owners) {
    const team = /^@[^/\s]+\/([^/\s]+)$/.exec(owner);
    if (team) teams.push(team[1]);
    else if (/^@[^/\s@]+$/.test(owner)) reviewers.push(owner.slice(1));
  }
  return { reviewers, team_reviewers: teams };
}

/** The summary: a heading and a table of one row per repository, '|' escaped in a cell. */
export function summaryTable(version, rows) {
  const cell = (text) =>
    String(text ?? '')
      .replace(/\r?\n/g, ' ')
      .replaceAll('|', '\\|');
  return [
    `## Bump pull requests for ${version}`,
    '',
    '| Repository | Was | Result |',
    '|---|---|---|',
    ...rows.map((row) => `| ${cell(row.repository)} | ${cell(row.was)} | ${cell(row.result)} |`),
    '',
  ].join('\n');
}

/** A file on `ref`, decoded, with its blob sha; undefined when it is not there. */
async function readFile(github, full, file, ref) {
  const { status, payload } = await github.request('GET', `/repos/${full}/contents/${file}`, {
    query: { ref },
    accept: [404],
  });
  if (status === 404) return undefined;
  // GitHub's shape, stated from knowledge: { type: 'file', encoding: 'base64', content, sha },
  // the content broken by line feeds (which Buffer's base64 decoding skips) and sha the blob's.
  if (payload?.type !== 'file' || payload.encoding !== 'base64' || typeof payload.sha !== 'string')
    throw new Row(`${file} on ${ref} is not a file the contents API serves whole: not touched`, {
      warn: true,
    });
  const bytes = Buffer.from(payload.content, 'base64');
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    throw new Row(`${file} on ${ref} is not UTF-8: not touched`, { warn: true });
  return { text, sha: payload.sha };
}

/**
 * Runs the bump over every installation of the app. `key` is the .pem's text or a private
 * KeyObject; `fetch`, `sleep` and `now` are the tests' to replace. Returns the rows ({ repository,
 * was, result }), the exit code and the summary.
 */
export async function run({
  api,
  appId,
  key,
  version,
  baseRepo,
  runUrl,
  log = (line) => console.log(line),
  fetch = globalThis.fetch,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  now = Date.now,
}) {
  const rows = [];
  const head = `base-bump/${version}`;
  const warn = (text) => log(command('warning', text));
  // A fresh JWT for each of the app's requests, so that a long run never meets an expired one.
  const app = client(api, fetch, () => appJwt({ appId, key, now: now() }));

  let slug;
  let installations;
  try {
    slug = (await app.json('GET', '/app'))?.slug;
    if (typeof slug !== 'string' || !slug) throw new Error('GET /app answered no slug');
    installations = await app.paginate('/app/installations', {});
  } catch (error) {
    const text = `the Niord base bump app could not be used: ${describe(error)}`;
    log(command('error', text));
    return {
      rows,
      exitCode: 1,
      summary: `## Bump pull requests for ${version}\n\nNot done: ${text}\n`,
    };
  }
  // The app's own user, GitHub's naming for an app, stated from knowledge: '<slug>[bot]'.
  const bot = `${slug}[bot]`;
  if (!installations.length)
    warn('the Niord base bump app is installed nowhere: no country gets a bump pull request');

  for (const installation of installations) {
    // GitHub's shape, stated from knowledge: an installation's account is { login, … }.
    const account = installation.account?.login ?? `installation ${installation.id}`;
    const label = `${account} (installation)`;
    try {
      // GitHub's field, stated from knowledge: suspended_at is set while the installation is
      // suspended, and such an installation mints no token.
      if (installation.suspended_at) {
        rows.push({ repository: label, was: '', result: 'installation suspended' });
        continue;
      }
      const { token, permissions } = await installationToken(app, installation.id, log);
      const lacking = ['contents', 'pull_requests'].filter((name) => permissions[name] !== 'write');
      if (lacking.length) {
        const result = `not done: the installation on ${account} lacks ${lacking.join(' and ')}: write`;
        rows.push({ repository: label, was: '', result });
        warn(`${label}: ${result}`);
        continue;
      }
      const github = client(api, fetch, () => token);
      // GitHub's shape, stated from knowledge: { total_count, repositories: [{ full_name,
      // default_branch, archived, … }] }, an object around the page.
      const repositories = await github.paginate(
        '/installation/repositories',
        {},
        (page) => page?.repositories,
      );
      for (const repository of repositories) {
        const row = { repository: repository.full_name, was: '', result: '' };
        rows.push(row);
        try {
          row.result = await bumpRepository(github, repository, row);
        } catch (error) {
          row.result = error instanceof Row ? error.message : `failed: ${describe(error)}`;
          if (!(error instanceof Row) || error.warn) warn(`${row.repository}: ${row.result}`);
        }
      }
    } catch (error) {
      const result = `failed: ${describe(error)}`;
      rows.push({ repository: label, was: '', result });
      warn(`${label}: ${result}`);
    }
  }
  return { rows, exitCode: 0, summary: summaryTable(version, rows) };

  /** One repository: its row's result, or a Row thrown. */
  async function bumpRepository(github, repository, row) {
    const full = repository.full_name;
    const base = repository.default_branch;
    if (repository.archived) return 'archived';
    const pom = await readFile(github, full, POM, base);
    if (!pom) return `not a country (no ${POM})`;
    const moved = moveParent(pom.text, version);
    if (moved.reason === 'no <parent>') return 'not a country (no <parent>)';
    if (moved.reason === 'parent') return `not a country (parent ${moved.parent})`;
    if (moved.reason === 'version')
      throw new Row(`unreadable parent version '${moved.from}'`, { warn: true });
    row.was = moved.from;

    // GitHub's list, stated from knowledge: the open pull requests without mergeable, each with
    // title, user.login, head { ref, repo.full_name } and base.ref. A fork's head is not ours.
    const open = (await github.paginate(`/repos/${full}/pulls`, { state: 'open' })).filter(
      (pull) =>
        pull.head?.repo?.full_name === full &&
        pull.head?.ref?.startsWith('base-bump/') &&
        pull.base?.ref === base,
    );
    const mine = open
      .filter((pull) => pull.user?.login === bot)
      .sort((a, b) => b.number - a.number);
    const theirs = open
      .filter((pull) => pull.user?.login !== bot)
      .sort((a, b) => a.number - b.number);
    const byHand = theirs.map((pull) => `#${pull.number} made by hand is open: not touched`);

    if (compareVersions(moved.from, version) >= 0) {
      const notes = [`at ${moved.from} already`];
      for (const pull of mine)
        notes.push(await closeIfBehind(github, full, base, moved.from, pull));
      return [...notes, ...byHand].join('; ');
    }
    if (mine.length) {
      const [pull, ...others] = mine;
      const result = await moveOrReplace(github, full, base, moved.from, pull);
      return [
        result,
        ...others.map((other) => `also open #${other.number}: close by hand`),
        ...byHand,
      ].join('; ');
    }
    if (theirs.length) return byHand.join('; ');
    const opened = await openFresh(github, full, base, moved.from, pom);
    return `opened #${opened.number}${opened.notes}`;
  }

  /** The app's pull request when the default branch has overtaken it: commented on and closed. */
  async function closeIfBehind(github, full, base, mainVersion, pull) {
    const branch = await readFile(github, full, POM, pull.head.ref);
    const at = branch && moveParent(branch.text, version).from;
    if (!at || !VERSION_PATTERN.test(at))
      return `#${pull.number}: no readable parent on ${pull.head.ref}, not touched`;
    if (compareVersions(at, mainVersion) > 0) return `#${pull.number} at ${at}: not touched`;
    try {
      await github.json('POST', `/repos/${full}/issues/${pull.number}/comments`, {
        body: {
          body: `${base} is at ${mainVersion}, and this pull request moves to ${at}: closed by the Niord base bump app. Your commits stay on ${pull.head.ref}.`,
        },
      });
      await github.json('PATCH', `/repos/${full}/pulls/${pull.number}`, {
        body: { state: 'closed' },
      });
    } catch (error) {
      warn(`${full}: closing #${pull.number} failed: ${describe(error)}`);
      return `closing #${pull.number} failed: ${describe(error)}`;
    }
    return `closed #${pull.number} (behind ${base})`;
  }

  /** The app's open bump pull request: moved in place, or replaced when it conflicts. */
  async function moveOrReplace(github, full, base, from, pull) {
    const ref = pull.head.ref;
    const branch = await readFile(github, full, POM, ref);
    if (!branch)
      throw new Row(`#${pull.number} has no ${POM} on ${ref}: not touched`, { warn: true });
    const onBranch = moveParent(branch.text, version);
    if (onBranch.reason)
      throw new Row(`#${pull.number}: no readable parent on ${ref}, not touched`, { warn: true });
    if (compareVersions(onBranch.from, version) >= 0) {
      const already = `#${pull.number} at ${onBranch.from} already`;
      // A run whose PUT landed and whose PATCH failed left the old title: set it once here. The
      // list's title, GitHub's shape stated from knowledge, says whether it is still the old one.
      if (onBranch.from !== version || pull.title === `Base bump to ${version}`) return already;
      try {
        await github.json('PATCH', `/repos/${full}/pulls/${pull.number}`, {
          body: {
            title: `Base bump to ${version}`,
            body: prBody({ version, from, baseRepo, runUrl, app: slug }),
          },
        });
      } catch (error) {
        warn(`${full}: refreshing the title of #${pull.number} failed: ${describe(error)}`);
        return `${already}; refreshing the title failed: ${describe(error)}`;
      }
      return `${already}; title refreshed`;
    }

    // GitHub's, stated from knowledge: the single pull request's mergeable is null while GitHub
    // computes it, so it is read again a little later.
    let mergeable = null;
    for (let read = 0; read < MERGEABLE_READS; read++) {
      if (read) await sleep(MERGEABLE_WAIT_MS);
      mergeable =
        (await github.json('GET', `/repos/${full}/pulls/${pull.number}`))?.mergeable ?? null;
      if (mergeable !== null) break;
    }

    if (mergeable === false) {
      // GitHub starts no pull_request workflow on a pull request that conflicts (stated from
      // knowledge), so a move in place would start nothing: a fresh one replaces it.
      let opened;
      try {
        opened = await openFresh(github, full, base, from, undefined, pull.number);
      } catch (error) {
        if (!(error instanceof Row)) throw error;
        throw new Row(`#${pull.number} conflicts with ${base}, not replaced: ${error.message}`, {
          warn: true,
        });
      }
      try {
        await github.json('POST', `/repos/${full}/issues/${pull.number}/comments`, {
          body: {
            body: `Replaced by #${opened.number}: this branch conflicts with ${base}, so its workflows no longer start. Your commits stay on ${ref}.`,
          },
        });
        await github.json('PATCH', `/repos/${full}/pulls/${pull.number}`, {
          body: { state: 'closed' },
        });
      } catch (error) {
        warn(`${full}: closing #${pull.number} failed: ${describe(error)}`);
        return `replaced #${pull.number} by #${opened.number} (conflict with ${base})${opened.notes}; closing #${pull.number} failed: ${describe(error)}`;
      }
      return `replaced #${pull.number} by #${opened.number} (conflict with ${base})${opened.notes}`;
    }

    try {
      await putPom(github, full, ref, branch.sha, onBranch.text);
    } catch (error) {
      if (refusedAsWorkflow(error))
        throw new Row(`GitHub refused the commit on ${ref} as a workflow change; push it by hand`, {
          warn: true,
        });
      // GitHub's, stated from knowledge: a blob sha that is no longer the branch's is a 409.
      if (error instanceof GitHubError && (error.status === 409 || error.status === 422))
        throw new Row(`#${pull.number} moved meanwhile; the next publish moves it`, { warn: true });
      throw error;
    }
    await github.json('PATCH', `/repos/${full}/pulls/${pull.number}`, {
      body: {
        title: `Base bump to ${version}`,
        body: prBody({ version, from, baseRepo, runUrl, app: slug }),
      },
    });
    return `moved #${pull.number} from ${onBranch.from}${mergeable === null ? ' (mergeability unknown)' : ''}`;
  }

  /**
   * A fresh base-bump/<version> from the default branch's head, the new parent committed on it,
   * the pull request opened and the code owners asked. `pom` is the default branch's, read just
   * before; a replacement reads it again. Returns { number, notes }, or throws its Row.
   */
  async function openFresh(github, full, base, from, pom, replaces) {
    let put;
    for (let attempt = 1; ; attempt++) {
      // GitHub's shape, stated from knowledge, here and in takeOver: GET git/ref/heads/<branch>
      // answers { ref, object: { sha, type: 'commit' } }.
      const defaultHead = (
        await github.json('GET', `/repos/${full}/git/ref/heads/${branchPath(base)}`)
      )?.object?.sha;
      try {
        await github.json('POST', `/repos/${full}/git/refs`, {
          body: { ref: `refs/heads/${head}`, sha: defaultHead },
        });
        const source = pom ?? (await readFile(github, full, POM, base));
        const moved = source && moveParent(source.text, version);
        if (!moved || moved.reason)
          throw new Row(`no readable parent on ${base} any more: not touched`, { warn: true });
        put = { sha: source.sha, text: moved.text };
        break;
      } catch (error) {
        if (refusedAsWorkflow(error)) {
          if (attempt === 1) continue;
          throw new Row(
            `GitHub refused the branch: ${base} changed workflow files meanwhile; the next publish opens it, or push ${head} by hand`,
            { warn: true },
          );
        }
        // GitHub's text, stated from knowledge, for a branch that exists already: a re-run's.
        if (
          !(
            error instanceof GitHubError &&
            error.status === 422 &&
            /reference already exists/i.test(error.message)
          )
        )
          throw error;
        put = await takeOver(github, full, defaultHead);
        break;
      }
    }

    if (put) {
      try {
        await putPom(github, full, head, put.sha, put.text);
      } catch (error) {
        if (refusedAsWorkflow(error))
          throw new Row(
            `GitHub refused the commit on ${head} as a workflow change; push it by hand`,
            { warn: true },
          );
        if (!(error instanceof GitHubError && error.status === 409)) throw error;
        // The default branch's pom changed between its read and the branch: the branch's own.
        const again = await readFile(github, full, POM, head);
        const moved = again && moveParent(again.text, version);
        if (!moved || moved.reason)
          throw new Row(`no readable parent on ${head}: not touched`, { warn: true });
        if (moved.from !== version) await putPom(github, full, head, again.sha, moved.text);
      }
    }

    // GitHub's shape, stated from knowledge: POST /pulls answers the new pull request, { number, … }.
    const pull = await github.json('POST', `/repos/${full}/pulls`, {
      body: {
        title: `Base bump to ${version}`,
        head,
        base,
        body: prBody({ version, from, baseRepo, runUrl, replaces, app: slug }),
      },
    });
    return { number: pull.number, notes: await askOwners(github, full, base, pull.number) };
  }

  /**
   * An existing base-bump/<version>: kept as it is when its pom is at the version; taken over,
   * with its own blob sha, when its head equals the default branch's or trails it (a run that
   * stopped between the branch and the commit); otherwise not the step's, and never forced.
   */
  async function takeOver(github, full, defaultHead) {
    const branchHead = (
      await github.json('GET', `/repos/${full}/git/ref/heads/${branchPath(head)}`)
    )?.object?.sha;
    const branch = await readFile(github, full, POM, head);
    const moved = branch && moveParent(branch.text, version);
    if (moved && !moved.reason && moved.from === version) return undefined;
    // GitHub's, stated from knowledge: compare's status is the head's relative to the base,
    // identical, ahead, behind or diverged.
    const { status } = await github.json(
      'GET',
      `/repos/${full}/compare/${defaultHead}...${branchHead}`,
    );
    if (moved && !moved.reason && (status === 'identical' || status === 'behind'))
      return { sha: branch.sha, text: moved.text };
    throw new Row(`a branch ${head} exists that the step did not make: not touched`, {
      warn: true,
    });
  }

  /** The new parent committed on `branch`, with that branch's blob sha of the pom. */
  async function putPom(github, full, branch, sha, text) {
    await github.json('PUT', `/repos/${full}/contents/${POM}`, {
      body: {
        message: `Base bump to ${version}: the <parent> of ${POM}`,
        content: Buffer.from(text, 'utf8').toString('base64'),
        sha,
        branch,
      },
    });
  }

  /** The code owners of /be/pom.xml asked for a review; the row's notes. */
  async function askOwners(github, full, base, number) {
    const file = await readFile(github, full, CODEOWNERS, base);
    const owners = file ? codeOwners(file.text) : { reviewers: [], team_reviewers: [] };
    if (!owners.reviewers.length && !owners.team_reviewers.length)
      return `; no code owner of /${POM} to ask`;
    try {
      await github.json('POST', `/repos/${full}/pulls/${number}/requested_reviewers`, {
        body: owners,
      });
    } catch (error) {
      if (!(error instanceof GitHubError && error.status === 422)) throw error;
      warn(`${full}: the review request on #${number} was refused: ${describe(error)}`);
      return `; review request refused: ${error.status} ${error.message}`;
    }
    return '';
  }
}

/**
 * The command line: the arguments, the API base and the app's id and key checked before any
 * request, then the run, its log printed as it comes and its summary printed and appended to
 * GITHUB_STEP_SUMMARY. Returns the exit code.
 */
export async function main(
  argv,
  env = process.env,
  { fetch = globalThis.fetch, stdout = process.stdout } = {},
) {
  const print = (line) => stdout.write(`${line}\n`);
  const refuse = (rule) => {
    print(command('error', rule));
    return 1;
  };
  let args;
  let api;
  try {
    args = parseArgs(argv);
    api = apiBase(args.api ?? env.GITHUB_API_URL);
  } catch (error) {
    return refuse(error.message);
  }
  const appId = env.NIORD_BUMP_APP_ID?.trim();
  if (!appId) return refuse("NIORD_BUMP_APP_ID is not set: the Niord base bump app's id");
  // An app id is digits; GitHub also takes the app's client id (Iv…) as iss, stated from knowledge.
  if (!/^[0-9]+$/.test(appId) && !/^Iv[0-9A-Za-z.]+$/.test(appId))
    return refuse("NIORD_BUMP_APP_ID is not an app id (digits) or an app's client id (Iv…)");
  if (!env.NIORD_BUMP_APP_PRIVATE_KEY?.trim())
    return refuse(
      "NIORD_BUMP_APP_PRIVATE_KEY is not set: the Niord base bump app's private key, its .pem whole",
    );
  let key;
  try {
    key = crypto.createPrivateKey(env.NIORD_BUMP_APP_PRIVATE_KEY);
  } catch {
    // Neither the key's text nor crypto's message is printed.
    return refuse(
      "NIORD_BUMP_APP_PRIVATE_KEY is not a private key in PEM form: the app's .pem whole, as GitHub generated it",
    );
  }
  if (key.asymmetricKeyType !== 'rsa')
    return refuse(
      "NIORD_BUMP_APP_PRIVATE_KEY is not an RSA key: the app's .pem, as GitHub generated it",
    );

  const outcome = await run({
    api,
    appId,
    key,
    version: args.version,
    baseRepo: env.BASE_REPO || undefined,
    runUrl: env.RUN_URL || undefined,
    log: print,
    fetch,
  });
  stdout.write(outcome.summary);
  // The runner's job summary file, stated from knowledge: Markdown appended to GITHUB_STEP_SUMMARY.
  if (env.GITHUB_STEP_SUMMARY) {
    try {
      fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `${outcome.summary}\n`);
    } catch (error) {
      print(
        command(
          'warning',
          `the summary could not be written to GITHUB_STEP_SUMMARY: ${error.message}`,
        ),
      );
    }
  }
  return outcome.exitCode;
}

// Run as a script rather than imported by its test; see workspace-paths-check.mjs for why both
// sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) process.exitCode = await main(process.argv.slice(2), process.env);
