/**
 * A fake GitHub for the bump script's tests (open-bump-pull-requests.mjs): node:http on
 * 127.0.0.1, the subset of GitHub's REST API a GitHub App's run makes. Under the app's JSON web
 * token: the app, its installations and an installation token minted. Under that token: the
 * installation's repositories, a file read and written through the Contents API, a branch read
 * and made, two commits compared, pull requests listed, read, opened, retitled and closed, a
 * comment and a review request. GitHub's shapes, stated from knowledge; a live run is their proof.
 *
 *   const { privateKey, publicKey } = appKeys();   // the app's pair; the script signs with privateKey
 *   const stub = await startGitHubStub({ app: { id: 4242, slug: 'niord-base-bump', publicKey }, … });
 *   stub.url            // 'http://127.0.0.1:<port>', the API base the script is given
 *   stub.port
 *   stub.requests       // every request, in order: { method, path, query, auth, body, status,
 *                       //   unknown?, reason? } (see "The request log" below)
 *   stub.state          // the repositories as the requests left them, live
 *   stub.fail(rule)     // a failure to provoke (see "Failures" below)
 *   stub.fileAt('o1/country-a', 'base-bump/4.0.77', 'be/pom.xml')   // a file's text, or undefined
 *   await stub.close();
 *
 * In a Worker, for a test whose main thread blocks in spawnSync while the script runs against
 * the stub (an in-process server could not answer then):
 *
 *   const stub = await startGitHubStubWorker(seed, { logFile });   // { url, port, close }
 *   … the script runs with GITHUB_API_URL=stub.url …
 *   const state = await stub.close();     // a copy of stub.state as the run left it
 *   readRequestLog(logFile);              // the request log, one JSON line per request, written
 *                                         // the moment each is answered; the file is emptied
 *                                         // when the worker starts
 *
 * The seed, every field but app optional:
 *
 *   {
 *     app: {
 *       id: 4242, slug: 'niord-base-bump', name?,
 *       owner?: 'o9',              // the app owner's login
 *       publicKey,                 // SPKI PEM, from appKeys()
 *     },
 *     now?: <ms since the epoch>,   // a fixed clock for the JWT checks, when the test fixes the
 *                                   // script's; the real clock otherwise
 *     installations: [{
 *       id: 1, account: 'o1', suspended?: false,
 *       permissions?: { metadata: 'read', contents: 'write', pull_requests: 'write' },   // the default
 *       repositories: ['o1/country-a', 'o1/tools'],
 *     }],
 *     repositories: {
 *       'o1/country-a': {
 *         defaultBranch?: 'main', archived?: false,
 *         files?: { 'be/pom.xml': '<project>…' },   // the default branch's first commit
 *         mainCommits?: 0,       // commits on the default branch after the first, files unchanged,
 *                                // so a branch can trail it
 *         branches?: {
 *           'base-bump/4.0.70': {
 *             from?: 'main',     // a branch name, the default branch by default; one seeded
 *                                // here may be named, in any order
 *             behind?: 0,        // commits back from that branch's head
 *             files?: { … },     // when given, one commit on top with these files changed
 *           },
 *         },
 *         pulls?: [{
 *           number?,                      // the next free one by default
 *           head: 'base-bump/4.0.70',     // a seeded branch
 *           base?: 'main',
 *           author?: 'app' | '<login>',   // 'app', the default, is the user '<slug>[bot]', a Bot
 *           mergeable?: true | false | null | [null, null, true],   // a list gives one value per
 *                                         // single pull GET, its last value kept
 *           title?, body?, state?: 'open',
 *         }],
 *         collaborators?: ['owner-a'],   // the users a review may be asked of; anyone when absent
 *         teams?: ['maintainers'],       // the team slugs likewise
 *       },
 *     },
 *     failures?: [rule],
 *   }
 *
 * A repository an installation lists that `repositories` does not describe is an empty one: one
 * commit with no file on 'main'. A seed that names a branch or a pull's head that does not exist
 * throws.
 *
 * The state, stub.state: { installations, tokens: [{ token, installation }], repositories:
 * { [full name]: { defaultBranch, archived, refs: { [branch]: sha }, commits: { [sha]: { parent,
 * files, message } }, pulls (the seed's shape, live), comments: [{ issue, body, by }],
 * reviewRequests: [{ pull, reviewers, team_reviewers }], collaborators, teams } } }. A commit holds
 * every file of its tree; its sha is 40 hex digits of a counter hashed with sha1, and a file's
 * blob sha is git's, the sha1 of 'blob <length>\0' and the bytes. Nothing is ever deleted.
 *
 * The request log: an entry per request, pushed when it arrives and given its status when it is
 * answered. `path` is the decoded pathname and `query` its parameters as an object; `auth` is
 * 'jwt', 'installation:<id>', 'none' or 'invalid' (then `reason` says what failed); `body` is the
 * parsed JSON or null; `unknown: true` marks a request no route below answers (a DELETE, a merge,
 * a review, a tag, any other path), answered 404.
 *
 * Failures, `fail(rule)` and seed.failures: { method?, path?, status: 500, message: 'Server
 * Error', times: 1 }. `path` is the exact pathname, a RegExp over it, or { regexp, flags } for a
 * seed that travels as JSON; an absent method or path matches any. `times` counts the requests the
 * rule answers, Infinity for always. A matching request is logged and answered { message } with
 * that status before anything else is checked.
 *
 * The routes, each with GitHub's shape: GET /app, GET /app/installations, POST
 * /app/installations/{id}/access_tokens (the JWT's); GET /installation/repositories, GET and PUT
 * /repos/{o}/{r}/contents/{path}, GET /repos/{o}/{r}/git/ref/heads/{branch}, POST
 * /repos/{o}/{r}/git/refs, GET /repos/{o}/{r}/compare/{base}...{head}, GET and POST
 * /repos/{o}/{r}/pulls, GET and PATCH /repos/{o}/{r}/pulls/{n}, POST
 * /repos/{o}/{r}/issues/{n}/comments, POST /repos/{o}/{r}/pulls/{n}/requested_reviewers (an
 * installation token's). A list pages by per_page (at most 100, 30 by default) and page, with a
 * Link header. A JWT route refuses an installation token and the other routes a JWT, with 401; a
 * repository outside the token's installation is 404; a write the installation's permissions do
 * not grant is 403 { message: 'Resource not accessible by integration' }.
 *
 * The refusals a script may read by their text, each GitHub's as stated from knowledge: a branch
 * that exists, 422 'Reference already exists'; a stale blob sha, 409 'is at <sha> but expected
 * <sha>'; a workflow file written without workflows: write, 422 'refusing to allow a GitHub App to
 * create or update workflow `<path>` without `workflows` permission'; a pull request's own
 * refusals, 422 'Validation Failed' with the detail in errors[0].message ('No commits between
 * <base> and <head>', 'A pull request already exists for <owner>:<head>.'); a review asked of a
 * name outside the collaborators, 422 'Reviews may only be requested from collaborators. …'.
 *
 * A helper for the tests only: no test runs it by its own name (it is not a *.test.mjs file, so the
 * test:guards glob passes it by), and the source zip, which lists the scripts/ files it holds by
 * name, leaves it out. Node built-ins only; the keys are made at run time.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';

const DEFAULT_PERMISSIONS = { metadata: 'read', contents: 'write', pull_requests: 'write' };
const LEVELS = { read: 1, write: 2 };
const ALPHANUMERIC = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

/** The app's key pair: the private key as GitHub hands it out, the public key the stub checks with. */
export function appKeys() {
  return crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    // GitHub's downloaded .pem is PKCS#1 ('RSA PRIVATE KEY'), stated from knowledge.
    privateKeyEncoding: { type: 'pkcs1', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });
}

