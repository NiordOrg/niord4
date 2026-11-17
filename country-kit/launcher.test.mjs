/**
 * The country launcher, templates/start-local.sh, run under bash (workflow-step.mjs's bashPath)
 * in a temporary country repository with stand-ins for everything it starts: docker, curl, npm,
 * netstat, lsof, taskkill and sleep on the PATH (fakes as workflow-step.mjs writes them), be/mvnw,
 * fe/setup.mjs and a JAVA_HOME whose java and jar are scripts. The launcher library is the real be/scripts/local-run-lib.sh, in the
 * layout of org.niord:niord-local-run that the stand-in jar unpacks. HOME and USERPROFILE are a
 * temporary folder, so the realm the library stages and the Niord homes never reach the user's.
 * No container, database or server is touched.
 */
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { where } from './lib/where.mjs';
import { bashPath } from '../fe/scripts/workflow-step.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const LAUNCHER = path.join(KIT, 'templates', 'start-local.sh');
const LIBRARY = path.join(where().root, 'be', 'scripts', 'local-run-lib.sh');
const VERSION = '4.0.9';
const slashes = (value) => value.replaceAll('\\', '/');
const quote = (value) => `'${String(value).replaceAll("'", `'\\''`)}'`;
const BASH = spawnSync(bashPath(), ['-c', 'exit 0']).status === 0;
const skipBash = BASH ? false : 'no bash on this machine';
// The launcher refuses a checkout whose path holds whitespace, and the test's country lives
// under the temporary folder.
const skip =
  skipBash ||
  (/\s/.test(os.tmpdir())
    ? "the temporary folder's path holds whitespace, which the launcher refuses"
    : false);

/**
 * A stand-in server: it records its PID in alive-<name> and waits until it is killed, 45 s at
 * most, in a read of a FIFO, which starts no process (process starts are slow on Windows).
 */
const serve = (name) =>
  `echo "$$" >> "$FAKE_DIR/alive-${name}"; mkfifo "$FAKE_DIR/hold-${name}" 2>/dev/null || true; read -r -t 45 <> "$FAKE_DIR/hold-${name}" || true`;

const MVNW = `#!/usr/bin/env bash
printf '%s\\n' "mvnw $* NIORD_HOME=\${NIORD_HOME:-} MAVEN_ARGS=\${MAVEN_ARGS:-}" >> "$FAKE_LOG"
case " $* " in
  *" dependency:copy "*)
    artifact="" out=""
    for arg in "$@"; do
      case "$arg" in
        -Dartifact=*) artifact="\${arg#-Dartifact=}" ;;
        -DoutputDirectory=*) out="\${arg#-DoutputDirectory=}" ;;
      esac
    done
    IFS=: read -r group id version type <<< "$artifact"
    mkdir -p "$out" && printf 'zip' > "$out/$id-$version.$type" ;;
  *" spring-boot:run "*)
    : > "$FAKE_DIR/backend-up"
    ${serve('backend')} ;;
  *) exit 97 ;;
