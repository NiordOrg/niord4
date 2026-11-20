#!/usr/bin/env node
/**
 * Black-box test of the public site's container.
 *
 *   npm run build:info && npm run image:info -- --tag niord-xx-info   # the image packages a built dist
 *   node scripts/info-container-test.mjs --image niord-xx-info --no-legal-doc --no-branding-text   # the base build ships neither
 *   node scripts/info-container-test.mjs --image niord-xx-info --no-legal-doc --no-branding-text --browser
 *   node scripts/info-container-test.mjs --image niord-xx-info:1 --legal-doc disclaimer.zz.html --language zz
 *
 * The container is the last thing between an anonymous reader and a Niord backend that also
 * serves an editor. What the image promises is narrow and worth asserting from the outside: only
 * GET and HEAD leave it, only six paths exist, the two report PDFs take no query but the one the
 * site composes, the list renders no report but the public one, a reader turned away by a busy
 * backend gets the site's own page with the 503 and its Retry-After, no credential survives the hop,
 * the backend learns nothing about the request that the client wrote itself, no upstream may set
 * a cookie on this origin, a missing asset is a 404 rather than the application rendered inside a
 * dialog, every response carries the site's security headers, a legal document or branding file
 * a country supplies is sandboxed instead, with the same nosniff and referrer policy, a response
 * over https behind a TLS front carries this host's Strict-Transport-Security once, and the
 * backend's own never passes, a missing
 * build file is never cached, redirects stay on the reader's scheme, every text file is served
 * pre-compressed, an https backend or chart server is verified, a configured chart server is sent
 * no credential either, and a backend address or frame-ancestors value of the wrong shape stops
 * the container before nginx starts. Only the teaser may be framed by another site, and a frame
 * that asks for the root is given the teaser frame document rather than the application.
 * Every container runs as a pod held to the restricted Pod Security standard runs it, and stricter:
 * as the image's own unprivileged user, with no capability, no privilege escalation and a read-only
 * root filesystem, /tmp alone writable.
 *
 * With --browser the frame policy is also checked as a browser applies it: Chromium frames the
 * site from a page of another origin — the teaser by each address the pages that embed it use,
 * and every other page — and opens the teaser and the map over plain HTTP under the site's
 * policy. That part needs Playwright's Chromium (npm ci, then npx playwright install chromium).
 * Frontend CI's Example country job runs the test with --browser against the example's image
 * (country-kit/example/run.mjs --image), so a failure there fails the Frontend gate; a country's
 * image:info runs it against the image it built before that image may be pushed; for the base
 * build it is a manual tool (fe/AGENTS.md); locally it is the flag to add after a change to the
 * frame policy, the teaser frame document, the hash-address rules or the teaser.
 *
 * --language names the UI language whose bundle and branding file the test asks for (default en):
 * a build that ships only its own language, as the example country's zz, has no en.json to serve.
 *
 * --no-legal-doc and --no-branding-text are for an image that ships no legal document, or no
 * branding text for that language — a country fresh from `init`, which has neither yet: each probe
 * of the missing file (a legal document served as a file, its sandbox headers, under a
 * percent-encoded path and, with --browser, its opaque origin; the branding text's sandbox
 * headers) is skipped by name and counted in the last line instead of failing. The nginx rules
 * they would prove stay asserted by the images that ship the files (the example's, and a
 * country's that ships them).
 *
 * The upstream is a stub in this process, not a Niord backend: what is being tested is the proxy,
 * so what the stub answers matters far less than what it is asked. Every assertion names both
 * what the client saw and what the stub saw, because "404" is worthless on its own — the request
 * must not have reached the backend at all.
 *
 * HOST REACHABILITY. The stub listens on every interface of the host and the container reaches it
 * under a name this file invents, mapped with `--add-host=<name>:host-gateway`. Linux Docker
 * (20.10 and later, so every GitHub runner) and Docker Desktop both translate `host-gateway` into
 * the address the host answers on. The name is NOT `host.docker.internal`, although that is the
 * familiar one: Docker Desktop already defines it, as an IPv6 address the container has no route
 * to, and a second entry for the same name leaves nginx picking one of the two per request. The
 * alternative, a user-defined network, would force the stub itself into a container for no gain.
 *
 * Every request is made with node:http and not with fetch, deliberately: fetch negotiates its own
 * Accept-Encoding and decompresses behind the test's back, and it filters the very headers this
 * file has to prove are dropped.
 *
 * No dependency beyond node: and the docker CLI, but for --browser. Exit 0 all green, 1 naming
 * every failure, 2 on a usage error or when the test cannot run: docker, a container that never
 * answers, or with --browser Playwright or Chromium missing.
 */
import http from 'node:http';
import https from 'node:https';
import { execFileSync, spawnSync } from 'node:child_process';

const USAGE =
  'usage: info-container-test.mjs --image <tag> [--legal-doc <file name> | --no-legal-doc] [--language <lang>] [--no-branding-text] [--browser] [--keep]\n' +
  '  --legal-doc         a legal document the image is expected to ship (default disclaimer.en.html)\n' +
  '  --no-legal-doc      the image ships no legal document: its probes are skipped by name\n' +
  '  --language          a UI language the image ships: its bundle and branding are asked for (default en)\n' +
  '  --no-branding-text  the image ships no assets/branding/<language>.json: its probe is skipped by name\n' +
  '  --browser           also check the frame policy and the tiles in Chromium';

const options = {
  image: '',
  legalDoc: 'disclaimer.en.html',
  legalDocGiven: false,
  noLegalDoc: false,
  noBrandingText: false,
  language: 'en',
  browser: false,
  keep: false,
};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--image' && argv[i + 1]) options.image = argv[++i];
  else if (argv[i] === '--legal-doc' && argv[i + 1]) {
    options.legalDoc = argv[++i];
    options.legalDocGiven = true;
  } else if (argv[i] === '--no-legal-doc') options.noLegalDoc = true;
  else if (argv[i] === '--no-branding-text') options.noBrandingText = true;
  else if (argv[i] === '--language' && argv[i + 1]) options.language = argv[++i];
  else if (argv[i] === '--browser') options.browser = true;
  else if (argv[i] === '--keep') options.keep = true;
  else {
    console.error(`info-container-test: unknown or incomplete option ${argv[i]}\n${USAGE}`);
    process.exit(2);
  }
}
if (!options.image) {
  console.error(`info-container-test: --image is required\n${USAGE}`);
  process.exit(2);
}
if (options.noLegalDoc && options.legalDocGiven) {
  console.error(
    `info-container-test: --no-legal-doc and --legal-doc contradict each other: name the legal document the image ships, or say it ships none\n${USAGE}`,
  );
  process.exit(2);
}
if (!/^[a-z]{2,3}$/.test(options.language)) {
  console.error(
    `info-container-test: --language "${options.language}" is not a language code\n${USAGE}`,
  );
  process.exit(2);
}

/** The name the container reaches this process by; see HOST REACHABILITY above. */
const UPSTREAM_HOST = 'niord-stub-upstream';

/**
 * Who may frame the teaser in the main run. A value of its own rather than the image's default,
 * so that what reaches the header is shown to be the deployment's.
 */
const TEASER_FRAME_ANCESTORS = "https://embedder.example 'self'";

function docker(args) {
  return execFileSync('docker', args, { encoding: 'utf8' }).trim();
}

/** The arguments that make a container reach this process as UPSTREAM_HOST. */
const HOST_MAPPING = `--add-host=${UPSTREAM_HOST}:host-gateway`;

/** The port the image's nginx listens on: an unprivileged one, as its user is not root. */
const CONTAINER_PORT = '8080';

/** The user the image must run as: unprivileged, and by number, which a pod can verify. */
const IMAGE_USER = '101:101';

/**
 * How a cluster that enforces the restricted Pod Security standard runs the container, and
 * stricter: as the image's own user (no --user, so an image that drops its USER runs as root and
 * fails the user check), with every capability dropped, no way to gain a privilege, and a
 * read-only root filesystem with /tmp alone writable. An image that needs more fails every run of
 * this test, before it is pushed, instead of failing in the cluster.
 */
const RESTRICTED = [
  '--cap-drop',
  'ALL',
  '--security-opt',
  'no-new-privileges',
  '--read-only',
  '--tmpfs',
  '/tmp',
];

/** Runs the image detached and restricted, with the given environment, its port on loopback. */
function startContainer(env) {
  const envArgs = Object.entries(env).flatMap(([name, value]) => ['-e', `${name}=${value}`]);
  return docker([
    'run',
    '-d',
    ...RESTRICTED,
    HOST_MAPPING,
    '-p',
    `127.0.0.1:0:${CONTAINER_PORT}`,
    ...envArgs,
    options.image,
  ]);
}

function publishedPort(container) {
  const published = docker(['port', container, CONTAINER_PORT]).split('\n')[0];
  const port = Number(published.slice(published.lastIndexOf(':') + 1));
  if (!port) throw new Error(`could not read the published port from "${published}"`);
  return port;
}

function removeContainer(container) {
  try {
    docker(['rm', '-f', container]);
  } catch {
    /* nothing left to do about it */
  }
}

/**
 * Starts the image with a configuration it must refuse, and answers how it ended: the exit code
 * and the log, or `running` when it was still serving after the deadline — which is the failure.
 */
async function runToRefusal(env) {
  const container = startContainer(env);
  try {
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline) {
      const [running, exitCode] = docker([
        'inspect',
        '-f',
        '{{.State.Running}} {{.State.ExitCode}}',
        container,
      ]).split(' ');
      if (running === 'false') {
        return { running: false, exitCode: Number(exitCode), log: containerLog(container) };
      }
      await sleep(250);
    }
    return { running: true, exitCode: undefined, log: containerLog(container) };
  } finally {
    removeContainer(container);
  }
}

