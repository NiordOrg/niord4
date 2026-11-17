/**
 * The CI filters and their copies, held to each other: what decides whether Frontend CI, Info CI
 * and their example and public-site parts run, written once in a workflow and again in
 * ./verify-local, which applies the same rules to a local branch.
 *
 *   npm run test:guards      # from fe/
 *
 * Where two copies are meant to be one expression, they are compared as strings: info-ci.yml's
 * Detect pattern and verify-local's INFO_RULE, fe-ci.yml's frontend rule and ADMIN_RULE, its
 * example rule and EXAMPLE_RULE. Info CI's push paths are globs and its Detect pattern a regular
 * expression, wider on purpose (all of scripts/info- and scripts/build-), so they are compared in
 * both directions by an instance of each: every push glob names a path the pattern takes, and
 * every alternative of the pattern a path a push glob takes. The kit sits at the repository root,
 * beside fe/, so its files are named country-kit/… in every filter, and the run commands that work
 * in fe/ reach it as ../country-kit/…; the two Prettier lists of the public site name the same
 * files. Push paths are matched as GitHub matches them: the last glob that matches decides, and a
 * glob starting with `!` excludes.
 *
 * The scaffold rule, what the generated country's backend build reads, has three copies:
 * fe-ci.yml's Detect `scaffold_rule`, which gives the Example job --backend, be-ci.yml's Detect
 * `scaffold_rule`, which runs its Generated country job, and verify-local's SCAFFOLD_RULE. They are
 * compared as strings, and the two Detect steps are run under bash in a git repository of the
 * test's own, so the outputs the jobs' conditions read are the ones each event gives.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';
import { KIT_BACKEND } from '../../country-kit/lib/where.mjs';
import { globToRegExp } from './glob.mjs';
import { runBash, stepEnv, stepRun } from './workflow-step.mjs';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = path.dirname(FE);
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8').replaceAll('\r', '');
const WORKFLOWS = path.join('.github', 'workflows');
const FE_CI = read(path.join(WORKFLOWS, 'fe-ci.yml'));
const BE_CI = read(path.join(WORKFLOWS, 'be-ci.yml'));
const INFO_CI = read(path.join(WORKFLOWS, 'info-ci.yml'));
const PUBLISH = read(path.join(WORKFLOWS, 'publish.yml'));
const VERIFY_LOCAL = read('verify-local');

/** One of verify-local's rules, `NAME='…'` on a line of its own. */
function shellRule(name) {
  const match = new RegExp(`^${name}='([^']*)'$`, 'm').exec(VERIFY_LOCAL);
  assert.ok(match, `verify-local sets ${name}`);
  return match[1];
}

/** The expression of a `grep -qE '…' <<< "$<input>"` in a step's bash. */
function grepRule(script, input) {
  const match = new RegExp(`grep -qE '([^']*)' <<< "\\$${input}"`).exec(script);
  assert.ok(match, `the step greps "$${input}"`);
  return match[1];
}

/** A rule assigned on a line of its own, `<name>='…'`, in a step's bash or in verify-local. */
function assignedRule(script, name) {
  const match = new RegExp(`^\\s*${name}='([^']*)'$`, 'm').exec(script);
  assert.ok(match, `the script sets ${name}='…' on a line of its own`);
  return match[1];
}

/** A workflow's push paths. */
const pushPaths = (text) => yaml.load(text).on.push.paths;

/** True when the push paths take `file`: the last glob that matches decides, `!` excludes. */
function pushTakes(globs, file) {
  let taken = false;
  for (const glob of globs) {
    const negated = glob.startsWith('!');
    if (globToRegExp(negated ? glob.slice(1) : glob).test(file)) taken = !negated;
  }
  return taken;
}

/** A path a push glob matches: `**` as two segments, `*` as one name. */
const globInstance = (glob) => glob.replaceAll('**', 'x/y.mjs').replaceAll('*', 'x');

/** The alternatives of a regular expression's text at its top level, split at `|`. */
function topLevel(text) {
  const parts = [''];
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (char === '\\') {
      parts[parts.length - 1] += text.slice(i, i + 2);
      i++;
      continue;
    }
    if (char === '(') depth++;
    else if (char === ')') depth--;
    if (char === '|' && depth === 0) parts.push('');
    else parts[parts.length - 1] += char;
  }
  return parts;
}

