/**
 * Tests of image:info (image-info.mjs, lib/info-image.mjs) without docker, a browser or the
 * network: an injected runner records every docker and node call and plays a docker that
 * "builds" by keeping the context's browser/ and hands it back on `docker cp`.
 *
 *   npm run test:guards      # from fe/
 *
 * One negative test per refusal: a source map, a sourceMappingURL, an unhashed bundle, a file off
 * the allow-list, a pre-compressed twin, a link, each malformed tag, --package-only in a country,
 * a workspace with no report or a local provenance, a local override while CI is set, an image
 * whose files differ from the dist, a label read back other than the report, a docker or node call
 * that cannot start (a spawn error, named by its code and message), and a missing Chromium. A
 * build with no legal document or no branding text is not refused: the container test is told to
 * skip those probes.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';
import { imageInfo, isCountryWorkspace, legalDocument } from './image-info.mjs';
import {
  assembleContext,
  defaultRun,
  dockerBuildArgs,
  dockerfileLabel,
  imageFilesProblems,
  LABEL_LINE_BYTES,
  labelProblems,
  NGINX_FILES,
  outputProblems,
  parseTag,
  readAllowList,
  runFailure,
} from './lib/info-image.mjs';
import { MARKER, markerText, where } from './lib/where.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;
const EXAMPLE_MANIFEST = path.join(KIT, 'example', 'fe', 'niord-country.json');

/** Text above precompress's 256-byte floor, so the twins are written. */
const BODY = `${'export const words = "the quick brown fox jumps over the lazy dog";\n'.repeat(20)}`;
const INDEX =
  '<!doctype html><html><head><link rel="stylesheet" href="styles-ABCD1234.css"></head>' +
  '<body><app-root></app-root><script src="main-EFGH5678.js" type="module"></script></body></html>';

let tmp;

/** A built public site, as `ng build niord-info` leaves it, under `browser`. */
function writeDist(browser, extra = {}) {
  const files = {
    'index.html': INDEX,
    'main-EFGH5678.js': BODY,
    'styles-ABCD1234.css': `body{margin:0}\n${'/* padding */\n'.repeat(30)}`,
    'chunk-CuJ_9sCH.js': BODY,
    'assets/i18n/zz.json': JSON.stringify({ MENU_DETAILS: 'Ðéţåîļš' }),
    'assets/img/flags/zz.png': Buffer.from([0x89, 0x50, 0x4e, 0x47]),
    ...extra,
  };
  for (const [name, content] of Object.entries(files)) {
    if (content === undefined) continue;
    fs.mkdirSync(path.dirname(path.join(browser, name)), { recursive: true });
    fs.writeFileSync(path.join(browser, name), content);
  }
}

/**
 * A country workspace `<tmp>/fe` with the kit package at <tmp>/.niord/kit, set up from the pins.
 * `extra` adds fields to the build report.
 */
function countryWorkspace({ report = 'pinned', legal = true, branding = true, extra = {} } = {}) {
  const fe = path.join(tmp, 'fe');
  const base = path.join(fe, '.niord', 'base');
  const kit = path.join(tmp, '.niord', 'kit');
  fs.mkdirSync(kit, { recursive: true });
  fs.writeFileSync(path.join(kit, MARKER), markerText('4.0.0-test'));
  fs.mkdirSync(path.join(base, 'ui-apps', 'info', 'nginx'), { recursive: true });
  for (const name of [...NGINX_FILES, 'precompress.mjs'])
    fs.writeFileSync(path.join(base, 'ui-apps', 'info', 'nginx', name), `# ${name}\n`);
  fs.writeFileSync(
    path.join(base, 'ui-apps', 'info', 'nginx', 'niord-security-headers.conf'),
    `add_header Content-Security-Policy "default-src 'self'" always;\n`,
  );
  fs.writeFileSync(path.join(base, 'ui-apps', 'info', 'Dockerfile'), 'FROM scratch\n');
  fs.copyFileSync(EXAMPLE_MANIFEST, path.join(fe, 'niord-country.json'));
  fs.mkdirSync(path.join(fe, '.niord', 'build', 'info', 'legal'), { recursive: true });
  if (legal)
    fs.writeFileSync(
      path.join(fe, '.niord', 'build', 'info', 'legal', 'disclaimer.zz.html'),
      '<p>x</p>',
    );
  if (branding) {
    fs.mkdirSync(path.join(fe, '.niord', 'build', 'info', 'branding'), { recursive: true });
    fs.writeFileSync(path.join(fe, '.niord', 'build', 'info', 'branding', 'zz.json'), '{}');
  }
  if (report) {
    fs.writeFileSync(
      path.join(fe, '.niord', 'build', 'niord-build-report.json'),
      JSON.stringify({
        format: 1,
        base: { version: '4.0.7', sha256: 'ab'.repeat(32) },
        provenance:
          report === 'local'
            ? 'local: ../niord-ng/fe @ 0123abc'
            : 'pinned: org.niord.web:niord-fe-src:4.0.7:zip',
        country: { code: 'xx', name: 'Example country' },
        ...extra,
      }),
    );
  }
  const browser = path.join(fe, 'dist', 'niord-info', 'browser');
  writeDist(browser);
  fs.writeFileSync(path.join(fe, 'dist', 'niord-info', 'browser-stats.json'), '{"outputs":{}}');
  return { fe, kit, browser };
}

/** A label the base image carries itself, which the read-back of the report's labels ignores. */
const BASE_IMAGE_LABEL = { 'org.example.base': 'alpine' };

/** What a runner answers when the process cannot start: no status, the spawn error. */
function spawnError(command, code = 'ENAMETOOLONG') {
  return {
    status: null,
    stdout: '',
    stderr: '',
    error: Object.assign(new Error(`spawnSync ${command} ${code}`), { code }),
  };
}

/** A call's name in `fakeRun`'s `errors`: docker's subcommand, or a node child's script. */
function callName(command, args) {
  if (command !== 'docker') return path.basename(args[0] ?? command);
  return args[0] === 'image' ? `image ${args[1]}` : args[0];
}

