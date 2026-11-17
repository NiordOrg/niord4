/**
 * What the workflows check out: never the user guides' pictures, outside the one workflow that
 * builds the guides. The screenshots live in guides/<sub-site>/images/ (tens of megabytes, and
 * every retake adds to the history) and only guides.yml reads them, so every other step that uses
 * actions/checkout names a sparse-checkout of one of two forms (be-ci.yml's header, What a job
 * checks out):
 *
 *  - the whole tree but the pictures: exactly the two gitignore-style lines `/*` and
 *    `!/guides/**\/images/` (sparse-checkout-cone-mode: false), nothing before, between or after
 *    them. git matches each file against the lines before it looks at the file's folders, so any
 *    further line that names files (`*.png`, `/guides/**`) takes pictures back even when the
 *    exclusion comes last;
 *  - a few folders in cone mode, the action's default, none of them the root, guides/ or a
 *    folder inside it.
 *
 * With either, actions/checkout makes a partial clone and downloads only the files the sparse set
 * puts on disk. A checkout with neither downloads every picture of the commit, and with
 * fetch-depth: 0 every picture in the history. The whole-tree patterns are also applied by git
 * itself, in a repository of the test's own shaped like guides/, so the rule is held to what git
 * does with it and not only to its text.
 *
 *   npm run test:guards      # from fe/
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WORKFLOWS = path.join(ROOT, '.github', 'workflows');

/** The workflow that builds the guides: the one checkout that takes the pictures. */
const GUIDES = 'guides.yml';

/** The exclusion of the whole-tree form: no folder named images anywhere under guides/. */
const NO_PICTURES = '!/guides/**/images/';

/** The whole-tree form, line for line: every path at the root, then the exclusion. */
const WHOLE_TREE = ['/*', NO_PICTURES];

const WHAT_TO_ADD = [
  'give it the whole tree but the pictures,',
  '    sparse-checkout: |',
  '      /*',
  `      ${NO_PICTURES}`,
  '    sparse-checkout-cone-mode: false',
  'or, when it reads a few folders, name them (sparse-checkout: be); only guides.yml checks the',
  "guides' pictures out (be-ci.yml's header, What a job checks out)",
].join('\n');