/** The container's output, both streams: the entrypoint reports a refusal on stderr. */
function containerLog(container) {
  const result = spawnSync('docker', ['logs', container], { encoding: 'utf8' });
  return `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
}

// ---------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------

function request(port, method, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path, headers }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () =>
        resolve({
          status: res.statusCode,
          headers: res.headers,
          rawHeaders: res.rawHeaders,
          body: Buffer.concat(chunks),
        }),
      );
    });
    req.setTimeout(20000, () => req.destroy(new Error(`${method} ${path} timed out`)));
    req.on('error', reject);
    req.end();
  });
}

// ---------------------------------------------------------------------------------------------
// The stub upstream
// ---------------------------------------------------------------------------------------------

/** 1x1 transparent PNG — the shape of an attachment, not its content. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

/** A repository file answers with the type its extension names, as the backend does. */
const REPO_FILE_TYPES = {
  html: ['text/html;charset=UTF-8', '<p>an uploaded page</p>'],
  svg: ['image/svg+xml', '<svg xmlns="http://www.w3.org/2000/svg"/>'],
  pdf: ['application/pdf', '%PDF-1.4\n%%EOF\n'],
};

/**
 * A PDF the stub answers as the backend does while it is making as many PDFs for readers as it
 * makes at once, or with another refusal the container must pass as it came: a request whose
 * series or message id names the case.
 */
const PDF_REFUSALS = {
  busy: [503, { 'retry-after': '10' }],
  gone: [404, {}],
};

let seen = [];
const stub = http.createServer((req, res) => {
  seen.push({ method: req.method, url: req.url, headers: { ...req.headers } });
  const refusal = /^\/rest\/message-reports\/.*\b(busy|gone)\b/.exec(req.url);
  if (refusal) {
    const [status, headers] = PDF_REFUSALS[refusal[1]];
    res.writeHead(status, { 'content-type': 'application/problem+json', ...headers });
    res.end(JSON.stringify({ status, detail: 'the stub refuses this PDF' }));
    return;
  }
  const isRepoFile = req.url.startsWith('/rest/repo/file/');
  const extension = req.url.split('?')[0].split('.').pop();
  const [type, body] = isRepoFile
    ? (REPO_FILE_TYPES[extension] ?? ['image/png', PNG])
    : ['application/json', JSON.stringify({ stub: true, url: req.url })];
  res.writeHead(200, {
    'content-type': type,
    // An upstream that sets a cookie is exactly what the public origin must swallow.
    'set-cookie': 'stub=1; Path=/',
    // The backend's web security sends this on every response, and the container sends its own.
    'x-content-type-options': 'nosniff',
    // Spring Security's own, on a request it holds for secure; the container sends its own, for
    // its host alone, and must hide this one.
    'strict-transport-security': 'max-age=1; includeSubDomains',
  });
  res.end(body);
});

/**
 * A second stub, behind TLS, whose certificate nothing trusts: a self-signed one for
 * UPSTREAM_HOST, made for this test and used nowhere else. The container must refuse to talk to
 * it, and must have named the host it expected while doing so (SNI). The private key is published
 * on purpose: nothing trusts the certificate, so the key protects nothing, and a secret scanner
 * that reports it has found a test fixture, not a leaked credential.
 */
const TLS_KEY = `-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgIKIDS6AohSVNKI31
Lm1XTmeyEjxA1R1n/xKq89Kikt2hRANCAAR+nmGo766PuRPuhmtlreIBRetTNE2H
l0BoXBjZluSpY3xK5FK3oH6y8Pj3cpP7cLKMaV3rjJxL43ME/+E8PLWl
-----END PRIVATE KEY-----
`;
const TLS_CERT = `-----BEGIN CERTIFICATE-----
MIIBszCCAVmgAwIBAgIUDLxgTenxTpj2z315ESQDsTWjz4UwCgYIKoZIzj0EAwIw
HjEcMBoGA1UEAwwTbmlvcmQtc3R1Yi11cHN0cmVhbTAgFw0yNjA5MjMxNTUzMDla
GA8yMTI2MDgzMDE1NTMwOVowHjEcMBoGA1UEAwwTbmlvcmQtc3R1Yi11cHN0cmVh
bTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABH6eYajvro+5E+6Ga2Wt4gFF61M0
TYeXQGhcGNmW5KljfErkUregfrLw+Pdyk/twsoxpXeuMnEvjcwT/4Tw8taWjczBx
MB0GA1UdDgQWBBT7qlkI/iScexps8NPnplPa0eI/hDAfBgNVHSMEGDAWgBT7qlkI
/iScexps8NPnplPa0eI/hDAPBgNVHRMBAf8EBTADAQH/MB4GA1UdEQQXMBWCE25p
b3JkLXN0dWItdXBzdHJlYW0wCgYIKoZIzj0EAwIDSAAwRQIgJHf1JkBkkcefWG/O
u+LzKeiHNOYKWcE5vrS/xhMHlEkCIQCq11YNj/v49QL7/dDF2YeHQflpt2IlsY3B
gzhw1iZY/w==
-----END CERTIFICATE-----
`;
const tlsSeen = { serverNames: [], requests: 0 };
const tlsStub = https.createServer(
  {
    key: TLS_KEY,
    cert: TLS_CERT,
    SNICallback: (serverName, done) => {
      tlsSeen.serverNames.push(serverName);
      done(null);
    },
  },
  (req, res) => {
    tlsSeen.requests++;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{}');
  },
);
// A refused handshake is the expected outcome, not an error of this process.
tlsStub.on('tlsClientError', () => {});

function listen(server) {
  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(0, '0.0.0.0', () => resolve(server.address().port));
  });
}

// ---------------------------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------------------------

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail });
}
/**
 * A probe this image cannot answer (--no-legal-doc, --no-branding-text): named, never counted as
 * passed.
 */
const skipped = [];
function skip(name, reason) {
  skipped.push({ name, reason });
}
function lastSeen() {
  return seen[seen.length - 1];
}

/** Every value a response carries for one header, one entry per header line. */
function headerLines(response, name) {
  const lines = [];
  for (let i = 0; i < response.rawHeaders.length; i += 2) {
    if (response.rawHeaders[i].toLowerCase() === name) lines.push(response.rawHeaders[i + 1]);
  }
  return lines;
}

/** One directive of a Content-Security-Policy, as its source list, or undefined. */
function cspDirective(policy, name) {
  const directive = String(policy ?? '')
    .split(';')
    .map((part) => part.trim().split(/\s+/))
    .find(([directiveName]) => directiveName === name);
  return directive?.slice(1).join(' ');
}

const SITE_POLICY = {
  'default-src': "'self'",
  'script-src': "'self'",
  'style-src': "'self' 'unsafe-inline'",
  'img-src': "'self' data: blob: https:",
  'connect-src': "'self'",
  'object-src': "'none'",
  'base-uri': "'self'",
  'form-action': "'self'",
};

/** Whether a response says nosniff exactly once, the backend's own copy hidden. */
function hasOneNosniff(response) {
  const lines = headerLines(response, 'x-content-type-options');
  return lines.length === 1 && lines[0] === 'nosniff';
}

/**
 * Whether a response carries the site's security headers: one Content-Security-Policy with the
 * site's directives and the expected frame-ancestors, one nosniff and the referrer policy. One
 * policy, because a second header would not replace the first — the browser enforces both.
 */
function securityHeaderProblems(response, frameAncestors = "'self'") {
  const problems = [];
  const policies = headerLines(response, 'content-security-policy');
  if (policies.length !== 1) problems.push(`${policies.length} Content-Security-Policy headers`);
  const policy = policies[0];
  for (const [name, sources] of Object.entries({
    ...SITE_POLICY,
    'frame-ancestors': frameAncestors,
  })) {
    const actual = cspDirective(policy, name);
    if (actual !== sources) problems.push(`${name} is ${JSON.stringify(actual)}, not ${sources}`);
  }
  if (!hasOneNosniff(response)) {
    problems.push(
      `x-content-type-options: ${JSON.stringify(headerLines(response, 'x-content-type-options'))}`,
    );
  }
  if (response.headers['referrer-policy'] !== 'strict-origin-when-cross-origin') {
    problems.push(`referrer-policy: ${response.headers['referrer-policy']}`);
  }
  return problems;
}

/**
 * Whether a legal document or branding file is served as a country's data rather than as part of
 * the application: one Content-Security-Policy, the sandbox and not the site's policy beside it,
 * one nosniff, the referrer policy, and the five minutes of every unhashed asset.
 */
function sandboxedFileProblems(response) {
  const problems = [];
  if (response.status !== 200) problems.push(`status ${response.status}`);
  const policies = headerLines(response, 'content-security-policy');
  if (policies.length !== 1 || policies[0] !== 'sandbox') {
    problems.push(`content-security-policy: ${JSON.stringify(policies)}`);
  }
  if (!hasOneNosniff(response)) {
    problems.push(
      `x-content-type-options: ${JSON.stringify(headerLines(response, 'x-content-type-options'))}`,
    );
  }
  if (response.headers['referrer-policy'] !== 'strict-origin-when-cross-origin') {
    problems.push(`referrer-policy: ${response.headers['referrer-policy']}`);
  }
  if (!String(response.headers['cache-control']).includes('max-age=300')) {
    problems.push(`cache-control: ${response.headers['cache-control']}`);
  }
  return problems;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitForSite(port) {
  const deadline = Date.now() + 60000;
  let lastError = 'no attempt made';
  while (Date.now() < deadline) {
    try {
      const response = await request(port, 'GET', '/');
      if (response.status === 200) return;
      lastError = `status ${response.status}`;
    } catch (error) {
      lastError = error.message;
    }
    await sleep(500);
  }
  throw new Error(`the container never answered GET / (${lastError})`);
}

/**
 * What a client can write about the request it is making, and a backend framework reads as fact:
 * the host, port, path prefix and scheme it was reached by, and — in Proxy-Authorization and
 * NiordDomain — a credential and the domain a signed-in editor works in.
 */
const CLIENT_FORWARDING_HEADERS = {
  'x-forwarded-for': '203.0.113.9',
  'x-forwarded-host': 'spoofed.example',
  'x-forwarded-port': '8443',
  'x-forwarded-prefix': '/spoofed',
  'x-forwarded-ssl': 'on',
  forwarded: 'for=192.0.2.1;host=spoofed.example;proto=https',
  'proxy-authorization': 'Basic c3Bvb2Y6c3Bvb2Y=',
  niorddomain: 'niord-client-nw',
};

async function run(port, stubPort) {
  // ---- control: the headers this test claims are dropped do leave this process ----------------
  const control = await request(stubPort, 'GET', '/rest/public/v1/control', {
    authorization: 'Bearer t',
    cookie: 'a=b',
    ...CLIENT_FORWARDING_HEADERS,
  });
  const controlSeen = lastSeen();
  check(
    'control: credentials and forwarding headers reach an upstream when nothing strips them',
    control.status === 200 &&
      controlSeen?.headers.authorization === 'Bearer t' &&
      controlSeen?.headers.cookie === 'a=b' &&
      Object.entries(CLIENT_FORWARDING_HEADERS).every(
        ([name, value]) => controlSeen?.headers[name] === value,
      ),
    'without this the header assertions below could pass because nothing was ever sent',
  );
  seen = [];

  // ---- methods and paths the public host must not carry ---------------------------------------
  const refused = [
    ['DELETE', '/rest/repo/file/x.png', 403, 'a repository file cannot be deleted through here'],
    ['POST', '/rest/repo/upload/x', 404, 'the upload endpoint does not exist on this host'],
    ['GET', '/rest/repo/list/x', 404, 'the repository listing does not exist on this host'],
    ['GET', '/rest/messages/search', 404, 'the editor message API does not exist on this host'],
    ['PUT', '/rest/settings/setting/mode', 404, 'settings cannot be written through here'],
    ['DELETE', '/rest/public/v1/messages', 403, 'the public API is read-only through here'],
    [
      'POST',
      '/rest/message-reports/report.pdf',
      403,
      'a report is rendered by asking for it, never by posting to it',
    ],
    ['POST', '/rest/message-reports/message/NW-333-26.pdf', 403, 'the same for one message'],
    [
      'GET',
      '/rest/message-reports/reports',
      404,
      'the report catalogue does not exist on this host: the site names its one report itself ' +
        'and offers the reader no choice of one',
    ],
    [
      'GET',
      '/rest/message-reports/all',
      404,
      'the sysadmin report listing does not exist on this host',
    ],
    ['GET', '/auth/realms/niord', 404, 'Keycloak is not reachable from the public site'],
    ['POST', '/', 403, 'the site itself takes no writes'],
  ];
  for (const [method, path, expected, why] of refused) {
    const before = seen.length;
    const response = await request(port, method, path);
    check(
      `${method} ${path} is refused with ${expected} and never reaches the backend`,
      response.status === expected && seen.length === before,
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s)`,
    );
  }

  // ---- the four paths that are proxied ---------------------------------------------------------
  // The container runs with a trailing slash on its backend address, so every URL the stub sees
  // below also shows that the slash was dropped rather than doubled into the path.
  const messages = await request(port, 'GET', '/rest/public/v1/messages?lang=da', {
    authorization: 'Bearer t',
    cookie: 'a=b',
    'x-forwarded-proto': 'https',
    ...CLIENT_FORWARDING_HEADERS,
  });
  const proxied = lastSeen();
  check(
    'GET /rest/public/v1/messages is proxied',
    messages.status === 200 && proxied?.url === '/rest/public/v1/messages?lang=da',
    `got ${messages.status}, stub saw ${proxied?.method} ${proxied?.url}`,
  );
  check(
    'the Authorization header does not leave the public host',
    !proxied?.headers.authorization,
    `stub saw authorization: ${JSON.stringify(proxied?.headers.authorization)}`,
  );
  check(
    'the Cookie header does not leave the public host',
    !proxied?.headers.cookie,
    `stub saw cookie: ${JSON.stringify(proxied?.headers.cookie)}`,
  );
  check(
    'an upstream Set-Cookie never reaches the client',
    messages.headers['set-cookie'] === undefined,
    `client saw set-cookie: ${JSON.stringify(messages.headers['set-cookie'])}`,
  );
  const leaked = ['x-forwarded-port', 'x-forwarded-prefix', 'x-forwarded-ssl', 'forwarded']
    .concat(['proxy-authorization', 'niorddomain'])
    .filter((name) => proxied?.headers[name] !== undefined);
  check(
    'no forwarding header, Proxy-Authorization or NiordDomain the client wrote reaches the backend',
    leaked.length === 0,
    `stub saw ${leaked.map((name) => `${name}: ${proxied?.headers[name]}`).join(', ')}`,
  );
  check(
    'the backend is told the host the reader asked for, not the one the client claims',
    proxied?.headers['x-forwarded-host'] === '127.0.0.1',
    `stub saw x-forwarded-host: ${proxied?.headers['x-forwarded-host']}`,
  );
  check(
    'the scheme a TLS front reports is passed on',
    proxied?.headers['x-forwarded-proto'] === 'https',
    `stub saw x-forwarded-proto: ${proxied?.headers['x-forwarded-proto']}`,
  );
  check(
    "the backend's address chain ends in the peer this container saw",
    String(proxied?.headers['x-forwarded-for']).split(',').pop().trim() !== '' &&
      proxied?.headers['x-real-ip'] ===
        String(proxied?.headers['x-forwarded-for']).split(',').pop().trim(),
    `stub saw x-forwarded-for: ${proxied?.headers['x-forwarded-for']}, ` +
      `x-real-ip: ${proxied?.headers['x-real-ip']}`,
  );
  check(
    'the proxied JSON carries the security headers',
    securityHeaderProblems(messages).length === 0,
    securityHeaderProblems(messages).join('; '),
  );

  await request(port, 'GET', '/rest/public/v1/messages?lang=en', { 'x-forwarded-proto': 'gopher' });
  check(
    'a scheme that is neither http nor https is replaced by the one this container saw',
    lastSeen()?.headers['x-forwarded-proto'] === 'http',
    `stub saw x-forwarded-proto: ${lastSeen()?.headers['x-forwarded-proto']}`,
  );

  await request(port, 'GET', '/rest/public/v1/messages?lang=da&myticket=1&q=ticket');
  check(
    'a parameter merely named or valued like a ticket is proxied',
    lastSeen()?.url === '/rest/public/v1/messages?lang=da&myticket=1&q=ticket',
    `stub saw ${lastSeen()?.url}`,
  );

  const settings = await request(port, 'GET', '/rest/settings/web');
  check(
    'GET /rest/settings/web is proxied',
    settings.status === 200 && lastSeen()?.url === '/rest/settings/web',
    `got ${settings.status}, stub saw ${lastSeen()?.url}`,
  );

  const repoFile = await request(port, 'GET', '/rest/repo/file/2026/a.png');
  check(
    'GET /rest/repo/file/… is proxied',
    repoFile.status === 200 && lastSeen()?.url === '/rest/repo/file/2026/a.png',
    `got ${repoFile.status}, stub saw ${lastSeen()?.url}`,
  );

  // An uploaded file is sandboxed rather than given the application's policy; a PDF is not
  // sandboxed at all. Either way it is never sniffed into another type.
  const repoFiles = [
    ['/rest/repo/file/2026/a.png', 'sandbox'],
    ['/rest/repo/file/2026/page.html', 'sandbox'],
    ['/rest/repo/file/2026/chart.svg', 'sandbox'],
    ['/rest/repo/file/2026/notice.pdf', undefined],
  ];
  for (const [path, expected] of repoFiles) {
    const response = await request(port, 'GET', path);
    const policies = headerLines(response, 'content-security-policy');
    check(
      `${path} is served with ${expected ? `Content-Security-Policy: ${expected}` : 'no policy'}`,
      response.status === 200 &&
        (expected ? policies.length === 1 && policies[0] === expected : policies.length === 0) &&
        hasOneNosniff(response) &&
        response.headers['referrer-policy'] === 'strict-origin-when-cross-origin',
      `got ${response.status}, content-security-policy: ${JSON.stringify(policies)}, ` +
        `x-content-type-options: ${JSON.stringify(headerLines(response, 'x-content-type-options'))}, ` +
        `referrer-policy: ${response.headers['referrer-policy']}`,
    );
  }

  const listPdf = await request(
    port,
    'GET',
    '/rest/message-reports/report.pdf?lang=da&report=public&mainType=NW&area=453&messageSeries=xx-nw',
    { authorization: 'Bearer t' },
  );
  const listPdfSeen = lastSeen();
  check(
    'GET /rest/message-reports/report.pdf is proxied with the report and the filter intact',
    listPdf.status === 200 &&
      listPdfSeen?.url ===
        '/rest/message-reports/report.pdf?lang=da&report=public&mainType=NW&area=453&messageSeries=xx-nw' &&
      !listPdfSeen?.headers.authorization,
    `got ${listPdf.status}, stub saw ${listPdfSeen?.method} ${listPdfSeen?.url}, ` +
      `authorization: ${JSON.stringify(listPdfSeen?.headers.authorization)}`,
  );

  const messagePdf = await request(
    port,
    'GET',
    '/rest/message-reports/message/Local%20Warning-120-26.pdf?lang=da',
  );
  check(
    'GET /rest/message-reports/message/….pdf is proxied with the id untouched',
    messagePdf.status === 200 &&
      lastSeen()?.url === '/rest/message-reports/message/Local%20Warning-120-26.pdf?lang=da',
    `got ${messagePdf.status}, stub saw ${lastSeen()?.url}`,
  );

  // A busy PDF: the backend answers a reader 503 at once while it makes as many PDFs as it makes at
  // once. The reader's tab gets the site's own page, still a 503 and with Retry-After, under the
  // site's headers and kept by nobody; any other refusal passes as the backend wrote it.
  for (const [path, what] of [
    ['/rest/message-reports/report.pdf?lang=da&report=public&messageSeries=busy', 'the list PDF'],
    ['/rest/message-reports/message/busy-1-26.pdf?lang=da', 'one message PDF'],
  ]) {
    const busy = await request(port, 'GET', path);
    const body = busy.body.toString('utf8');
    const problems = [
      ...securityHeaderProblems(busy),
      ...(busy.status === 503 ? [] : [`status ${busy.status}`]),
      ...(busy.headers['retry-after'] === '10'
        ? []
        : [`retry-after ${JSON.stringify(busy.headers['retry-after'])}`]),
      ...(busy.headers['cache-control'] === 'no-store'
        ? []
        : [`cache-control ${JSON.stringify(busy.headers['cache-control'])}`]),
      ...(/^text\/html/.test(busy.headers['content-type'] ?? '')
        ? []
        : [`content-type ${busy.headers['content-type']}`]),
      ...(body.includes('src="/pdf-busy.js"') && body.includes('id="pdf-busy-text"')
        ? []
        : ['the body is not the busy page']),
    ];
    check(
      `a busy backend's 503 on ${what} reaches the reader as the site's page, with Retry-After`,
      problems.length === 0 && lastSeen()?.url === path,
      `${problems.join('; ')}; stub saw ${lastSeen()?.url}`,
    );
  }
  const gone = await request(port, 'GET', '/rest/message-reports/message/gone-1-26.pdf?lang=da');
  check(
    "another refusal of a PDF passes as the backend's own",
    gone.status === 404 &&
      /^application\/problem\+json/.test(gone.headers['content-type'] ?? '') &&
      !gone.body.toString('utf8').includes('pdf-busy'),
    `got ${gone.status}, content-type ${gone.headers['content-type']}`,
  );
  const byName = await request(port, 'GET', '/pdf-busy.html');
  check(
    'the busy page is no page of its own: asked for by its name it is a 404',
    byName.status === 404,
    `got ${byName.status}`,
  );
  const busyScript = await request(port, 'GET', '/pdf-busy.js');
  check(
    "the busy page's script is served",
    busyScript.status === 200 && /javascript/.test(busyScript.headers['content-type'] ?? ''),
    `got ${busyScript.status}, content-type ${busyScript.headers['content-type']}`,
  );

  // A short id written with a letter outside ASCII, as encodeURIComponent spells it.
  const danishPdf = await request(
    port,
    'GET',
    '/rest/message-reports/message/Lokal%C3%86-7-26.pdf?lang=da',
  );
  check(
    'GET /rest/message-reports/message/….pdf keeps a percent-encoded letter in the id',
    danishPdf.status === 200 &&
      lastSeen()?.url === '/rest/message-reports/message/Lokal%C3%86-7-26.pdf?lang=da',
    `got ${danishPdf.status}, stub saw ${lastSeen()?.url}`,
  );

  // A short id that holds a slash travels as one encoded segment, %2F in either case. Both
  // locations that take a message id pass the request line on as it arrived, so the backend gets
  // the id whole: a slash decoded on the way would split it into a path no handler maps.
  const messageIdUrls = [
    ['/rest/public/v1/message/NW-333-26?lang=da', 'the public API, an id without a slash'],
    ['/rest/public/v1/message/B%2F1%202026?lang=da', 'the public API, an encoded slash'],
    ['/rest/public/v1/message/FA%2f14%202026', 'the public API, in lower case, no query'],
    ['/rest/message-reports/message/B%2F1%202026.pdf?lang=da', 'one message PDF, an encoded slash'],
    ['/rest/message-reports/message/A%2f1%202026.pdf?lang=en', 'one message PDF, in lower case'],
  ];
  for (const [url, why] of messageIdUrls) {
    const before = seen.length;
    const response = await request(port, 'GET', url);
    check(
      `GET ${url} is proxied with the message id untouched`,
      response.status === 200 && seen.length === before + 1 && lastSeen()?.url === url,
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s), ` +
        `the last ${lastSeen()?.url}`,
    );
  }

  // A repository file's name may hold a `;`, which every link to it writes as `%3B`: the location
  // passes the request line on as it arrived, so the backend gets the escape and not a raw `;`,
  // which it refuses. Every escape a file name can need passes as written, and so do the RFC 3986
  // path characters a link keeps raw. The headers of the location stay: the sandbox for every
  // type but a PDF, one nosniff, the referrer policy.
  const repoFileNames = [
    ['GET', '/rest/repo/file/2026/a%3Bb.png', 'sandbox', 'an encoded semicolon'],
    ['GET', '/rest/repo/file/2026/a%3bb.pdf', undefined, 'the same in lower case, a PDF'],
    ['HEAD', '/rest/repo/file/2026/a%3Bb.png', 'sandbox', 'HEAD of the same'],
    ['GET', '/rest/repo/file/2026/100%25%20cover.html', 'sandbox', 'an encoded percent, a space'],
    [
      'GET',
      '/rest/repo/file/2026/d%C3%A6kning%20%C3%B8%20%C3%A5.svg',
      'sandbox',
      'Danish letters as UTF-8 escapes',
    ],
    ['GET', '/rest/repo/file/2026/a%253Bb.png', 'sandbox', 'an encoded percent before 3B'],
    ['GET', '/rest/repo/file/2026/Rapport..pdf', undefined, 'two dots inside a name'],
    [
      'GET',
      "/rest/repo/file/2026/a:b@c!$&'()*+,=~.png",
      'sandbox',
      'the path characters a link keeps raw',
    ],
    ['GET', '/rest/repo/file/2026/a.png?v=2', 'sandbox', 'a query, which is not held'],
  ];
  for (const [method, url, expected, why] of repoFileNames) {
    const before = seen.length;
    const response = await request(port, method, url);
    const policies = headerLines(response, 'content-security-policy');
    check(
      `${method} ${url} is proxied as written, with ${expected ? `Content-Security-Policy: ${expected}` : 'no policy'}`,
      response.status === 200 &&
        seen.length === before + 1 &&
        lastSeen()?.method === method &&
        lastSeen()?.url === url &&
        (expected ? policies.length === 1 && policies[0] === expected : policies.length === 0) &&
        hasOneNosniff(response) &&
        response.headers['referrer-policy'] === 'strict-origin-when-cross-origin',
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s), ` +
        `the last ${lastSeen()?.method} ${lastSeen()?.url}, ` +
        `content-security-policy: ${JSON.stringify(policies)}`,
    );
  }

  const head = await request(port, 'HEAD', '/rest/settings/web');
  check(
    'HEAD /rest/settings/web is proxied',
    head.status === 200 && lastSeen()?.method === 'HEAD',
    `got ${head.status}, stub saw ${lastSeen()?.method} ${lastSeen()?.url}`,
  );

  // ---- the query the two report PDFs accept ----------------------------------------------------
  // The report endpoint reads far more from its query than the site sends — a debug switch that
  // answers with the HTML the PDF is rendered from, the page layout, free-form template values,
  // the whole administration search — so the container checks the query as one whole string. What
  // the site composes passes untouched; anything else is refused before it leaves the container,
  // in every spelling the backend would read as the same parameter. A list names the public report
  // exactly once, so every refused list below that is not about the report names it too, and is
  // refused for the one reason it gives.
  const LIST_PDF = '/rest/message-reports/report.pdf';
  const MESSAGE_PDF = '/rest/message-reports/message/NW-333-26.pdf';

  const acceptedQueries = [
    [
      LIST_PDF,
      'lang=en&report=public&mainType=NW&mainType=NM&area=453&area=1023' +
        '&messageSeries=xx-nw&messageSeries=xx-nw-local',
      'every filter name, repeated as the site repeats it',
    ],
    [
      LIST_PDF,
      'lang=da&publication=6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b&report=public',
      'a publication, by the id the public API hands out, and the public report named last',
    ],
    [
      LIST_PDF,
      'report=public&lang=kl&messageSeries=xx-nm',
      'the public report, the one report the site names, named first',
    ],
  ];
  const acceptedRequests = [];
  for (const [path, query, why] of acceptedQueries) {
    const url = `${path}?${query}`;
    const response = await request(port, 'GET', url);
    check(
      `GET ${path} is proxied with its query intact: ${why}`,
      response.status === 200 && lastSeen()?.url === url,
      `sent ${url}; got ${response.status}, stub saw ${lastSeen()?.url}`,
    );
    acceptedRequests.push(`GET ${url}`);
  }

  const refusedQueries = [
    [
      'GET',
      LIST_PDF,
      'lang=en&mainType=NW&mainType=NM&area=453&area=1023' +
        '&messageSeries=xx-nw&messageSeries=xx-nw-local',
      'the whole filter and no report: the site always names the public one',
    ],
    [
      'GET',
      LIST_PDF,
      'lang=da&publication=6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b',
      'a publication and no report',
    ],
    ['GET', LIST_PDF, 'lang=da', 'the language alone, with no report'],
    ['HEAD', LIST_PDF, 'lang=da&mainType=NW', 'HEAD without a report is refused as well'],
    ['GET', LIST_PDF, 'lang=da&report=public&report=public', 'the public report named twice'],
    [
      'GET',
      LIST_PDF,
      'report=public&lang=kl&messageSeries=xx-nm&report=public',
      'the public report named first and last',
    ],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&debug=true',
      'the debug switch returns the HTML behind the PDF',
    ],
    ['GET', MESSAGE_PDF, 'lang=da&debug=true', 'the same switch on one message'],
    [
      'HEAD',
      LIST_PDF,
      'lang=da&report=public&debug=true',
      'HEAD is proxied as well, so it is checked as well',
    ],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&%64ebug=true',
      'a percent-encoded letter, which the backend decodes back to debug',
    ],
    ['GET', LIST_PDF, 'lang=da&report=public&Debug=true', 'a name in another case'],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&debug=false&debug=true',
      'a name given twice, where a reader of the first and a reader of the last disagree',
    ],
    ['GET', LIST_PDF, 'lang=da&report=public;debug=true', 'a pair joined by ; rather than &'],
    ['GET', LIST_PDF, 'lang=da&report=public&&debug', 'a doubled & and a name without ='],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&param:searchCriteria=AREA-CLOSED',
      'a free-form template value, which replaces what the sheet says it lists',
    ],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&param%3AsearchCriteria=AREA-CLOSED',
      'the same with its colon percent-encoded',
    ],
    ['GET', MESSAGE_PDF, 'lang=da&param:x=y', 'a free-form template value on one message'],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&pageSize=A0',
      'the page size is not the reader’s to set',
    ],
    ['GET', LIST_PDF, 'lang=da&report=public&pageOrientation=landscape', 'nor is the orientation'],
    ['GET', LIST_PDF, 'lang=da&report=public&unknown=1', 'a name the site does not send'],
    ['GET', LIST_PDF, 'lang=da&report=public%3Cb%3E', 'a report name that decodes into markup'],
    ['GET', LIST_PDF, 'lang=kl&messageSeries=xx-nm&report=nm-report', 'a report the editors print'],
    ['GET', LIST_PDF, 'lang=da&report=fa-list', 'another report definition, by its name'],
    ['GET', LIST_PDF, 'lang=da&report=publicity', 'a name that merely begins with the public one'],
    ['GET', LIST_PDF, 'lang=da&report=Public', 'the public report in another case'],
    ['GET', LIST_PDF, 'lang=da&report=', 'an empty report name'],
    [
      'GET',
      MESSAGE_PDF,
      'lang=da&messageSeries=xx-nw',
      'a list filter on one message, which takes the language alone',
    ],
    [
      'GET',
      LIST_PDF,
      'report=public&lang=da%26debug%3Dtrue',
      'a value that decodes into a second pair',
    ],
    [
      'GET',
      LIST_PDF,
      'lang=da&report=public&messageSeries=%3Ch1%3EAREA%20CLOSED%3C%2Fh1%3E',
      'a value that decodes into markup',
    ],
    ['GET', LIST_PDF, '', 'no query at all: the site always names the language'],
  ];
  for (const [method, path, query, why] of refusedQueries) {
    const url = query ? `${path}?${query}` : path;
    const before = seen.length;
    const response = await request(port, method, url);
    check(
      `${method} ${url} is refused with 403 and never reaches the backend`,
      response.status === 403 && seen.length === before,
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s)`,
    );
  }

  // ---- the raw request line of the two locations that take a message id -------------------------
  // They pass the request line on as it arrived, so a spelling nginx normalises away for its own
  // matching would reach the backend verbatim. An encoded slash is the one escape of a path
  // separator they let through, alone and in the id's one segment.
  const refusedLines = [
    ['/rest/message-reports/message/..;x.pdf?lang=da', 'a `..;` segment'],
    ['/rest/message-reports/message/%2e%2e%3bx.pdf?lang=da', 'the same, percent-encoded'],
    ['/rest/message-reports/message/NW%251-26.pdf?lang=da', 'an encoded percent in the id'],
    ['/rest/message-reports/message/NW%252F1-26.pdf?lang=da', 'a doubly encoded slash'],
    ['/rest/message-reports/message/NW%2F%2F1-26.pdf?lang=da', 'an encoded double slash'],
    ['/rest/message-reports/message/NW%2f..%2f1-26.pdf?lang=da', 'a `..` between encoded slashes'],
    ['/rest/message-reports/message/NW%5C1-26.pdf?lang=da', 'an encoded backslash in the id'],
    ['/rest/message-reports/message/NW/1-26.pdf?lang=da', 'a further segment'],
    ['/rest/message-reports//message/NW-1-26.pdf?lang=da', 'a doubled slash nginx merges'],
    ['/rest/message-reports/message//NW-1-26.pdf?lang=da', 'a doubled slash before the id'],
    ['/rest/message-reports/message/NW;x=1.pdf?lang=da', 'a semicolon in the id'],
    ['/rest/public/v1/message/B%252F1%202026', 'the public API: a doubly encoded slash'],
    ['/rest/public/v1/message/B%2F%2F1%202026', 'the public API: an encoded double slash'],
    ['/rest/public/v1/message/B%2F..%2Fx', 'the public API: a `..` between encoded slashes'],
    ['/rest/public/v1/message/B%2e1', 'the public API: an encoded dot'],
    ['/rest/public/v1/message/B%5C1', 'the public API: an encoded backslash'],
    ['/rest/public/v1/message/NW;x=1', 'the public API: a semicolon in the id'],
    ['/rest/public/v1/message/NW/1', 'the public API: a further segment'],
    [
      '/rest/public/v1/messages%2F..%2Fmessage/x',
      'the public API: a line nginx normalises into the message path',
    ],
  ];
  for (const [url, why] of refusedLines) {
    const before = seen.length;
    const response = await request(port, 'GET', url);
    check(
      `GET ${url} is refused and never reaches the backend`,
      response.status >= 400 && response.status < 500 && seen.length === before,
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s)`,
    );
  }

  // ---- the raw request line of a repository file -----------------------------------------------
  // Passed on as it arrived too, so it is held to the paths the backend's firewall serves for a
  // repository file: what that firewall refuses, and every spelling nginx would fold into another
  // path, is a 403 here. A NUL and a broken escape nginx refuses itself, with 400, before any
  // location is chosen.
  const refusedRepoLines = [
    ['/rest/repo/file/2026/a;b.png', 403, 'a raw semicolon, a path parameter to the backend'],
    ['/rest/repo/file/2026/..;/a.png', 403, 'a `..;` segment'],
    ['/rest/repo/file/2026/../a.png', 403, 'a `..` segment'],
    ['/rest/repo/file/2026/./a.png', 403, 'a `.` segment'],
    ['/rest/repo/file/2026/..', 403, 'a `..` segment at the end'],
    ['/rest/repo/file/2026/%2e%2e/a.png', 403, 'an encoded `..` segment'],
    ['/rest/repo/file/2026/.%2E/a.png', 403, 'a half-encoded `..` segment'],
    ['/rest/repo/file/2026/a%2Eb.png', 403, 'an encoded dot inside a name'],
    ['/rest/repo/file/2026/a%2Fb.png', 403, 'an encoded slash'],
    ['/rest/repo/file/2026/a%2fb.png', 403, 'an encoded slash in lower case'],
    ['/rest/repo/file/2026/a%252Fb.png', 403, 'a doubly encoded slash'],
    ['/rest/repo/file/2026/a%252eb.png', 403, 'a doubly encoded dot'],
    ['/rest/repo/file/2026/a%5Cb.png', 403, 'an encoded backslash'],
    ['/rest/repo/file/2026/a%255Cb.png', 403, 'a doubly encoded backslash'],
    ['/rest/repo/file/2026/a\\b.png', 403, 'a raw backslash'],
    ['/rest/repo/file/2026/a%2500b.png', 403, 'a doubly encoded NUL'],
    ['/rest/repo/file/2026/a%00b.png', 400, 'a NUL'],
    ['/rest/repo/file/2026/a%%20b.png', 400, 'a percent that begins no escape'],
    ['/rest/repo/file/2026//a.png', 403, 'a doubled slash nginx merges'],
    ['/rest/repo//file/2026/a.png', 403, 'a doubled slash before the file path'],
    ['/rest/repo/file/2026/', 403, 'a folder, an empty name'],
    ['/rest/repo/%66ile/2026/a.png', 403, 'an encoded letter nginx decodes into the path'],
    [
      '/rest/public/v1/%2e%2e/%2e%2e/repo/file/2026/a.png',
      403,
      'a line nginx normalises into the repository path',
    ],
    ['/rest/repo/file/2026/a^b.png', 403, 'a character outside RFC 3986, kept raw by a browser'],
    ['/rest/repo/file/2026/a[1].png', 403, 'brackets, kept raw by a browser'],
    ['/rest/repo/file/2026/æ.png', 403, 'a raw letter outside ASCII'],
    ['/rest/repo/file/2026/a%3Bb.png?ticket=abc', 403, 'a ticket beside an encoded semicolon'],
  ];
  for (const [url, expected, why] of refusedRepoLines) {
    const before = seen.length;
    const response = await request(port, 'GET', url);
    check(
      `GET ${url} is refused with ${expected} and never reaches the backend`,
      response.status === expected && seen.length === before,
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s)` +
        (seen.length === before ? '' : `, the last ${lastSeen()?.url}`),
    );
  }

  // ---- a one-time ticket in the query ----------------------------------------------------------
  const refusedTickets = [
    ['/rest/public/v1/messages?lang=da&ticket=abc', 'the public API'],
    ['/rest/public/v1/messages?ticket=abc&lang=da', 'the ticket named first'],
    ['/rest/public/v1/messages?lang=da&%74icket=abc', 'a percent-encoded letter in the name'],
    ['/rest/public/v1/messages?lang=da&tic%6Bet=abc', 'an upper-case escape'],
    ['/rest/settings/web?ticket=abc', 'the settings'],
    ['/rest/repo/file/2026/a.png?ticket=abc', 'a repository file'],
    ['/rest/message-reports/report.pdf?lang=da&report=public&ticket=abc', 'the list PDF'],
    ['/rest/message-reports/message/NW-333-26.pdf?lang=da&ticket=abc', 'one message PDF'],
  ];
  for (const [url, why] of refusedTickets) {
    const before = seen.length;
    const response = await request(port, 'GET', url);
    check(
      `GET ${url} is refused with 403 and never reaches the backend`,
      response.status === 403 && seen.length === before,
      `${why}; got ${response.status}, stub saw ${seen.length - before} request(s)`,
    );
  }

  // ---- the URLs of the site this one replaces --------------------------------------------------
  // The Location is the path alone: an absolute one would be built from this container's own
  // http scheme, which a browser refuses to follow from an https page's frame.
  const teaser = await request(port, 'GET', '/teaser.html');
  check(
    'GET /teaser.html redirects to /teaser, relatively',
    teaser.status === 301 && teaser.headers.location === '/teaser',
    `got ${teaser.status} ${teaser.headers.location}`,
  );
  const details = await request(port, 'GET', '/details.html');
  check(
    'GET /details.html redirects to /messages/print, relatively',
    details.status === 301 && details.headers.location === '/messages/print',
    `got ${details.status} ${details.headers.location}`,
  );

  // ---- the application ------------------------------------------------------------------------
  const before = seen.length;
  const route = await request(port, 'GET', '/messages/map');
  check(
    'a router path is answered by the application, not by the backend',
    route.status === 200 &&
      String(route.headers['content-type']).includes('text/html') &&
      route.body.includes('<app-root') &&
      seen.length === before,
    `got ${route.status} ${route.headers['content-type']}, stub saw ${seen.length - before} request(s)`,
  );

  const index = await request(port, 'GET', '/');
  check(
    'the application shell is never cached',
    index.status === 200 && String(index.headers['cache-control']).includes('no-store'),
    `got ${index.status}, cache-control: ${index.headers['cache-control']}`,
  );

  // ---- a frame that asks for the root ------------------------------------------------------------
  // A page that embeds the teaser by the hash-routed site's address asks for the root: the
  // fragment that names the teaser never reaches the server. A frame's request for the root is
  // given the teaser frame document, which shows nothing and takes the teaser's policy; a reader's
  // is given the application, as always. Sec-Fetch-Dest says which, in either spelling of a frame.
  const FRAME = { 'sec-fetch-dest': 'iframe' };
  const rootInFrame = await request(port, 'GET', '/', FRAME);
  const rootInFrameWithQuery = await request(port, 'GET', '/?lang=da', FRAME);
  const rootInFrameset = await request(port, 'GET', '/', { 'sec-fetch-dest': 'frame' });
  const rootAsDocument = await request(port, 'GET', '/', { 'sec-fetch-dest': 'document' });
  const frameDocument = (response) =>
    response.status === 200 &&
    response.body.includes('src="/teaser-frame.js"') &&
    !response.body.includes('<app-root');
  const application = (response) => response.status === 200 && response.body.includes('<app-root');
  for (const [what, response] of [
    ['an iframe', rootInFrame],
    ['an iframe, with a query', rootInFrameWithQuery],
    ['a frame', rootInFrameset],
  ]) {
    check(
      `${what} that asks for the root is given the teaser frame document, not the application`,
      frameDocument(response),
      `got ${response.status}: ${response.body.toString('utf8').slice(0, 120)}`,
    );
  }
  for (const [what, response] of [
    ['a request that names no destination', index],
    ['a request for a document', rootAsDocument],
  ]) {
    check(
      `${what} for the root is given the application`,
      application(response),
      `got ${response.status}: ${response.body.toString('utf8').slice(0, 120)}`,
    );
  }
  check(
    'the root says that its answer depends on Sec-Fetch-Dest, and is never cached',
    [index, rootInFrame].every(
      (response) =>
        /sec-fetch-dest/i.test(headerLines(response, 'vary').join(',')) &&
        String(response.headers['cache-control']).includes('no-store'),
    ),
    `vary: ${JSON.stringify(headerLines(index, 'vary'))} and ` +
      `${JSON.stringify(headerLines(rootInFrame, 'vary'))}, cache-control: ` +
      `${index.headers['cache-control']} and ${rootInFrame.headers['cache-control']}`,
  );
  const frameScript = await request(port, 'GET', '/teaser-frame.js');
  check(
    "the teaser frame document's script is served as a script",
    frameScript.status === 200 &&
      /javascript/.test(String(frameScript.headers['content-type'])) &&
      frameScript.body.includes('teaserFrameTarget'),
    `got ${frameScript.status} ${frameScript.headers['content-type']}`,
  );

  const bundle = await request(port, 'GET', `/assets/i18n/${options.language}.json`);
  check(
    'an unhashed asset revalidates',
    bundle.status === 200 && String(bundle.headers['cache-control']).includes('max-age=300'),
    `got ${bundle.status}, cache-control: ${bundle.headers['cache-control']}`,
  );

  const missing = await request(port, 'GET', '/assets/legal/does-not-exist.xx.html');
  check(
    'a missing asset is a 404 and not the application',
    missing.status === 404 && !missing.body.includes('<app-root'),
    `got ${missing.status}; the legal dialog reads a 200 containing <app-root as a miss, but a ` +
      'bundle or a branding file would be parsed as the application',
  );
  // A rolling update can hand a reader a page that asks one pod for an asset only another has.
  check(
    'a missing asset is a 404 that nothing may keep',
    missing.status === 404 && headerLines(missing, 'cache-control').join() === 'no-store',
    `cache-control: ${JSON.stringify(headerLines(missing, 'cache-control'))}`,
  );

  const NO_LEGAL = 'the image ships no legal document (--no-legal-doc)';
  const NO_BRANDING = `the image ships no assets/branding/${options.language}.json (--no-branding-text)`;
  const sandboxProbe = (path) =>
    `${path} is served with Content-Security-Policy: sandbox and not the site's policy`;
  const sandboxed = [];
  if (options.noLegalDoc) {
    skip('an existing legal document is served as a file', NO_LEGAL);
    skip(sandboxProbe('/assets/legal/<legal document>'), NO_LEGAL);
  } else {
    const legal = await request(port, 'GET', `/assets/legal/${options.legalDoc}`);
    check(
      `an existing legal document is served as a file (${options.legalDoc})`,
      legal.status === 200 && !legal.body.includes('<app-root'),
      `got ${legal.status}, ${legal.body.length} bytes`,
    );
    sandboxed.push([`/assets/legal/${options.legalDoc}`, legal]);
  }

  // A legal document or branding file is a country's data and not the application, so it is
  // sandboxed rather than given the site's policy. nginx decodes the path before it chooses a
  // location, so a percent-encoded letter reaches the same location and is sandboxed as well.
  for (const [path, missing] of [
    ['/assets/branding/favicon.png', undefined],
    [`/assets/branding/${options.language}.json`, options.noBrandingText ? NO_BRANDING : undefined],
    ['/assets/%6cegal/<legal document>', options.noLegalDoc ? NO_LEGAL : undefined],
  ]) {
    if (missing) skip(sandboxProbe(path), missing);
    else {
      const asked = path.replace('<legal document>', options.legalDoc);
      sandboxed.push([asked, await request(port, 'GET', asked)]);
    }
  }
  for (const [path, response] of sandboxed) {
    const problems = sandboxedFileProblems(response);
    check(sandboxProbe(path), problems.length === 0, problems.join('; '));
  }

  // A miss there is answered by the same named location as any asset's, with the site's policy.
  const missingBranding = await request(port, 'GET', '/assets/branding/does-not-exist.png');
  check(
    'a missing branding file is a 404 that nothing may keep',
    missingBranding.status === 404 &&
      headerLines(missingBranding, 'cache-control').join() === 'no-store',
    `got ${missingBranding.status}, cache-control: ` +
      JSON.stringify(headerLines(missingBranding, 'cache-control')),
  );

  // ---- the security headers --------------------------------------------------------------------
  // Every location that sets a header of its own must include them again, or nginx drops the
  // server's; so each kind of location is asked once, error responses included.
  const shellResponse = await request(port, 'GET', '/index.html');
  const pages = [
    ['/', index, "'self'"],
    ['/messages/map', route, "'self'"],
    ['/index.html', shellResponse, "'self'"],
    [`/assets/i18n/${options.language}.json`, bundle, "'self'"],
    ['/assets/legal/does-not-exist.xx.html (404)', missing, "'self'"],
    ['/assets/branding/does-not-exist.png (404)', missingBranding, "'self'"],
    ['/teaser', await request(port, 'GET', '/teaser'), TEASER_FRAME_ANCESTORS],
    ['/teaser?lang=da', await request(port, 'GET', '/teaser?lang=da'), TEASER_FRAME_ANCESTORS],
    ['/teaser/?lang=da', await request(port, 'GET', '/teaser/?lang=da'), TEASER_FRAME_ANCESTORS],
    ['/teaser in an iframe', await request(port, 'GET', '/teaser', FRAME), TEASER_FRAME_ANCESTORS],
    ['/teasers (not the teaser)', await request(port, 'GET', '/teasers'), "'self'"],
    // The router moves a path below the teaser on to the message list.
    ['/teaser/x (below the teaser)', await request(port, 'GET', '/teaser/x'), "'self'"],
    ['/teaser/x in an iframe', await request(port, 'GET', '/teaser/x', FRAME), "'self'"],
    ['/teaser.html (301)', teaser, "'self'"],
    ['/ in an iframe (the teaser frame document)', rootInFrame, TEASER_FRAME_ANCESTORS],
    ['/?lang=da in an iframe', rootInFrameWithQuery, TEASER_FRAME_ANCESTORS],
    ['/ in a frame', rootInFrameset, TEASER_FRAME_ANCESTORS],
    ['/ as a document', rootAsDocument, "'self'"],
    // Every other page in a frame is the application, and stays out of another site's frame.
    ['/messages/map in an iframe', await request(port, 'GET', '/messages/map', FRAME), "'self'"],
    ['/index.html in an iframe', await request(port, 'GET', '/index.html', FRAME), "'self'"],
    [
      '/teaser-frame.html by its own name, in an iframe',
      await request(port, 'GET', '/teaser-frame.html', FRAME),
      "'self'",
    ],
    ['/rest/messages/search (404)', await request(port, 'GET', '/rest/messages/search'), "'self'"],
    ['DELETE /rest/public/v1/x (403)', await request(port, 'DELETE', '/rest/public/v1/x')],
  ];
  for (const [what, response, frameAncestors = "'self'"] of pages) {
    const problems = response ? securityHeaderProblems(response, frameAncestors) : ['no response'];
    check(
      `${what} carries the security headers, frame-ancestors ${frameAncestors}`,
      problems.length === 0,
      problems.join('; '),
    );
  }

  // The policy allows the site's own script files and nothing else, so neither document the site
  // serves may carry an inline script: a script element without a src would be blocked in the
  // reader's browser.
  const shell = shellResponse.body.toString('utf8');
  for (const [what, html] of [
    ['index.html', shell],
    ['the teaser frame document', rootInFrame.body.toString('utf8')],
  ]) {
    const inlineScripts = (html.match(/<script\b[^>]*>/gi) ?? []).filter(
      (tag) => !/\bsrc=/i.test(tag),
    );
    check(
      `${what} carries no inline script`,
      inlineScripts.length === 0,
      `found ${inlineScripts.join(', ')}`,
    );
  }

  // ---- caching and compression of the builder's own output -------------------------------------
  // The builder writes src before type, so the two attributes are matched apart and not in order.
  const moduleTag = (shell.match(/<script\b[^>]*>/gi) ?? []).find((tag) =>
    /type=["']module["']/i.test(tag),
  );
  const script = moduleTag?.match(/src=["']([^"']+)["']/i)?.[1];
  check('index.html names a module script', Boolean(script), `parsed: ${script}`);

  // A build file that is not there must never be cached: during a rolling update a reader can be
  // handed a chunk name by one pod that another pod does not have yet.
  const missingBuildFiles = ['/main-ZZZZZZZZ.js', '/styles-ZZZZZZZZ.css', '/media/missing.woff2'];
  for (const path of missingBuildFiles) {
    const response = await request(port, 'GET', path);
    const cacheControl = headerLines(response, 'cache-control');
    check(
      `a missing build file (${path}) is a 404 that nothing may keep`,
      response.status === 404 &&
        cacheControl.length === 1 &&
        cacheControl[0] === 'no-store' &&
        securityHeaderProblems(response).length === 0,
      `got ${response.status}, cache-control: ${JSON.stringify(cacheControl)}; ` +
        securityHeaderProblems(response).join('; '),
    );
  }

  if (script) {
    const path = script.startsWith('/') ? script : `/${script}`;
    const plain = await request(port, 'GET', path);
    const cacheControl = headerLines(plain, 'cache-control');
    check(
      'a hashed chunk is cached forever, by one Cache-Control header and no Expires',
      plain.status === 200 &&
        cacheControl.length === 1 &&
        cacheControl[0] === 'public, max-age=31536000, immutable' &&
        plain.headers.expires === undefined,
      `${path}: ${plain.status}, cache-control: ${JSON.stringify(cacheControl)}, ` +
        `expires: ${plain.headers.expires}`,
    );
    check(
      'a hashed chunk carries the security headers',
      securityHeaderProblems(plain).length === 0,
      securityHeaderProblems(plain).join('; '),
    );

    const brotli = await request(port, 'GET', path, { 'accept-encoding': 'br' });
    check(
      'a hashed chunk is served pre-compressed with brotli',
      brotli.status === 200 &&
        brotli.headers['content-encoding'] === 'br' &&
        brotli.body.length < plain.body.length,
      `${path}: ${brotli.body.length} B brotli against ${plain.body.length} B raw, ` +
        `content-encoding: ${brotli.headers['content-encoding']}`,
    );
    check(
      'the brotli response varies on Accept-Encoding',
      String(brotli.headers.vary).toLowerCase().includes('accept-encoding'),
      `vary: ${brotli.headers.vary}`,
    );

    const gzip = await request(port, 'GET', path, { 'accept-encoding': 'gzip' });
    check(
      'a client that speaks only gzip gets gzip, not the raw file',
      gzip.status === 200 &&
        gzip.headers['content-encoding'] === 'gzip' &&
        gzip.body.length < plain.body.length,
      `${path}: ${gzip.body.length} B gzip against ${plain.body.length} B raw, ` +
        `content-encoding: ${gzip.headers['content-encoding']}`,
    );

    const browser = await request(port, 'GET', path, {
      'accept-encoding': 'gzip, deflate, br, zstd',
    });
    check(
      'a browser that offers both is given brotli',
      browser.headers['content-encoding'] === 'br',
      `content-encoding: ${browser.headers['content-encoding']}`,
    );
  }

  const shellGzip = await request(port, 'GET', '/index.html', { 'accept-encoding': 'gzip' });
  check(
    'index.html is pre-compressed as well',
    shellGzip.status === 200 && shellGzip.headers['content-encoding'] === 'gzip',
    `content-encoding: ${shellGzip.headers['content-encoding']}`,
  );

  // ---- the sea chart ---------------------------------------------------------------------------
  const wmsBefore = seen.length;
  const wms = await request(port, 'GET', '/wms/cells?x=1');
  check(
    'an unconfigured WMS answers empty instead of failing',
    wms.status === 200 && wms.body.length === 0 && seen.length === wmsBefore,
    `got ${wms.status}, ${wms.body.length} bytes, stub saw ${seen.length - wmsBefore} request(s)`,
  );

  // ---- Strict-Transport-Security ---------------------------------------------------------------
  // Sent where a TLS front reports https, once and with this host's value, by every kind of
  // location: the ones that include the security headers, the sandboxed ones that include the
  // common headers, the repository files that write theirs out, the returns and the proxied
  // ones. The stub sends a copy of its own on every response, as the backend does, which must not
  // pass. Over plain HTTP, or with a scheme that is neither, there is none.
  const HTTPS = { 'x-forwarded-proto': 'https' };
  const HSTS = 'max-age=31536000';
  const hstsProxied = [];
  const hstsRequest = async (path, headers) => {
    const response = await request(port, 'GET', path, headers);
    if (/^\/rest\/(public\/v1|repo\/file)\//.test(path) && !path.includes('ticket=')) {
      hstsProxied.push(`GET ${path}`);
    }
    return response;
  };
  const hstsPaths = [
    ['the root', '/'],
    ['the application shell', '/index.html'],
    ['a router path', '/messages/map'],
    ['the teaser', '/teaser'],
    ['an unhashed asset', `/assets/i18n/${options.language}.json`],
    ['a branding file (sandboxed)', '/assets/branding/favicon.png'],
    ['a missing asset (404)', '/assets/legal/does-not-exist.xx.html'],
    ['a missing build file (404)', '/main-ZZZZZZZZ.js'],
    ['a redirect (301)', '/teaser.html'],
    ['a path that does not exist on this host (404)', '/rest/messages/search'],
    ['Keycloak (404)', '/auth/realms/niord'],
    ['the empty sea chart', '/wms/cells?x=1'],
    ['the proxied public API', '/rest/public/v1/messages?lang=da'],
    ['a proxied repository file (its headers written out)', '/rest/repo/file/2026/a.png'],
    [
      'a ticket refused in the repository-file location (403)',
      '/rest/repo/file/2026/a.png?ticket=abc',
    ],
  ];
  if (script) hstsPaths.push(['a hashed chunk', script.startsWith('/') ? script : `/${script}`]);
  if (!options.noLegalDoc) {
    hstsPaths.push(['a legal document (sandboxed)', `/assets/legal/${options.legalDoc}`]);
  }
  for (const [what, path] of hstsPaths) {
    const response = await hstsRequest(path, HTTPS);
    const lines = headerLines(response, 'strict-transport-security');
    check(
      `over https, ${what} carries one Strict-Transport-Security, ${HSTS}`,
      lines.length === 1 && lines[0] === HSTS,
      `GET ${path}: ${response.status}, strict-transport-security: ${JSON.stringify(lines)}`,
    );
  }
  for (const [what, path, headers] of [
    ['the root, with no X-Forwarded-Proto', '/', {}],
    ['the root, over http', '/', { 'x-forwarded-proto': 'http' }],
    ['the root, with a scheme that is neither', '/', { 'x-forwarded-proto': 'gopher' }],
    ['the proxied public API, over http', '/rest/public/v1/messages?lang=en', {}],
    ['a proxied repository file, over http', '/rest/repo/file/2026/a.png', {}],
  ]) {
    const response = await hstsRequest(path, headers);
    const lines = headerLines(response, 'strict-transport-security');
    check(
      `${what} carries no Strict-Transport-Security, the backend's included`,
      response.status === 200 && lines.length === 0,
      `GET ${path}: ${response.status}, strict-transport-security: ${JSON.stringify(lines)}`,
    );
  }

  // ---- and nothing else ever reached the backend -----------------------------------------------
  const expected = [
    'GET /rest/public/v1/messages?lang=da',
    'GET /rest/public/v1/messages?lang=en',
    'GET /rest/public/v1/messages?lang=da&myticket=1&q=ticket',
    'GET /rest/settings/web',
    'GET /rest/repo/file/2026/a.png',
    ...repoFiles.map(([path]) => `GET ${path}`),
    'GET /rest/message-reports/report.pdf?lang=da&report=public&mainType=NW&area=453&messageSeries=xx-nw',
    'GET /rest/message-reports/message/Local%20Warning-120-26.pdf?lang=da',
    'GET /rest/message-reports/report.pdf?lang=da&report=public&messageSeries=busy',
    'GET /rest/message-reports/message/busy-1-26.pdf?lang=da',
    'GET /rest/message-reports/message/gone-1-26.pdf?lang=da',
    'GET /rest/message-reports/message/Lokal%C3%86-7-26.pdf?lang=da',
    ...messageIdUrls.map(([url]) => `GET ${url}`),
    ...repoFileNames.map(([method, url]) => `${method} ${url}`),
    'HEAD /rest/settings/web',
    ...acceptedRequests,
    ...hstsProxied,
  ];
  const actual = seen.map((entry) => `${entry.method} ${entry.url}`);
  check(
    `the backend was asked for exactly the ${expected.length} allow-listed requests`,
    actual.length === expected.length && expected.every((entry, i) => actual[i] === entry),
    `stub log:\n      ${actual.join('\n      ') || '(empty)'}`,
  );
}

/**
 * An https backend whose certificate nothing trusts: the container must refuse it — no request
 * reaches it — and must have asked for the backend by name while trying (SNI), which is what a
 * backend behind a shared TLS front needs to answer with the right certificate.
 */
async function runAgainstUntrustedTls(tlsPort) {
  const container = startContainer({ NIORD_SERVER_URL: `https://${UPSTREAM_HOST}:${tlsPort}` });
  try {
    const port = publishedPort(container);
    await waitForSite(port);
    const response = await request(port, 'GET', '/rest/settings/web');
    check(
      'an https backend with a certificate nothing trusts is refused',
      response.status === 502 && tlsSeen.requests === 0,
      `got ${response.status}, the TLS stub answered ${tlsSeen.requests} request(s)`,
    );
    check(
      'the backend is asked for by name (SNI)',
      tlsSeen.serverNames.includes(UPSTREAM_HOST),
      `server names the TLS stub saw: ${JSON.stringify(tlsSeen.serverNames)}`,
    );
  } finally {
    removeContainer(container);
  }
}

/**
 * The address the container reaches this process by, as its /etc/hosts maps UPSTREAM_HOST. The
 * /wms/ location resolves a name only through the resolvers it lists, which a test container does
 * not have, so the chart server is named by this address instead. An IPv4 address is preferred,
 * since Docker may map the name to both.
 */
function upstreamAddress(container) {
  const addresses = docker(['exec', container, 'cat', '/etc/hosts'])
    .split('\n')
    .map((line) => line.trim().split(/\s+/))
    .filter(([address, ...names]) => !address.startsWith('#') && names.includes(UPSTREAM_HOST))
    .map(([address]) => address);
  const address = addresses.find((candidate) => !candidate.includes(':')) ?? addresses[0];
  if (!address) throw new Error(`the container's /etc/hosts does not map ${UPSTREAM_HOST}`);
  return address.includes(':') ? `[${address}]` : address;
}

/**
 * A configured chart server. The /wms/ location is dormant in the base build, but a country that
 * sets WMS_SERVER_URL makes it a proxied path like the others: no credential a client wrote
 * reaches the chart server, and an https one whose certificate nothing trusts is refused.
 */
async function runWithChartServer(stubPort, tlsPort, address) {
  const backend = `http://${UPSTREAM_HOST}:${stubPort}`;
  let container = startContainer({
    NIORD_SERVER_URL: backend,
    WMS_SERVER_URL: `http://${address}:${stubPort}`,
  });
  try {
    const port = publishedPort(container);
    await waitForSite(port);
    const before = seen.length;
    const response = await request(port, 'GET', '/wms/cells?x=1', {
      authorization: 'Bearer t',
      'proxy-authorization': 'Basic c3Bvb2Y6c3Bvb2Y=',
      cookie: 'a=b',
      niorddomain: 'niord-client-nw',
    });
    const tile = seen.length > before ? lastSeen() : undefined;
    check(
      'GET /wms/ is proxied to a configured chart server',
      response.status === 200 && tile?.url === '/wms/cells?x=1',
      `got ${response.status}, stub saw ${tile ? `${tile.method} ${tile.url}` : 'nothing'}`,
    );
    const leaked = ['authorization', 'proxy-authorization', 'cookie', 'niorddomain'].filter(
      (name) => tile?.headers[name] !== undefined,
    );
    check(
      'no Authorization, Proxy-Authorization, Cookie or NiordDomain reaches the chart server',
      tile !== undefined && leaked.length === 0,
      tile
        ? `stub saw ${leaked.map((name) => `${name}: ${tile.headers[name]}`).join(', ')}`
        : 'the chart server was never asked',
    );
  } finally {
    removeContainer(container);
  }

  container = startContainer({
    NIORD_SERVER_URL: backend,
    WMS_SERVER_URL: `https://${address}:${tlsPort}`,
  });
  try {
    const port = publishedPort(container);
    await waitForSite(port);
    const before = tlsSeen.requests;
    const response = await request(port, 'GET', '/wms/cells?x=1');
    check(
      'an https chart server with a certificate nothing trusts is refused',
      response.status === 502 && tlsSeen.requests === before,
      `got ${response.status}, the TLS stub answered ${tlsSeen.requests - before} request(s)`,
    );
  } finally {
    removeContainer(container);
  }
}

/**
 * The frame policy and the tiles as a browser applies them (--browser). Chromium opens a page this
 * process serves on one loopback port, which frames the container's site on another. Two ports
 * are two origins, so the page is another site to the container; both are on loopback, so no rule
 * of the browser's about reaching the local network stands between them; and the browser sends
 * Sec-Fetch-Dest to its own machine as it does to an https site. The container is told that page
 * may frame the teaser. An image off the two origins — a map tile — is answered with a blank one
 * and every other request off them is refused, so nothing leaves the machine.
 */
async function runInBrowser(stubPort) {
  let chromium;
  try {
    ({ chromium } = await import('@playwright/test'));
  } catch (error) {
    throw new Error(`--browser needs Playwright (npm ci in fe/): ${error.message}`, {
      cause: error,
    });
  }

  let siteOrigin = '';
  const embedder = http.createServer((req, res) => {
    const src = new URL(req.url, 'http://embedder.invalid').searchParams.get('src') ?? '/';
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(
      `<!doctype html><html lang="en"><title>Another site</title>` +
        `<iframe title="Embedded" src="${siteOrigin}${src}" width="800" height="600"></iframe>`,
    );
  });
  const embedderPort = await new Promise((resolve, reject) => {
    embedder.on('error', reject);
    embedder.listen(0, '127.0.0.1', () => resolve(embedder.address().port));
  });
  const embedderOrigin = `http://127.0.0.1:${embedderPort}`;
  const container = startContainer({
    NIORD_SERVER_URL: `http://${UPSTREAM_HOST}:${stubPort}`,
    NIORD_TEASER_FRAME_ANCESTORS: embedderOrigin,
  });
  const port = publishedPort(container);
  siteOrigin = `http://127.0.0.1:${port}`;
  let browser;
  try {
    await waitForSite(port);
    try {
      browser = await chromium.launch();
    } catch (error) {
      throw new Error(
        `Chromium did not start (npx playwright install chromium): ${error.message}`,
        { cause: error },
      );
    }
    const context = await browser.newContext();
    let tiles = 0;
    await context.route(
      (url) => url.origin !== siteOrigin && url.origin !== embedderOrigin,
      (route) => {
        if (route.request().resourceType() !== 'image') return route.abort();
        tiles++;
        return route.fulfill({
          status: 200,
          contentType: 'image/png',
          headers: { 'access-control-allow-origin': '*' },
          body: PNG,
        });
      },
    );
    const violations = [];
    await context.exposeBinding('niordContainerTestCspViolation', ({ frame }, violation) => {
      violations.push({ ...violation, page: frame.url() });
    });
    await context.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (event) =>
        window.niordContainerTestCspViolation({
          directive: event.effectiveDirective,
          blocked: event.blockedURI,
        }),
      );
    });
    const page = await context.newPage();

    /** What the frame on the embedding page shows now. */
    async function framed() {
      const frame = page.mainFrame().childFrames()[0];
      const url = frame?.url() ?? '';
      if (!frame || !url.startsWith(siteOrigin)) return { url };
      try {
        return {
          url,
          ...(await frame.evaluate(() => ({
            ready: document.readyState === 'complete',
            teaser: Boolean(document.querySelector('app-teaser')),
            application: Boolean(document.querySelector('app-root')),
            otherPage: Boolean(
              document.querySelector('app-messages, app-message-map, app-publications'),
            ),
            text: document.body?.innerText.trim() ?? '',
          }))),
        };
      } catch {
        return { url };
      }
    }

    /** Polls the frame until `done` holds or the time is up, and answers what it showed last. */
    async function frameUntil(done, ms) {
      const deadline = Date.now() + ms;
      let state = await framed();
      while (!done(state) && Date.now() < deadline) {
        await sleep(200);
        state = await framed();
      }
      return state;
    }

    // [the address the embedding page frames, what the frame must end up showing, where]
    const frames = [
      ['/teaser?lang=da', 'the teaser', '/teaser?lang=da'],
      ['/#/teaser?lang=da', 'the teaser', '/teaser?lang=da'],
      ['/?lang=da#/teaser', 'the teaser', '/teaser?lang=da'],
      ['/teaser.html#?lang=da', 'the teaser', '/teaser?lang=da'],
      ['/teaser#/messages/map', 'the teaser', '/teaser#/messages/map'],
      ['/#/messages/map', 'nothing', '/#/messages/map'],
      ['/#/publications', 'nothing', '/#/publications'],
      ['/messages/details', 'a refusal'],
      ['/teaser/x', 'a refusal'],
    ];
    for (const [src, expected, at] of frames) {
      await page.goto(`${embedderOrigin}/?src=${encodeURIComponent(src)}`);
      let state;
      let ok;
      if (expected === 'the teaser') {
        state = await frameUntil((s) => s.teaser, 20000);
        ok = state.teaser && !state.otherPage && state.url === `${siteOrigin}${at}`;
      } else if (expected === 'nothing') {
        state = await frameUntil((s) => s.ready, 10000);
        // Long enough for a script that would move the frame on to have done so.
        await sleep(1500);
        state = await framed();
        ok =
          state.ready &&
          !state.application &&
          state.text === '' &&
          state.url === `${siteOrigin}${at}`;
      } else {
        state = await frameUntil((s) => s.url.startsWith('chrome-error:'), 10000);
        ok = state.url.startsWith('chrome-error:');
      }
      check(
        `in a frame on another site, ${src} shows ${expected}`,
        ok,
        `the frame shows ${JSON.stringify(state)}`,
      );
    }

    await page.goto(`${siteOrigin}/`);
    const root = await page
      .waitForFunction(() => document.querySelector('app-root')?.children.length > 0, null, {
        timeout: 20000,
      })
      .then(() => true)
      .catch(() => false);
    check('a reader who opens the root is given the application, in a browser', root);

    for (const [route, selector] of [
      ['/teaser', 'app-teaser'],
      ['/messages/map', 'app-message-map'],
    ]) {
      tiles = 0;
      const before = violations.length;
      await page.goto(`${siteOrigin}${route}`);
      await page.waitForSelector(selector, { timeout: 20000 }).catch(() => undefined);
      const deadline = Date.now() + 10000;
      while (tiles === 0 && violations.length === before && Date.now() < deadline) await sleep(200);
      await sleep(500);
      const raised = violations
        .slice(before)
        .map((v) => `${v.directive} refused ${v.blocked || 'inline content'}`);
      check(
        `over plain HTTP, ${route} asks for base-map tiles and breaks none of the site's policy`,
        tiles > 0 && raised.length === 0,
        `${tiles} tile(s) asked for; ${raised.join(', ') || 'no violation'}`,
      );
    }

    // Opened on its own, a legal document is sandboxed: its origin is opaque, so even a script
    // the browser let run in it could reach nothing of this origin.
    if (options.noLegalDoc) {
      skip(
        'opened on its own, a legal document has an opaque origin, in a browser',
        'the image ships no legal document (--no-legal-doc)',
      );
    } else {
      const legalResponse = await page.goto(`${siteOrigin}/assets/legal/${options.legalDoc}`);
      const legalOrigin = await page.evaluate(() => self.origin).catch((error) => error.message);
      check(
        `opened on its own, ${options.legalDoc} has an opaque origin, in a browser`,
        legalResponse?.status() === 200 && legalOrigin === 'null',
        `got ${legalResponse?.status()}, self.origin: ${legalOrigin}`,
      );
    }
  } finally {
    await browser?.close();
    removeContainer(container);
    embedder.close();
  }
}