/**
 * The labels of the LABEL instructions in a Dockerfile's text, read as Docker reads them: a line
 * ending in a backslash after a character that is not a backslash continues on the next (Docker's
 * own rule, so a line ending in an escaped backslash and one more ends the instruction), a comment
 * line inside a continuation is dropped, and inside double quotes `\\`, `\"` and `\$` stand for
 * the character after the backslash. An unescaped `$` is an error here, since Docker would
 * substitute a variable for it.
 */
function dockerfileLabels(text) {
  const instructions = [];
  let open;
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    if (open !== undefined && /^\s*#/.test(line)) continue;
    const continued = /([^\\])\\[ \t]*$/.exec(line);
    const part = continued ? line.slice(0, continued.index + 1) : line;
    open = (open ?? '') + part;
    if (!continued) {
      instructions.push(open);
      open = undefined;
    }
  }
  const lines = instructions.filter((line) => /^LABEL\s/i.test(line));
  const labels = {};
  for (const line of lines) {
    let i = line.indexOf(' ');
    const quoted = () => {
      if (line[i] !== '"') throw new Error(`a LABEL word at ${i} is not double-quoted`);
      let value = '';
      for (i++; line[i] !== '"'; ) {
        if (i >= line.length) throw new Error('a LABEL value is not closed');
        if (line[i] === '\\' && ['\\', '"', '$'].includes(line[i + 1])) {
          value += line[i + 1];
          i += 2;
        } else if (line[i] === '$') throw new Error(`an unescaped $ at ${i}`);
        else value += line[i++];
      }
      i++;
      return value;
    };
    while (i < line.length) {
      if (line[i] === ' ') {
        i++;
        continue;
      }
      const name = quoted();
      if (line[i++] !== '=') throw new Error(`no = after the label ${name}`);
      labels[name] = quoted();
    }
  }
  return { labels, count: lines.length, last: instructions.filter((line) => line.trim()).at(-1) };
}

/**
 * A runner that records each call and plays docker: `build` keeps a copy of the context it was
 * given, `image inspect` answers the labels of the kept Dockerfile's LABEL line beside the base
 * image's own, `create` answers an id, `cp` copies the kept web root or header file out. `tamper`
 * changes the kept web root before it is copied out; `inspect` changes the labels inspect
 * answers; each call `errors` names (callName) cannot start.
 */
function fakeRun({ tamper, inspect, errors = [] } = {}) {
  const calls = [];
  const image = path.join(tmp, 'image');
  const run = (command, args) => {
    calls.push({ command, args: [...args] });
    if (errors.includes(callName(command, args))) return spawnError(command);
    if (command !== 'docker') return { status: 0, stdout: '', stderr: '' };
    if (args[0] === 'version') return { status: 0, stdout: '29.0.0\n', stderr: '' };
    if (args[0] === 'image' && args[1] === 'inspect') {
      const { labels } = dockerfileLabels(fs.readFileSync(path.join(image, 'Dockerfile'), 'utf8'));
      const answer = { ...BASE_IMAGE_LABEL, ...labels };
      const stdout = `${JSON.stringify(inspect ? inspect(answer) : answer)}\n`;
      return { status: 0, stdout, stderr: '' };
    }
    if (args[0] === 'build') {
      const context = args.at(-1);
      calls.at(-1).context = listing(context);
      fs.cpSync(context, image, { recursive: true });
      return { status: 0, stdout: '', stderr: '' };
    }
    if (args[0] === 'create') return { status: 0, stdout: '0123456789abcdef\n', stderr: '' };
    if (args[0] === 'cp') {
      tamper?.(path.join(image, 'browser'));
      const [from, to] = args.slice(1);
      const source = from.endsWith(':/usr/share/nginx/html')
        ? path.join(image, 'browser')
        : path.join(image, 'nginx', path.basename(from));
      const target = fs.existsSync(to) ? path.join(to, path.basename(source)) : to;
      fs.cpSync(source, target, { recursive: true });
      return { status: 0, stdout: '', stderr: '' };
    }
    return { status: 0, stdout: '', stderr: '' };
  };
  return { run, calls };
}

/** Every file under `dir`, relative and with `/`, sorted. */
function listing(dir) {
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(dir, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}

/** Runs image:info with every output kept; answers the exit code and the printed lines. */
async function runImageInfo({ argv, fe, kit, env = {}, run, chromium = async () => undefined }) {
  const lines = [];
  const code = await imageInfo({
    argv,
    cwd: fe,
    kit,
    env,
    run,
    chromium,
    log: (line) => lines.push(line),
    error: (line) => lines.push(line),
  });
  return { code, output: lines.join('\n') };
}

beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'image-info-'));
});
afterEach(() => fs.rmSync(tmp, { recursive: true, force: true }));

describe('parseTag', () => {
  it('takes a name with a tag, with and without a registry host and port', () => {
    assert.equal(parseTag('niord-xx-info:1').tag, '1');
    assert.deepEqual(parseTag('harbor.example.org:5000/sifa/niord-xx-info:4.0.7.12'), {
      host: 'harbor.example.org:5000',
      path: 'sifa/niord-xx-info',
      tag: '4.0.7.12',
      reference: 'harbor.example.org:5000/sifa/niord-xx-info:4.0.7.12',
    });
  });

  for (const [what, tag] of [
    ['an uppercase name', 'Niord-xx-info:1'],
    ['no tag', 'niord-xx-info'],
    ['an empty tag', 'niord-xx-info:'],
    ['a digest', `niord-xx-info@sha256:${'0'.repeat(64)}`],
    ['a semicolon', 'niord-xx-info;rm:1'],
    ['a leading "-"', '--niord-xx-info:1'],
    ['a space', 'niord xx:1'],
    ['an empty path component', 'harbor.example.org//niord:1'],
    ['more than 255 characters', `${'a'.repeat(250)}:12345`],
  ])
    it(`refuses ${what}`, () => assert.throws(() => parseTag(tag)));

  it('lets the base product leave out the tag, never a country', () => {
    assert.equal(parseTag('niord-dk-info', { requireTag: false }).tag, undefined);
    assert.throws(() => parseTag('niord-dk-info'), /has no tag/);
  });
});