/** Every text an alternative stands for, its groups `(a|b)` and `(a)?` expanded. */
function expandGroups(text) {
  let open = -1;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\\') i++;
    else if (text[i] === '(') {
      open = i;
      break;
    }
  }
  if (open < 0) return [text];
  let depth = 0;
  let close = -1;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '\\') i++;
    else if (text[i] === '(') depth++;
    else if (text[i] === ')' && --depth === 0) {
      close = i;
      break;
    }
  }
  assert.ok(close > open, `the group in ${text} closes`);
  const optional = text[close + 1] === '?';
  const head = text.slice(0, open);
  const tail = text.slice(close + (optional ? 2 : 1));
  const inner = topLevel(text.slice(open + 1, close)).flatMap(expandGroups);
  return [...inner, ...(optional ? [''] : [])].flatMap((middle) =>
    expandGroups(tail).map((rest) => `${head}${middle}${rest}`),
  );
}

/**
 * A path each top-level alternative of an anchored rule `^(…)` matches: groups expanded, `\.` a
 * dot, `[^/]*` a name, and an alternative without `$` (a folder or a name's start) completed with
 * a file.
 */
function ruleInstances(rule) {
  const match = /^\^\((.*)\)$/.exec(rule);
  assert.ok(match, `${rule} is written ^(…)`);
  return topLevel(match[1]).flatMap((alternative) =>
    expandGroups(alternative).map((text) => {
      const whole = text.endsWith('$');
      const plain = (whole ? text.slice(0, -1) : text)
        .replaceAll('[^/]*', 'x')
        .replace(/\\(.)/g, '$1');
      return whole ? plain : `${plain}x.mjs`;
    }),
  );
}

/** The steps of every job of a parsed workflow, with the job's key and defaults. */
function steps(workflow) {
  return Object.entries(workflow.jobs).flatMap(([job, { defaults, steps: list = [] }]) =>
    list.map((step) => ({ job, defaults, step })),
  );
}

/** The one step whose name starts with `name`, `{ job, defaults, step }`. */
function stepNamed(text, name) {
  const found = steps(yaml.load(text)).filter(({ step }) => step.name?.startsWith(name));
  assert.equal(found.length, 1, `one step is named ${name}`);
  return found[0];
}

/** The folder a step's `run` works in, relative to the repository root. */
const workingDirectory = ({ defaults, step }) =>
  step['working-directory'] ?? defaults?.run?.['working-directory'] ?? '.';

/** The quoted arguments of a `npx prettier --check` command line. */
function prettierPaths(text) {
  const at = text.indexOf('npx prettier --check');
  assert.ok(at >= 0, 'the step runs npx prettier --check');
  return [...text.slice(at).matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

const INFO_DETECT = stepRun(INFO_CI, { job: 'changes', step: 'Detect' });
const FE_DETECT = stepRun(FE_CI, { job: 'changes', step: 'Detect' });
const BE_DETECT = stepRun(BE_CI, { job: 'changes', step: 'Detect' });

function infoPattern() {
  const match = /^\s*pattern='([^']*)'$/m.exec(INFO_DETECT);
  assert.ok(match, "info-ci.yml's Detect sets pattern='…'");
  return match[1];
}

