import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { markerText, where } from '../lib/where.mjs';
import { writeZip } from '../lib/zip.mjs';
import {
  backendEnv,
  bashPath,
  dockerRefusal,
  lazyChunkProblems,
  main,
  mavenRunner,
  parseArgs,
  previousKitZip,
  previousZip,
  textOverrideProblems,
} from './run.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** A built application's folder: index.html loading main and preloading one chunk, and `chunks`. */
function dist(chunks) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-dist-'));
  temporary.push(dir);
  fs.writeFileSync(
    path.join(dir, 'index.html'),
    '<html><head><link rel="modulepreload" href="chunk-SHARED.js"></head>' +
      '<body><app-root></app-root><script src="main-ABC.js" type="module"></script></body></html>',
  );
  for (const [file, text] of Object.entries({
    'main-ABC.js': 'bootstrap()',
    'chunk-SHARED.js': 'shared()',
    ...chunks,
  }))
    fs.writeFileSync(path.join(dir, file), text);
  return dir;
}

describe('the lazy-chunk check of the example harness', () => {
  it('passes a selector found only in a lazy chunk', () => {
    const where = dist({ 'chunk-LAZY.js': 'selectors:[["app-harbour-page"]]' });
    assert.deepEqual(lazyChunkProblems({ dist: where, markers: ['app-harbour-page'] }), []);
  });

  it('fails a selector in the main chunk', () => {
    const where = dist({ 'main-ABC.js': 'selectors:[["app-harbour-page"]]' });
    assert.deepEqual(lazyChunkProblems({ dist: where, markers: ['app-harbour-page'] }), [
      'app-harbour-page is in main-ABC.js, which index.html loads on the first visit',
      `app-harbour-page is in no lazy chunk of ${where}`,
    ]);
  });

  it('fails a selector in a chunk index.html preloads', () => {
    const where = dist({
      'chunk-SHARED.js': 'selectors:[["app-welcome-note"]]',
      'chunk-LAZY.js': 'selectors:[["app-welcome-note"]]',
    });
    assert.deepEqual(lazyChunkProblems({ dist: where, markers: ['app-welcome-note'] }), [
      'app-welcome-note is in chunk-SHARED.js, which index.html loads on the first visit',
    ]);
  });

  it('fails a selector in no chunk at all', () => {
    const where = dist({});
    assert.deepEqual(lazyChunkProblems({ dist: where, markers: ['app-about-page'] }), [
      `app-about-page is in no lazy chunk of ${where}`,
    ]);
  });
});

describe('the text-override check of the example harness', () => {
  /** A country fe/ with an English override, and a built dist whose en bundle holds `text`. */
  function built(text) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-overrides-'));
    temporary.push(root);
    const fe = path.join(root, 'fe');
    fs.mkdirSync(path.join(fe, 'text-overrides'), { recursive: true });
    fs.writeFileSync(
      path.join(fe, 'text-overrides', 'en.json'),
      JSON.stringify({ admin: { 'main.header.domain.label': { text: 'Waters', fp: 'x' } } }),
    );
    const i18n = path.join(fe, 'dist', 'niord-app', 'browser', 'assets', 'i18n');
    fs.mkdirSync(i18n, { recursive: true });
    fs.writeFileSync(
      path.join(i18n, 'en.json'),
      JSON.stringify({ main: { header: { domain: { label: text } } } }),
    );
    return { fe, dist: path.join(fe, 'dist') };
  }

  it('passes an override whose text is in the built bundle', () => {
    assert.deepEqual(textOverrideProblems(built('Waters')), []);
  });

  it('fails an override the built bundle does not carry', () => {
    assert.deepEqual(textOverrideProblems(built('Domain')), [
      `main.header.domain.label is "Domain" in niord-app's en bundle, not the override's "Waters"`,
    ]);
  });

  it('fails an override of a language the build does not ship', () => {
    const where = built('Waters');
    fs.renameSync(
      path.join(where.fe, 'text-overrides', 'en.json'),
      path.join(where.fe, 'text-overrides', 'zz.json'),
    );
    assert.deepEqual(textOverrideProblems(where), [
      'niord-app has no zz bundle, which text-overrides/zz.json overrides',
    ]);
  });
});