/** A sparse-checkout's lines, each trimmed, blank ones dropped. */
const patternLines = (value) =>
  String(value ?? '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

/** Whether a step's `with:` asks for gitignore-style patterns rather than cone mode. */
const nonCone = (inputs) => String(inputs['sparse-checkout-cone-mode']).trim() === 'false';

/**
 * Why a checkout with these `with:` inputs would download the guides' pictures, or null when it
 * would not.
 */
function picturesProblem(inputs = {}) {
  const lines = patternLines(inputs['sparse-checkout']);
  if (lines.length === 0) return 'has no sparse-checkout';
  // A filter of the step's own replaces the blob:none the action gives a sparse checkout, and one
  // that lets files through (blob:limit=10m) downloads the pictures the sparse set keeps off disk.
  if (inputs.filter !== undefined && String(inputs.filter).trim() !== 'blob:none')
    return `sets filter: ${inputs.filter}, which replaces the blob:none a sparse checkout gets and can let the pictures through`;
  if (nonCone(inputs))
    return lines.join('\n') === WHOLE_TREE.join('\n')
      ? null
      : `has gitignore-style patterns other than exactly ${WHOLE_TREE.join(' and ')}, which a line naming files can undo wherever it stands`;
  const patterns = lines.filter((line) => /[*?[\]!]/.test(line));
  if (patterns.length)
    return `names ${patterns.join(', ')} in cone mode, which takes folders, not patterns (set sparse-checkout-cone-mode: false)`;
  const wide = lines.filter((line) => {
    const folder = line.replace(/^\/+|\/+$/g, '');
    return folder === '' || folder === '.' || folder === 'guides' || folder.startsWith('guides/');
  });
  return wide.length ? `takes ${wide.join(', ')} in cone mode, which holds the pictures` : null;
}

/** Every step of every workflow that uses actions/checkout: `{ file, job, index, inputs }`. */
function checkouts() {
  return fs
    .readdirSync(WORKFLOWS)
    .filter((file) => /\.ya?ml$/.test(file))
    .sort()
    .flatMap((file) => {
      const workflow = yaml.load(fs.readFileSync(path.join(WORKFLOWS, file), 'utf8'));
      return Object.entries(workflow.jobs ?? {}).flatMap(([job, { steps = [] }]) =>
        steps
          .map((step, index) => ({ file, job, index, step }))
          .filter(({ step }) => String(step.uses ?? '').startsWith('actions/checkout@'))
          .map(({ step, ...where }) => ({ ...where, inputs: step.with ?? {} })),
      );
    });
}

describe("the workflows' checkouts", () => {
  const all = checkouts();

  it('finds the checkouts of every workflow it guards', () => {
    const files = new Set(all.map(({ file }) => file));
    for (const file of [
      'be-ci.yml',
      'fe-ci.yml',
      'info-ci.yml',
      'publish.yml',
      'retract-version.yml',
      GUIDES,
    ])
      assert.ok(files.has(file), `no actions/checkout step found in ${file}`);
  });

  it("leaves the guides' pictures out of every checkout but guides.yml's", () => {
    const problems = all
      .filter(({ file }) => file !== GUIDES)
      .map(({ file, job, index, inputs }) => {
        const problem = picturesProblem(inputs);
        return problem && `${file}, job ${job}, step ${index + 1} (actions/checkout) ${problem}`;
      })
      .filter(Boolean);
    assert.deepEqual(problems, [], `${problems.join('\n')}\n${WHAT_TO_ADD}`);
  });

  it('checks the whole tree out in guides.yml, whose build reads the pictures', () => {
    const guides = all.filter(({ file }) => file === GUIDES);
    assert.equal(guides.length, 1);
    assert.deepEqual(guides[0].inputs, {}, 'guides.yml checks out everything, with no inputs');
  });

  it('refuses a checkout that downloads the pictures, and takes the two forms', () => {
    const whole = { 'sparse-checkout': `/*\n${NO_PICTURES}\n`, 'sparse-checkout-cone-mode': false };
    for (const inputs of [
      undefined,
      {},
      { 'fetch-depth': 0 },
      { 'sparse-checkout': '' },
      { 'sparse-checkout': '/*\n', 'sparse-checkout-cone-mode': false },
      { 'sparse-checkout': `/*\n${NO_PICTURES}\n/guides/\n`, 'sparse-checkout-cone-mode': false },
      { 'sparse-checkout': `/*\n!/guides/*/images/\n`, 'sparse-checkout-cone-mode': false },
      // A line that matches files decides them before the exclusion of their folder is read.
      { 'sparse-checkout': `/*\n*.png\n${NO_PICTURES}\n`, 'sparse-checkout-cone-mode': false },
      {
        'sparse-checkout': `/*\n/guides/**/*.jpg\n${NO_PICTURES}\n`,
        'sparse-checkout-cone-mode': false,
      },
      { 'sparse-checkout': `/*\n/guides/**\n${NO_PICTURES}\n`, 'sparse-checkout-cone-mode': false },
      { 'sparse-checkout': `/guides/**\n/*\n${NO_PICTURES}\n`, 'sparse-checkout-cone-mode': false },
      { 'sparse-checkout': `${NO_PICTURES}\n/*\n`, 'sparse-checkout-cone-mode': false },
      { 'sparse-checkout': `${NO_PICTURES}\n`, 'sparse-checkout-cone-mode': false },
      // Cone mode reads the exclusion as a folder's name and takes everything else.
      { 'sparse-checkout': `/*\n${NO_PICTURES}\n` },
      { 'sparse-checkout': `/*\n${NO_PICTURES}\n`, 'sparse-checkout-cone-mode': true },
      { 'sparse-checkout': 'guides' },
      { 'sparse-checkout': 'be\nguides/editor-manual\n' },
      { 'sparse-checkout': '/guides/' },
      { 'sparse-checkout': '.' },
      { 'sparse-checkout': '/' },
      // A filter that lets files through downloads what the sparse set only keeps off disk.
      { ...whole, filter: 'blob:limit=10m' },
      { 'sparse-checkout': 'be', filter: 'tree:0' },
    ])
      assert.notEqual(picturesProblem(inputs), null, JSON.stringify(inputs));
    for (const inputs of [
      whole,
      { ...whole, 'sparse-checkout-cone-mode': 'false' },
      { 'sparse-checkout': 'be' },
      { 'sparse-checkout': '.github' },
      { 'sparse-checkout': 'be/k8s\nbe/niord/sample\n' },
      { 'sparse-checkout': 'fe/scripts', 'fetch-depth': 0 },
      { 'sparse-checkout': 'guidesx' },
      { ...whole, filter: 'blob:none' },
    ])
      assert.equal(picturesProblem(inputs), null, JSON.stringify(inputs));
  });

  it('finds every picture of the guides in a folder named images, where the checkouts skip it', () => {
    // The exclusion goes by the folder's name, so a picture stored anywhere else under guides/
    // (a screenshots/ folder, Images/, loose beside the text) is downloaded by every job.
    const listed = spawnSync('git', ['ls-files', '-z', '--', 'guides'], {
      cwd: ROOT,
      encoding: 'utf8',
    });
    assert.equal(listed.status, 0, `git ls-files guides: ${listed.stderr}`);
    const stray = listed.stdout
      .split('\0')
      .filter((file) => /\.(png|jpe?g|gif|svg|webp|bmp|ico|pdf)$/i.test(file))
      .filter((file) => !/^guides\/(.*\/)?images\//.test(file));
    assert.deepEqual(
      stray,
      [],
      'move each into an images/ folder of its sub-site (guides/<sub-site>/images/): the CI checkouts leave out the folders of that name and no other',
    );
  });
});

describe('the whole-tree patterns, as git applies them', () => {
  let dir;
  const files = {
    'README.md': 'root\n',
    'be/pom.xml': '<project/>\n',
    'fe/package.json': '{}\n',
    'docs/plan.md': 'plan\n',
    'guides/README.md': 'guides\n',
    'guides/build.sh': 'echo build\n',
    'guides/editor-manual/manual.adoc': '= Manual\n',
    'guides/editor-manual/images/editor.png': 'png\n',
    'guides/editor-manual/images/photo.jpg': 'jpg\n',
    'guides/editor-manual/images/detail/map.png': 'png\n',
    'guides/editor-manual/images/detail/chart.svg': '<svg/>\n',
    'guides/images/niord.png': 'png\n',
    'guides/images/spinner.gif': 'gif\n',
    'guides/_parked/secom/README.md': 'parked\n',
    'guides/_parked/secom/images/portal.png': 'png\n',
    'guides/_parked/secom/images/flow.svg': '<svg/>\n',
    'fe/ui-apps/admin/public/images/logo.png': 'png\n',
  };
  const pictures = Object.keys(files).filter((file) => /^guides\/(.*\/)?images\//.test(file));
  // A repository of the test's own: no user or system configuration, and an identity of its own.
  const git = (cwd, ...args) => {
    const inherited = Object.fromEntries(
      Object.entries(process.env).filter(([key]) => !key.startsWith('GIT_')),
    );
    const result = spawnSync('git', args, {
      cwd,
      encoding: 'utf8',
      env: {
        ...inherited,
        GIT_CONFIG_GLOBAL: path.join(dir, 'gitconfig'),
        GIT_CONFIG_NOSYSTEM: '1',
        GIT_AUTHOR_NAME: 'Niord test',
        GIT_AUTHOR_EMAIL: 'test@niord.invalid',
        GIT_COMMITTER_NAME: 'Niord test',
        GIT_COMMITTER_EMAIL: 'test@niord.invalid',
      },
    });
    assert.equal(result.status, 0, `git ${args.join(' ')}: ${result.stderr}`);
    return result.stdout;
  };

  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-ci-checkouts-'));
    fs.writeFileSync(path.join(dir, 'gitconfig'), '');
    const source = path.join(dir, 'source');
    fs.mkdirSync(source);
    git(source, 'init', '-q');
    for (const [relative, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(source, relative)), { recursive: true });
      fs.writeFileSync(path.join(source, relative), text);
    }
    git(source, 'add', '-A');
    git(source, 'commit', '-q', '-m', 'the tree');
  });
  after(() => fs.rmSync(dir, { recursive: true, force: true, maxRetries: 3 }));

  it('leave out every folder named images under guides/, and nothing else', () => {
    const lists = [
      ...new Set(
        checkouts()
          .filter(({ inputs }) => nonCone(inputs) && patternLines(inputs['sparse-checkout']).length)
          .map(({ inputs }) => patternLines(inputs['sparse-checkout']).join('\n')),
      ),
    ];
    assert.ok(lists.length > 0, 'no workflow has a whole-tree checkout');
    lists.forEach((list, n) => {
      // As actions/checkout sets it up: core.sparseCheckout, the patterns appended to
      // info/sparse-checkout, then the checkout.
      const clone = path.join(dir, `clone-${n}`);
      git(dir, 'clone', '-q', '--no-checkout', path.join(dir, 'source'), clone);
      git(clone, 'config', 'core.sparseCheckout', 'true');
      const gitDir = git(clone, 'rev-parse', '--absolute-git-dir').trim();
      fs.mkdirSync(path.join(gitDir, 'info'), { recursive: true });
      fs.appendFileSync(path.join(gitDir, 'info', 'sparse-checkout'), `\n${list}\n`);
      git(clone, 'checkout', '-q', '--force', 'HEAD');
      const present = Object.keys(files).filter((file) => fs.existsSync(path.join(clone, file)));
      assert.deepEqual(
        present,
        Object.keys(files).filter((file) => !pictures.includes(file)),
        `the patterns\n${list}\nput on disk:\n${present.join('\n')}`,
      );
    });
  });
});