esac
`;

const JAVA = `#!/usr/bin/env bash
echo 'openjdk version "25.0.1" 2025-10-21' >&2
`;

const JAR = `#!/usr/bin/env bash
printf '%s\\n' "jar $*" >> "$FAKE_LOG"
case "$1" in
  xf) cp -R "$FAKE_DIR/local-run/." . ;;
  --create) while (( $# )); do if [ "$1" = --file ]; then : > "$2"; break; fi; shift; done ;;
esac
`;

/** A stand-in script of the zip's that logs its call. */
const logging = (name) => `#!/usr/bin/env bash
printf '%s\\n' "${name} $*" >> "$FAKE_LOG"
`;

/**
 * The stub stand-in: the real stub's parentVersion, and a log line instead of the set-up when it
 * is run. FAKE_SETUP_FAIL makes the set-up refuse.
 */
const setupStub = () => `export { parentVersion } from ${JSON.stringify(
  pathToFileURL(path.join(KIT, 'templates', 'setup.mjs')).href,
)};
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
if (process.argv[1] && fs.realpathSync(path.resolve(process.argv[1])) === fs.realpathSync(fileURLToPath(import.meta.url))) {
  fs.appendFileSync(process.env.FAKE_LOG, 'setup.mjs\\n');
  if (process.env.FAKE_SETUP_FAIL) {
    console.error('setup: refused');
    process.exit(1);
  }
}
`;

const POM = `<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <!-- <artifactId>not-this-one</artifactId> -->
    <parent>
        <groupId>org.niord</groupId>
        <artifactId>niord-parent</artifactId>
        <version>${VERSION}</version>
        <relativePath/>
    </parent>
    <groupId>org.niord.xx</groupId>
    <artifactId>niord-xx</artifactId>
    <dependencies>
        <dependency><groupId>org.niord.base</groupId><artifactId>niord-base</artifactId></dependency>
    </dependencies>
</project>
`;

const SETTINGS =
  '<settings><servers><server><id>github</id><username>u</username><password>p</password></server></servers></settings>\n';

/** A country repository the launcher runs in: `{ dir, root, home, env }`. */
function country({ cc = 'xx', settings = true, startLocal } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-launcher-'));
  const write = (relative, text, mode) => {
    const file = path.join(dir, ...relative.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
    if (mode) fs.chmodSync(file, mode);
  };
  write('repo/start-local.sh', fs.readFileSync(LAUNCHER), 0o755);
  write('repo/fe/niord-country.json', `${JSON.stringify({ country: cc, name: 'Example' })}\n`);
  write('repo/fe/setup.mjs', setupStub());
  write('repo/fe/node_modules/.package-lock.json', '{}\n');
  write('repo/be/pom.xml', POM);
  write('repo/be/mvnw', MVNW, 0o755);
  write('repo/be/bootstrap/batch-set.json', '{}\n');
  if (startLocal !== undefined) write('repo/start-local.json', startLocal);
  write('jdk/bin/java', JAVA, 0o755);
  write('jdk/bin/jar', JAR, 0o755);
  write('local-run/scripts/local-run-lib.sh', fs.readFileSync(LIBRARY));
  write('local-run/scripts/bootstrap.sh', logging('bootstrap.sh'));
  write('local-run/scripts/load-sample.sh', logging('load-sample.sh'));
  write('local-run/docker-compose.yml', 'name: be\n');
  write('local-run/keycloak/niord-realm-local.json', '{}\n');
  fs.mkdirSync(path.join(dir, 'home'));
  if (settings) write('home/.m2/settings.xml', SETTINGS);
  const home = slashes(path.join(dir, 'home'));
  return {
    dir,
    root: slashes(path.join(dir, 'repo')),
    home,
    env: {
      HOME: home,
      USERPROFILE: home,
      JAVA_HOME: slashes(path.join(dir, 'jdk')),
      FAKE_DIR: slashes(dir),
      FE_PORT: '',
      INFO_PORT: '',
      BE_PORT: '',
      NIORD_LOCAL_BASE_REPO: '',
      MAVEN_ARGS: '',
      NIORD_HOME: '',
      WINDOWS: '',
      FAKE_SETUP_FAIL: '',
    },
  };
}

/** An answer that prints `text` with the shell's own printf, starting no process for it. */
const prints = (match, text = '') => ({ match, script: `    printf '%s' ${quote(text)}` });

/**
 * docker's answers: `guard` is what the launcher's mark query reads for the database; with
 * `createFails`, createdb fails, which ends the run there (set -e); with `markFails`, the mark
 * after createdb fails.
 */
const dockerAnswers = ({ guard = '', createFails = false, markFails = false } = {}) => [
  prints('info'),
  prints('inspect *', 'running healthy\n'),
  prints('*shobj_description*', guard),
  prints('*select 1 from pg_database*'),
  prints('*pg_stat_activity*', '0\n'),
  prints('*select exists*', 'f\n'),
  { ...prints('exec niord-db createdb *'), exit: createFails ? 1 : 0 },
  prints('exec niord-db dropdb *'),
  { ...prints('*comment on database*'), exit: markFails ? 1 : 0 },
];
/** A docker that must not be called. */
const NO_DOCKER = [
  { match: '*', script: "    echo 'docker must not be called here' >&2", exit: 99 },
];

const FAKES = {
  curl: [
    {
      match: '*/protocol/openid-connect/token*',
      script: `    printf '%s' '{"access_token":"t0ken"}'`,
    },
    { match: '*-K - */rest/bootstrap', script: `    printf '%s\\n%s' '{"state":"pending"}' 200` },
    {
      match: '*-m 3 *',
      script: '    if [ -f "$FAKE_DIR/backend-up" ]; then printf 200; else printf 000; fi',
    },
    { match: '*-m 5 *', script: '    printf 000' },
  ],
  npm: [
    { match: 'run start:admin *', script: `    ${serve('admin')}` },
    { match: 'run start:info *', script: `    ${serve('info')}` },
  ],
  // The waits of the launcher and the library return at once: every stand-in answers at its
  // first poll, or as soon as the backend's stand-in has started.
  sleep: [prints('*')],
  netstat: [prints('*')],
  lsof: [{ match: '*', exit: 1 }],
  taskkill: [prints('*')],
};

/** A `case` pattern: `*` matches anything, every other character is itself. */
const pattern = (glob) =>
  glob
    .split('*')
    .map((part) => (part ? quote(part) : ''))
    .join('*') || '*';

/**
 * A fake command in `bin`, as workflow-step.mjs's runBash writes them: it appends
 * `<name> <args>` to the call log and answers from `answers`, the first entry whose `match` (a glob
 * over the arguments joined by spaces) fits, running its `script` (bash, the arguments in "$@")
 * and exiting with its `exit` (0 by default). An unknown call fails with exit 97.
 */
function writeFake(bin, name, answers) {
  const branches = answers.map(
    (answer) =>
      `  ${pattern(answer.match)})\n${answer.script ?? ''}\n    exit ${answer.exit ?? 0} ;;`,
  );
  const text = [
    '#!/usr/bin/env bash',
    `printf '%s\\n' "${name} $*" >> "$FAKE_LOG"`,
    'case "$*" in',
    ...branches,
    `  *) echo "fake ${name}: no answer for: $*" >&2; exit 97 ;;`,
    'esac',
    '',
  ].join('\n');
  fs.writeFileSync(path.join(bin, name), text, { mode: 0o755 });
  fs.chmodSync(path.join(bin, name), 0o755);
}

/**
 * A whole run starts some hundred processes, at a few hundred milliseconds each on a Windows
 * workstation: well past runBash's 60 s when the machine is busy, so the launcher has a runner of
 * its own, asynchronous, so that the cases run side by side.
 */
const LAUNCH_TIMEOUT = 240_000;

/**
 * Runs the launcher with `args` in the repository, the fakes first on the PATH:
 * `{ status, stdout, stderr, calls }`, the calls in order without the fake sleep's.
 */
function launch(repo, args = [], { env = {}, docker = dockerAnswers() } = {}) {
  const bin = fs.mkdtempSync(path.join(repo.dir, 'bin-'));
  for (const [name, answers] of Object.entries({ ...FAKES, docker })) writeFake(bin, name, answers);
  const log = path.join(bin, 'calls.log');
  fs.writeFileSync(log, '');
  // On Windows a PATH entry must be bash's own form (/c/...), since `:` separates its entries.
  const script = [
    'if command -v cygpath > /dev/null 2>&1; then PATH="$(cygpath -u "$FAKE_BIN"):$PATH"; else PATH="$FAKE_BIN:$PATH"; fi',
    'export PATH',
    `cd ${quote(repo.root)} && exec "$BASH" ./start-local.sh ${args.map(quote).join(' ')}`,
  ].join('\n');
  const inherited = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !/^(GITHUB_|RUNNER_|GH_|ACTIONS_)/.test(key)),
  );
  return new Promise((resolve, reject) => {
    const child = spawn(bashPath(), ['-c', script], {
      env: { ...inherited, FAKE_BIN: slashes(bin), FAKE_LOG: slashes(log), ...repo.env, ...env },
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8').on('data', (data) => (stdout += data));
    child.stderr.setEncoding('utf8').on('data', (data) => (stderr += data));
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`the launcher ran past ${LAUNCH_TIMEOUT / 1000} s:\n${stdout}\n${stderr}`));
    }, LAUNCH_TIMEOUT);
    child.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on('close', (status) => {
      clearTimeout(timer);
      const calls = fs
        .readFileSync(log, 'utf8')
        .split('\n')
        .map((line) => line.replace(/\r$/, ''))
        .filter((line) => line && !line.startsWith('sleep '));
      resolve({ status, stdout, stderr, calls });
    });
  });
}

/** Every stand-in server the launcher started is gone: no PID of an alive-<name> file lives. */
function assertStopped(repo) {
  const pids = fs
    .readdirSync(repo.dir)
    .filter((name) => name.startsWith('alive-'))
    .flatMap((name) =>
      fs.readFileSync(path.join(repo.dir, name), 'utf8').split(/\s+/).filter(Boolean),
    );
  assert.ok(pids.length >= 3, `the stand-in servers left no PIDs: ${pids}`);
  const result = spawnSync(
    bashPath(),
    [
      '-c',
      'for pid in "$@"; do if kill -0 "$pid" 2>/dev/null; then echo "$pid"; fi; done',
      'alive',
      ...pids,
    ],
    { encoding: 'utf8' },
  );
  assert.equal(result.stdout.trim(), '', 'a stand-in server still runs after the launcher exited');
}

const remove = (repo) => fs.rmSync(repo.dir, { recursive: true, force: true });
const output = (result) => `${result.stdout}\n${result.stderr}`;
/** The index of the first call that `test` matches; fails when there is none. */
function at(calls, test, what) {
  const index = calls.findIndex((call) => (typeof test === 'string' ? call === test : test(call)));
  assert.ok(index >= 0, `no call ${what ?? test}:\n${calls.join('\n')}`);
  return index;
}
const isBackendStart = (call) => call.startsWith('mvnw ') && call.includes(' spring-boot:run ');
const springBoot = (calls) => calls.filter(isBackendStart);
/** The indexes of the backend starts. */
const springBootAt = (calls) => calls.flatMap((call, i) => (isBackendStart(call) ? [i] : []));

describe('the country launcher, templates/start-local.sh', { concurrency: 4 }, () => {
  it('is bash that parses', { skip: skipBash }, async () => {
    const result = spawnSync(bashPath(), ['-n', slashes(LAUNCHER)], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  });

  it('is LF only, and names no country', () => {
    const text = fs.readFileSync(LAUNCHER, 'utf8');
    assert.ok(!text.includes('\r'), 'a CR in the template');
    assert.ok(text.startsWith('#!/usr/bin/env bash\n'));
    assert.doesNotMatch(text, /niord_(dk|gl)\b|niord-(dk|gl)\b/);
  });

  describe('refuses before any docker call', { concurrency: 4 }, () => {
    for (const [what, args, env, status, message] of [
      ['an unknown argument', ['--slot', '2'], {}, 2, /Unknown argument: --slot \(see --help\)/],
      ['--reset without --sample', ['--reset'], {}, 2, /--reset .* --sample only/],
      ['a port that is not a number', [], { FE_PORT: 'abc' }, 2, /FE_PORT must be a port number/],
      ['a port below 1024', [], { BE_PORT: '80' }, 2, /BE_PORT must be a port number/],
      ['two equal ports', [], { INFO_PORT: '4200' }, 2, /three different ports/],
    ])
      it(`${what}, with exit ${status}`, { skip }, async () => {
        const repo = country();
        try {
          const result = await launch(repo, args, { env, docker: NO_DOCKER });
          assert.equal(result.status, status, output(result));
          assert.match(result.stderr, message);
          assert.deepEqual(result.calls, []);
        } finally {
          remove(repo);
        }
      });

    it('three-letter country in the manifest, exit 1, no calls', { skip }, async () => {
      for (const args of [[], ['--sample']]) {
        const repo = country({ cc: 'xxx' });
        try {
          const result = await launch(repo, args, { docker: NO_DOCKER });
          assert.equal(result.status, 1, output(result));
          assert.match(
            result.stderr,
            /fe\/niord-country\.json's country "xxx" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z/,
          );
          assert.deepEqual(result.calls, []);
        } finally {
          remove(repo);
        }
      }
    });

    it(
      "an fe/setup.mjs without parentVersion, naming the kit's stub, exit 1, no calls",
      { skip },
      async () => {
        const repo = country();
        try {
          fs.writeFileSync(
            path.join(repo.dir, 'repo', 'fe', 'setup.mjs'),
            'export const other = 1;\n',
          );
          const result = await launch(repo, [], { docker: NO_DOCKER });
          assert.equal(result.status, 1, output(result));
          assert.match(
            result.stderr,
            /fe\/setup\.mjs has no parentVersion: copy \.niord\/kit\/templates\/setup\.mjs over it/,
          );
          assert.deepEqual(result.calls, []);
        } finally {
          remove(repo);
        }
      },
    );

    it(
      'a missing GitHub Packages credential, with exit 1, and fetches nothing',
      { skip },
      async () => {
        const repo = country({ settings: false });
        try {
          const result = await launch(repo, [], { docker: NO_DOCKER });
          assert.equal(result.status, 1, output(result));
          assert.match(result.stderr, /settings\.xml/);
          assert.match(result.stderr, /NIORD_LOCAL_BASE_REPO/);
          assert.deepEqual(result.calls, []);
        } finally {
          remove(repo);
        }
      },
    );

    for (const [what, text, message] of [
      ['a start-local.json with another key', '{"database":"niord_xx_local","port":1}', /one key/],
      ['a start-local.json naming niord', '{"database":"niord"}', /niord/],
      ['a start-local.json naming a sample database', '{"database":"niord_sample_xx"}', /sample/],
      ['a start-local.json naming no plain identifier', '{"database":"Niord-XX"}', /identifier/],
    ])
      it(`${what}, with exit 1`, { skip }, async () => {
        const repo = country({ startLocal: text });
        try {
          const result = await launch(repo, [], { docker: NO_DOCKER });
          assert.equal(result.status, 1, output(result));
          assert.match(result.stderr, /start-local\.json/);
          assert.match(result.stderr, message);
          assert.deepEqual(result.calls, []);
        } finally {
          remove(repo);
        }
      });

    it('--help prints the header, and exits 0', { skip }, async () => {
      const repo = country();
      try {
        const result = await launch(repo, ['--help'], { docker: NO_DOCKER });
        assert.equal(result.status, 0, output(result));
        assert.match(result.stdout, /\.\/start-local\.sh --sample --reset/);
        assert.doesNotMatch(result.stdout, /set -euo pipefail/);
        assert.doesNotMatch(result.stdout, /two-letter/);
        assert.deepEqual(result.calls, []);
      } finally {
        remove(repo);
      }
    });
  });

  it(
    "runs the country's own bootstrap in order, with no compose call while the shared containers run",
    { skip },
    async () => {
      const repo = country();
      try {
        const result = await launch(repo);
        // The port watch ends the run once the stand-in servers stop answering.
        assert.equal(result.status, 1, output(result));
        const { calls } = result;
        const copies = calls.filter((call) => call.includes(' dependency:copy '));
        assert.equal(copies.length, 2, calls.join('\n'));
        for (const [copy, artifact] of [
          [copies[0], 'niord-local-run'],
          [copies[1], 'niord-bootstrap'],
        ]) {
          assert.ok(copy.startsWith('mvnw -B -q dependency:copy '), copy);
          assert.ok(copy.includes(`-Dartifact=org.niord:${artifact}:${VERSION}:zip `), copy);
          assert.match(copy, /-DoutputDirectory=(?:[A-Za-z]:)?\/\S*\/repo\/\.local-run\/4\.0\.9 /);
        }
        const setup = at(calls, 'setup.mjs');
        assert.ok(calls.indexOf(copies[1]) < setup, 'the fetch comes before the set-up');
        const inspect = at(calls, (call) => call.startsWith('docker inspect '), 'docker inspect');
        assert.ok(setup < inspect, 'the set-up comes before the containers');
        assert.deepEqual(
          calls.filter((call) => /^docker compose\b/.test(call)),
          [],
          'a compose call while the three containers run',
        );
        const guard = at(
          calls,
          (call) => call.includes('shobj_description') && call.includes("datname = 'niord_xx'"),
          'the mark query',
        );
        const create = at(
          calls,
          'docker exec niord-db createdb -U niord -T template_postgis niord_xx',
        );
        const mark = at(
          calls,
          (call) => call.includes('comment on database niord_xx is '),
          'the mark',
        );
        assert.ok(inspect < guard && guard < create && create < mark);
        const [backend, ...more] = springBoot(calls);
        assert.deepEqual(more, [], 'one backend start');
        assert.ok(
          backend.startsWith(
            'mvnw -B spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments=',
          ),
          backend,
        );
        for (const argument of [
          '-Duser.timezone=UTC',
          '-Dserver.port=8888',
          '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/niord_xx ',
          '-Dmode=development',
          '-DadminAppUrl=http://localhost:4200',
        ])
          assert.ok(backend.includes(argument), `${argument} in ${backend}`);
        assert.doesNotMatch(backend, /secom/);
        assert.match(backend, /-Dniord\.home=\S*\/home\/\.niord-local\/niord_xx /);
        assert.match(backend, /NIORD_HOME=\S*\/home\/\.niord-local\/niord_xx /);
        const start = calls.indexOf(backend);
        assert.ok(mark < start);
        const bootstrap = at(calls, (call) => call.startsWith('bootstrap.sh '), 'bootstrap.sh');
        assert.match(
          calls[bootstrap],
          /^bootstrap\.sh http:\/\/127\.0\.0\.1:8888 \S*\/\.local-run\/4\.0\.9\/niord-bootstrap-4\.0\.9\.zip \S*\/\.local-run\/bootstrap-2\.zip$/,
        );
        const admin = at(calls, 'npm run start:admin -- --configuration local --port 4200');
        const info = at(calls, 'npm run start:info -- --port 4400');
        // The two dev servers start side by side, so either may log first.
        assert.ok(start < bootstrap && bootstrap < admin && bootstrap < info);
        assert.equal(
          calls.some((call) => call.startsWith('load-sample.sh')),
          false,
        );
        assert.match(result.stdout, /Example is running \(loopback only\)/);
        assert.match(result.stdout, /niord-xx on the base product 4\.0\.9/);
        assert.match(result.stdout, /Never expose it/);
        assert.match(
          result.stdout,
          /The shared containers niord-db, niord-keycloak and niord-smtp keep running/,
        );
        // The library staged the realm under the temporary home, where the compose file mounts it from.
        assert.ok(
          fs.existsSync(
            path.join(repo.dir, 'home', '.niord-local', 'keycloak', 'niord-realm-local.json'),
          ),
        );
        assertStopped(repo);
      } finally {
        remove(repo);
      }
    },
  );

  it('stops before the containers when the set-up refuses', { skip }, async () => {
    const repo = country();
    try {
      const result = await launch(repo, [], { env: { FAKE_SETUP_FAIL: '1' } });
      assert.equal(result.status, 1, output(result));
      assert.match(result.stderr, /Set-up refused/);
      at(result.calls, 'setup.mjs');
      assert.deepEqual(
        result.calls.filter((call) => call.startsWith('docker ') && call !== 'docker info'),
        [],
      );
      assert.deepEqual(springBoot(result.calls), []);
      // The Niord home is made when the backend starts.
      assert.equal(fs.existsSync(path.join(repo.dir, 'home', '.niord-local', 'niord_xx')), false);
    } finally {
      remove(repo);
    }
  });

  it(
    'loads the sample into niord_sample_<cc> after the base archive alone, and starts the backend again',
    { skip },
    async () => {
      const repo = country();
      try {
        const result = await launch(repo, ['--sample']);
        assert.equal(result.status, 1, output(result));
        const { calls } = result;
        assert.equal(
          calls.some((call) => call.includes('shobj_description')),
          false,
          'no mark guard under --sample',
        );
        assert.equal(
          calls.some((call) => call.includes(' dropdb ')),
          false,
          'no drop without --reset',
        );
        const create = at(
          calls,
          'docker exec niord-db createdb -U niord -T template_postgis niord_sample_xx',
        );
        const [first, second, ...more] = springBoot(calls);
        assert.ok(second, `two backend starts:\n${calls.join('\n')}`);
        assert.deepEqual(more, []);
        for (const backend of [first, second]) {
          assert.ok(
            backend.includes(
              '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/niord_sample_xx ',
            ),
            backend,
          );
          assert.ok(backend.includes('-Dniord.secom.enabled=false'), backend);
          assert.match(backend, /NIORD_HOME=\S*\/home\/\.niord-local\/niord_sample_xx /);
        }
        const bootstrap = at(calls, (call) => call.startsWith('bootstrap.sh '), 'bootstrap.sh');
        assert.match(
          calls[bootstrap],
          /^bootstrap\.sh http:\/\/127\.0\.0\.1:8888 \S*\/niord-bootstrap-4\.0\.9\.zip$/,
        );
        const load = at(calls, (call) => call.startsWith('load-sample.sh '), 'load-sample.sh');
        assert.match(
          calls[load],
          // The sample's attachments are rows of the SQL, so the loader is given no home
          /^load-sample\.sh --host localhost --db niord_sample_xx$/,
        );
        const admin = at(calls, (call) => call.startsWith('npm run start:admin '), 'start:admin');
        const [firstAt, secondAt] = springBootAt(calls);
        assert.ok(create < firstAt);
        assert.ok(firstAt < bootstrap && bootstrap < load && load < secondAt);
        assert.ok(secondAt < admin);
        assert.match(
          result.stdout,
          /the base product's bootstrap and its sample, with this country's settings and texts/,
        );
        assert.match(
          result.stdout,
          /sysadmin \/ sysadmin, admin \/ admin, editor \/ editor, user \/ user/,
        );
        assertStopped(repo);
      } finally {
        remove(repo);
      }
    },
  );

  it('--sample --reset drops niord_sample_<cc> first', { skip }, async () => {
    const repo = country();
    try {
      // The run ends at the createdb, which is as far as --reset reaches.
      const result = await launch(repo, ['--sample', '--reset'], {
        docker: dockerAnswers({ createFails: true }),
      });
      assert.equal(result.status, 1, output(result));
      const { calls } = result;
      const active = at(
        calls,
        (call) => call.includes("datname = 'niord_sample_xx' and backend_type"),
        'the session check',
      );
      const drop = at(calls, 'docker exec niord-db dropdb -U niord --if-exists niord_sample_xx');
      const create = at(
        calls,
        'docker exec niord-db createdb -U niord -T template_postgis niord_sample_xx',
      );
      assert.ok(active < drop && drop < create);
      assert.equal(calls.filter((call) => call.includes(' dropdb ')).length, 1);
      assert.deepEqual(springBoot(calls), []);
    } finally {
      remove(repo);
    }
  });

  it(
    'refuses a database that exists without its mark, and starts no backend',
    { skip },
    async () => {
      const repo = country();
      try {
        const result = await launch(repo, [], { docker: dockerAnswers({ guard: '-\n' }) });
        assert.equal(result.status, 1, output(result));
        assert.match(result.stderr, /niord_xx/);
        assert.match(result.stderr, /never touched/);
        assert.match(result.stderr, /start-local\.json/);
        assert.match(result.stderr, /docker exec niord-db dropdb -U niord niord_xx\)/);
        assert.equal(
          result.calls.some((call) => call.includes(' createdb ')),
          false,
        );
        assert.equal(
          result.calls.some((call) => call.includes('comment on database')),
          false,
        );
        assert.deepEqual(springBoot(result.calls), []);
        // No Niord home is made for a database that is never touched.
        assert.equal(fs.existsSync(path.join(repo.dir, 'home', '.niord-local', 'niord_xx')), false);
      } finally {
        remove(repo);
      }
    },
  );

  it(
    'says how to recover when the new database cannot be marked, and starts no backend',
    { skip },
    async () => {
      const repo = country();
      try {
        const result = await launch(repo, [], { docker: dockerAnswers({ markFails: true }) });
        assert.equal(result.status, 1, output(result));
        assert.match(result.stderr, /niord_xx was created but could not be marked/);
        assert.match(result.stderr, /docker exec niord-db dropdb -U niord niord_xx and run/);
        assert.deepEqual(springBoot(result.calls), []);
      } finally {
        remove(repo);
      }
    },
  );

  it(
    "uses start-local.json's database, and a scratch repository's MAVEN_ARGS without a credential",
    { skip },
    async () => {
      const repo = country({ settings: false, startLocal: '{ "database": "niord_xx_local" }\n' });
      const scratch = path.join(repo.dir, 'm2');
      fs.mkdirSync(scratch);
      try {
        const result = await launch(repo, [], {
          env: {
            NIORD_LOCAL_BASE_REPO: slashes(scratch),
            FE_PORT: '4210',
            INFO_PORT: '4410',
            BE_PORT: '8898',
          },
        });
        assert.equal(result.status, 1, output(result));
        const { calls } = result;
        assert.match(result.stdout, /credential is not checked/);
        for (const call of calls.filter((entry) => entry.startsWith('mvnw ')))
          assert.match(
            call,
            /MAVEN_ARGS=-Dmaven\.repo\.local=\S*\/m2 -Dmaven\.repo\.local\.tail=\S*\/home\/\.m2\/repository$/,
          );
        at(
          calls,
          (call) => call.includes("datname = 'niord_xx_local'"),
          'the mark query on niord_xx_local',
        );
        at(calls, 'docker exec niord-db createdb -U niord -T template_postgis niord_xx_local');
        const [backend] = springBoot(calls);
        assert.ok(
          backend.includes(
            '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/niord_xx_local ',
          ),
          backend,
        );
        assert.ok(backend.includes('-Dserver.port=8898'), backend);
        assert.ok(backend.includes('-DadminAppUrl=http://localhost:4210'), backend);
        assert.match(backend, /NIORD_HOME=\S*\/home\/\.niord-local\/niord_xx_local /);
        at(
          calls,
          (call) => call.startsWith('bootstrap.sh http://127.0.0.1:8898 '),
          'bootstrap.sh on 8898',
        );
        at(calls, 'npm run start:admin -- --configuration local --port 4210');
        at(calls, 'npm run start:info -- --port 4410');
        assertStopped(repo);
      } finally {
        remove(repo);
      }
    },
  );
});