// Each refusal throws before the harness builds, installs or copies anything.
describe('the example harness', () => {
  // A path that does not exist, outside the tree and the temp fixtures: a refusal must leave it unmade.
  const outside = path.join(os.tmpdir(), `niord-example-test-${process.pid}`);

  it('refuses a --work inside fe/ or inside country-kit/, the two roots the zip is built from', () => {
    assert.throws(
      () => parseArgs(['--work', path.join(where().base, 'example-work')]),
      /--work .* is inside the base product's fe\//,
    );
    assert.throws(
      () => parseArgs(['--work', path.join(where().kit, 'example', 'work')]),
      /--work .* is inside the base product's country-kit\//,
    );
  });

  it('refuses a --work or --maven-repo path that MAVEN_ARGS would split or cmd.exe would read', () => {
    assert.throws(
      () => main(['--work', path.join(os.tmpdir(), 'with space', 'example')]),
      /--work .* has a space or a character cmd\.exe reads/,
    );
    assert.throws(
      () => main(['--work', outside, '--maven-repo', path.join(os.tmpdir(), 'a&b', 'repository')]),
      /--maven-repo .* has a space or a character cmd\.exe reads/,
    );
  });

  it('takes --image alone and --with-deps only with it, neither with a value', () => {
    assert.deepEqual(
      (({ image, withDeps }) => ({ image, withDeps }))(parseArgs(['--work', outside, '--image'])),
      { image: true, withDeps: false },
    );
    assert.equal(parseArgs(['--work', outside, '--image', '--with-deps']).withDeps, true);
    assert.equal(parseArgs(['--work', outside]).image, false);
    assert.throws(
      () => parseArgs(['--work', outside, '--with-deps']),
      /--with-deps .* comes with --image/,
    );
    assert.throws(() => parseArgs(['--work', outside, '--image', 'yes']), /unknown argument "yes"/);
  });

  it('refuses --upgrade-from without a zip, with a missing one and with one MAVEN_ARGS would split', () => {
    assert.throws(
      () => parseArgs(['--work', outside, '--upgrade-from']),
      /--upgrade-from needs the previous published niord-fe-src zip/,
    );
    assert.throws(
      () => parseArgs(['--work', outside, '--upgrade-from', '--image']),
      /--upgrade-from needs the previous/,
    );
    assert.throws(
      () => main(['--work', outside, '--upgrade-from', path.join(outside, 'missing.zip')]),
      /--upgrade-from .*missing\.zip is not a file/,
    );
    assert.throws(
      () => parseArgs(['--work', outside, '--upgrade-from', path.join(os.tmpdir(), 'a b.zip')]),
      /--upgrade-from .* has a space or a character cmd\.exe reads/,
    );
  });

  it("takes the previous zip's version from its catalog, and refuses the harness's own", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-previous-'));
    temporary.push(dir);
    const zipOf = (catalog) => {
      const file = path.join(dir, `${catalog.version ?? 'none'}.zip`);
      fs.writeFileSync(
        file,
        writeZip([{ name: 'catalog.json', data: Buffer.from(JSON.stringify(catalog)) }]),
      );
      return file;
    };
    const previous = previousZip(zipOf({ format: 1, version: '4.0.183' }));
    assert.equal(previous.version, '4.0.183');
    assert.match(previous.sha256, /^[0-9a-f]{64}$/);
    assert.equal(
      parseArgs(['--work', outside, '--upgrade-from', zipOf({ version: '4.0.2' })]).upgradeFrom,
      path.join(dir, '4.0.2.zip'),
    );
    assert.throws(
      () => previousZip(zipOf({ format: 1, version: '4.0.0-example' })),
      /is version 4\.0\.0-example, which the harness makes itself/,
    );
    assert.throws(
      () => previousZip(zipOf({ format: 1 })),
      /has no catalog\.json naming its version/,
    );
    const notZip = path.join(dir, 'not.zip');
    fs.writeFileSync(notZip, 'not a zip');
    assert.throws(() => previousZip(notZip), /--upgrade-from .*not\.zip: is not a ZIP archive/);
  });

  it('takes --upgrade-kit-from only with --upgrade-from and a file MAVEN_ARGS would not split', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-kit-from-'));
    temporary.push(dir);
    const zip = path.join(dir, 'niord-fe-src-4.0.98.zip');
    const kit = path.join(dir, 'niord-country-kit-4.0.98.zip');
    fs.writeFileSync(zip, 'zip');
    fs.writeFileSync(kit, 'kit');
    assert.equal(
      parseArgs(['--work', outside, '--upgrade-from', zip, '--upgrade-kit-from', kit])
        .upgradeKitFrom,
      kit,
    );
    assert.equal(parseArgs(['--work', outside, '--upgrade-from', zip]).upgradeKitFrom, undefined);
    assert.throws(
      () => parseArgs(['--work', outside, '--upgrade-from', zip, '--upgrade-kit-from']),
      /--upgrade-kit-from needs the previous published niord-country-kit zip/,
    );
    assert.throws(
      () => parseArgs(['--work', outside, '--upgrade-kit-from', '--image', '--upgrade-from', zip]),
      /--upgrade-kit-from needs the previous/,
    );
    assert.throws(
      () => parseArgs(['--work', outside, '--upgrade-kit-from', kit]),
      /--upgrade-kit-from comes with --upgrade-from/,
    );
    assert.throws(
      () =>
        parseArgs([
          '--work',
          outside,
          '--upgrade-from',
          zip,
          '--upgrade-kit-from',
          path.join(os.tmpdir(), 'a b.zip'),
        ]),
      /--upgrade-kit-from .* has a space or a character cmd\.exe reads/,
    );
    assert.throws(
      () =>
        parseArgs([
          '--work',
          outside,
          '--upgrade-from',
          zip,
          '--upgrade-kit-from',
          path.join(dir, 'missing.zip'),
        ]),
      /--upgrade-kit-from .*missing\.zip is not a file/,
    );
  });

  describe("the previous release's two zips", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-previous-'));
    temporary.push(dir);
    const catalog = (version) => ({
      name: 'catalog.json',
      data: Buffer.from(JSON.stringify({ format: 1, version })),
    });
    const file = (name, entries) => {
      fs.writeFileSync(path.join(dir, name), writeZip(entries));
      return path.join(dir, name);
    };
    const preMove = file('pre.zip', [
      catalog('4.0.97'),
      { name: 'country-kit/example/be/pom.xml', data: Buffer.from('<project/>') },
      { name: 'country-kit/example/fe/setup.mjs', data: Buffer.from('// stub\n') },
    ]);
    const postMove = file('post.zip', [catalog('4.0.111')]);
    const kitOf = (name, version) =>
      file(name, [
        { name: 'API-CHANGES.md', data: Buffer.from('# Country API changes\n') },
        ...(version ? [{ name: 'kit.json', data: Buffer.from(markerText(version)) }] : []),
      ]);
    const BEFORE_KIT =
      /4\.0\.97, from before the kit package: an upgrade test starts at 4\.0\.109 or later/;

    it('refuses a zip from before the kit package, which holds country-kit/example/be/', () => {
      assert.throws(() => previousZip(preMove), BEFORE_KIT);
      assert.deepEqual(Object.keys(previousZip(postMove)).sort(), ['sha256', 'version', 'zip']);
      assert.equal(previousZip(postMove).version, '4.0.111');
    });

    it("takes the kit package's version from its marker, and refuses another version or no marker", () => {
      const kit = previousKitZip(kitOf('kit.zip', '4.0.111'), '4.0.111');
      assert.equal(kit.version, '4.0.111');
      assert.match(kit.sha256, /^[0-9a-f]{64}$/);
      assert.ok(Buffer.isBuffer(kit.zip));
      assert.throws(
        () => previousKitZip(kitOf('other.zip', '4.0.112'), '4.0.111'),
        /is the kit package of 4\.0\.112, not of 4\.0\.111/,
      );
      assert.throws(
        () => previousKitZip(kitOf('none.zip'), '4.0.111'),
        /--upgrade-kit-from .*none\.zip has no kit\.json naming its version/,
      );
    });

    it('refuses, before it builds anything, a zip without --upgrade-kit-from', () => {
      assert.throws(
        () => main(['--work', outside, '--upgrade-from', postMove]),
        /4\.0\.111: give its kit package, that release's niord-country-kit, with --upgrade-kit-from/,
      );
      assert.equal(fs.existsSync(outside), false, 'nothing was made in --work');
    });

    it('refuses, before it builds anything, a zip from before the kit package, with or without a kit', () => {
      assert.throws(() => main(['--work', outside, '--upgrade-from', preMove]), BEFORE_KIT);
      assert.throws(
        () =>
          main([
            '--work',
            outside,
            '--upgrade-from',
            preMove,
            '--upgrade-kit-from',
            kitOf('kit-97.zip', '4.0.97'),
          ]),
        BEFORE_KIT,
      );
      assert.equal(fs.existsSync(outside), false, 'nothing was made in --work');
    });
  });

  it(
    'refuses a --work folder inside the base product fe/',
    {
      skip: /\s/.test(where().base) && 'the checkout path has a space, refused first',
    },
    () => {
      assert.throws(
        () => parseArgs(['--work', path.join(where().base, 'work')]),
        /is inside the base product's fe\//,
      );
    },
  );

  it('takes --backend alone and --skip-frontend only with it, neither with a value', () => {
    const pick = ({ backend, skipFrontend }) => ({ backend, skipFrontend });
    assert.deepEqual(pick(parseArgs(['--work', outside])), {
      backend: false,
      skipFrontend: false,
    });
    assert.deepEqual(pick(parseArgs(['--work', outside, '--backend'])), {
      backend: true,
      skipFrontend: false,
    });
    assert.deepEqual(pick(parseArgs(['--work', outside, '--skip-frontend', '--backend'])), {
      backend: true,
      skipFrontend: true,
    });
    assert.equal(parseArgs(['--work', outside, '--image', '--backend']).backend, true);
    assert.throws(
      () => parseArgs(['--work', outside, '--backend', 'yes']),
      /unknown argument "yes"/,
    );
  });

  it('refuses --skip-frontend without --backend, and with a frontend step', () => {
    assert.throws(
      () => parseArgs(['--work', outside, '--skip-frontend']),
      /^Error: --skip-frontend leaves only init, set-up and the backend steps, so it comes with --backend$/,
    );
    const FRONTEND =
      /^Error: --skip-frontend runs no frontend step: --image\/--upgrade-from are frontend steps$/;
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-skip-'));
    temporary.push(dir);
    const zip = path.join(dir, 'niord-fe-src-4.0.98.zip');
    fs.writeFileSync(zip, 'zip');
    for (const extra of [['--image'], ['--image', '--with-deps'], ['--upgrade-from', zip]])
      assert.throws(
        () => parseArgs(['--work', outside, '--backend', '--skip-frontend', ...extra]),
        FRONTEND,
        extra.join(' '),
      );
    // --with-deps alone is refused first for wanting --image.
    assert.throws(
      () => parseArgs(['--work', outside, '--backend', '--skip-frontend', '--with-deps']),
      /--with-deps|--skip-frontend runs no frontend step/,
    );
  });

  it('refuses --backend without Docker before it makes --work or builds anything', () => {
    let probed = 0;
    assert.throws(
      () =>
        main(['--work', outside, '--backend'], {
          docker: () => {
            probed++;
            return { status: 1 };
          },
        }),
      /^Error: --backend builds yy's backend with its integration tests on Testcontainers, which need Docker: docker info failed with exit 1; start Docker, or run without --backend$/,
    );
    assert.equal(probed, 1);
    assert.equal(fs.existsSync(outside), false, 'nothing was made in --work');
  });

  it("words Docker's refusal by how docker info ended, and passes a running Docker", () => {
    assert.equal(dockerRefusal({ status: 0, stdout: '29.0.1\n' }), undefined);
    assert.match(dockerRefusal({ status: 1 }), /: docker info failed with exit 1; start Docker/);
    const missing = Object.assign(new Error('spawnSync docker ENOENT'), { code: 'ENOENT' });
    assert.equal(
      dockerRefusal({ status: null, error: missing }),
      "--backend builds yy's backend with its integration tests on Testcontainers, which need Docker: docker info could not be run: spawnSync docker ENOENT; start Docker, or run without --backend",
    );
  });

  it('gives the backend a NIORD_HOME with forward slashes on Windows, and the environment as it is elsewhere', () => {
    const work = path.join(os.tmpdir(), 'niord-work');
    const windows = backendEnv(
      { PATH: 'p', NIORD_HOME: 'C:\\Users\\x\\Temp\\niord' },
      {
        work,
        platform: 'win32',
      },
    );
    assert.equal(windows.PATH, 'p');
    assert.ok(!windows.NIORD_HOME.includes('\\'), windows.NIORD_HOME);
    assert.match(windows.NIORD_HOME, /\/niord-home$/);
    assert.equal(windows.NIORD_HOME, path.resolve(work, 'niord-home').replaceAll('\\', '/'));
    const given = { PATH: 'p', NIORD_HOME: '/srv/niord' };
    assert.deepEqual(backendEnv(given, { work, platform: 'linux' }), given);
    assert.equal('NIORD_HOME' in backendEnv({ PATH: 'p' }, { work, platform: 'linux' }), false);
    const env = { PATH: 'p' };
    backendEnv(env, { work, platform: 'win32' });
    assert.deepEqual(env, { PATH: 'p' }, 'the environment given is left as it was');
  });

  it("runs the checks under NIORD_BASH, else Git's bash on Windows, else bash", () => {
    const GIT_BASH = 'C:/Program Files/Git/bin/bash.exe';
    assert.equal(
      bashPath({ env: { NIORD_BASH: '/opt/bash' }, platform: 'win32', exists: () => false }),
      '/opt/bash',
    );
    assert.equal(
      bashPath({ env: {}, platform: 'win32', exists: (file) => file === GIT_BASH }),
      GIT_BASH,
    );
    assert.throws(
      () => bashPath({ env: {}, platform: 'win32', exists: () => false }),
      /^Error: Git Bash is not at C:\/Program Files\/Git\/bin\/bash\.exe: set NIORD_BASH$/,
    );
    assert.equal(bashPath({ env: {}, platform: 'linux' }), 'bash');
  });

  describe('Maven under a fake mvnw', () => {
    /** A be/ whose mvnw and mvnw.cmd append their arguments to calls.txt and exit `status`. */
    function fakeBe(status = 0) {
      const be = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-fake-mvnw-'));
      temporary.push(be);
      fs.writeFileSync(
        path.join(be, 'mvnw'),
        `#!/bin/sh\nprintf '%s\\n' "$@" >> "$(dirname "$0")/calls.txt"\nexit ${status}\n`,
        { mode: 0o755 },
      );
      // The space before >> keeps a last argument ending in a digit from reading as 1>>.
      fs.writeFileSync(
        path.join(be, 'mvnw.cmd'),
        `@echo %* >>"%~dp0calls.txt"\r\n@exit /b ${status}\r\n`,
      );
      return be;
    }
    /** The arguments calls.txt holds: one a line from sh, one line of them all from cmd.exe. */
    const calls = (be) =>
      fs
        .readFileSync(path.join(be, 'calls.txt'), 'utf8')
        .split(/\s+/)
        .filter(Boolean)
        .map((arg) => arg.replace(/^"(.*)"$/, '$1'));
    const m2 = path.join(os.tmpdir(), 'niord-fake-m2');
    const repo = path.join(os.tmpdir(), 'niord-fake-repository');
    const repository = [`-Dmaven.repo.local=${m2}`, `-Dmaven.repo.local.tail=${repo}`];
    const install = [
      '-B',
      '-ntp',
      '-Drevision=4.0.0-example',
      '-pl',
      'niord',
      'install',
      '-DskipTests',
    ];

    it('passes the arguments given, then the two repository arguments', () => {
      const be = fakeBe();
      mavenRunner(repository)(be, install);
      assert.deepEqual(calls(be), [...install, ...repository]);
    });

    it('throws on a non-zero exit', () => {
      const be = fakeBe(1);
      assert.throws(() => mavenRunner(repository)(be, ['-B', 'verify']), /^Error: Maven exited 1$/);
    });

    it('hands the runner the environment and marks the repository arguments as paths', () => {
      const seen = [];
      const env = { PATH: 'p', NIORD_HOME: 'C:/w/niord-home' };
      mavenRunner(repository, (options) => {
        seen.push(options);
        return 0;
      })('be', ['verify'], { env });
      assert.deepEqual(seen, [
        { be: 'be', args: ['verify', ...repository], paths: repository, env },
      ]);
    });
  });
});