const sha1 = (bytes) => crypto.createHash('sha1').update(bytes).digest('hex');

/** Git's blob sha of a file's text: the sha1 of 'blob <length>\0' and the UTF-8 bytes. */
const blobSha = (text) => {
  const bytes = Buffer.from(text, 'utf8');
  return sha1(Buffer.concat([Buffer.from(`blob ${bytes.length}\0`), bytes]));
};

/** Starts a server on 127.0.0.1 and an ephemeral port; resolves to it with its port. */
function listen(handler) {
  const server = http.createServer(handler);
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

const close = (server) =>
  new Promise((resolve) => {
    server.closeAllConnections?.();
    server.close(() => resolve());
  });

/** A request's body. */
const bodyOf = (request) =>
  new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });

/** Whether a failure rule takes this request. */
function matches(rule, method, path) {
  if (rule.method && rule.method.toUpperCase() !== method) return false;
  if (rule.path === undefined) return true;
  if (typeof rule.path === 'string') return rule.path === path;
  const pattern =
    rule.path instanceof RegExp ? rule.path : new RegExp(rule.path.regexp, rule.path.flags);
  pattern.lastIndex = 0;
  return pattern.test(path);
}

const normalRule = (rule) => ({ ...rule, times: rule.times ?? 1 });

/** The state a seed describes. */
function build(seed) {
  if (!seed?.app?.publicKey || seed.app.id === undefined)
    throw new Error('the seed needs app.id and app.publicKey');
  let counter = 0;
  const nextSha = () => sha1(`commit ${++counter}`);
  const installations = (seed.installations ?? []).map((installation) => ({
    id: installation.id,
    account: installation.account,
    suspended: Boolean(installation.suspended),
    permissions: { ...(installation.permissions ?? DEFAULT_PERMISSIONS) },
    repositories: [...(installation.repositories ?? [])],
  }));
  const names = new Set([
    ...Object.keys(seed.repositories ?? {}),
    ...installations.flatMap((installation) => installation.repositories),
  ]);
  const repositories = {};
  for (const full of names)
    repositories[full] = repository(full, seed.repositories?.[full] ?? {}, nextSha);
  return {
    state: { installations, tokens: [], repositories },
    nextSha,
    failures: (seed.failures ?? []).map(normalRule),
  };
}