describe('outputProblems', () => {
  const allowed = readAllowList();
  const check = (extra) => {
    const browser = path.join(tmp, 'browser');
    writeDist(browser, extra);
    return outputProblems(browser, allowed);
  };

  it('passes a production build', () => assert.deepEqual(check(), []));

  it('refuses a source map', () => {
    assert.match(
      check({ 'main-EFGH5678.js.map': '{}' }).join('\n'),
      /main-EFGH5678\.js\.map: a source map/,
    );
  });

  it('refuses a sourceMappingURL comment', () => {
    const problems = check({
      'chunk-CuJ_9sCH.js': `${BODY}//# sourceMappingURL=chunk-CuJ_9sCH.js.map\n`,
    });
    assert.match(problems.join('\n'), /chunk-CuJ_9sCH\.js: names a source map/);
  });

  it('refuses an index.html whose bundles carry no content hash (--configuration local)', () => {
    const problems = check({
      'index.html':
        '<!doctype html><link rel="stylesheet" href="styles.css"><script src="main.js" type="module"></script>',
      'main.js': BODY,
      'styles.css': 'body{}',
    });
    assert.match(problems.join('\n'), /index\.html: "main\.js" carries no content hash/);
    assert.match(problems.join('\n'), /index\.html: "styles\.css" carries no content hash/);
  });

  it('refuses a missing index.html', () => {
    assert.match(check({ 'index.html': undefined }).join('\n'), /index\.html: missing/);
  });

  it('refuses a file outside the allow-list', () => {
    const problems = check({ 'assets/i18n/status/x.json': '{}', 'README.txt': 'x' }).join('\n');
    assert.match(problems, /assets\/i18n\/status\/x\.json: not in info-image-allowed\.json/);
    assert.match(problems, /README\.txt: not in info-image-allowed\.json/);
  });

  it('refuses a twin compressed before the context', () => {
    assert.match(check({ 'main-EFGH5678.js.br': 'x' }).join('\n'), /pre-compressed already/);
  });

  it('refuses a symbolic link', (t) => {
    const browser = path.join(tmp, 'browser');
    writeDist(browser);
    try {
      fs.symlinkSync(
        path.join(browser, 'main-EFGH5678.js'),
        path.join(browser, 'chunk-AAAAAAAA.js'),
      );
    } catch {
      t.skip('this account may not create symbolic links');
      return;
    }
    assert.match(outputProblems(browser, allowed).join('\n'), /chunk-AAAAAAAA\.js: a link/);
  });

  it('refuses a hard link', (t) => {
    const browser = path.join(tmp, 'browser');
    writeDist(browser);
    try {
      fs.linkSync(path.join(browser, 'main-EFGH5678.js'), path.join(browser, 'chunk-BBBBBBBB.js'));
    } catch {
      t.skip('this file system may not create hard links');
      return;
    }
    assert.match(outputProblems(browser, allowed).join('\n'), /chunk-BBBBBBBB\.js: a hard link/);
  });
});

describe('assembleContext', () => {
  it('holds only the Dockerfile, the five nginx files and the dist pre-compressed, and leaves the dist as it was', () => {
    const browser = path.join(tmp, 'dist', 'browser');
    writeDist(browser);
    const before = listing(browser);
    const out = path.join(tmp, 'context');
    const totals = assembleContext({ browserDir: browser, baseRoot: FE, out });
    assert.ok(totals.files >= 3, JSON.stringify(totals));
    const files = listing(out);
    assert.deepEqual(
      files.filter((file) => !file.startsWith('browser/')),
      ['Dockerfile', ...NGINX_FILES.map((name) => `nginx/${name}`)],
    );
    const served = files.filter((file) => file.startsWith('browser/')).map((file) => file.slice(8));
    assert.deepEqual(
      served.filter((file) => !/\.(br|gz)$/.test(file)),
      before,
    );
    for (const twin of ['main-EFGH5678.js.br', 'main-EFGH5678.js.gz', 'styles-ABCD1234.css.gz'])
      assert.ok(served.includes(twin), `${twin} in ${served.join(', ')}`);
    assert.deepEqual(listing(browser), before, 'the dist itself is unchanged');
  });

  it('refuses a context folder that is not empty', () => {
    const browser = path.join(tmp, 'dist', 'browser');
    writeDist(browser);
    const out = path.join(tmp, 'context');
    fs.mkdirSync(out);
    fs.writeFileSync(path.join(out, 'left-over'), 'x');
    assert.throws(() => assembleContext({ browserDir: browser, baseRoot: FE, out }), /not empty/);
  });
});

describe('imageFilesProblems', () => {
  function imageOf(browser) {
    const out = path.join(tmp, 'context');
    assembleContext({ browserDir: browser, baseRoot: FE, out });
    return path.join(out, 'browser');
  }

  it('passes an image that holds exactly the dist and its twins', () => {
    const browser = path.join(tmp, 'dist');
    writeDist(browser);
    assert.deepEqual(imageFilesProblems({ distDir: browser, extractedDir: imageOf(browser) }), []);
  });

  it('names a file whose bytes differ, one missing, one extra and a twin of other bytes', () => {
    const browser = path.join(tmp, 'dist');
    writeDist(browser);
    const image = imageOf(browser);
    fs.appendFileSync(path.join(image, 'main-EFGH5678.js'), '\n// changed');
    fs.rmSync(path.join(image, 'assets', 'i18n', 'zz.json'));
    fs.writeFileSync(path.join(image, 'extra.js'), 'x');
    fs.writeFileSync(path.join(image, 'chunk-CuJ_9sCH.js.gz'), zlib.gzipSync('other'));
    const problems = imageFilesProblems({ distDir: browser, extractedDir: image }).join('\n');
    assert.match(problems, /main-EFGH5678\.js: differs between the dist and the image/);
    assert.match(problems, /assets\/i18n\/zz\.json: in the dist but not in the image/);
    assert.match(problems, /extra\.js: in the image but not in the dist/);
    assert.match(problems, /chunk-CuJ_9sCH\.js\.gz: does not unpack to chunk-CuJ_9sCH\.js/);
  });
});