describe('the CI filters', () => {
  it("info-ci.yml's Detect pattern is verify-local's INFO_RULE", () => {
    assert.equal(infoPattern(), shellRule('INFO_RULE'));
  });

  it("info-ci.yml's push paths and its Detect pattern name the same files", () => {
    const globs = pushPaths(INFO_CI);
    const pattern = new RegExp(infoPattern());
    for (const glob of globs)
      assert.ok(
        pattern.test(globInstance(glob)),
        `the push path ${glob} (${globInstance(glob)}) is not in the Detect pattern`,
      );
    for (const instance of ruleInstances(infoPattern()))
      assert.ok(
        globs.some((glob) => globToRegExp(glob).test(instance)),
        `the Detect pattern takes ${instance}, which no push path does`,
      );
  });

  it("fe-ci.yml's frontend and example rules are verify-local's ADMIN_RULE and EXAMPLE_RULE", () => {
    assert.equal(grepRule(FE_DETECT, 'relevant'), shellRule('ADMIN_RULE'));
    assert.equal(grepRule(FE_DETECT, 'changed'), shellRule('EXAMPLE_RULE'));
  });

  it('the example rules take every be/ file the kit package carries', () => {
    const example = [
      new RegExp(grepRule(FE_DETECT, 'changed')),
      new RegExp(shellRule('EXAMPLE_RULE')),
    ];
    const carried = Object.values(KIT_BACKEND).map(({ from }) => `be/${from}`);
    for (const rule of example) {
      for (const file of carried) assert.ok(rule.test(file), `${rule.source} takes ${file}`);
      for (const file of [
        'be/niord/src/main/resources/defaults/base/settings-old.json',
        'be/niord/src/main/resources/META-INF/resources/img/other.png',
        'be/niord/src/main/resources/defaults/base/README.md',
        'be/niord/src/main/java/org/niord/core/Foo.java',
      ])
        assert.ok(!rule.test(file), `${rule.source} takes ${file}, which the kit does not carry`);
    }
  });

  it('the example rules take the files of fe/scripts/ the source zip carries, and no other', () => {
    const example = [
      new RegExp(grepRule(FE_DETECT, 'changed')),
      new RegExp(shellRule('EXAMPLE_RULE')),
    ];
    const carried = [
      'fe/scripts/glob.mjs',
      'fe/scripts/build-urls-check.mjs',
      'fe/scripts/build-urls-allowed.json',
      'fe/scripts/info-container-test.mjs',
    ];
    for (const rule of example) {
      for (const file of carried) assert.ok(rule.test(file), `${rule.source} takes ${file}`);
      for (const file of [
        'fe/scripts/docs-check.mjs',
        'fe/scripts/ci-test-shard.mjs',
        'fe/scripts/kit-move-tombstone.mjs',
      ])
        assert.ok(!rule.test(file), `${rule.source} takes ${file}, which the zip does not carry`);
    }
  });

  it('names the kit at the repository root, beside fe/', () => {
    for (const name of fs.readdirSync(path.join(ROOT, WORKFLOWS)))
      assert.ok(
        !read(path.join(WORKFLOWS, name)).includes('fe/country-kit'),
        `${name} names fe/country-kit`,
      );
    assert.ok(!VERIFY_LOCAL.includes('fe/country-kit'), 'verify-local names fe/country-kit');

    const infoPush = pushPaths(INFO_CI);
    const info = [new RegExp(infoPattern()), new RegExp(shellRule('INFO_RULE'))];
    for (const file of [
      'country-kit/pack.mjs',
      'country-kit/lib/where.mjs',
      'country-kit/where.test.mjs',
      'country-kit/.prettierrc',
    ]) {
      assert.ok(pushTakes(infoPush, file), `info-ci.yml's push paths take ${file}`);
      for (const rule of info) assert.ok(rule.test(file), `${rule.source} takes ${file}`);
    }

    const fePush = pushPaths(FE_CI);
    const frontend = [
      new RegExp(grepRule(FE_DETECT, 'relevant')),
      new RegExp(shellRule('ADMIN_RULE')),
    ];
    const example = [
      new RegExp(grepRule(FE_DETECT, 'changed')),
      new RegExp(shellRule('EXAMPLE_RULE')),
    ];
    assert.ok(pushTakes(fePush, 'country-kit/README.md'), "fe-ci.yml's push paths take the kit");
    assert.ok(
      !pushTakes(fePush, 'fe/ui-apps/info/src/main.ts'),
      "fe-ci.yml's push paths leave the public site to Info CI",
    );
    for (const rule of [...frontend, ...example])
      assert.ok(rule.test('country-kit/README.md'), `${rule.source} takes country-kit/README.md`);
    for (const rule of example)
      assert.ok(rule.test('be/docs/country-api.md'), `${rule.source} takes be/docs/country-api.md`);
  });

  it("runs the administration jobs on a change to the backend's endpoint table", () => {
    // The guard tests and the specs read the table (scripts/endpoint-roles.test.mjs), so a
    // backend change that touches it alone must run them.
    const fePush = pushPaths(FE_CI);
    const frontend = [
      new RegExp(grepRule(FE_DETECT, 'relevant')),
      new RegExp(shellRule('ADMIN_RULE')),
    ];
    const table = 'be/niord/src/test/resources/endpoints';
    assert.ok(fs.existsSync(path.join(ROOT, table)), `${table} is a folder`);
    const entry = fs.readdirSync(path.join(ROOT, table)).find((name) => name.endsWith('.json'));
    assert.ok(entry, `${table} holds a table file`);
    assert.ok(pushTakes(fePush, `${table}/${entry}`), `fe-ci.yml's push paths take ${table}/`);
    for (const rule of frontend)
      assert.ok(rule.test(`${table}/${entry}`), `${rule.source} takes ${table}/${entry}`);
    for (const file of [
      'be/niord/src/test/resources/other.json',
      'be/niord/src/test/java/org/niord/web/EndpointSweepITTest.java',
      'be/niord/src/main/java/org/niord/web/MessageRestService.java',
    ]) {
      assert.ok(!pushTakes(fePush, file), `fe-ci.yml's push paths take ${file}`);
      for (const rule of frontend) assert.ok(!rule.test(file), `${rule.source} takes ${file}`);
    }
  });

  it('runs the kit from fe/ as ../country-kit/', () => {
    const exists = (script) =>
      assert.ok(fs.existsSync(path.resolve(FE, script)), `${script} is a file from fe/`);

    const zip = stepNamed(PUBLISH, 'Build and check the source zip');
    assert.equal(workingDirectory(zip), 'fe');
    for (const command of ['build', 'verify', 'guard'])
      assert.match(
        zip.step.run,
        new RegExp(`^node \\.\\./country-kit/source-zip\\.mjs ${command} `, 'm'),
      );
    exists('../country-kit/source-zip.mjs');

    const harness = stepNamed(FE_CI, 'Set up, build, test and lint the example country');
    assert.equal(harness.job, 'example');
    assert.equal(workingDirectory(harness), 'fe');
    assert.ok(
      harness.step.run.startsWith('node ../country-kit/example/run.mjs '),
      `the example step runs ${harness.step.run}`,
    );
    exists('../country-kit/example/run.mjs');

    const local = VERIFY_LOCAL.indexOf('node ../country-kit/example/run.mjs');
    assert.ok(local >= 0, 'verify-local runs node ../country-kit/example/run.mjs');
    const before = [...VERIFY_LOCAL.slice(0, local).matchAll(/^\s*(cd .*)$/gm)].at(-1);
    assert.equal(before?.[1], 'cd "$ROOT/fe" || return');
  });

  it("Info CI's and verify-local's Prettier checks of the public site name the same files", () => {
    const step = stepNamed(INFO_CI, 'Check formatting');
    assert.equal(workingDirectory(step), 'fe');
    const workflow = prettierPaths(step.step.run);
    const start = VERIFY_LOCAL.indexOf('info-format \\');
    assert.ok(start >= 0, 'verify-local runs the info-format step');
    const local = prettierPaths(VERIFY_LOCAL.slice(start, VERIFY_LOCAL.indexOf('||', start)));
    assert.deepEqual(local, workflow);
    for (const file of workflow.filter((name) => !name.includes('*')))
      assert.ok(fs.existsSync(path.resolve(FE, file)), `${file} is a file from fe/`);
  });
});