/**
 * Configurations the container must refuse before nginx starts, each with a message that names
 * the variable at fault.
 */
async function runRefusals(stubPort) {
  const backend = `http://${UPSTREAM_HOST}:${stubPort}`;
  const refusals = [
    [{}, 'NIORD_SERVER_URL', 'no backend address at all: the image has no default'],
    [{ NIORD_SERVER_URL: '' }, 'NIORD_SERVER_URL', 'an empty backend address'],
    [{ NIORD_SERVER_URL: `${backend}/niord` }, 'NIORD_SERVER_URL', 'a backend address with a path'],
    [{ NIORD_SERVER_URL: `${backend}?x=1` }, 'NIORD_SERVER_URL', 'a backend address with a query'],
    [
      { NIORD_SERVER_URL: `http://user:secret@${UPSTREAM_HOST}:${stubPort}` },
      'NIORD_SERVER_URL',
      'a backend address with credentials',
    ],
    [
      { NIORD_SERVER_URL: `ftp://${UPSTREAM_HOST}` },
      'NIORD_SERVER_URL',
      'a scheme nginx cannot use',
    ],
    [
      { NIORD_SERVER_URL: `${backend};` },
      'NIORD_SERVER_URL',
      'a character that would end the nginx directive',
    ],
    [
      { NIORD_SERVER_URL: backend, NIORD_TEASER_FRAME_ANCESTORS: '' },
      'NIORD_TEASER_FRAME_ANCESTORS',
      'no pages allowed to embed the teaser, not even none',
    ],
    [
      { NIORD_SERVER_URL: backend, NIORD_TEASER_FRAME_ANCESTORS: "* ; script-src 'unsafe-inline'" },
      'NIORD_TEASER_FRAME_ANCESTORS',
      'a value that would add a directive to the policy',
    ],
    [
      { NIORD_SERVER_URL: backend, NIORD_TEASER_FRAME_ANCESTORS: '"*"' },
      'NIORD_TEASER_FRAME_ANCESTORS',
      'a value that would end the quoted nginx string',
    ],
  ];
  for (const [env, variable, why] of refusals) {
    const outcome = await runToRefusal(env);
    check(
      `the container refuses to start with ${why}`,
      !outcome.running && outcome.exitCode !== 0 && outcome.log.includes(variable),
      `${outcome.running ? 'still running' : `exit ${outcome.exitCode}`}; log: ${outcome.log}`,
    );
  }
}