describe('dockerBuildArgs', () => {
  it('is the constant shape with the tag one element, and never a label', () => {
    assert.deepEqual(dockerBuildArgs({ context: path.join(tmp, 'c'), tag: 'x/y:1' }), [
      'build',
      '-f',
      path.join(tmp, 'c', 'Dockerfile'),
      '-t',
      'x/y:1',
      path.join(tmp, 'c'),
    ]);
  });

  it('gives ALPINE_IMAGE its value as one element when a base is given, and nothing without one', () => {
    const base = 'public.ecr.aws/docker/library/alpine:3.22';
    assert.deepEqual(dockerBuildArgs({ context: path.join(tmp, 'c'), tag: 'x/y:1', base }), [
      'build',
      '-f',
      path.join(tmp, 'c', 'Dockerfile'),
      '--build-arg',
      `ALPINE_IMAGE=${base}`,
      '-t',
      'x/y:1',
      path.join(tmp, 'c'),
    ]);
    const without = dockerBuildArgs({
      context: path.join(tmp, 'c'),
      tag: 'x/y:1',
      base: undefined,
    });
    assert.ok(!without.includes('--build-arg'), without.join(' '));
    assert.ok(!without.some((arg) => arg.startsWith('ALPINE_IMAGE')), without.join(' '));
  });
});

describe("the public site's Dockerfile", () => {
  it('takes its base from ALPINE_IMAGE, an Alpine tag by default, declared before the first FROM', () => {
    const lines = fs
      .readFileSync(path.join(FE, 'ui-apps', 'info', 'Dockerfile'), 'utf8')
      .split(/\r?\n/);
    const arg = lines.findIndex((line) => /^ARG ALPINE_IMAGE=/.test(line));
    const from = lines.findIndex((line) => /^FROM\s/i.test(line));
    assert.match(lines[arg] ?? '', /^ARG ALPINE_IMAGE=alpine:[0-9][0-9.]*$/);
    assert.ok(arg >= 0 && arg < from, 'the ARG comes before the first FROM');
    assert.equal(lines[from], 'FROM ${ALPINE_IMAGE}');
    assert.equal(lines.filter((line) => /^FROM\s/i.test(line)).length, 1, 'one stage');
  });
});