/** The three copies of the scaffold rule, `[where, rule]`. */
const scaffoldRules = () => [
  ["fe-ci.yml's Detect", assignedRule(FE_DETECT, 'scaffold_rule')],
  ["be-ci.yml's Detect", assignedRule(BE_DETECT, 'scaffold_rule')],
  ['verify-local', assignedRule(VERIFY_LOCAL, 'SCAFFOLD_RULE')],
];

const HARNESS = { job: 'example', step: 'Set up, build, test and lint the example' };
const GENERATED =
  'node ../country-kit/example/run.mjs --work "$RUNNER_TEMP/example" --backend --skip-frontend';

describe('the scaffold rule and the generated country', () => {
  it("fe-ci.yml's and be-ci.yml's scaffold_rule are verify-local's SCAFFOLD_RULE", () => {
    const [[, fe], [, be], [, local]] = scaffoldRules();
    assert.equal(fe, local);
    assert.equal(be, local);
  });

  it("takes what the generated country's backend build reads, and no other", () => {
    for (const [where, text] of scaffoldRules()) {
      const rule = new RegExp(text);
      for (const file of [
        'be/pom.xml',
        'be/niord/pom.xml',
        'be/niord-app/pom.xml',
        'be/niord-bootstrap/pom.xml',
        'be/niord/bootstrap/domains.json',
        'be/niord/src/main/resources/defaults/base/settings.json',
        'be/niord/src/test/java/org/niord/test/CountryBuildGroup.java',
        'be/docs/country-api.md',
        'be/mvnw.cmd',
        'be/.mvn/wrapper/maven-wrapper.properties',
        'be/k8s/scripts/check-overrides.sh',
        'country-kit/init.mjs',
        'country-kit/init-answers.schema.json',
        'country-kit/lib/scaffold.mjs',
        'country-kit/lib/answers.mjs',
        'country-kit/lib/where.mjs',
        'country-kit/templates/be/pom.xml.tmpl',
        'country-kit/templates/ci.yml',
        'country-kit/example/run.mjs',
        '.github/workflows/be-ci.yml',
      ])
        assert.ok(rule.test(file), `${where}'s scaffold rule takes ${file}`);
      for (const file of [
        'be/niord/src/main/java/org/niord/core/Foo.java',
        'be/docs/cicd/design.md',
        'country-kit/README.md',
        'country-kit/templates/image.yml',
        'fe/ui-apps/admin/x.ts',
      ])
        assert.ok(!rule.test(file), `${where}'s scaffold rule takes ${file}`);
    }
  });

  it("gives fe-ci.yml's Example --backend when a scaffold input changed, in 45 minutes", () => {
    const workflow = yaml.load(FE_CI);
    assert.equal(workflow.jobs.changes.outputs.scaffold, '${{ steps.detect.outputs.scaffold }}');
    assert.equal(workflow.jobs.example['timeout-minutes'], 45);
    assert.match(stepEnv(FE_CI, HARNESS).BACKEND ?? '', /needs\.changes\.outputs\.scaffold\b/);
    const run = stepRun(FE_CI, HARNESS);
    assert.ok(run.includes('${BACKEND:+--backend}'), run);
    const harness = (BACKEND) =>
      runBash(run, {
        env: { BACKEND, UPGRADE_FROM: '', UPGRADE_KIT_FROM: '' },
        fakes: { node: [{ match: '*', out: '' }] },
      });
    const backend = harness('yes');
    assert.equal(backend.status, 0, backend.stderr);
    assert.match(backend.calls[0], / --image --with-deps --backend$/);
    const none = harness('');
    assert.equal(none.status, 0, none.stderr);
    assert.match(none.calls[0], / --with-deps$/);
  });

  it("runs be-ci.yml's Generated country job on the scaffold output, restoring Maven's cache only", () => {
    const workflow = yaml.load(BE_CI);
    assert.equal(workflow.jobs.changes.outputs.scaffold, '${{ steps.detect.outputs.scaffold }}');
    const job = workflow.jobs['generated-country'];
    assert.ok(job, 'be-ci.yml has a job generated-country');
    assert.equal(job.name, 'Generated country');
    assert.equal(job.needs, 'changes');
    assert.equal(job.if, "needs.changes.outputs.scaffold == 'true'");
    assert.equal(job.defaults?.run?.['working-directory'], 'fe');
    assert.equal(job.steps.at(-1).run.trim(), GENERATED);
    const uses = job.steps.map((step) => step.uses).filter(Boolean);
    assert.ok(
      uses.some((action) => action.startsWith('actions/cache/restore@')),
      `the job restores the Maven cache: ${uses}`,
    );
    assert.ok(
      !uses.some((action) => action.startsWith('actions/cache@')),
      `the job saves no cache: ${uses}`,
    );
    const java = job.steps.find((step) => step.uses?.startsWith('actions/setup-java@'));
    assert.equal(String(java?.with?.['java-version']), '25');
    assert.ok(
      job.steps.some((step) => step.run?.trim() === 'docker version'),
      'the job checks Docker',
    );
  });
});