/** One repository's state: its commits and branches, its pulls, no comment yet. */
function repository(full, spec, nextSha) {
  const defaultBranch = spec.defaultBranch ?? 'main';
  const branches = spec.branches ?? {};
  if (defaultBranch in branches)
    throw new Error(`seed ${full}: ${defaultBranch} is the default branch`);
  const commits = {};
  const commit = (parent, files, message) => {
    const sha = nextSha();
    commits[sha] = { parent, files, message };
    return sha;
  };
  let head = commit(null, { ...(spec.files ?? {}) }, 'The first commit');
  for (let n = 0; n < (spec.mainCommits ?? 0); n++)
    head = commit(head, { ...commits[head].files }, `Commit ${n + 2} on ${defaultBranch}`);
  const refs = { [defaultBranch]: head };
  const resolving = new Set();
  const resolve = (name) => {
    if (refs[name]) return refs[name];
    const branch = branches[name];
    if (!branch) throw new Error(`seed ${full}: no branch ${name}`);
    if (resolving.has(name)) throw new Error(`seed ${full}: the branches' from names a cycle`);
    resolving.add(name);
    let sha = resolve(branch.from ?? defaultBranch);
    for (let n = 0; n < (branch.behind ?? 0); n++) {
      sha = commits[sha].parent;
      if (!sha) throw new Error(`seed ${full}: ${name} is further behind than its history`);
    }
    if (branch.files) sha = commit(sha, { ...commits[sha].files, ...branch.files }, `On ${name}`);
    refs[name] = sha;
    return sha;
  };
  for (const name of Object.keys(branches)) resolve(name);
  let free = Math.max(0, ...(spec.pulls ?? []).map(({ number }) => number ?? 0));
  const pulls = (spec.pulls ?? []).map((pull) => {
    if (!refs[pull.head]) throw new Error(`seed ${full}: pull ${pull.number}'s head is no branch`);
    return {
      number: pull.number ?? ++free,
      head: pull.head,
      base: pull.base ?? defaultBranch,
      author: pull.author ?? 'app',
      mergeable: structuredClone(pull.mergeable === undefined ? true : pull.mergeable),
      title: pull.title ?? `Base bump on ${pull.head}`,
      body: pull.body ?? '',
      state: pull.state ?? 'open',
    };
  });
  return {
    defaultBranch,
    archived: Boolean(spec.archived),
    refs,
    commits,
    pulls,
    comments: [],
    reviewRequests: [],
    collaborators: spec.collaborators ? [...spec.collaborators] : null,
    teams: spec.teams ? [...spec.teams] : null,
  };
}