describe('dockerfileLabel', () => {
  it('writes one LABEL line that reads back as the values given, in the order given', () => {
    const value = '{"a":"b \\"c\\" \\\\ $HOME ${HOME} $(x) `y` # z","t":"Ðéţåîļš"}';
    const line = dockerfileLabel([
      ['org.example.b', value],
      ['org.example.a', '1'],
    ]);
    assert.equal(line.split('\n').length, 1);
    assert.match(line, /^LABEL "org\.example\.b"=".*" "org\.example\.a"="1"$/);
    assert.deepEqual(dockerfileLabels(line).labels, {
      'org.example.b': value,
      'org.example.a': '1',
    });
    assert.deepEqual(Object.keys(dockerfileLabels(line).labels), [
      'org.example.b',
      'org.example.a',
    ]);
  });

  it('continues a long instruction on lines Docker reads back as one, never starting one with white space or #', () => {
    const value = 'a "b" \\ $c ${HOME} $(x) `y`   # z ## Ðéţåîļš '.repeat(12);
    for (const lineBytes of [16, 17, 24, 31, 64]) {
      const text = dockerfileLabel({ 'org.example.a': value, 'org.example.b': '2' }, { lineBytes });
      const lines = text.split('\n');
      assert.ok(lines.length > 1, `${lineBytes}: continued`);
      for (const [i, line] of lines.entries()) {
        assert.ok(Buffer.byteLength(line) <= lineBytes, `${lineBytes}: "${line}" is too long`);
        if (i > 0) assert.doesNotMatch(line, /^[\s#]/u, `${lineBytes}: "${line}"`);
        if (i < lines.length - 1) assert.match(line, /[^\\]\\$/, `${lineBytes}: "${line}"`);
      }
      assert.deepEqual(dockerfileLabels(text).labels, {
        'org.example.a': value,
        'org.example.b': '2',
      });
    }
  });

  it('never ends a continued line in an escaped backslash, which Docker would not read as continued', () => {
    for (const [value, lineBytes] of [
      ['\\'.repeat(12), 32],
      ['x"'.repeat(200), 24],
      ['a\\"b\\\\c'.repeat(100), 17],
      [JSON.stringify(Array.from({ length: 200 }, () => 'a "b" \\ c')), 25],
    ]) {
      const text = dockerfileLabel({ 'org.example.a': value }, { lineBytes });
      const lines = text.split('\n');
      assert.ok(lines.length > 1, `${lineBytes}: continued`);
      for (const line of lines.slice(0, -1))
        assert.match(line, /[^\\]\\$/, `${lineBytes}: "${line}"`);
      assert.equal(dockerfileLabels(text).labels['org.example.a'], value, `${lineBytes}`);
    }
  });

  it('refuses a run of backslashes longer than a line, which no break Docker reads can part', () => {
    assert.throws(
      () => dockerfileLabel({ 'org.example.a': '\\'.repeat(12) }, { lineBytes: 16 }),
      /cannot be broken into lines of 16 bytes/,
    );
  });

  it('keeps the physical lines of a report of 150 KB within LABEL_LINE_BYTES', () => {
    const value = JSON.stringify({
      hatch: Array.from({ length: 1200 }, (_, i) => ({
        key: `info.k${i}`,
        why: 'a "b" \\ $c # Ðé',
      })),
    }).repeat(3);
    const text = dockerfileLabel({ 'org.example.a': value });
    assert.ok(value.length > 150000);
    const lines = text.split('\n');
    for (const line of lines) assert.ok(Buffer.byteLength(line) <= LABEL_LINE_BYTES);
    for (const line of lines.slice(0, -1)) assert.match(line.slice(-2), /^[^\\]\\$/);
    assert.equal(dockerfileLabels(text).labels['org.example.a'], value);
  });

  it('refuses a value it cannot break without starting a line with #', () => {
    assert.throws(
      () => dockerfileLabel({ a: '#'.repeat(40) }, { lineBytes: 16 }),
      /cannot be broken into lines of 16 bytes/,
    );
  });

  it('takes an object as well, and writes nothing for no labels', () => {
    assert.equal(dockerfileLabel({ 'org.example.a': 'x' }), 'LABEL "org.example.a"="x"');
    assert.equal(dockerfileLabel({}), '');
    assert.equal(dockerfileLabel(), '');
  });

  for (const [what, labels] of [
    ['a newline', { 'org.example.a': 'x\ny' }],
    ['a carriage return', { 'org.example.a': 'x\ry' }],
    ['a tab', { 'org.example.a': 'x\ty' }],
    ['a control character in a name', { 'org.example.\u0001': 'x' }],
    ['an empty name', { '': 'x' }],
    ['a value that is not a string', { 'org.example.a': 1 }],
  ])
    it(`refuses ${what}`, () => assert.throws(() => dockerfileLabel(labels)));
});

describe('assembleContext with labels', () => {
  /** A base product root under `<tmp>/base` whose Dockerfile is `dockerfile`. */
  function baseWith(dockerfile) {
    const root = path.join(tmp, 'base');
    fs.mkdirSync(path.join(root, 'ui-apps', 'info', 'nginx'), { recursive: true });
    for (const name of NGINX_FILES)
      fs.writeFileSync(path.join(root, 'ui-apps', 'info', 'nginx', name), `# ${name}\n`);
    fs.writeFileSync(path.join(root, 'ui-apps', 'info', 'Dockerfile'), dockerfile);
    const browser = path.join(tmp, 'dist', 'browser');
    writeDist(browser);
    return { root, browser, out: path.join(tmp, 'context') };
  }

  it("ends the context's Dockerfile copy with the LABEL line and leaves the base product's as it was", () => {
    const { root, browser, out } = baseWith('# syntax=docker/dockerfile:1\nFROM scratch');
    assembleContext({
      browserDir: browser,
      baseRoot: root,
      out,
      labels: { 'org.example.a': '$x' },
    });
    assert.equal(
      fs.readFileSync(path.join(out, 'Dockerfile'), 'utf8'),
      '# syntax=docker/dockerfile:1\nFROM scratch\nLABEL "org.example.a"="\\$x"\n',
    );
    assert.equal(
      fs.readFileSync(path.join(root, 'ui-apps', 'info', 'Dockerfile'), 'utf8'),
      '# syntax=docker/dockerfile:1\nFROM scratch',
    );
  });

  it('copies the Dockerfile unchanged with no labels', () => {
    const { root, browser, out } = baseWith('FROM scratch\n');
    assembleContext({ browserDir: browser, baseRoot: root, out });
    assert.equal(fs.readFileSync(path.join(out, 'Dockerfile'), 'utf8'), 'FROM scratch\n');
  });

  it('refuses an escape directive behind a byte order mark', () => {
    const bom = String.fromCharCode(0xfeff);
    const { root, browser, out } = baseWith(`${bom}# escape=\`\nFROM scratch\n`);
    assert.throws(
      () => assembleContext({ browserDir: browser, baseRoot: root, out, labels: { a: 'b' } }),
      /parser directive/,
    );
  });

  it('refuses a Dockerfile whose parser directives change the escape character', () => {
    const { root, browser, out } = baseWith(
      '# syntax=docker/dockerfile:1\n# escape=`\nFROM scratch\n',
    );
    assert.throws(
      () => assembleContext({ browserDir: browser, baseRoot: root, out, labels: { a: 'b' } }),
      /parser directive "# escape=`"/,
    );
  });
});

describe('labelProblems', () => {
  const answering = (result) => () => ({ stdout: '', stderr: '', ...result });

  it('passes labels that read back as written, whatever else the image carries', () => {
    const run = answering({ status: 0, stdout: '{"org.example.base":"x","org.example.a":"1"}\n' });
    assert.deepEqual(labelProblems({ run, tag: 'x:1', labels: { 'org.example.a': '1' } }), []);
  });

  it('names a label missing or read back otherwise, by its lengths and never its value', () => {
    const run = answering({ status: 0, stdout: '{"org.example.a":"the value read back"}\n' });
    const problems = labelProblems({
      run,
      tag: 'x:1',
      labels: { 'org.example.a': 'written value', 'org.example.b': 'other' },
    });
    assert.deepEqual(problems, [
      'org.example.a: reads back other than written (read: 19 characters, written: 13 characters)',
      'org.example.b: not on the image (written: 5 characters)',
    ]);
    assert.doesNotMatch(problems.join('\n'), /value read back|written value/);
  });

  it('takes an image with no labels (inspect answers null)', () => {
    const run = answering({ status: 0, stdout: 'null\n' });
    assert.deepEqual(labelProblems({ run, tag: 'x:1', labels: { a: 'b' } }), [
      'a: not on the image (written: 1 character)',
    ]);
  });

  it('names an inspect that fails or cannot start', () => {
    assert.match(
      labelProblems({
        run: answering({ status: 1, stderr: 'No such image\n' }),
        tag: 'x:1',
        labels: {},
      })[0],
      /docker image inspect x:1 failed \(exit 1\): No such image/,
    );
    assert.match(
      labelProblems({ run: () => spawnError('docker', 'ENOENT'), tag: 'x:1', labels: {} })[0],
      /failed \(ENOENT: spawnSync docker ENOENT\)/,
    );
  });
});

describe('runFailure', () => {
  it('names a spawn error by its code and message, a signal, or the exit status', () => {
    assert.equal(runFailure(spawnError('docker')), 'ENAMETOOLONG: spawnSync docker ENAMETOOLONG');
    assert.equal(runFailure({ status: null, signal: 'SIGTERM' }), 'ended by SIGTERM');
    assert.equal(runFailure({ status: 3 }), 'exit 3');
  });
});

describe('image:info in a country workspace', () => {
  it('knows a country workspace by the kit package beside fe/', () => {
    const { fe, kit } = countryWorkspace();
    assert.equal(isCountryWorkspace(kit, fe), true);
    assert.equal(isCountryWorkspace(KIT, FE), false);
    assert.equal(legalDocument(fe, 'zz'), 'disclaimer.zz.html');
  });

  it("knows a country workspace by the kit package's marker at <root>/.niord/kit", () => {
    const kit = path.join(tmp, 'c', '.niord', 'kit');
    fs.mkdirSync(kit, { recursive: true });
    fs.writeFileSync(path.join(kit, MARKER), markerText('4.0.0-test'));
    assert.equal(isCountryWorkspace(kit, path.join(tmp, 'c', 'fe')), true);
    assert.equal(isCountryWorkspace(kit, path.join(tmp, 'c')), false);
    assert.equal(isCountryWorkspace(kit, path.join(tmp, 'other', 'fe')), false);
  });

  it('stops with exit 2 when the kit is in no layout it knows', async () => {
    const kit = path.join(tmp, 'somewhere', 'kit');
    fs.mkdirSync(kit, { recursive: true });
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'x/y:1', '--package-only'],
      fe: tmp,
      kit,
      run: () => assert.fail('nothing is run'),
    });
    assert.equal(code, 2);
    assert.match(output, /^image:info: the kit at .* is in no layout it knows: /);
  });

  it('builds with the report as labels in the Dockerfile copy, reads them back, checks the image it built and leaves the dist unchanged', async () => {
    const { fe, kit, browser } = countryWorkspace();
    const before = listing(browser);
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'harbor.example.org/xx/niord-xx-info:4.0.7.3'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 0, output);

    const build = calls.find((call) => call.args[0] === 'build');
    const report = fs.readFileSync(
      path.join(fe, '.niord', 'build', 'niord-build-report.json'),
      'utf8',
    );
    const context = build.args.at(-1);
    assert.deepEqual(build.args, [
      'build',
      '-f',
      path.join(context, 'Dockerfile'),
      '-t',
      'harbor.example.org/xx/niord-xx-info:4.0.7.3',
      context,
    ]);
    assert.doesNotMatch(output, /the base image is/, 'no base named without ALPINE_IMAGE');
    const kept = dockerfileLabels(fs.readFileSync(path.join(tmp, 'image', 'Dockerfile'), 'utf8'));
    assert.match(kept.last, /^LABEL /, 'the Dockerfile copy ends with the LABEL line');
    assert.deepEqual(kept.labels, {
      'org.niord.build-report': JSON.stringify(JSON.parse(report)),
      'org.opencontainers.image.version': '4.0.7',
    });
    assert.deepEqual(calls[calls.indexOf(build) + 1].args, [
      'image',
      'inspect',
      '--format',
      '{{json .Config.Labels}}',
      'harbor.example.org/xx/niord-xx-info:4.0.7.3',
    ]);
    assert.match(
      output,
      /reads org\.niord\.build-report and org\.opencontainers\.image\.version back as written/,
    );
    assert.ok(path.basename(context).startsWith('niord-info-image-'), context);
    assert.equal(fs.existsSync(context), false, 'the context is removed');
    assert.deepEqual(
      build.context.filter((file) => !file.startsWith('browser/')),
      ['Dockerfile', ...NGINX_FILES.map((name) => `nginx/${name}`)],
    );
    assert.ok(build.context.includes('browser/main-EFGH5678.js.br'));
    assert.deepEqual(listing(browser), before, 'the dist is unchanged');

    const node = calls.filter((call) => call.command === process.execPath).map((call) => call.args);
    assert.equal(node.length, 3, JSON.stringify(node));
    assert.equal(path.basename(node[0][0]), 'info-separation-check.mjs');
    assert.deepEqual(node[0].slice(1), [
      '--stats',
      path.join(fe, 'dist', 'niord-info', 'browser-stats.json'),
      '--root',
      '.niord/base/',
      '--country',
      'xx',
    ]);
    assert.equal(path.basename(node[1][0]), 'info-layout-check.mjs');
    assert.deepEqual([node[1][1], node[1][3]], ['--dist', '--nginx']);
    assert.equal(path.basename(node[2][0]), 'info-container-test.mjs');
    assert.deepEqual(node[2].slice(1), [
      '--image',
      'harbor.example.org/xx/niord-xx-info:4.0.7.3',
      '--legal-doc',
      'disclaimer.zz.html',
      '--language',
      'zz',
      '--browser',
    ]);
    assert.ok(
      calls.some((call) => call.args[0] === 'rm'),
      'the created container is removed',
    );
    for (const call of calls) assert.ok(Array.isArray(call.args));
  });

  it('with --no-browser runs no layout check, no browser part, and says the image is not checked for a push', async () => {
    const { fe, kit } = countryWorkspace();
    const { run, calls } = fakeRun();
    let asked = false;
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1', '--no-browser'],
      fe,
      kit,
      run,
      chromium: async () => {
        asked = true;
        return 'no Chromium';
      },
    });
    assert.equal(code, 0, output);
    assert.equal(asked, false);
    const node = calls.filter((call) => call.command === process.execPath).map((call) => call.args);
    assert.deepEqual(
      node.map((args) => path.basename(args[0])),
      ['info-separation-check.mjs', 'info-container-test.mjs'],
    );
    assert.ok(!node[1].includes('--browser'));
    assert.match(output, /not checked for a push/);
  });

  it('fails when the image does not hold the dist the checks read', async () => {
    const { fe, kit } = countryWorkspace();
    const { run } = fakeRun({
      tamper: (web) => fs.appendFileSync(path.join(web, 'main-EFGH5678.js'), '\n// swapped'),
    });
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 1, output);
    assert.match(output, /main-EFGH5678\.js: differs between the dist and the image/);
  });

  it('fails when a check against the image fails', async () => {
    const { fe, kit } = countryWorkspace();
    const { run: docker } = fakeRun();
    const run = (command, args, options) =>
      command === process.execPath && args[0].endsWith('info-separation-check.mjs')
        ? { status: 1, stdout: '', stderr: '' }
        : docker(command, args, options);
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 1, output);
    assert.match(output, /the separation check \(exit 1\)/);
  });

  it('exits 2 when Chromium is missing and --no-browser is not given, before docker builds', async () => {
    const { fe, kit } = countryWorkspace();
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
      chromium: async () => 'Chromium does not start: no executable',
    });
    assert.equal(code, 2, output);
    assert.match(output, /npx playwright install --only-shell chromium/);
    assert.equal(calls.length, 0);
  });

  it('refuses --package-only', async () => {
    const { fe, kit } = countryWorkspace();
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1', '--package-only'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 2, output);
    assert.match(output, /always checked before a push/);
    assert.equal(calls.length, 0);
  });

  it('refuses a workspace with no build report', async () => {
    const { fe, kit } = countryWorkspace({ report: false });
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 1, output);
    assert.match(output, /niord-build-report\.json does not exist/);
    assert.equal(calls.length, 0);
  });

  it('refuses a workspace set up from a local source', async () => {
    const { fe, kit } = countryWorkspace({ report: 'local' });
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 1, output);
    assert.match(output, /set up from a local source/);
    assert.equal(calls.length, 0);
  });

  for (const name of ['NIORD_BASE', 'NIORD_PACK_DA'])
    it(`refuses ${name} while CI is set`, async () => {
      const { fe, kit } = countryWorkspace();
      const { run, calls } = fakeRun();
      const { code, output } = await runImageInfo({
        argv: ['--tag', 'niord-xx-info:1'],
        fe,
        kit,
        run,
        env: { CI: 'true', [name]: '../niord-ng/fe' },
      });
      assert.equal(code, 1, output);
      assert.match(output, new RegExp(`${name} is set while CI is`));
      assert.equal(calls.length, 0);
    });

  /** The container test's arguments after the script, in a run over `workspace`. */
  async function containerTestArgs(workspace) {
    const { fe, kit } = countryWorkspace(workspace);
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 0, output);
    const test = calls.find(
      (call) =>
        call.command === process.execPath && call.args[0].endsWith('info-container-test.mjs'),
    );
    return { args: test.args.slice(1), output };
  }

  it('warns, and has the container test skip its legal-document probes, for a build that staged no legal document', async () => {
    const { args, output } = await containerTestArgs({ legal: false });
    assert.deepEqual(args, [
      '--image',
      'niord-xx-info:1',
      '--no-legal-doc',
      '--language',
      'zz',
      '--browser',
    ]);
    assert.match(
      output,
      /warning: \.niord\/build\/info\/legal\/ holds no legal document.*add info\/assets\/legal\/disclaimer\.zz\.html/,
    );
    assert.doesNotMatch(output, /branding/);
  });

  it('warns, and has the container test skip its branding-text probe, for a build that staged no branding text', async () => {
    const { args, output } = await containerTestArgs({ branding: false });
    assert.deepEqual(args, [
      '--image',
      'niord-xx-info:1',
      '--legal-doc',
      'disclaimer.zz.html',
      '--language',
      'zz',
      '--no-branding-text',
      '--browser',
    ]);
    assert.match(
      output,
      /warning: \.niord\/build\/info\/branding\/ holds no zz\.json.*add info\/assets\/branding\/zz\.json/,
    );
  });

  it('asks for both with both staged, and warns of neither', async () => {
    const { args, output } = await containerTestArgs({});
    assert.deepEqual(args, [
      '--image',
      'niord-xx-info:1',
      '--legal-doc',
      'disclaimer.zz.html',
      '--language',
      'zz',
      '--browser',
    ]);
    assert.doesNotMatch(output, /warning/);
  });

  it('refuses a dist that is not a production build, before docker is asked', async () => {
    const { fe, kit, browser } = countryWorkspace();
    fs.writeFileSync(path.join(browser, 'main-EFGH5678.js.map'), '{}');
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'niord-xx-info:1'],
      fe,
      kit,
      run,
    });
    assert.equal(code, 1, output);
    assert.match(output, /a source map/);
    assert.equal(calls.length, 0);
  });

  for (const tag of [
    'niord-xx-info',
    'Niord:1',
    'x;y:1',
    '-x:1',
    'a b:1',
    `x@sha256:${'0'.repeat(64)}`,
  ])
    it(`exits 2 on the tag "${tag.slice(0, 20)}"`, async () => {
      const { fe, kit } = countryWorkspace();
      const { run, calls } = fakeRun();
      const { code, output } = await runImageInfo({ argv: ['--tag', tag], fe, kit, run });
      assert.equal(code, 2, output);
      assert.equal(calls.length, 0);
    });
});