describe('the two Detect steps against a real git history', () => {
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
  /** A Detect step run in the repository for `event`, from `base` to `head` on a pull request. */
  const detect = (script, event, { base = '', head = '', fakes = {} } = {}) => {
    const result = runBash(script, {
      cwd: repo,
      env: {
        ...gitEnv(),
        EVENT: event,
        BASE_SHA: base,
        HEAD_SHA: head,
        RUN_ITS: 'true',
        GH_TOKEN: '',
        REPO: 'example/niord',
        SHA: commits.java,
        RUN_ATTEMPT: '1',
        RUNS_URL: 'https://ci.example/runs',
      },
      fakes,
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    return result;
  };
  // Every question to the API fails, so the run on main finds nothing tested already.
  const noApi = { gh: [{ match: '*', err: 'HTTP 404: Not Found\n', exit: 1 }] };

  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-ci-filters-git-'));
    repo = path.join(dir, 'repo');
    fs.mkdirSync(repo);
    fs.writeFileSync(path.join(dir, 'gitconfig'), '');
    git('init', '-q');
    commits.first = commit(
      {
        'be/niord-app/pom.xml': '<project/>\n',
        'be/niord/src/main/java/X.java': 'class X {}\n',
        'README.md': 'Niord\n',
      },
      'the base',
    );
    commits.app = commit({ 'be/niord-app/pom.xml': '<project>app</project>\n' }, 'the app pom');
    commits.java = commit({ 'be/niord/src/main/java/X.java': 'class X { }\n' }, 'a class');
  });
  after(() => fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3 }));

  it('finds a scaffold change in a pull request that changes be/niord-app/pom.xml', () => {
    const pr = { base: commits.first, head: commits.app };
    for (const [name, script] of [
      ['be-ci.yml', BE_DETECT],
      ['fe-ci.yml', FE_DETECT],
    ]) {
      const result = detect(script, 'pull_request', pr);
      assert.equal(result.outputs.scaffold, 'true', `${name}: ${result.stdout}`);
    }
  });

  it('finds none in a pull request that changes only a class of niord-base', () => {
    const pr = { base: commits.app, head: commits.java };
    const be = detect(BE_DETECT, 'pull_request', pr);
    assert.equal(be.outputs.backend, 'true', be.stdout);
    assert.equal(be.outputs.scaffold, 'false', be.stdout);
    assert.match(be.summary, /Generated country: \*\*false\*\*/);
    const fe = detect(FE_DETECT, 'pull_request', pr);
    assert.equal(fe.outputs.scaffold, 'false', fe.stdout);
  });

  it("builds the generated country in Backend CI on every push to main, whatever's tested", () => {
    const push = detect(BE_DETECT, 'push', { fakes: noApi });
    assert.equal(push.outputs.tested, 'false', push.stdout);
    assert.equal(push.outputs.scaffold, 'true', push.stdout);
    assert.match(push.summary, /Generated country: \*\*true\*\*/);
    assert.ok(
      push.calls.some((call) => call.startsWith('gh api ')),
      push.calls.join('\n'),
    );
    const dispatch = detect(BE_DETECT, 'workflow_dispatch');
    assert.equal(dispatch.outputs.scaffold, 'true', dispatch.stdout);
  });

  it("leaves main's generated backend to Backend CI in Frontend CI, and builds it on a dispatch", () => {
    const push = detect(FE_DETECT, 'push');
    assert.equal(push.outputs.scaffold, 'false', push.stdout);
    assert.equal(push.outputs.example, 'true', push.stdout);
    assert.match(push.summary, /Generated country's backend in the example: \*\*false\*\*/);
    const dispatch = detect(FE_DETECT, 'workflow_dispatch');
    assert.equal(dispatch.outputs.scaffold, 'true', dispatch.stdout);
    assert.match(dispatch.summary, /Generated country's backend in the example: \*\*true\*\*/);
  });
});