/** The server, with `onAnswer` called with each log entry before its answer is sent. */
async function serve(seed, onAnswer) {
  const { state, nextSha, failures } = build(seed);
  const app = {
    id: seed.app.id,
    slug: seed.app.slug ?? 'niord-base-bump',
    name: seed.app.name ?? 'Niord base bump',
    owner: seed.app.owner ?? 'o9',
    publicKey: seed.app.publicKey,
  };
  const botLogin = `${app.slug}[bot]`;
  const now = () => seed.now ?? Date.now();
  const suspendedAt = new Date(now()).toISOString();
  const requests = [];
  let base;
  let commentIds = 0;

  /** What is wrong with a JSON web token, or undefined when GitHub would take it. */
  const jwtProblem = (value) => {
    const [h, p, s] = value.split('.');
    let header, payload;
    try {
      header = JSON.parse(Buffer.from(h, 'base64url').toString('utf8'));
      payload = JSON.parse(Buffer.from(p, 'base64url').toString('utf8'));
    } catch {
      return 'its header or payload is not JSON';
    }
    // GitHub's rules, stated from knowledge: RS256, iss the app's id, iat and exp integer seconds,
    // exp at most ten minutes after iat and not passed, iat not ahead of the clock (a minute's
    // skew allowed).
    if (header?.alg !== 'RS256') return `alg is ${header?.alg}, not RS256`;
    let signed;
    try {
      signed = crypto.verify(
        'sha256',
        Buffer.from(`${h}.${p}`),
        app.publicKey,
        Buffer.from(s, 'base64url'),
      );
    } catch {
      // A signature or key crypto cannot read verifies nothing.
    }
    if (!signed) return "the signature does not verify with the app's public key";
    if (payload?.iss !== String(app.id)) return `iss is ${JSON.stringify(payload?.iss)}`;
    if (!Number.isInteger(payload.iat) || !Number.isInteger(payload.exp))
      return 'iat and exp are not integers';
    const seconds = Math.floor(now() / 1000);
    if (payload.exp - payload.iat > 600) return 'exp is more than ten minutes after iat';
    if (payload.exp <= seconds) return 'exp has passed';
    if (payload.iat > seconds + 60) return 'iat is ahead of the clock';
    return undefined;
  };

  /** The credential a request carries. */
  const authenticate = (header) => {
    if (!header) return { auth: 'none', message: 'Requires authentication' };
    const match = /^(Bearer|token) (\S+)$/i.exec(header);
    if (!match)
      return { auth: 'invalid', reason: 'not a Bearer credential', message: 'Bad credentials' };
    const [, scheme, value] = match;
    const minted = state.tokens.find((token) => token.token === value);
    if (minted)
      return {
        auth: `installation:${minted.installation}`,
        installation: state.installations.find(({ id }) => id === minted.installation),
      };
    if (value.split('.').length === 3) {
      const reason =
        jwtProblem(value) ??
        (scheme.toLowerCase() === 'bearer' ? undefined : 'a JSON web token needs Bearer');
      if (!reason) return { auth: 'jwt' };
      return { auth: 'invalid', reason, message: 'A JSON web token could not be decoded' };
    }
    return { auth: 'invalid', reason: 'a token the stub never minted', message: 'Bad credentials' };
  };

  const granted = (installation, permission, level) =>
    (LEVELS[installation.permissions[permission]] ?? 0) >= LEVELS[level];

  /** A list's page and its Link header, as GitHub pages: per_page (at most 100) and page. */
  const paged = (url, items) => {
    const perPage = Math.min(Math.max(Number(url.searchParams.get('per_page')) || 30, 1), 100);
    const page = Math.max(Number(url.searchParams.get('page')) || 1, 1);
    const last = Math.max(Math.ceil(items.length / perPage), 1);
    const link = (n) => {
      const target = new URL(url.href);
      target.searchParams.set('per_page', String(perPage));
      target.searchParams.set('page', String(n));
      return target.href;
    };
    const links = [];
    if (page < last) links.push(`<${link(page + 1)}>; rel="next"`, `<${link(last)}>; rel="last"`);
    if (page > 1) links.push(`<${link(1)}>; rel="first"`, `<${link(page - 1)}>; rel="prev"`);
    return {
      items: items.slice((page - 1) * perPage, page * perPage),
      headers: links.length ? { Link: links.join(', ') } : {},
    };
  };

  const account = (login) => ({ login, type: 'Organization' });

  const installationShape = (installation) => ({
    id: installation.id,
    account: account(installation.account),
    app_id: app.id,
    app_slug: app.slug,
    target_type: 'Organization',
    repository_selection: 'selected',
    permissions: installation.permissions,
    // GitHub's field, stated from knowledge: null unless the installation is suspended.
    suspended_at: installation.suspended ? suspendedAt : null,
  });

  const repositoryShape = (full) => {
    const [owner, name] = full.split('/');
    const repo = state.repositories[full];
    return {
      id: Object.keys(state.repositories).indexOf(full) + 1,
      name,
      full_name: full,
      owner: account(owner),
      private: true,
      default_branch: repo.defaultBranch,
      archived: repo.archived,
      html_url: `https://github.com/${full}`,
    };
  };

  const userOf = (author) =>
    author === 'app' ? { login: botLogin, type: 'Bot' } : { login: author, type: 'User' };

  /** The single pull GET's mergeable: a seeded list gives its values in turn, the last kept. */
  const takeMergeable = (pull) =>
    Array.isArray(pull.mergeable)
      ? pull.mergeable.length > 1
        ? pull.mergeable.shift()
        : pull.mergeable[0]
      : pull.mergeable;
  const peekMergeable = (pull) =>
    Array.isArray(pull.mergeable) ? pull.mergeable[0] : pull.mergeable;

  const pullShape = (full, pull, mergeable) => {
    const repo = state.repositories[full];
    const shape = {
      number: pull.number,
      state: pull.state,
      title: pull.title,
      body: pull.body,
      html_url: `https://github.com/${full}/pull/${pull.number}`,
      user: userOf(pull.author),
      head: { ref: pull.head, sha: repo.refs[pull.head], repo: { full_name: full } },
      base: { ref: pull.base, sha: repo.refs[pull.base], repo: { full_name: full } },
      draft: false,
      merged: false,
    };
    if (mergeable === undefined) return shape;
    // GitHub's, stated from knowledge: the single pull GET carries mergeable, null while GitHub
    // computes it, and mergeable_state; the list omits both.
    const mergeableState = mergeable === true ? 'clean' : mergeable === false ? 'dirty' : 'unknown';
    return { ...shape, mergeable, mergeable_state: mergeableState };
  };

  /** A branch name ('refs/heads/' or 'heads/' allowed before it) or a commit sha, as its commit. */
  const commitOf = (repo, ref) => {
    const name = ref.replace(/^(refs\/)?heads\//, '');
    return repo.refs[name] ?? (repo.commits[ref] ? ref : undefined);
  };

  /** Base and head compared, head relative to base, as GitHub's compare answers. */
  const compare = (repo, baseSha, headSha) => {
    const chain = (sha) => {
      const list = [];
      for (let at = sha; at; at = repo.commits[at].parent) list.push(at);
      return list;
    };
    const baseChain = chain(baseSha);
    const headChain = chain(headSha);
    const inBase = new Set(baseChain);
    const aheadBy = headChain.findIndex((sha) => inBase.has(sha));
    const mergeBase = headChain[aheadBy];
    const behindBy = baseChain.indexOf(mergeBase);
    const status =
      aheadBy === 0 && behindBy === 0
        ? 'identical'
        : behindBy === 0
          ? 'ahead'
          : aheadBy === 0
            ? 'behind'
            : 'diverged';
    return { status, aheadBy, behindBy, mergeBase, ahead: headChain.slice(0, aheadBy) };
  };

  const validation = (message, extra = {}) => [
    422,
    { message: 'Validation Failed', errors: [{ resource: 'PullRequest', ...extra, message }] },
  ];

  const NOT_FOUND = [404, { message: 'Not Found' }];
  const NOT_ACCESSIBLE = [403, { message: 'Resource not accessible by integration' }];
  const ARCHIVED = [403, { message: 'Repository was archived so is read-only.' }];

  // Each handler answers [status, payload, headers?]; `context` holds the request's parts.
  const appRoutes = [
    {
      method: 'GET',
      pattern: /^\/app$/,
      handle: () => [
        200,
        {
          id: app.id,
          slug: app.slug,
          name: app.name,
          owner: account(app.owner),
          html_url: `https://github.com/apps/${app.slug}`,
        },
      ],
    },
    {
      method: 'GET',
      pattern: /^\/app\/installations$/,
      handle: ({ url }) => {
        const { items, headers } = paged(url, state.installations);
        return [200, items.map(installationShape), headers];
      },
    },
    {
      method: 'POST',
      pattern: /^\/app\/installations\/(\d+)\/access_tokens$/,
      handle: ({ params }) => {
        const installation = state.installations.find(({ id }) => String(id) === params[0]);
        if (!installation) return NOT_FOUND;
        // GitHub's, stated from knowledge: a suspended installation mints no token.
        if (installation.suspended)
          return [403, { message: 'This installation has been suspended' }];
        const token = `ghs_${Array.from({ length: 36 }, () => ALPHANUMERIC[crypto.randomInt(62)]).join('')}`;
        state.tokens.push({ token, installation: installation.id });
        return [
          201,
          {
            token,
            expires_at: new Date(now() + 3_600_000).toISOString(),
            permissions: installation.permissions,
            repository_selection: 'selected',
          },
        ];
      },
    },
  ];

  const installationRoutes = [
    {
      method: 'GET',
      pattern: /^\/installation\/repositories$/,
      handle: ({ url, installation }) => {
        const { items, headers } = paged(url, installation.repositories);
        // GitHub's shape, stated from knowledge: an object holding the page, not a bare list.
        return [
          200,
          {
            total_count: installation.repositories.length,
            repository_selection: 'selected',
            repositories: items.map(repositoryShape),
          },
          headers,
        ];
      },
    },
    {
      method: 'GET',
      pattern: /^\/repos\/[^/]+\/[^/]+\/contents\/(.+)$/,
      repository: true,
      read: 'contents',
      handle: ({ url, repo, params }) => {
        const path = params[0];
        const ref = url.searchParams.get('ref') ?? repo.defaultBranch;
        const sha = commitOf(repo, ref);
        if (!sha) return [404, { message: `No commit found for the ref ${ref}` }];
        const text = repo.commits[sha].files[path];
        if (text === undefined) return NOT_FOUND;
        // GitHub's shape, stated from knowledge: base64 broken by a line feed every 60
        // characters and after the last, with the file's blob sha.
        const content = (
          Buffer.from(text, 'utf8')
            .toString('base64')
            .match(/.{1,60}/g) ?? []
        )
          .map((line) => `${line}\n`)
          .join('');
        return [
          200,
          {
            type: 'file',
            encoding: 'base64',
            size: Buffer.byteLength(text, 'utf8'),
            name: path.split('/').pop(),
            path,
            sha: blobSha(text),
            content,
          },
        ];
      },
    },
    {
      method: 'PUT',
      pattern: /^\/repos\/[^/]+\/[^/]+\/contents\/(.+)$/,
      repository: true,
      write: 'contents',
      handle: ({ repo, params, body, installation }) => {
        const path = params[0];
        // GitHub's refusal, stated from knowledge: an app without workflows: write cannot write
        // a workflow file.
        if (path.startsWith('.github/workflows/') && !granted(installation, 'workflows', 'write'))
          return [
            422,
            {
              message: `refusing to allow a GitHub App to create or update workflow \`${path}\` without \`workflows\` permission`,
            },
          ];
        if (typeof body?.message !== 'string')
          return [422, { message: 'Invalid request.\n\n"message" wasn\'t supplied.' }];
        if (typeof body.content !== 'string')
          return [422, { message: 'Invalid request.\n\n"content" wasn\'t supplied.' }];
        const branch = body.branch ?? repo.defaultBranch;
        const parent = repo.refs[branch];
        if (!parent) return [404, { message: `Branch ${branch} not found` }];
        const files = repo.commits[parent].files;
        if (files[path] !== undefined) {
          if (!body.sha) return [422, { message: 'Invalid request.\n\n"sha" wasn\'t supplied.' }];
          // GitHub's, stated from knowledge: a blob sha that is not the branch's file is a 409.
          const current = blobSha(files[path]);
          if (body.sha !== current)
            return [409, { message: `is at ${current} but expected ${body.sha}` }];
        }
        const text = Buffer.from(body.content, 'base64').toString('utf8');
        const sha = nextSha();
        repo.commits[sha] = { parent, files: { ...files, [path]: text }, message: body.message };
        repo.refs[branch] = sha;
        return [
          files[path] === undefined ? 201 : 200,
          {
            content: { name: path.split('/').pop(), path, sha: blobSha(text), type: 'file' },
            commit: { sha, message: body.message, parents: [{ sha: parent }] },
          },
        ];
      },
    },
    {
      method: 'GET',
      pattern: /^\/repos\/[^/]+\/[^/]+\/git\/ref\/heads\/(.+)$/,
      repository: true,
      read: 'contents',
      handle: ({ repo, params }) => {
        const sha = repo.refs[params[0]];
        if (!sha) return NOT_FOUND;
        return [200, { ref: `refs/heads/${params[0]}`, object: { sha, type: 'commit' } }];
      },
    },
    {
      method: 'POST',
      pattern: /^\/repos\/[^/]+\/[^/]+\/git\/refs$/,
      repository: true,
      write: 'contents',
      // The stub keeps branches only: a tag or another ref is a route it does not answer.
      accepts: ({ body }) => typeof body?.ref === 'string' && body.ref.startsWith('refs/heads/'),
      handle: ({ repo, body }) => {
        const name = body.ref.slice('refs/heads/'.length);
        if (!repo.commits[body.sha]) return [422, { message: 'Object does not exist' }];
        // GitHub's text, stated from knowledge.
        if (repo.refs[name]) return [422, { message: 'Reference already exists' }];
        repo.refs[name] = body.sha;
        return [201, { ref: body.ref, object: { sha: body.sha, type: 'commit' } }];
      },
    },
    {
      method: 'GET',
      pattern: /^\/repos\/[^/]+\/[^/]+\/compare\/(.+?)\.\.\.(.+)$/,
      repository: true,
      read: 'contents',
      handle: ({ repo, params }) => {
        const baseSha = commitOf(repo, params[0]);
        const headSha = commitOf(repo, params[1]);
        if (!baseSha || !headSha) return NOT_FOUND;
        // GitHub's, stated from knowledge: status is the head's relative to the base.
        const result = compare(repo, baseSha, headSha);
        return [
          200,
          {
            status: result.status,
            ahead_by: result.aheadBy,
            behind_by: result.behindBy,
            total_commits: result.aheadBy,
            base_commit: { sha: baseSha },
            merge_base_commit: { sha: result.mergeBase },
            commits: [...result.ahead].reverse().map((sha) => ({ sha })),
          },
        ];
      },
    },
    {
      method: 'GET',
      pattern: /^\/repos\/[^/]+\/[^/]+\/pulls$/,
      repository: true,
      read: 'pull_requests',
      handle: ({ url, repo, full }) => {
        const wanted = url.searchParams.get('state') ?? 'open';
        const head = url.searchParams.get('head');
        const baseRef = url.searchParams.get('base');
        const owner = full.split('/')[0];
        const pulls = repo.pulls
          .filter((pull) => wanted === 'all' || pull.state === wanted)
          .filter((pull) => !head || head === `${owner}:${pull.head}`)
          .filter((pull) => !baseRef || baseRef === pull.base)
          // GitHub's default order, stated from knowledge: the newest first.
          .sort((a, b) => b.number - a.number);
        const { items, headers } = paged(url, pulls);
        // The list omits mergeable, as GitHub's does (stated from knowledge).
        return [200, items.map((pull) => pullShape(full, pull)), headers];
      },
    },
    {
      method: 'POST',
      pattern: /^\/repos\/[^/]+\/[^/]+\/pulls$/,
      repository: true,
      write: 'pull_requests',
      handle: ({ repo, full, body }) => {
        const owner = full.split('/')[0];
        for (const field of ['title', 'head', 'base'])
          if (typeof body?.[field] !== 'string')
            return validation(`${field} wasn't supplied`, { field, code: 'missing_field' });
        const head = body.head.startsWith(`${owner}:`)
          ? body.head.slice(owner.length + 1)
          : body.head;
        if (!repo.refs[head])
          return validation('head is invalid', { field: 'head', code: 'invalid' });
        if (!repo.refs[body.base])
          return validation('base is invalid', { field: 'base', code: 'invalid' });
        // GitHub's texts, stated from knowledge, in the error's message under 'Validation Failed'.
        if (
          repo.pulls.some(
            (pull) => pull.state === 'open' && pull.head === head && pull.base === body.base,
          )
        )
          return validation(`A pull request already exists for ${owner}:${head}.`, {
            code: 'custom',
          });
        if (compare(repo, repo.refs[body.base], repo.refs[head]).aheadBy === 0)
          return validation(`No commits between ${body.base} and ${head}`, { code: 'custom' });
        const pull = {
          number: Math.max(0, ...repo.pulls.map(({ number }) => number)) + 1,
          head,
          base: body.base,
          author: 'app',
          mergeable: true,
          title: body.title,
          body: body.body ?? '',
          state: 'open',
        };
        repo.pulls.push(pull);
        return [201, pullShape(full, pull, null)];
      },
    },
    {
      method: 'GET',
      pattern: /^\/repos\/[^/]+\/[^/]+\/pulls\/(\d+)$/,
      repository: true,
      read: 'pull_requests',
      handle: ({ repo, full, params }) => {
        const pull = repo.pulls.find(({ number }) => String(number) === params[0]);
        if (!pull) return NOT_FOUND;
        return [200, pullShape(full, pull, takeMergeable(pull))];
      },
    },
    {
      method: 'PATCH',
      pattern: /^\/repos\/[^/]+\/[^/]+\/pulls\/(\d+)$/,
      repository: true,
      write: 'pull_requests',
      handle: ({ repo, full, params, body }) => {
        const pull = repo.pulls.find(({ number }) => String(number) === params[0]);
        if (!pull) return NOT_FOUND;
        if (body?.state !== undefined && !['open', 'closed'].includes(body.state))
          return validation('state is not included in the list', {
            field: 'state',
            code: 'invalid',
          });
        if (body?.base !== undefined && !repo.refs[body.base])
          return validation('base is invalid', { field: 'base', code: 'invalid' });
        for (const field of ['title', 'body', 'state', 'base'])
          if (body?.[field] !== undefined) pull[field] = body[field];
        return [200, pullShape(full, pull, peekMergeable(pull))];
      },
    },
    {
      method: 'POST',
      pattern: /^\/repos\/[^/]+\/[^/]+\/issues\/(\d+)\/comments$/,
      repository: true,
      // GitHub's, stated from knowledge: a pull request's comment needs issues or pull_requests.
      write: ['issues', 'pull_requests'],
      handle: ({ repo, full, params, body }) => {
        const pull = repo.pulls.find(({ number }) => String(number) === params[0]);
        if (!pull) return NOT_FOUND;
        if (typeof body?.body !== 'string')
          return [
            422,
            { message: 'Validation Failed', errors: [{ field: 'body', code: 'missing_field' }] },
          ];
        const id = ++commentIds;
        repo.comments.push({ issue: pull.number, body: body.body, by: botLogin });
        return [
          201,
          {
            id,
            body: body.body,
            user: userOf('app'),
            html_url: `https://github.com/${full}/pull/${pull.number}#issuecomment-${id}`,
          },
        ];
      },
    },
    {
      method: 'POST',
      pattern: /^\/repos\/[^/]+\/[^/]+\/pulls\/(\d+)\/requested_reviewers$/,
      repository: true,
      write: 'pull_requests',
      handle: ({ repo, full, params, body }) => {
        const pull = repo.pulls.find(({ number }) => String(number) === params[0]);
        if (!pull) return NOT_FOUND;
        const reviewers = body?.reviewers ?? [];
        const teamReviewers = body?.team_reviewers ?? [];
        if (!Array.isArray(reviewers) || !Array.isArray(teamReviewers))
          return [422, { message: 'Invalid request.' }];
        // GitHub's text, stated from knowledge: team_reviewers are slugs, and a name outside the
        // repository's collaborators or teams refuses the whole request.
        const outside =
          (repo.collaborators && reviewers.some((login) => !repo.collaborators.includes(login))) ||
          (repo.teams && teamReviewers.some((slug) => !repo.teams.includes(slug)));
        if (outside)
          return [
            422,
            {
              message: `Reviews may only be requested from collaborators. One or more of the users or teams you specified is not a collaborator of the ${full} repository.`,
            },
          ];
        repo.reviewRequests.push({ pull: pull.number, reviewers, team_reviewers: teamReviewers });
        return [
          201,
          {
            ...pullShape(full, pull),
            requested_reviewers: reviewers.map((login) => ({ login, type: 'User' })),
            requested_teams: teamReviewers.map((slug) => ({ slug, name: slug })),
          },
        ];
      },
    },
  ];

  const routes = [
    ...appRoutes.map((route) => ({ ...route, credential: 'jwt' })),
    ...installationRoutes.map((route) => ({ ...route, credential: 'installation' })),
  ];

  /** One request's answer, as [status, payload, headers?], with the entry marked unknown when no route takes it. */
  const answerOf = (context, entry, credential) => {
    let params;
    const route = routes.find((candidate) => {
      if (candidate.method !== context.method) return false;
      const match = candidate.pattern.exec(context.path);
      if (!match || (candidate.accepts && !candidate.accepts(context))) return false;
      params = match.slice(1);
      return true;
    });
    if (!route) {
      entry.unknown = true;
      return NOT_FOUND;
    }
    if (credential.auth === 'none' || credential.auth === 'invalid')
      return [401, { message: credential.message }];
    if (route.credential === 'jwt' && credential.auth !== 'jwt')
      return [401, { message: 'A JSON web token could not be decoded' }];
    if (route.credential === 'installation' && credential.auth === 'jwt')
      return [401, { message: 'Bad credentials' }];
    const installation = credential.installation;
    const routeContext = { ...context, params, installation };
    if (route.repository) {
      const [, , owner, name] = context.path.split('/');
      const full = `${owner}/${name}`;
      const repo = state.repositories[full];
      // GitHub's, stated from knowledge: a repository the token cannot see is Not Found.
      if (!repo || !installation.repositories.includes(full)) return NOT_FOUND;
      if (route.read && !granted(installation, route.read, 'read')) return NOT_ACCESSIBLE;
      if (route.write) {
        const any = [route.write].flat();
        if (!any.some((permission) => granted(installation, permission, 'write')))
          return NOT_ACCESSIBLE;
        if (repo.archived) return ARCHIVED;
      }
      Object.assign(routeContext, { repo, full });
    }
    return route.handle(routeContext);
  };

  const handler = async (request, response) => {
    const url = new URL(request.url, base);
    let path = url.pathname;
    try {
      path = decodeURIComponent(url.pathname);
    } catch {
      // A malformed escape: the pathname as it came.
    }
    const raw = await bodyOf(request);
    let body = null;
    let malformed = false;
    if (raw.length) {
      try {
        body = JSON.parse(raw.toString('utf8'));
      } catch {
        malformed = true;
      }
    }
    const credential = authenticate(request.headers.authorization);
    const entry = {
      method: request.method,
      path,
      query: Object.fromEntries(url.searchParams),
      auth: credential.auth,
      body,
      status: undefined,
    };
    if (credential.reason) entry.reason = credential.reason;
    requests.push(entry);

    let answer;
    const rule = failures.find((candidate) => matches(candidate, request.method, path));
    if (rule) {
      rule.times -= 1;
      if (rule.times <= 0) failures.splice(failures.indexOf(rule), 1);
      answer = [rule.status ?? 500, { message: rule.message ?? 'Server Error' }];
    } else if (malformed) {
      answer = [400, { message: 'Problems parsing JSON' }];
    } else {
      try {
        answer = answerOf({ method: request.method, path, url, body }, entry, credential);
      } catch (error) {
        answer = [500, { message: `the stub failed: ${error.message}` }];
      }
    }
    const [status, payload, headers = {}] = answer;
    entry.status = status;
    onAnswer?.(entry);
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
    response.end(JSON.stringify(payload));
  };

  const { server, port } = await listen((request, response) => {
    handler(request, response).catch((error) => {
      response.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ message: `the stub failed: ${error.message}` }));
    });
  });
  base = `http://127.0.0.1:${port}`;

  return {
    url: base,
    port,
    requests,
    state,
    fail(rule) {
      failures.push(normalRule(rule));
    },
    /** A file's text on a branch or at a commit, or undefined. */
    fileAt(full, ref, path) {
      const repo = state.repositories[full];
      const sha = repo && commitOf(repo, ref);
      return sha ? repo.commits[sha].files[path] : undefined;
    },
    close: () => close(server),
  };
}