/**
 * What the image declares about how it runs: its user, which the runs above take as it is, and
 * its port. The container must also be running as that user, not merely declare it.
 */
function runImageConfig(container) {
  const user = docker(['image', 'inspect', '-f', '{{.Config.User}}', options.image]);
  check(
    `the image runs as the unprivileged user ${IMAGE_USER}, given by number`,
    user === IMAGE_USER,
    `Config.User is "${user}"`,
  );
  const ports = docker(['image', 'inspect', '-f', '{{json .Config.ExposedPorts}}', options.image]);
  check(
    `the image exposes port ${CONTAINER_PORT} alone`,
    JSON.stringify(Object.keys(JSON.parse(ports || '{}'))) === `["${CONTAINER_PORT}/tcp"]`,
    `Config.ExposedPorts is ${ports}`,
  );
  const running = docker(['exec', container, 'id', '-u']);
  check(
    `the container runs as uid ${IMAGE_USER.split(':')[0]}`,
    running === IMAGE_USER.split(':')[0],
    `id -u in the container is ${running}`,
  );
}

// ---------------------------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------------------------

let container = '';
let exitCode = 0;
try {
  const stubPort = await listen(stub);
  const tlsPort = await listen(tlsStub);
  // The trailing slash is how an address is often copied, and the image drops it.
  container = startContainer({
    NIORD_SERVER_URL: `http://${UPSTREAM_HOST}:${stubPort}/`,
    WMS_SERVER_URL: '',
    NIORD_TEASER_FRAME_ANCESTORS: TEASER_FRAME_ANCESTORS,
  });
  const port = publishedPort(container);
  console.log(
    `info-container-test: ${options.image} on http://127.0.0.1:${port}, stub upstream on ${stubPort}`,
  );

  await waitForSite(port);
  runImageConfig(container);
  const chartServerAddress = upstreamAddress(container);
  await run(port, stubPort);
  if (options.browser) await runInBrowser(stubPort);
  await runAgainstUntrustedTls(tlsPort);
  await runWithChartServer(stubPort, tlsPort, chartServerAddress);
  await runRefusals(stubPort);

  const failures = results.filter((result) => !result.ok);
  console.log('');
  for (const result of results) console.log(`  ${result.ok ? 'ok  ' : 'FAIL'}  ${result.name}`);
  for (const { name, reason } of skipped) console.log(`  skip  ${name} (${reason})`);
  const skips = skipped.length ? `; ${skipped.length} skipped, as named above` : '';
  if (failures.length) {
    console.error(`\ninfo-container-test: ${failures.length} of ${results.length} failed${skips}:`);
    for (const failure of failures) console.error(`  ${failure.name}\n      ${failure.detail}`);
    console.error('\ncontainer log:');
    console.error(containerLog(container));
    exitCode = 1;
  } else {
    console.log(`\ninfo-container-test: ${results.length} assertions, all green${skips}.`);
  }
} catch (error) {
  console.error(`info-container-test: ${error.message}`);
  if (container) console.error(containerLog(container));
  exitCode = 2;
} finally {
  if (container && !options.keep) {
    removeContainer(container);
  } else if (container) {
    console.log(`info-container-test: container ${container} left running (--keep)`);
  }
  stub.close();
  tlsStub.close();
}

process.exit(exitCode);