describe("image:info's labels and the calls that cannot start", () => {
  const TAG = 'harbor.example.org/xx/niord-xx-info:4.0.7.3';

  /** The build report of the workspace in `fe`, as image:info reads it. */
  const reportOf = (fe) =>
    JSON.parse(
      fs.readFileSync(path.join(fe, '.niord', 'build', 'niord-build-report.json'), 'utf8'),
    );

  it('names the code and message of a docker build that cannot start', async () => {
    const { fe, kit } = countryWorkspace();
    const { run } = fakeRun({ errors: ['build'] });
    const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
    assert.equal(code, 1, output);
    assert.match(
      output,
      /docker build of \S+ failed \(ENAMETOOLONG: spawnSync docker ENAMETOOLONG\)/,
    );
  });

  it('names the code and message of a docker version that cannot start', async () => {
    const { fe, kit } = countryWorkspace();
    const { run } = fakeRun({ errors: ['version'] });
    const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
    assert.equal(code, 2, output);
    assert.match(output, /docker does not answer.*ENAMETOOLONG: spawnSync docker ENAMETOOLONG/);
  });

  for (const [what, name, expected] of [
    [
      'a node child',
      'info-separation-check.mjs',
      /the separation check \(ENAMETOOLONG: spawnSync /,
    ],
    [
      'docker create',
      'create',
      /docker create \S+ failed.*ENAMETOOLONG: spawnSync docker ENAMETOOLONG/,
    ],
    ['docker cp', 'cp', /docker cp out of \S+ failed.*ENAMETOOLONG: spawnSync docker ENAMETOOLONG/],
  ])
    it(`names the code and message of ${what} that cannot start`, async () => {
      const { fe, kit } = countryWorkspace();
      const { run } = fakeRun({ errors: [name] });
      const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
      assert.equal(code, 1, output);
      assert.match(output, expected);
      assert.doesNotMatch(output, /exit none/);
    });

  it('warns of a docker rm that cannot start, and the checks still pass', async () => {
    const { fe, kit } = countryWorkspace();
    const { run } = fakeRun({ errors: ['rm'] });
    const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
    assert.equal(code, 0, output);
    assert.match(output, /warning: docker rm .*ENAMETOOLONG: spawnSync docker ENAMETOOLONG/);
  });

  it('passes no docker argument longer than 4096 characters with a build report of 40 KB', async () => {
    const hatch = Array.from({ length: 400 }, (_, i) => ({
      key: `info.example_section.example_key_${String(i).padStart(4, '0')}`,
      reason: 'accepted for the example country until its own text is written',
    }));
    const { fe, kit } = countryWorkspace({ extra: { hatch } });
    assert.ok(JSON.stringify(reportOf(fe)).length > 40000);
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
    assert.equal(code, 0, output);
    for (const call of calls.filter((call) => call.command === 'docker'))
      for (const arg of call.args)
        assert.ok(
          arg.length <= 4096,
          `docker ${call.args[0]}: an argument of ${arg.length} characters`,
        );
  });

  it("writes the labels as the last instruction of the context's Dockerfile, and a report of any characters round-trips", async () => {
    const note =
      'a "quote", a \\ backslash, $ and ${HOME} and $(x), a `tick`, # not a comment, Ðéţåîļš';
    const { fe, kit } = countryWorkspace({ extra: { note } });
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
    assert.equal(code, 0, output);
    const { labels, count, last } = dockerfileLabels(
      fs.readFileSync(path.join(tmp, 'image', 'Dockerfile'), 'utf8'),
    );
    assert.equal(count, 1, 'one LABEL instruction');
    assert.match(last, /^LABEL /);
    assert.deepEqual(labels, {
      'org.niord.build-report': JSON.stringify(reportOf(fe)),
      'org.opencontainers.image.version': '4.0.7',
    });
    assert.equal(JSON.parse(labels['org.niord.build-report']).note, note);
    const names = calls.map((call) => callName(call.command, call.args));
    assert.equal(names.indexOf('image inspect'), names.indexOf('build') + 1, names.join(', '));
  });

  it('fails, naming the label and not its value, when the image carries another report, and checks nothing after', async () => {
    const { fe, kit } = countryWorkspace();
    const { run, calls } = fakeRun({
      inspect: (labels) => ({
        ...labels,
        'org.niord.build-report': `${labels['org.niord.build-report'] ?? ''} `,
      }),
    });
    const { code, output } = await runImageInfo({ argv: ['--tag', TAG], fe, kit, run });
    assert.equal(code, 1, output);
    assert.match(output, /org\.niord\.build-report/);
    assert.doesNotMatch(output, /niord-fe-src:4\.0\.7:zip/, 'the value is not printed');
    const names = calls.map((call) => callName(call.command, call.args));
    assert.ok(names.includes('image inspect'), names.join(', '));
    assert.deepEqual(names.slice(names.indexOf('image inspect') + 1), [], names.join(', '));
  });

  it('defaultRun answers no status and the spawn error of a command that does not exist', () => {
    const result = defaultRun('niord-no-such-command-p', ['--version'], { capture: true });
    assert.equal(result.status, null);
    assert.equal(result.error?.code, 'ENOENT');
  });
});

describe('image:info in the base product', () => {
  it('with --package-only builds with no label, takes a tag without a version and checks nothing', async () => {
    const fe = path.join(tmp, 'fe');
    writeDist(path.join(fe, 'dist', 'niord-info', 'browser'));
    const { run, calls } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'p3c1-test-info', '--package-only'],
      fe,
      kit: KIT,
      run,
    });
    assert.equal(code, 0, output);
    assert.deepEqual(
      calls.map((call) => call.args[0]),
      ['version', 'build'],
    );
    assert.ok(!calls[1].args.includes('--label'));
    assert.equal(calls[1].args[calls[1].args.indexOf('-t') + 1], 'p3c1-test-info');
    const kept = dockerfileLabels(fs.readFileSync(path.join(tmp, 'image', 'Dockerfile'), 'utf8'));
    assert.equal(kept.count, 0, 'no LABEL line in the Dockerfile copy');
    assert.equal(
      fs.readFileSync(path.join(tmp, 'image', 'Dockerfile'), 'utf8'),
      fs.readFileSync(path.join(FE, 'ui-apps', 'info', 'Dockerfile'), 'utf8'),
    );
  });

  it('builds on ALPINE_IMAGE and names it before the build when set, and neither when unset', async () => {
    const fe = path.join(tmp, 'fe');
    writeDist(path.join(fe, 'dist', 'niord-info', 'browser'));
    const mirror = 'public.ecr.aws/docker/library/alpine:3.22';
    const argv = ['--tag', 'p3c1-test-info', '--package-only'];
    const buildArgs = (calls) => calls.find((call) => call.args[0] === 'build').args;
    const setRun = fakeRun();
    const set = await runImageInfo({
      argv,
      fe,
      kit: KIT,
      env: { ALPINE_IMAGE: mirror },
      run: setRun.run,
    });
    assert.equal(set.code, 0, set.output);
    const args = buildArgs(setRun.calls);
    assert.equal(args[args.indexOf('--build-arg') + 1], `ALPINE_IMAGE=${mirror}`, args.join(' '));
    const lines = set.output.split('\n');
    const named = lines.indexOf(`image:info: the base image is ${mirror} (ALPINE_IMAGE)`);
    assert.ok(named >= 0, set.output);
    assert.ok(named < lines.findIndex((line) => line.startsWith('image:info: built ')));

    const unsetRun = fakeRun();
    const unset = await runImageInfo({ argv, fe, kit: KIT, run: unsetRun.run });
    assert.equal(unset.code, 0, unset.output);
    assert.doesNotMatch(unset.output, /base image|ALPINE_IMAGE/);
    assert.ok(!buildArgs(unsetRun.calls).includes('--build-arg'), 'no build argument when unset');
  });

  it('refuses to run without --package-only', async () => {
    const fe = path.join(tmp, 'fe');
    writeDist(path.join(fe, 'dist', 'niord-info', 'browser'));
    const { run, calls } = fakeRun();
    const { code } = await runImageInfo({ argv: ['--tag', 'x:1'], fe, kit: KIT, run });
    assert.equal(code, 2);
    assert.equal(calls.length, 0);
  });

  it('exits 2 on an unknown option', async () => {
    const { run } = fakeRun();
    const { code, output } = await runImageInfo({
      argv: ['--tag', 'x:1', '--push'],
      fe: tmp,
      kit: KIT,
      run,
    });
    assert.equal(code, 2);
    assert.match(output, /usage:/);
  });
});