/** The stub in this process. */
export function startGitHubStub(seed) {
  return serve(seed);
}

/** The stub in a Worker, its log appended to `logFile` as each request is answered. */
export function startGitHubStubWorker(seed, { logFile } = {}) {
  if (!logFile) throw new Error('startGitHubStubWorker needs a logFile');
  const worker = new Worker(new URL(import.meta.url), {
    workerData: { niordGitHubStub: true, seed, logFile },
  });
  return new Promise((resolve, reject) => {
    const exited = (code) =>
      reject(new Error(`the stub's worker exited with ${code} before it listened`));
    worker.once('error', reject);
    worker.once('exit', exited);
    worker.once('message', ({ port }) => {
      worker.off('error', reject);
      worker.off('exit', exited);
      // A Worker is an EventEmitter: an 'error' with no listener would throw in the test's
      // process, so a later failure is kept and handed to the caller by close().
      let failure;
      let posted = false;
      const pending = new Set();
      const failAll = (error) => {
        failure ??= error;
        for (const fail of pending) fail(failure);
        pending.clear();
      };
      worker.on('error', failAll);
      worker.once('exit', (code) => {
        if (!posted)
          failAll(new Error(`the stub's worker exited with ${code} before it posted its state`));
      });
      let closing;
      const close = () =>
        (closing ??= new Promise((done, fail) => {
          if (failure) {
            fail(failure);
            return;
          }
          pending.add(fail);
          worker.once('message', ({ state }) => {
            posted = true;
            pending.delete(fail);
            worker.terminate().then(() => (failure ? fail(failure) : done(state)), fail);
          });
          worker.postMessage('close');
        }));
      resolve({ url: `http://127.0.0.1:${port}`, port, close });
    });
  });
}

/** The entries a Worker's stub wrote, in order. */
export function readRequestLog(logFile) {
  if (!fs.existsSync(logFile)) return [];
  return fs
    .readFileSync(logFile, 'utf8')
    .split('\n')
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

// The Worker's side: the stub, its port posted, its log on disk, its state posted on 'close'.
if (!isMainThread && workerData?.niordGitHubStub) {
  const { seed, logFile } = workerData;
  fs.writeFileSync(logFile, '');
  const stub = await serve(seed, (entry) =>
    fs.appendFileSync(logFile, `${JSON.stringify(entry)}\n`),
  );
  parentPort.on('message', async (message) => {
    if (message !== 'close') return;
    await stub.close();
    parentPort.postMessage({ state: structuredClone(stub.state) });
  });
  parentPort.postMessage({ port: stub.port });
}
