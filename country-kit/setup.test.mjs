import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { pseudoLocalise } from './example/pseudo-localise.mjs';
import { refusal } from './guard-build.mjs';
import { gitFiles, KIT_ADDED, kitEntries } from './source-zip.mjs';
import {
  CONFIG_TYPE,
  configBytes,
  MANIFEST_TYPE,
  PACK_TYPE,
  pushPack,
  pushWithdrawn,
} from './lib/oci.mjs';
import { copyKit } from './lib/local-sources.mjs';
import { readManifest } from './lib/manifest.mjs';
import { fingerprint } from './lib/overrides.mjs';
import { buildPackArchive } from './lib/pack-archive.mjs';
import { lockText, parsePin, readPins } from './lib/pins.mjs';
import { REPORT_DATA } from './lib/upgrade-report.mjs';
import { KIT_BACKEND, markerText, where } from './lib/where.mjs';
import { buildScripts } from './lib/workspace.mjs';
import { writeZip } from './lib/zip.mjs';
import {
  archiveCache,
  checkStub,
  parseArgs,
  resolvePacks,
  runChecks,
  setup,
  STUB_CHANGED,
  updatePins,
  withdrawnError,
} from './setup-kit.mjs';
import { fakeRegistry } from './test-fixtures/fake-registry.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;
const EXAMPLE = path.join(KIT, 'example');
const temporary = [];
after(() => {
  for (const folder of temporary)
    fs.rmSync(folder, { recursive: true, force: true, maxRetries: 3 });
});
/** A fresh temporary folder, removed when the file's tests are done. */
const temp = () => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-setup-'));
  temporary.push(folder);
  return folder;
};
const quiet = () => {};

/** The example country copied out of the tree, its pack zz generated; returns its fe/. */
function exampleCountry() {
  const root = temp();
  for (const relative of gitFiles(EXAMPLE).filter((file) => /^(be|fe)\//.test(file))) {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.copyFileSync(path.join(EXAMPLE, relative), path.join(root, relative));
  }
  pseudoLocalise({ fe: FE, out: path.join(root, 'fe', 'packs', 'zz') });
  return path.join(root, 'fe');
}

const read = (fe, relative) => fs.readFileSync(path.join(fe, relative), 'utf8');

// The example's country package, as the registry publishes it: no test touches the registry.
const PUBLISHED = { 'pretty-bytes': { time: { '7.1.0': '2025-09-21T07:36:56.073Z' } } };
const registry = async (url) => {
  const name = decodeURIComponent(url.slice('https://registry.npmjs.org/'.length));
  if (!PUBLISHED[name]) throw new Error(`${url} is not a package of the fixture`);
  return PUBLISHED[name];
};
const NOW = new Date('2026-10-22T12:00:00Z');

// The example's guarded override: the selection toggle, below src/app/ and with its base path.
const TOGGLE = 'features/messages/components/message-selection-toggle/message-selection-toggle';
const BASE_TOGGLE = `ui-apps/admin/src/app/${TOGGLE}`;

describe('the stub check', () => {
  it("accepts the example's stub, which is the template, with either line ending", () => {
    assert.equal(read(EXAMPLE, 'fe/setup.mjs'), read(KIT, 'templates/setup.mjs'));
    const crlf = path.join(temp(), 'setup.mjs');
    fs.writeFileSync(crlf, read(KIT, 'templates/setup.mjs').replace(/\n/g, '\r\n'));
    checkStub(crlf);
  });

  it('refuses a stub that differs from the template', () => {
    const edited = path.join(temp(), 'setup.mjs');
    fs.writeFileSync(
      edited,
      read(KIT, 'templates/setup.mjs').replace('const MAX_ENTRIES', '// \nconst MAX_ENTRIES'),
    );
    assert.throws(() => checkStub(edited), /fe\/setup\.mjs is not the kit's own/);
  });
});

const FLAG = fs.readFileSync(path.join(EXAMPLE, 'fe', 'packs', 'zz', 'img', 'flags', 'zz.png'));
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]']);
/** fetch, refusing every URL that is not on a loopback host: no test reaches ghcr.io. */
const guard = (url, init) => {
  const { hostname, origin } = new URL(url);
  if (!LOOPBACK.has(hostname)) throw new Error(`the test refused a request to ${origin}`);
  return fetch(url, init);
};
const CREDENTIALS = { username: 'example', password: 'secret-token' };
const DATE = '2026-10-22';

/**
 * A pack folder `<root>/<lang>/`: pack.json, its flag, an administration bundle saying `text`, and
 * both status files, its text files ending lines with `eol`. Returns the folder.
 */
function packFolder(root, lang, { text = 'Gem', eol = '\n' } = {}) {
  const dir = path.join(root, lang);
  const write = (relative, data) => {
    fs.mkdirSync(path.dirname(path.join(dir, relative)), { recursive: true });
    fs.writeFileSync(path.join(dir, relative), data);
  };
  const pack = { lang, label: lang.toUpperCase(), locale: lang, flag: `img/flags/${lang}.png` };
  write('pack.json', `${JSON.stringify(pack, null, 2)}\n`.replaceAll('\n', eol));
  write(`img/flags/${lang}.png`, FLAG);
  write(`i18n/admin/${lang}.json`, `{${eol}  "common": { "save": "${text}" }${eol}}${eol}`);
  write('status/fingerprints.json', `{}${eol}`);
  write('status/unaccepted.json', `{ "note": "the reviewers' notes" }${eol}`);
  return dir;
}

/** The pack folder `dir` published to `registry` as example/niord-lang-<lang>:<tag>; its pin. */
async function publishPack(registry, dir, lang, tag) {
  const repository = `example/niord-lang-${lang}`;
  const digest = await pushPack({
    registry: registry.host,
    repository,
    tag,
    archive: buildPackArchive(dir, lang).zip,
    lang,
    credentials: CREDENTIALS,
    fetch: guard,
  });
  return `${registry.host}/${repository}:${tag}@${digest}`;
}

/**
 * A manifest stored as it is under `example/niord-lang-<lang>:<tag>`, in a shape the client would
 * never push: `mediaType` the manifest's own, `layerType` its one layer's, with the pack's config.
 */
function storeManifest(registry, lang, tag, { mediaType = MANIFEST_TYPE, layerType }) {
  const config = configBytes(lang);
  const layer = Buffer.from('not a pack');
  const manifest = {
    schemaVersion: 2,
    mediaType,
    config: { mediaType: CONFIG_TYPE, digest: registry.putBlob(config), size: config.length },
    layers: [{ mediaType: layerType, digest: registry.putBlob(layer), size: layer.length }],
  };
  return registry.putManifest(
    `example/niord-lang-${lang}`,
    Buffer.from(JSON.stringify(manifest)),
    tag,
  );
}

/** A pack's administration bundle with its first text changed by `change`. */
function changeFirstText(dir, lang, change) {
  const file = path.join(dir, 'i18n', 'admin', `${lang}.json`);
  const bundle = JSON.parse(fs.readFileSync(file, 'utf8'));
  const first = (object) => {
    for (const [key, value] of Object.entries(object)) {
      if (typeof value === 'string') {
        object[key] = change(value);
        return true;
      }
      if (value && typeof value === 'object' && first(value)) return true;
    }
    return false;
  };
  assert.ok(first(bundle), `${file} has no text`);
  fs.writeFileSync(file, `${JSON.stringify(bundle, null, 2)}\n`);
}

/** The withdrawn marker of example/niord-lang-<lang>, pushed whole. */
const pushMarker = (registry, lang, withdrawn = []) =>
  pushWithdrawn({
    registry: registry.host,
    repository: `example/niord-lang-${lang}`,
    lang,
    withdrawn,
    credentials: CREDENTIALS,
    fetch: guard,
  });

describe('the packs set-up reads', () => {
  const pinned = { apps: {}, packs: { da: { pinned: true, owner: 'example' } } };
  let registry;
  let root;
  let reference;
  before(async () => {
    registry = await fakeRegistry({ auth: true });
    root = temp();
    reference = await publishPack(registry, packFolder(root, 'da'), 'da', '1');
    await pushMarker(registry, 'da');
  });
  after(() => registry.close());
  const resolve = (fe, options = {}) =>
    resolvePacks({
      fe,
      base: fe,
      manifest: pinned,
      env: {},
      pins: { da: parsePin(options.reference ?? reference) },
      fetch: guard,
      ...options,
    });

  it('pulls a pinned pack by its pin, reading the withdrawn marker first, and stages its archive', async () => {
    const fe = temp();
    registry.requests.length = 0;
    const lines = [];
    const result = await resolve(fe, { log: (line) => lines.push(line) });
    const manifests = registry.requests.filter((r) => r.path.includes('/manifests/'));
    assert.match(manifests[0].path, /\/manifests\/withdrawn$/);
    assert.equal(result.dirs.da, path.join(fe, '.niord', 'packs', 'da'));
    assert.deepEqual(gitless(result.dirs.da), [
      'i18n/admin/da.json',
      'img/flags/da.png',
      'pack.json',
      'status/fingerprints.json',
    ]);
    assert.deepEqual(result.sources, { en: 'base', da: 'pinned' });
    assert.deepEqual(result.references, { da: reference });
    assert.deepEqual(result.foreign, ['da']);
    assert.deepEqual(result.withdrawn, []);
    assert.match(
      lines.join('\n'),
      /^pack da: .* pulled and verified, staged into \.niord\/packs\/da\/$/m,
    );
    // The archive is kept by its sha256, verified again and reused.
    assert.equal(fs.readdirSync(path.join(fe, '.niord', 'oci')).length, 1);
    lines.length = 0;
    await resolve(fe, { log: (line) => lines.push(line) });
    assert.match(lines.join('\n'), /verified from \.niord\/oci\//);
  });

  it('pulls again over a cached archive that no longer hashes to its digest', async () => {
    const fe = temp();
    await resolve(fe);
    const [cached] = fs.readdirSync(path.join(fe, '.niord', 'oci'));
    fs.writeFileSync(path.join(fe, '.niord', 'oci', cached), 'tampered');
    const lines = [];
    await resolve(fe, { log: (line) => lines.push(line) });
    assert.match(lines.join('\n'), /pulled and verified/);
  });

  it('reads no cached archive whose size is not the layer’s', () => {
    const cache = archiveCache(temp());
    const digest = `sha256:${'b'.repeat(64)}`;
    const bytes = Buffer.from('an archive');
    cache.put(digest, bytes);
    assert.deepEqual(cache.get(digest, bytes.length), bytes);
    assert.equal(cache.get(digest, bytes.length + 1), undefined);
    assert.equal(cache.get(`sha256:${'c'.repeat(64)}`, 1), undefined);
  });

  it('deletes the cached archives no pin of the run used', async () => {
    const fe = temp();
    await resolve(fe);
    const [used] = fs.readdirSync(path.join(fe, '.niord', 'oci'));
    const stale = `${'b'.repeat(64)}.zip`;
    fs.writeFileSync(path.join(fe, '.niord', 'oci', stale), 'a pin moved on');
    await resolve(fe);
    assert.deepEqual(fs.readdirSync(path.join(fe, '.niord', 'oci')), [used]);
  });

  it('fails a pack whose withdrawn marker cannot be read, naming the marker', async () => {
    const lang = 'fo';
    const pin = await publishPack(registry, packFolder(root, lang), lang, '1');
    // A marker whose one layer is a pack's, not a marker's.
    storeManifest(registry, lang, 'withdrawn', { layerType: PACK_TYPE });
    const fe = temp();
    await assert.rejects(
      resolvePacks({
        fe,
        base: fe,
        manifest: { apps: {}, packs: { fo: { pinned: true, owner: 'example' } } },
        env: {},
        pins: { fo: parsePin(pin) },
        fetch: guard,
      }),
      /pack fo: the withdrawn marker could not be read: the withdrawn marker of .*niord-lang-fo's layer is application\/vnd\.niord\.lang-pack\.v1\+zip/,
    );
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs', 'fo')), false);
  });

  it('refuses a pin whose digest the registry does not have, and a manifest that is not its digest', async () => {
    const fe = temp();
    const other = reference.replace(/sha256:[0-9a-f]{64}$/, `sha256:${'0'.repeat(64)}`);
    await assert.rejects(
      resolve(fe, { reference: other }),
      /pack da: .*has no manifest sha256:0{64}/,
    );
    registry.knobs.corruptManifest = true;
    try {
      await assert.rejects(resolve(fe), /pack da: .*its bytes are not the digest's/);
    } finally {
      delete registry.knobs.corruptManifest;
    }
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs', 'da')), false);
  });

  it('fails a pack without a withdrawn marker as unreadable', async () => {
    const lang = 'nb';
    const pin = await publishPack(registry, packFolder(root, lang), lang, '1');
    await assert.rejects(
      resolvePacks({
        fe: temp(),
        base: temp(),
        manifest: { apps: {}, packs: { nb: { pinned: true, owner: 'example' } } },
        env: {},
        pins: { nb: parsePin(pin) },
        fetch: guard,
      }),
      /pack nb: .*has no withdrawn marker \(:withdrawn\), so the pack is unreadable/,
    );
  });

  it('reports a withdrawn pin with its reason, and neither pulls nor stages it', async () => {
    const lang = 'kl';
    const pin = await publishPack(registry, packFolder(root, lang), lang, '4');
    const { digest } = parsePin(pin);
    await pushMarker(registry, lang, [{ digest, tag: '4', reason: 'a broken date', date: DATE }]);
    const fe = temp();
    fs.mkdirSync(path.join(fe, '.niord', 'packs', 'kl'), { recursive: true });
    registry.requests.length = 0;
    const result = await resolvePacks({
      fe,
      base: fe,
      manifest: { apps: {}, packs: { kl: { pinned: true, owner: 'example' } } },
      env: {},
      pins: { kl: parsePin(pin) },
      fetch: guard,
    });
    assert.deepEqual(result.withdrawn, [
      { lang, reference: pin, digest, tag: '4', reason: 'a broken date', date: DATE },
    ]);
    assert.equal(result.dirs.kl, undefined);
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs', 'kl')), false);
    assert.ok(!registry.requests.some((r) => r.path.endsWith(`/manifests/${digest}`)));
    assert.equal(
      withdrawnError(result.withdrawn[0]),
      `pack kl: ${pin} is withdrawn (${DATE}): a broken date; move the pin with node setup.mjs --update-pins`,
    );
  });

  it('stages a home pack in exactly its archive layout, without status/unaccepted.json, and the checks read packs/', async () => {
    const fe = temp();
    packFolder(path.join(fe, 'packs'), 'zz', { eol: '\r\n' });
    const result = await resolvePacks({
      fe,
      base: fe,
      manifest: { apps: {}, packs: { zz: { home: true, publish: true } } },
      env: {},
    });
    assert.equal(result.dirs.zz, path.join(fe, 'packs', 'zz'));
    assert.deepEqual(result.foreign, []);
    const staged = path.join(fe, '.niord', 'packs', 'zz');
    assert.deepEqual(gitless(staged), [
      'i18n/admin/zz.json',
      'img/flags/zz.png',
      'pack.json',
      'status/fingerprints.json',
    ]);
    assert.doesNotMatch(read(staged, 'pack.json'), /\r/);
  });

  it('stages a local NIORD_PACK_<LANG> copy as its archive would unpack, and removes a staged pack no longer named', async () => {
    const fe = path.join(root, 'local', 'fe');
    fs.mkdirSync(path.join(fe, '.niord', 'packs', 'xy'), { recursive: true });
    packFolder(path.join(root, 'local'), 'da', { eol: '\r\n' });
    const result = await resolvePacks({
      fe,
      base: fe,
      manifest: pinned,
      env: { NIORD_PACK_DA: '../da' },
    });
    assert.equal(result.dirs.da, path.join(fe, '.niord', 'packs', 'da'));
    assert.deepEqual(gitless(result.dirs.da), [
      'i18n/admin/da.json',
      'img/flags/da.png',
      'pack.json',
      'status/fingerprints.json',
    ]);
    assert.doesNotMatch(read(result.dirs.da, 'i18n/admin/da.json'), /\r/);
    assert.match(result.sources.da, /^local: .*\/local\/da$/);
    assert.deepEqual(result.foreign, ['da']);
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs', 'xy')), false);

    fs.writeFileSync(path.join(root, 'local', 'da', 'renames.json'), '{}');
    await assert.rejects(
      resolvePacks({ fe, base: fe, manifest: pinned, env: { NIORD_PACK_DA: '../da' } }),
      /renames\.json is not part of a pack/,
    );
  });

  it('refuses NIORD_PACK_<LANG> when CI is set', async () => {
    await assert.rejects(
      resolvePacks({
        fe: temp(),
        base: temp(),
        manifest: pinned,
        env: { NIORD_PACK_DA: '../da', CI: 'true' },
      }),
      /NIORD_PACK_DA is refused when CI is set/,
    );
  });

  it('fails a home pack the country does not have, and a pinned pack without a pin', async () => {
    const fe = temp();
    await assert.rejects(
      resolvePacks({ fe, base: fe, manifest: { packs: { zz: { home: true, publish: false } } } }),
      /packs\/zz\/ does not exist/,
    );
    await assert.rejects(
      resolvePacks({ fe, base: fe, manifest: pinned, env: {} }),
      /da is a pinned pack with no pin in niord-pins\.lock\.json: run node setup\.mjs --update-pins/,
    );
  });
});

describe('the pins of the lock', () => {
  const digest = `sha256:${'a'.repeat(64)}`;
  const manifest = {
    packs: { da: { pinned: true }, kl: { pinned: true, owner: 'example' }, zz: { home: true } },
  };
  const lock = (packs) => JSON.stringify({ format: 1, base: {}, packs });

  it('reads full references under the manifest’s owners, sifa-dk by default', () => {
    const pins = readPins(
      lock({
        da: `ghcr.io/sifa-dk/niord-lang-da:12@${digest}`,
        kl: `ghcr.io/example/niord-lang-kl:3@${digest}`,
      }),
      manifest,
    );
    assert.equal(pins.da.repository, 'sifa-dk/niord-lang-da');
    assert.equal(pins.kl.tag, '3');
  });

  for (const [what, packs, message] of [
    [
      'a pin under another owner than the manifest names',
      {
        da: `ghcr.io/example/niord-lang-da:12@${digest}`,
        kl: `ghcr.io/example/niord-lang-kl:3@${digest}`,
      },
      /packs\.da is under the owner example, but niord-country\.json names sifa-dk/,
    ],
    [
      'a pin for a pack the manifest does not pin',
      {
        da: `ghcr.io/sifa-dk/niord-lang-da:1@${digest}`,
        kl: `ghcr.io/example/niord-lang-kl:3@${digest}`,
        zz: `ghcr.io/sifa-dk/niord-lang-zz:1@${digest}`,
      },
      /packs\.zz pins a pack niord-country\.json does not name as pinned/,
    ],
    [
      'a pinned pack without a pin',
      { da: `ghcr.io/sifa-dk/niord-lang-da:1@${digest}` },
      /kl is a pinned pack with no pin in niord-pins\.lock\.json: run node setup\.mjs --update-pins/,
    ],
    [
      'a pin of another language than its key',
      {
        da: `ghcr.io/sifa-dk/niord-lang-nb:1@${digest}`,
        kl: `ghcr.io/example/niord-lang-kl:3@${digest}`,
      },
      /packs\.da names the pack nb, not da/,
    ],
    [
      'a pin without its digest, or on another registry',
      { da: 'ghcr.io/sifa-dk/niord-lang-da:1', kl: `docker.io/example/niord-lang-kl:3@${digest}` },
      /is not a pin: .*\n.*is not a pack reference/,
    ],
  ])
    it(`refuses ${what}`, () => {
      assert.throws(() => readPins(lock(packs), manifest), message);
    });

  it('writes the pins sorted, each a full pin parsePin reads', () => {
    const text = lockText({
      version: '4.0.1',
      sha256: 'c'.repeat(64),
      packs: {
        kl: `ghcr.io/example/niord-lang-kl:3@${digest}`,
        da: `ghcr.io/sifa-dk/niord-lang-da:12@${digest}`,
      },
    });
    assert.deepEqual(Object.keys(JSON.parse(text).packs), ['da', 'kl']);
    assert.deepEqual(
      Object.values(JSON.parse(text).packs).map((pin) => {
        const { registry, repository, tag, digest: pinned } = parsePin(pin);
        return [registry, repository, tag, pinned];
      }),
      [
        ['ghcr.io', 'sifa-dk/niord-lang-da', '12', digest],
        ['ghcr.io', 'example/niord-lang-kl', '3', digest],
      ],
    );
    assert.throws(
      () => lockText({ version: '4.0.1', sha256: 'c'.repeat(64), packs: { da: 'da:12' } }),
      /is not a pack reference/,
    );
  });

  it('needs no pin for a pack a developer copies with NIORD_PACK_<LANG>', () => {
    assert.deepEqual(
      Object.keys(
        readPins(lock({ da: `ghcr.io/sifa-dk/niord-lang-da:1@${digest}` }), manifest, {
          local: ['kl'],
        }),
      ),
      ['da'],
    );
  });
});

describe('--update-pins', () => {
  let registry;
  let root;
  let pins;
  const manifest = { apps: {}, packs: { da: { pinned: true, owner: 'example' } } };
  const env = () => ({ NIORD_PACK_REGISTRY: `http://${registry.host}` });
  before(async () => {
    registry = await fakeRegistry({ auth: true });
    root = temp();
    pins = {};
    for (const tag of ['1', '2', '10'])
      pins[tag] = await publishPack(
        registry,
        packFolder(path.join(root, tag), 'da', { text: `Gem ${tag}` }),
        'da',
        tag,
      );
    await pushMarker(registry, 'da', [
      { digest: parsePin(pins['10']).digest, tag: '10', reason: 'a broken date', date: DATE },
    ]);
  });
  after(() => registry.close());
  /** A country's fe/ with a lock of the base given, the packs and the kit pin, if any. */
  const country = (packs = {}, kit = undefined) => {
    const fe = temp();
    fs.writeFileSync(
      path.join(fe, 'niord-pins.lock.json'),
      lockText({ version: '4.0.1', sha256: 'c'.repeat(64), kit, packs }),
    );
    return fe;
  };
  const lockOf = (fe) => JSON.parse(read(fe, 'niord-pins.lock.json'));

  it('takes --update-pins alone or with <lang>=<tag> pairs', () => {
    assert.deepEqual(parseArgs(['--update-pins']), { updatePins: {} });
    assert.deepEqual(parseArgs(['--update-pins', 'da=12', 'kl=3']), {
      updatePins: { da: '12', kl: '3' },
    });
    for (const args of [
      ['--update-pins', 'da=latest'],
      ['--update-pins', 'da'],
      ['--update-pins', 'da=1', 'da=2'],
      ['--reason', 'x', '--update-pins'],
    ])
      assert.throws(() => parseArgs(args), /--update-pins/, args.join(' '));
  });

  it('pins the highest tag the withdrawn marker does not list, with its digest, keeping the base', async () => {
    const fe = country();
    const lines = await updatePins({ fe, manifest, env: env(), fetch: guard });
    assert.deepEqual(lockOf(fe), {
      format: 1,
      base: { version: '4.0.1', sha256: 'c'.repeat(64) },
      packs: { da: pins['2'] },
    });
    assert.match(
      lines.join('\n'),
      new RegExp(`^da: no pin -> ${pins['2'].replace(/[.[\]]/g, '\\$&')}$`, 'm'),
    );
  });

  it('keeps the kit pin', async () => {
    const fe = country({}, { sha256: 'd'.repeat(64) });
    await updatePins({ fe, manifest, env: env(), fetch: guard });
    const lock = lockOf(fe);
    assert.deepEqual(lock, {
      format: 1,
      base: { version: '4.0.1', sha256: 'c'.repeat(64) },
      kit: { sha256: 'd'.repeat(64) },
      packs: { da: pins['2'] },
    });
    assert.deepEqual(Object.keys(lock), ['format', 'base', 'kit', 'packs']);
  });

  it('pins the tag given, and refuses one that is withdrawn or absent', async () => {
    const fe = country({ da: pins['2'] });
    await updatePins({ fe, manifest, given: { da: '1' }, env: env(), fetch: guard });
    assert.equal(lockOf(fe).packs.da, pins['1']);
    await assert.rejects(
      updatePins({ fe, manifest, given: { da: '10' }, env: env(), fetch: guard }),
      /niord-lang-da:10 is withdrawn \(2026-10-22\): a broken date; pin another tag/,
    );
    await assert.rejects(
      updatePins({ fe, manifest, given: { da: '7' }, env: env(), fetch: guard }),
      /has no tag 7/,
    );
    await assert.rejects(
      updatePins({ fe, manifest, given: { nb: '1' }, env: env(), fetch: guard }),
      /nb is not a pinned pack of niord-country\.json/,
    );
    assert.equal(lockOf(fe).packs.da, pins['1']);
  });

  it('says a pin is unchanged, and writes the same lock, when it is the one it would choose', async () => {
    const fe = country({ da: pins['2'] });
    const before = read(fe, 'niord-pins.lock.json');
    const lines = await updatePins({ fe, manifest, env: env(), fetch: guard });
    assert.match(lines.join('\n'), /^da: .*, unchanged$/m);
    assert.equal(read(fe, 'niord-pins.lock.json'), before);
  });

  it('skips a tag that is not a readable pack, and refuses one named', async () => {
    const lang = 'sv';
    const sv = { apps: {}, packs: { sv: { pinned: true, owner: 'example' } } };
    const good = {};
    for (const tag of ['1', '2'])
      good[tag] = await publishPack(
        registry,
        packFolder(path.join(root, `sv-${tag}`), lang),
        lang,
        tag,
      );
    await pushMarker(registry, lang);
    storeManifest(registry, lang, '11', { layerType: 'application/vnd.oci.image.layer.v1.tar' });
    storeManifest(registry, lang, '12', {
      mediaType: 'application/vnd.oci.image.index.v1+json',
      layerType: PACK_TYPE,
    });
    const fe = country();
    const lines = await updatePins({ fe, manifest: sv, env: env(), fetch: guard });
    assert.equal(lockOf(fe).packs.sv, good['2']);
    const text = lines.join('\n');
    assert.match(
      text,
      /^sv: tag 12 is not a readable pack \(the manifest 12 of example\/niord-lang-sv is not an OCI image manifest\); skipped$/m,
    );
    assert.match(
      text,
      /^sv: tag 11 is not a readable pack \(.*'s layer is application\/vnd\.oci\.image\.layer\.v1\.tar, not application\/vnd\.niord\.lang-pack\.v1\+zip\); skipped$/m,
    );
    // The chosen pack's archive is cached for the set-up that follows.
    assert.equal(fs.readdirSync(path.join(fe, '.niord', 'oci')).length, 1);
    const before = read(fe, 'niord-pins.lock.json');
    await assert.rejects(
      updatePins({ fe, manifest: sv, given: { sv: '11' }, env: env(), fetch: guard }),
      /niord-lang-sv:11 is not a readable pack: .*'s layer is application\/vnd\.oci\.image\.layer\.v1\.tar/,
    );
    await assert.rejects(
      updatePins({ fe, manifest: sv, given: { sv: '12' }, env: env(), fetch: guard }),
      /niord-lang-sv:12 is not a readable pack: .*is not an OCI image manifest/,
    );
    assert.equal(read(fe, 'niord-pins.lock.json'), before);
  });

  it('writes no pin of a pack without a withdrawn marker', async () => {
    const lang = 'nb';
    await publishPack(registry, packFolder(path.join(root, 'nb'), lang), lang, '1');
    const fe = country();
    const before = read(fe, 'niord-pins.lock.json');
    await assert.rejects(
      updatePins({
        fe,
        manifest: { apps: {}, packs: { nb: { pinned: true, owner: 'example' } } },
        env: env(),
        fetch: guard,
      }),
      /niord-lang-nb has no withdrawn marker \(:withdrawn\): the pack is unreadable, and no pin is written/,
    );
    assert.equal(read(fe, 'niord-pins.lock.json'), before);
  });

  it('refuses when the withdrawn marker lists every tag', async () => {
    const lang = 'fo';
    const pin = await publishPack(registry, packFolder(path.join(root, 'fo'), lang), lang, '1');
    await pushMarker(registry, lang, [
      { digest: parsePin(pin).digest, tag: '1', reason: 'a broken date', date: DATE },
    ]);
    const fe = country();
    const before = read(fe, 'niord-pins.lock.json');
    await assert.rejects(
      updatePins({
        fe,
        manifest: { apps: {}, packs: { fo: { pinned: true, owner: 'example' } } },
        env: env(),
        fetch: guard,
      }),
      /niord-lang-fo has no tag that its withdrawn marker does not list$/,
    );
    assert.equal(read(fe, 'niord-pins.lock.json'), before);
  });

  it('refuses a NIORD_PACK_REGISTRY that names ghcr.io, which is the default', async () => {
    for (const value of ['ghcr.io', 'https://ghcr.io'])
      await assert.rejects(
        updatePins({ fe: country(), manifest, env: { NIORD_PACK_REGISTRY: value }, fetch: guard }),
        /NIORD_PACK_REGISTRY names a loopback registry \(http:\/\/127\.0\.0\.1:<port>\); leave it unset for ghcr\.io/,
        value,
      );
  });

  it('removes the pin of a pack no longer pinned', async () => {
    const fe = country({
      da: pins['1'],
      kl: `ghcr.io/sifa-dk/niord-lang-kl:1@sha256:${'d'.repeat(64)}`,
    });
    const lines = await updatePins({ fe, manifest, env: env(), fetch: guard });
    assert.deepEqual(Object.keys(lockOf(fe).packs), ['da']);
    assert.match(lines.join('\n'), /^kl: .* removed: not a pinned pack$/m);
  });

  it('asks ghcr.io unless a loopback registry is named, and refuses any other', async () => {
    const fe = country();
    await assert.rejects(
      updatePins({ fe, manifest, env: {}, fetch: guard }),
      /the test refused a request to https:\/\/ghcr\.io/,
    );
    await assert.rejects(
      updatePins({
        fe,
        manifest,
        env: { NIORD_PACK_REGISTRY: 'http://registry.example:5000' },
        fetch: guard,
      }),
      /is refused/,
    );
  });
});

describe('a pinned pack at set-up', () => {
  let packs;
  let fe;
  let base;
  let pin;
  const REPORT = '.niord/niord-upgrade-report.md';
  const at = (version, source, lines = []) =>
    setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source,
      version,
      sha256: 'e'.repeat(64),
      provenance: `pinned: the test ${version}`,
      args: [],
      env: {},
      log: (line) => lines.push(line),
      warn: (line) => lines.push(line),
      fetchJson: registry,
      fetch: guard,
      now: NOW,
    });

  let source;
  /** The lock pinning zz to `reference`. */
  const pinTo = (reference) =>
    fs.writeFileSync(
      path.join(fe, 'niord-pins.lock.json'),
      lockText({ version: '4.0.0-test', sha256: 'e'.repeat(64), packs: { zz: reference } }),
    );
  /** The example's zz with `edit` made to a copy, published as `tag`; its pin. */
  const variant = (tag, edit) => {
    const dir = path.join(temp(), 'zz');
    fs.cpSync(source, dir, { recursive: true });
    edit(dir);
    return publishPack(packs, dir, 'zz', tag);
  };

  // The example with its pack zz published to a local registry and pinned instead of home.
  before(async () => {
    packs = await fakeRegistry({ auth: true });
    fe = exampleCountry();
    base = path.join(fe, '.niord', 'base');
    pin = await publishPack(packs, path.join(fe, 'packs', 'zz'), 'zz', '1');
    await pushMarker(packs, 'zz');
    source = path.join(temp(), 'zz');
    fs.cpSync(path.join(fe, 'packs', 'zz'), source, { recursive: true });
    fs.rmSync(path.join(fe, 'packs'), { recursive: true });
    const manifest = JSON.parse(read(fe, 'niord-country.json'));
    manifest.packs.zz = { pinned: true, owner: 'example' };
    fs.writeFileSync(path.join(fe, 'niord-country.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    pinTo(pin);
  });
  after(() => packs.close());

  it('sets up from the pulled pack, records its reference, and a production build accepts it', async () => {
    const lines = [];
    await at('4.0.0-test', FE, lines);
    assert.match(
      lines.join('\n'),
      /^setup: pack zz: .* pulled and verified, staged into \.niord\/packs\/zz\/$/m,
    );
    const report = JSON.parse(read(fe, '.niord/build/niord-build-report.json'));
    assert.deepEqual(report.packs.zz, {
      source: 'pinned',
      reference: pin,
      apps: ['admin', 'info'],
    });
    assert.equal(refusal(fe), undefined);
    assert.ok(fs.existsSync(path.join(fe, '.niord', 'build', 'info', 'i18n', 'zz.json')));
    assert.ok(fs.existsSync(path.join(fe, '.niord', 'packs', 'zz', 'backend')));
  });

  it('leaves no staged pack, for a build or Maven to read, when a pinned pack fails the pack check', async () => {
    pinTo(await variant('2', (dir) => changeFirstText(dir, 'zz', (text) => `<b>${text}</b>`)));
    const lines = [];
    try {
      await assert.rejects(
        at('4.0.0-test', base, lines),
        /check\(s\) failed; nothing was staged or generated for the applications, and \.niord\/packs\/ was removed/,
      );
    } finally {
      pinTo(pin);
    }
    assert.match(lines.join('\n'), /^setup: error: pack check: .*markup/m);
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs', 'zz', 'backend')), false);
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs')), false);
    // The last good set-up's staging and record stay.
    assert.ok(fs.existsSync(path.join(fe, '.niord', 'build', 'info', 'i18n', 'zz.json')));
  });

  it('names the pin, and what to do, for a secret in a pinned pack, never printing it', async () => {
    const github = ['gh', 'p_', 'Z8x7C6v5'.repeat(5)].join('');
    const leaky = await variant('3', (dir) => changeFirstText(dir, 'zz', () => github));
    pinTo(leaky);
    const lines = [];
    try {
      await assert.rejects(at('4.0.0-test', base, lines), /check\(s\) failed/);
    } finally {
      pinTo(pin);
    }
    const text = lines.join('\n');
    const escaped = leaky.replace(/[.[\]]/g, '\\$&');
    assert.match(
      text,
      new RegExp(
        `^setup: error: secrets: pinned pack zz \\(${escaped}\\): \\.niord/packs/zz/i18n/admin/zz\\.json:\\d+: github-token \\(ghp_…, 44 characters\\) — pin another tag \\(node setup\\.mjs --update-pins zz=<tag>\\) and tell its home$`,
        'm',
      ),
    );
    assert.ok(!text.includes(github.slice(4, 12)));
  });

  it('stops on a withdrawn pin with its reason first, and lists it in the upgrade report’s To do', async () => {
    const { digest } = parsePin(pin);
    await pushMarker(packs, 'zz', [
      { digest, tag: '1', reason: 'a wrong date pattern', date: DATE },
    ]);
    // The backend settings name zz: a withdrawn pin is not a pack without its backend half.
    const settings = path.resolve(
      fe,
      '..',
      'be',
      'src',
      'main',
      'resources',
      'defaults',
      'country',
      'settings.json',
    );
    fs.mkdirSync(path.dirname(settings), { recursive: true });
    fs.writeFileSync(
      settings,
      `${JSON.stringify({ values: { modelLanguages: ['en', 'zz'], defaultLanguage: 'en' } }, null, 2)}\n`,
    );
    const lines = [];
    try {
      await assert.rejects(
        at('4.0.1-test', base, lines),
        /check\(s\) failed; nothing was staged or generated/,
      );
    } finally {
      fs.rmSync(settings);
    }
    const errors = lines.filter((line) => line.startsWith('setup: error: '));
    assert.equal(
      errors[0],
      `setup: error: pack zz: ${pin} is withdrawn (${DATE}): a wrong date pattern; move the pin with node setup.mjs --update-pins`,
    );
    assert.ok(!errors.slice(1).some((line) => /\bzz\b/.test(line)), errors.join('\n'));
    const warnings = lines.filter((line) => line.startsWith('setup: warning: '));
    assert.ok(!warnings.some((line) => /\bzz\b/.test(line)), warnings.join('\n'));
    const todo = read(fe, REPORT).split('\n## To do\n')[1].split('\n## ')[0];
    assert.ok(
      todo.includes(
        `the pinned pack zz, \`${pin}\`, is withdrawn by its home (${DATE}): a wrong date pattern`,
      ),
      todo,
    );
    assert.equal(
      JSON.parse(read(fe, '.niord/build/niord-build-report.json')).base.version,
      '4.0.0-test',
    );
  });
});

describe('the production-build guard', () => {
  const workspace = (report) => {
    const fe = temp();
    if (report !== undefined) {
      fs.mkdirSync(path.join(fe, '.niord', 'build'), { recursive: true });
      fs.writeFileSync(
        path.join(fe, '.niord', 'build', 'niord-build-report.json'),
        JSON.stringify(report),
      );
    }
    return fe;
  };

  it('lets a workspace set up from the pinned zip build', () => {
    assert.equal(
      refusal(workspace({ provenance: 'pinned: org.niord.web:niord-fe-src:4.0.1:zip sha256:ab' })),
      undefined,
    );
  });

  it('refuses a workspace set up from a local checkout or pack, and one never set up', () => {
    assert.match(
      refusal(workspace({ provenance: 'local: /x/niord/fe @ abc' })),
      /set up from a local source/,
    );
    assert.match(
      refusal(workspace({ provenance: 'local: packs from NIORD_PACK_* over pinned: …' })),
      /local source/,
    );
    assert.match(refusal(workspace()), /does not exist: run `node setup\.mjs` first/);
  });
});

describe('set-up of the example country from a checkout', () => {
  let fe;
  const printed = [];
  before(async () => {
    fe = exampleCountry();
    await setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: FE,
      version: '4.0.0-test',
      sha256: null,
      provenance: 'local: the test @ no git',
      args: [],
      env: {},
      log: (line) => printed.push(line),
      warn: (line) => printed.push(line),
      fetchJson: registry,
      now: NOW,
    });
  });

  it('copies the checkout as the zip would hold it, with its catalog', () => {
    assert.ok(fs.existsSync(path.join(fe, '.niord', 'base', 'catalog.json')));
    // The kit is beside the base product, in the root's .niord/kit/, as the kit package unpacks.
    const kit = path.join(fe, '..', '.niord', 'kit');
    assert.ok(fs.existsSync(path.join(kit, 'setup-kit.mjs')));
    assert.equal(read(kit, 'kit.json'), markerText('4.0.0-test'));
    // A local base copy carries nothing of the kit, as the source zip carries nothing of it.
    const base = path.join(fe, '.niord', 'base');
    assert.equal(fs.existsSync(path.join(base, 'country-kit')), false);
    assert.equal(
      fs.existsSync(
        path.join(fe, '.niord', 'base', 'ui-apps', 'admin', 'src', 'app', 'country-specific', 'dk'),
      ),
      false,
    );
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'base', 'languages', 'da')), false);
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'base', 'docs')), false);
  });

  it("stages exactly the UI languages, the named flags and the country's branding", () => {
    const files = (app) => gitless(path.join(fe, '.niord', 'build', app));
    assert.deepEqual(files('info'), [
      'branding/apple-touch-icon.png',
      'branding/favicon.png',
      'branding/logo-gray.png',
      'branding/zz.json',
      'i18n/zz.json',
      'img/flags/zz.png',
      'legal/disclaimer.zz.html',
    ]);
    assert.deepEqual(
      files('admin').filter((file) => /^(i18n|img)\//.test(file)),
      ['i18n/en.json', 'i18n/zz.json', 'img/flags/en.png', 'img/flags/zz.png'],
    );
    assert.ok(files('admin').includes('niord-build-report.json'));
  });

  it('writes the build report with the provenance, which a production build then refuses', () => {
    const report = JSON.parse(read(fe, '.niord/build/niord-build-report.json'));
    assert.equal(report.provenance, 'local: the test @ no git');
    assert.deepEqual(report.packs.zz, { source: 'home', apps: ['admin', 'info'] });
    assert.deepEqual(report.escapeHatch, []);
    assert.match(refusal(fe), /set up from a local source/);
  });

  it('generates the workspace and the language configuration', () => {
    const angular = JSON.parse(read(fe, 'angular.json'));
    assert.equal(
      angular.projects['niord-info'].architect.build.options.index,
      'info/app/index.html',
    );
    assert.equal(JSON.parse(read(fe, 'package.json')).name, 'niord-xx-fe');
    const info = JSON.parse(read(fe, '.niord/generated/info/language.config.json'));
    assert.deepEqual(info.siteLanguages, ['zz']);
    assert.deepEqual(info.brandingLanguages, ['zz']);
    assert.equal(info.languages[0].locale, 'en-GB');
  });

  it("fills the extension points' seams and compiles the override in its file's place", () => {
    const angular = JSON.parse(read(fe, 'angular.json'));
    const admin = angular.projects['niord-app'].architect;
    const info = angular.projects['niord-info'].architect;
    const swaps = (options) =>
      Object.fromEntries(options.fileReplacements.map((entry) => [entry.with, entry.replace]));
    const defaults = (app) => `.niord/base/ui-apps/${app}/src/app/country-specific/default`;
    for (const options of [admin.build.options, admin.build.configurations.production]) {
      const swapped = swaps(options);
      assert.equal(
        swapped['admin/app/country.providers.ts'],
        `${defaults('admin')}/country.providers.ts`,
      );
      assert.equal(
        swapped['admin/app/country.routes.ts'],
        `${defaults('admin')}/country.routes.ts`,
      );
      assert.equal(swapped['admin/app/country.menu.ts'], `${defaults('admin')}/country.menu.ts`);
      assert.equal(swapped[`admin/overrides/${TOGGLE}.ts`], `.niord/base/${BASE_TOGGLE}.ts`);
    }
    const swapped = swaps(info.build.options);
    assert.equal(swapped['info/app/country.routes.ts'], `${defaults('info')}/country.routes.ts`);
    assert.equal(
      swapped['info/app/country.providers.ts'],
      `${defaults('info')}/country.providers.ts`,
    );
    // The base product's spec of the overridden file runs from its copy beside the generated
    // files, its import of the file pointed back into .niord/base/, where the override replaces it.
    const copy = `.niord/generated/admin/base-specs/${TOGGLE}.spec.ts`;
    assert.ok(
      admin.test.options.include.includes(
        `../../../../generated/admin/base-specs/${TOGGLE}.spec.ts`,
      ),
    );
    assert.match(
      read(fe, copy),
      /from '(\.\.\/){7}base\/ui-apps\/admin\/src\/app\/features\/messages\/components\/message-selection-toggle\/message-selection-toggle';/,
    );
  });

  it('records the override in the build report, with the base product spec run against it', () => {
    const { overrides } = JSON.parse(read(fe, '.niord/build/niord-build-report.json'));
    assert.equal(overrides.length, 1);
    assert.equal(overrides[0].file, `${BASE_TOGGLE}.ts`);
    assert.equal(overrides[0].with, `admin/overrides/${TOGGLE}.ts`);
    assert.equal(Object.keys(overrides[0].basedOn).length, 3);
    assert.equal(overrides[0].baseSpec, `${BASE_TOGGLE}.spec.ts`);
  });

  it("prints the text-override count and the country's packages, and records both in the report", () => {
    const text = printed.join('\n');
    assert.match(text, /^setup: text overrides: en 1 \(admin 1, info 0\)$/m);
    assert.match(text, /^setup: country dependencies: .*1 package/m);
    assert.match(text, /^setup: {3}pretty-bytes@7\.1\.0 for admin — The harbour page/m);
    assert.doesNotMatch(text, /warning|deprecated/);
    const report = JSON.parse(read(fe, '.niord/build/niord-build-report.json'));
    assert.deepEqual(report.textOverrides, { en: { admin: 1, info: 0 } });
    assert.deepEqual(
      report.dependencies.map(({ name, version, app, installScript }) => ({
        name,
        version,
        app,
        installScript,
      })),
      [{ name: 'pretty-bytes', version: '7.1.0', app: 'admin', installScript: null }],
    );
    assert.equal(report.auditAccepted, 0);
    assert.equal(report.format, 1);
  });

  it('says there is no previous set-up record, and writes no upgrade report', () => {
    assert.match(
      printed.join('\n'),
      /^setup: no previous set-up record \(\.niord\/build\/niord-build-report\.json\), so no upgrade report$/m,
    );
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'niord-upgrade-report.md')), false);
  });

  it("stages the override's text in the English bundle, between the pack and branding", () => {
    const en = JSON.parse(read(fe, '.niord/build/admin/i18n/en.json'));
    assert.equal(en.messages.filter.domain.label, 'Waters');
  });

  it("merges the country's package into the generated package.json and lockfile", () => {
    const pkg = JSON.parse(read(fe, 'package.json'));
    assert.equal(pkg.dependencies['pretty-bytes'], '7.1.0');
    assert.equal(pkg.scripts.build, buildScripts().build);
    const lock = JSON.parse(read(fe, 'package-lock.json'));
    assert.equal(lock.packages[''].dependencies['pretty-bytes'], '7.1.0');
    assert.equal(lock.packages['node_modules/pretty-bytes'].version, '7.1.0');
    // The publish time is cached, as it is older than 7 days.
    assert.match(read(fe, '.niord/country-dependency-ages.json'), /"pretty-bytes@7\.1\.0\+sha512-/);
  });

  it("records the footer's country-only utility beside the staging folders, never in them", () => {
    assert.deepEqual(JSON.parse(read(fe, '.niord/build/country-utilities.json')), {
      format: 1,
      admin: [],
      info: [{ file: 'info/app/footer/footer.component.ts', utility: 'sm:grid-cols-[3fr_2fr]' }],
    });
    for (const app of ['admin', 'info'])
      assert.ok(
        !gitless(path.join(fe, '.niord', 'build', app)).some((file) =>
          file.includes('country-utilities'),
        ),
        app,
      );
    assert.match(
      printed.join('\n'),
      /^setup: country-only utilities: admin none; info sm:grid-cols-\[3fr_2fr\]$/m,
    );
  });

  it('maps internal/* only in the tsconfigs of the application the country overrides', () => {
    const internal = (relative) => /@niord\/(admin|info)\/internal/.exec(read(fe, relative))?.[1];
    assert.equal(internal('tsconfig.json'), undefined);
    assert.equal(internal('.niord/tsconfig.admin.app.json'), 'admin');
    assert.equal(internal('.niord/tsconfig.admin.spec.json'), 'admin');
    assert.equal(internal('.niord/tsconfig.info.app.json'), undefined);
    assert.equal(internal('.niord/tsconfig.info.spec.json'), undefined);
  });

  it('prints the accepted audit findings in full, and the report, served without sign-in, counts them', async () => {
    const accepted = path.join(fe, 'audit-accepted.json');
    fs.writeFileSync(
      accepted,
      `${JSON.stringify(
        [
          {
            advisory: 'GHSA-xvch-5gv4-984h',
            package: 'minimist',
            reason: 'Reached only through the build; the base product drops it next.',
            added: '2026-10-20',
          },
        ],
        null,
        2,
      )}\n`,
    );
    const lines = [];
    try {
      await setup({
        fe,
        stub: path.join(fe, 'setup.mjs'),
        source: FE,
        version: '4.0.0-test',
        sha256: null,
        provenance: 'local: the test @ no git',
        args: [],
        env: {},
        log: (line) => lines.push(line),
        warn: (line) => lines.push(line),
        fetchJson: registry,
        now: NOW,
      });
    } finally {
      fs.rmSync(accepted);
    }
    assert.match(lines.join('\n'), /^setup: {3}GHSA-xvch-5gv4-984h in minimist — Reached only/m);
    for (const copy of [
      '.niord/build/niord-build-report.json',
      '.niord/build/admin/niord-build-report.json',
    ]) {
      const text = read(fe, copy);
      assert.equal(JSON.parse(text).auditAccepted, 1, copy);
      assert.doesNotMatch(text, /GHSA|minimist|Reached only/, copy);
    }
  });

  // Last, because it breaks the country: set up again from the unpacked base, as from a zip.
  it('stops before staging or generating anything when a check fails', async () => {
    const file = path.join(fe, 'admin', 'environments', 'environment.production.ts');
    fs.writeFileSync(
      file,
      read(fe, 'admin/environments/environment.production.ts').replace(
        "url: '/auth'",
        "url: 'https://sso.example.org/auth'",
      ),
    );
    fs.rmSync(path.join(fe, 'angular.json'));
    const errors = [];
    await assert.rejects(
      setup({
        fe,
        stub: path.join(fe, 'setup.mjs'),
        source: path.join(fe, '.niord', 'base'),
        version: '4.0.0-test',
        sha256: 'a'.repeat(64),
        provenance: 'pinned: the test',
        args: [],
        env: {},
        log: quiet,
        warn: (line) => errors.push(line),
        fetchJson: registry,
        now: NOW,
      }),
      /1 check\(s\) failed; nothing was staged or generated/,
    );
    assert.match(
      errors.join('\n'),
      /keycloak\.url is 'https:\/\/sso\.example\.org\/auth', not origin-relative/,
    );
    const report = JSON.parse(read(fe, '.niord/build/niord-build-report.json'));
    assert.equal(report.provenance, 'local: the test @ no git');
    assert.equal(fs.existsSync(path.join(fe, 'angular.json')), false);
    // The home pack's staged copy, which the country's Maven build would read, is gone too.
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'packs')), false);
  });
});

describe('the kit hash the stub hands over', () => {
  it('reaches the build report and its staged copy, naming both packages', async () => {
    const fe = exampleCountry();
    const common = {
      fe,
      stub: path.join(fe, 'setup.mjs'),
      version: '4.0.0-test',
      args: [],
      env: {},
      log: quiet,
      warn: quiet,
      fetchJson: registry,
      now: NOW,
    };
    await setup({ ...common, source: FE, sha256: null, provenance: 'local: the test @ no git' });
    const zip = 'a'.repeat(64);
    const kit = 'd'.repeat(64);
    await setup({
      ...common,
      source: path.join(fe, '.niord', 'base'),
      sha256: zip,
      kitSha256: kit,
      provenance: `pinned: org.niord.web:niord-fe-src:4.0.0-test:zip sha256:${zip}`,
    });
    const text = read(fe, '.niord/build/niord-build-report.json');
    const report = JSON.parse(text);
    assert.deepEqual(report.base, { version: '4.0.0-test', sha256: zip, kit: { sha256: kit } });
    assert.equal(
      report.provenance,
      `pinned: org.niord.web:niord-fe-src:4.0.0-test:zip sha256:${zip}; org.niord:niord-country-kit:4.0.0-test:zip sha256:${kit}`,
    );
    assert.equal(read(fe, '.niord/build/admin/niord-build-report.json'), text);
  });
});

describe('the kit a set-up from a checkout copies', () => {
  const options = (fe, lines = []) => ({
    fe,
    stub: path.join(fe, 'setup.mjs'),
    source: FE,
    version: '4.0.0-test',
    sha256: null,
    provenance: 'local: the test @ no git',
    args: [],
    env: {},
    log: (line) => lines.push(line),
    warn: (line) => lines.push(line),
    fetchJson: registry,
    now: NOW,
  });

  it("copies the kit package's files into ../.niord/kit/, the contract with LF line ends and the backend files with theirs", async () => {
    const fe = exampleCountry();
    const lines = [];
    await setup(options(fe, lines));
    const kit = path.join(fe, '..', '.niord', 'kit');
    const entries = kitEntries({ version: '4.0.0-test' });
    assert.deepEqual(gitless(kit), entries.map(({ name }) => name).sort());
    for (const { name, data } of entries)
      assert.ok(fs.readFileSync(path.join(kit, name)).equals(data), name);
    for (const { entry } of Object.values(KIT_BACKEND))
      assert.ok(fs.existsSync(path.join(kit, entry)), `${entry} is not copied`);
    assert.doesNotMatch(read(kit, 'docs/country-api.md'), /\r/);
    assert.doesNotMatch(read(kit, 'backend/mvnw.cmd'), /(^|[^\r])\n/);
    assert.equal(read(kit, 'kit.json'), markerText('4.0.0-test'));
    assert.match(
      lines.join('\n'),
      new RegExp(
        `^setup: ${entries.length - Object.keys(KIT_ADDED).length} file\\(s\\) of the kit .+ copied into \\.\\./\\.niord/kit/, with its marker, the backend contract and its backend/ files$`,
        'm',
      ),
    );

    // A set-up from the unpacked base product copies no kit: the stub unpacked it.
    fs.writeFileSync(path.join(kit, 'left.txt'), 'left as it was\n');
    const again = [];
    await setup({
      ...options(fe, again),
      source: path.join(fe, '.niord', 'base'),
      sha256: 'a'.repeat(64),
      provenance: 'pinned: the test',
    });
    assert.equal(read(kit, 'left.txt'), 'left as it was\n');
    assert.doesNotMatch(again.join('\n'), /of the kit .* copied/);
  });

  it('copies the kit before the stub check, so the copy the refusal names is there', async () => {
    const fe = exampleCountry();
    const stub = path.join(fe, 'setup.mjs');
    fs.appendFileSync(stub, '// edited\n');
    await assert.rejects(
      setup(options(fe)),
      /^Error: fe\/setup\.mjs is not the kit's own: it is never edited\. In fe\/, copy \.\.\/\.niord\/kit\/templates\/setup\.mjs over setup\.mjs$/,
    );
    assert.equal(read(fe, '../.niord/kit/templates/setup.mjs'), read(KIT, 'templates/setup.mjs'));
  });

  it('refuses a kit whose list selects a name the copy adds, and a checkout without the contract or a backend file', () => {
    // A kit folder without git, walked: its list selects every file.
    const root = temp();
    const kit = path.join(root, 'country-kit');
    const be = path.join(root, 'be');
    fs.mkdirSync(path.join(kit, 'docs'), { recursive: true });
    fs.mkdirSync(path.join(be, 'docs'), { recursive: true });
    fs.writeFileSync(
      path.join(kit, 'kit-include.json'),
      `${JSON.stringify({ format: 1, note: 'all', include: ['**'], exclude: [] })}\n`,
    );
    fs.writeFileSync(path.join(be, 'docs', 'country-api.md'), '# Contract\r\n');
    // The backend files with the wrong line ends, which the copy corrects as the build does.
    const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    for (const { from, eol } of Object.values(KIT_BACKEND)) {
      const file = path.join(be, ...from.split('/'));
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, eol === null ? PNG : eol === 'crlf' ? 'a\nb\n' : 'a\r\nb\r\n');
    }
    const target = path.join(root, '.niord', 'kit');
    const copy = () => copyKit({ kit, target, version: '4.0.0-test', be });
    assert.equal(copy(), 1);
    assert.equal(read(target, 'docs/country-api.md'), '# Contract\n');
    assert.equal(read(target, 'kit.json'), markerText('4.0.0-test'));
    assert.equal(read(target, 'backend/mvnw.cmd'), 'a\r\nb\r\n');
    assert.equal(read(target, 'backend/mvnw'), 'a\nb\n');
    assert.equal(read(target, 'backend/defaults/base/settings.json'), 'a\nb\n');
    assert.ok(fs.readFileSync(path.join(target, 'backend', 'img', 'company-logo.png')).equals(PNG));
    for (const name of Object.values(KIT_ADDED)) {
      fs.mkdirSync(path.dirname(path.join(kit, name)), { recursive: true });
      fs.writeFileSync(path.join(kit, name), 'given by the kit\n');
      assert.throws(copy, new RegExp(`${name}.* is selected, but the copy adds that name`));
      fs.rmSync(path.join(kit, name));
    }
    fs.rmSync(path.join(kit, 'backend'), { recursive: true });
    // A missing backend file is found before the copy starts: the target is left as it was.
    const properties = path.join(be, '.mvn', 'wrapper', 'maven-wrapper.properties');
    fs.rmSync(properties);
    fs.writeFileSync(path.join(target, 'left.txt'), 'left as it was\n');
    assert.throws(copy, {
      message: `${properties} is missing: the kit package carries it as backend/maven-wrapper.properties`,
    });
    assert.equal(read(target, 'left.txt'), 'left as it was\n');
    fs.rmSync(path.join(be, 'docs', 'country-api.md'));
    assert.throws(copy, /country-api\.md is missing: the kit carries it as docs\/country-api\.md/);
  });
});

describe('guarded overrides at set-up', () => {
  let fe;
  let base;
  const again = (args, warnings = []) =>
    setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: base,
      version: '4.0.0-test',
      sha256: 'a'.repeat(64),
      provenance: 'pinned: the test',
      args,
      env: {},
      log: (line) => warnings.push(line),
      warn: (line) => warnings.push(line),
      fetchJson: registry,
      now: NOW,
    });

  before(async () => {
    fe = exampleCountry();
    base = path.join(fe, '.niord', 'base');
    await setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: FE,
      version: '4.0.0-test',
      sha256: null,
      provenance: 'local: the test @ no git',
      args: [],
      env: {},
      log: quiet,
      warn: quiet,
      fetchJson: registry,
      now: NOW,
    });
  });

  it('refuses an unknown argument before it does anything', async () => {
    await assert.rejects(again(['--force']), /unknown argument "--force": node setup\.mjs takes/);
    await assert.rejects(again(['--reason', 'x']), /--add-override and --reason come together/);
  });

  it('refuses --add-override of a protected file, and leaves the manifest as it was', async () => {
    const manifest = read(fe, 'niord-country.json');
    await assert.rejects(
      again([
        '--add-override',
        'ui-apps/admin/src/app/core/interceptors/auth-token.interceptor.ts',
        '--reason',
        'x',
      ]),
      /auth-token\.interceptor\.ts is protected/,
    );
    assert.equal(read(fe, 'niord-country.json'), manifest);
    assert.equal(fs.existsSync(path.join(fe, 'admin', 'overrides', 'core')), false);
  });

  it('fails when a file an override is based on changed, naming it and --accept-override', async () => {
    const html = path.join(base, `${BASE_TOGGLE}.html`);
    fs.writeFileSync(
      html,
      `${fs.readFileSync(html, 'utf8')}<!-- changed by the base product -->\n`,
    );
    const lines = [];
    await assert.rejects(again([], lines), /1 check\(s\) failed; nothing was staged or generated/);
    assert.match(
      lines.join('\n'),
      new RegExp(
        `error: override ${BASE_TOGGLE}\\.ts: the base product changed ${BASE_TOGGLE}\\.html .*node setup\\.mjs --accept-override ${BASE_TOGGLE}\\.ts`,
      ),
    );
  });

  it('records the change with --accept-override, after which set-up passes', async () => {
    const lines = [];
    await again(['--accept-override', `admin/overrides/${TOGGLE}.ts`], lines);
    const text = lines.join('\n');
    assert.match(text, new RegExp(`${BASE_TOGGLE}\\.html: sha256:[0-9a-f]{64} -> sha256:`));
    // The diff of the base product's current template against the country's copy.
    assert.match(text, /-\d+ <!-- changed by the base product -->/);
    const [entry] = JSON.parse(read(fe, 'niord-country.json')).overrides;
    assert.equal(
      entry.basedOn[`${BASE_TOGGLE}.html`],
      fingerprint(fs.readFileSync(path.join(base, `${BASE_TOGGLE}.html`))),
    );
    await again([]);
  });
});

describe('a version change at set-up: the upgrade report', () => {
  let fe;
  let base;
  let zipped;
  const REPORT = '.niord/niord-upgrade-report.md';
  const HTML = `${BASE_TOGGLE}.html`;
  const recorded = () => JSON.parse(read(fe, '.niord/build/niord-build-report.json')).base;
  const at = (version, sha256, args = [], lines = []) =>
    setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: base,
      version,
      sha256,
      provenance: `pinned: the test ${version}`,
      args,
      env: {},
      log: (line) => lines.push(line),
      warn: (line) => lines.push(line),
      fetchJson: registry,
      now: NOW,
    });

  // The example set up from a zip of 4.0.0-test, as the stub leaves it: the zip in .niord/ and its
  // sha256 in the record. The zip holds the files the report and --accept-override read.
  before(async () => {
    fe = exampleCountry();
    base = path.join(fe, '.niord', 'base');
    await setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: FE,
      version: '4.0.0-test',
      sha256: null,
      provenance: 'local: the test @ no git',
      args: [],
      env: {},
      log: quiet,
      warn: quiet,
      fetchJson: registry,
      now: NOW,
    });
    const names = [
      'tsconfig.json',
      'package.json',
      'ui-apps/admin/src/public-api.ts',
      'ui-apps/admin/src/testing-api.ts',
      'ui-apps/info/src/public-api.ts',
      'ui-apps/info/src/testing-api.ts',
      ...['ts', 'html', 'scss'].map((ext) => `${BASE_TOGGLE}.${ext}`),
    ];
    // The record names no kit zip, so the report reads no previous kit and compares the kit's
    // files by version.
    const zip = writeZip(
      names.map((name) => ({ name, data: fs.readFileSync(path.join(base, name)) })),
    );
    fs.writeFileSync(path.join(fe, '.niord', 'niord-fe-src-4.0.0-test.zip'), zip);
    zipped = createHash('sha256').update(zip).digest('hex');
    await at('4.0.0-test', zipped);
  });

  it('fails a bump on the changed overridden file, writes the report anyway and keeps the record', async () => {
    // 4.0.1-test changes the overridden template and deprecates roleGuard, which the example uses.
    fs.appendFileSync(path.join(base, HTML), '<!-- changed by the base product -->\n');
    const api = path.join(base, 'ui-apps', 'admin', 'src', 'public-api.ts');
    const marked = read(base, 'ui-apps/admin/src/public-api.ts').replace(
      /(\n \*\/\nexport \{ roleGuard \})/,
      '\n *\n * Deprecated since 4.0.1-test, removed in 4.0.2-test: a guard of a later version$1',
    );
    assert.match(marked, /removed in 4\.0\.2-test/, "the API's roleGuard statement was not found");
    fs.writeFileSync(api, marked);

    const lines = [];
    await assert.rejects(
      at('4.0.1-test', 'b'.repeat(64), [], lines),
      /1 check\(s\) failed; nothing was staged or generated/,
    );
    assert.match(
      lines.join('\n'),
      /^setup: the upgrade report \(4\.0\.0-test to 4\.0\.1-test\): \.niord\/niord-upgrade-report\.md$/m,
    );
    const text = read(fe, REPORT);
    assert.match(text, /^# Upgrade report: xx from 4\.0\.0-test to 4\.0\.1-test\n/);
    assert.deepEqual(
      [...text.matchAll(/^## (.+)$/gm)].map((match) => match[1]),
      ['Versions', 'To do', 'To look at', 'New for you', 'Dependency changes'],
    );
    assert.match(text, /^Set-up of 4\.0\.1-test failed: 1 check /m);
    assert.match(
      text,
      new RegExp(
        `the base product changed \`${HTML}\`: compare the copy with the base product's diff below \\(4\\.0\\.0-test to 4\\.0\\.1-test; - before, \\+ now\\)[^\\n]*\\n\\n {2}\`\`\`diff\\n {2}\\+\\d+ <!-- changed by the base product -->\\n {2}\`\`\``,
      ),
    );
    assert.match(
      text,
      /`admin\/app\/country\.routes\.ts` uses `roleGuard` from `@niord\/admin\/api`, deprecated since 4\.0\.1-test, removed in 4\.0\.2-test/,
    );
    // The override check's line is the diff item's, not a To-do item of its own.
    const data = JSON.parse(read(fe, REPORT_DATA));
    assert.equal(data.failed, 1);
    assert.ok(
      data.todo.some((item) => item.includes(`the base product changed \`${HTML}\`: compare`)),
      data.todo.join('\n'),
    );
    assert.ok(!data.todo.some((item) => item.startsWith('override ')), data.todo.join('\n'));
    assert.deepEqual(recorded(), { version: '4.0.0-test', sha256: zipped });
  });

  it("--accept-override prints the base product's diff since the recorded fingerprint, and set-up advances the record", async () => {
    const lines = [];
    await at(
      '4.0.1-test',
      'b'.repeat(64),
      ['--accept-override', `admin/overrides/${TOGGLE}.ts`],
      lines,
    );
    const text = lines.join('\n');
    assert.match(
      text,
      new RegExp(
        `^setup: {5}the base product's diff of ${HTML} since the recorded fingerprint \\(4\\.0\\.0-test to 4\\.0\\.1-test; - before, \\+ now\\):\\nsetup: {7}\\+\\d+ <!-- changed by the base product -->$`,
        'm',
      ),
    );
    assert.match(text, /warning: country API: .*roleGuard.*it is removed in 4\.0\.2-test/);
    assert.match(read(fe, REPORT), /^Set-up of 4\.0\.1-test passed\.$/m);
    assert.deepEqual(recorded(), { version: '4.0.1-test', sha256: 'b'.repeat(64) });
  });

  it('writes no report when the version stays', async () => {
    const before = read(fe, REPORT);
    const lines = [];
    await at('4.0.1-test', 'b'.repeat(64), [], lines);
    assert.doesNotMatch(lines.join('\n'), /upgrade report/);
    assert.equal(read(fe, REPORT), before);
  });

  it('a bump whose stub differs from the kit writes the report with the copy as a To-do item, and stops', async () => {
    // The committed stub is the previous version's; the base product changed its template since.
    const stub = path.join(fe, 'setup.mjs');
    const kitStub = read(fe, 'setup.mjs');
    fs.writeFileSync(stub, kitStub.replace('const MAX_ENTRIES', '// 4.0.1\nconst MAX_ENTRIES'));
    try {
      const lines = [];
      await assert.rejects(
        at('4.0.2-test', 'c'.repeat(64), [], lines),
        /1 check\(s\) failed; nothing was staged or generated/,
      );
      assert.match(
        lines.join('\n'),
        /^setup: error: stub: fe\/setup\.mjs is not the kit's own; the base product changed it: in fe\/, copy \.\.\/\.niord\/kit\/templates\/setup\.mjs over setup\.mjs$/m,
      );
      const todo = read(fe, REPORT).split('\n## To do\n')[1].split('\n## ')[0];
      assert.match(
        todo,
        /^- `setup\.mjs`: the base product changed the stub: copy `\.\.\/\.niord\/kit\/templates\/setup\.mjs` over it/m,
      );
      // The stub's line is the stub item's, not a To-do item of its own.
      const data = JSON.parse(read(fe, REPORT_DATA));
      assert.ok(
        data.todo.some((item) => item.startsWith('`setup.mjs`: the base product changed the stub')),
        data.todo.join('\n'),
      );
      assert.ok(!data.todo.includes(STUB_CHANGED), data.todo.join('\n'));
      assert.deepEqual(recorded(), { version: '4.0.1-test', sha256: 'b'.repeat(64) });
      // Without a version change the stub is refused at once, as before.
      await assert.rejects(at('4.0.1-test', 'b'.repeat(64)), /fe\/setup\.mjs is not the kit's own/);
    } finally {
      fs.writeFileSync(stub, kitStub);
    }
  });

  it("gives a pack check finding its line as the checks' errors hold it, prefix included", () => {
    // A copy of the home pack, one administration text with another placeholder than English's.
    const zz = path.join(temp(), 'zz');
    fs.cpSync(path.join(fe, 'packs', 'zz'), zz, { recursive: true });
    const bundle = path.join(zz, 'i18n', 'admin', 'zz.json');
    const texts = JSON.parse(fs.readFileSync(bundle, 'utf8'));
    let broken;
    const breakOne = (node, prefix) => {
      for (const [key, value] of Object.entries(node)) {
        if (broken) return;
        if (typeof value === 'object') breakOne(value, `${prefix}${key}.`);
        else if (/\{\{\s*\w+\s*\}\}/.test(value)) {
          node[key] = value.replace(/\{\{\s*\w+\s*\}\}/, '{{broken}}');
          broken = `${prefix}${key}`;
        }
      }
    };
    breakOne(texts, '');
    assert.ok(broken, 'the home pack has no administration text with a placeholder');
    fs.writeFileSync(bundle, `${JSON.stringify(texts, null, 2)}\n`);
    const checks = runChecks({
      fe,
      base,
      manifest: readManifest(path.join(fe, 'niord-country.json')),
      packDirs: { en: path.join(base, 'languages', 'en'), zz },
    });
    const found = checks.findings.packs.placeholders;
    assert.deepEqual(
      found.map(({ lang, app, key }) => ({ lang, app, key })),
      [{ lang: 'zz', app: 'admin', key: broken }],
    );
    assert.match(found[0].error, /^pack check: .*: \S+ has the placeholders broken, English /);
    assert.ok(checks.errors.includes(found[0].error), checks.errors.join('\n'));
  });
});

describe('country dependencies and the country API at set-up', () => {
  let fe;
  let base;
  const again = (args, lines = [], options = {}) =>
    setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: base,
      version: '4.0.0-test',
      sha256: 'a'.repeat(64),
      provenance: 'pinned: the test',
      args,
      env: {},
      log: (line) => lines.push(line),
      warn: (line) => lines.push(line),
      fetchJson: registry,
      now: NOW,
      ...options,
    });

  before(async () => {
    fe = exampleCountry();
    base = path.join(fe, '.niord', 'base');
    await setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: FE,
      version: '4.0.0-test',
      sha256: null,
      provenance: 'local: the test @ no git',
      args: [],
      env: {},
      log: quiet,
      warn: quiet,
      fetchJson: registry,
      now: NOW,
    });
  });

  it('takes --update-country-lock alone, without a value or another argument', () => {
    assert.deepEqual(parseArgs(['--update-country-lock']), { updateLock: true });
    assert.throws(
      () => parseArgs(['--update-country-lock', 'now']),
      /--update-country-lock stands alone and takes no value/,
    );
    assert.throws(
      () => parseArgs(['--accept-override', 'x', '--update-country-lock']),
      /--update-country-lock stands alone/,
    );
  });

  it("makes the country's lock with npm, then sets up from it", async () => {
    const committed = read(fe, 'country/package-lock.json');
    fs.rmSync(path.join(fe, 'country', 'package-lock.json'));
    const calls = [];
    // npm as `npm install --package-lock-only` leaves it: the base product's lockfile and the
    // entry the country's package adds.
    const run = (args, { cwd }) => {
      calls.push(args.join(' '));
      const lock = JSON.parse(fs.readFileSync(path.join(cwd, 'package-lock.json'), 'utf8'));
      lock.packages[''].dependencies['pretty-bytes'] = '7.1.0';
      lock.packages['node_modules/pretty-bytes'] =
        JSON.parse(committed).packages['node_modules/pretty-bytes'];
      fs.writeFileSync(path.join(cwd, 'package-lock.json'), JSON.stringify(lock));
    };
    const lines = [];
    await again(['--update-country-lock'], lines, { run });
    assert.deepEqual(calls, ['install --package-lock-only --ignore-scripts --no-audit --no-fund']);
    assert.deepEqual(JSON.parse(read(fe, 'country/package-lock.json')), JSON.parse(committed));
    assert.match(lines.join('\n'), /node_modules\/pretty-bytes 7\.1\.0/);
  });

  it('warns about a deprecated API export the country uses, and set-up passes', async () => {
    const api = path.join(base, 'ui-apps', 'admin', 'src', 'public-api.ts');
    const text = fs.readFileSync(api, 'utf8');
    const marked = text.replace(
      /(\n \*\/\nexport \{ roleGuard \})/,
      '\n *\n * Deprecated since 4.0.0-test: a guard of a later version$1',
    );
    assert.notEqual(marked, text, "the API's roleGuard statement was not found");
    fs.writeFileSync(api, marked);
    const lines = [];
    try {
      await again([], lines);
    } finally {
      fs.writeFileSync(api, text);
    }
    assert.match(
      lines.join('\n'),
      /warning: country API: admin\/app\/country\.routes\.ts uses roleGuard from @niord\/admin\/api, deprecated since 4\.0\.0-test: a guard of a later version; its removal is not scheduled yet/,
    );
  });

  // Last, because it breaks the country's lock.
  it('stops before staging or generating anything when a dependency breaks a rule', async () => {
    const file = path.join(fe, 'country', 'package-lock.json');
    const lock = JSON.parse(fs.readFileSync(file, 'utf8'));
    lock.packages['node_modules/pretty-bytes'].resolved =
      'http://registry.example.org/pretty-bytes/-/pretty-bytes-7.1.0.tgz';
    fs.writeFileSync(file, JSON.stringify(lock, null, 2));
    fs.rmSync(path.join(fe, 'angular.json'));
    const staged = read(fe, '.niord/build/niord-build-report.json');
    const lines = [];
    await assert.rejects(again([], lines), /check\(s\) failed; nothing was staged or generated/);
    assert.match(
      lines.join('\n'),
      /error: country dependencies: .*node_modules\/pretty-bytes.*https:\/\/registry\.npmjs\.org\//,
    );
    assert.equal(fs.existsSync(path.join(fe, 'angular.json')), false);
    assert.equal(read(fe, '.niord/build/niord-build-report.json'), staged);
  });
});

describe('the secret scan at set-up', () => {
  it('stops before staging on a token in a branding file and a text override, never printing it', async () => {
    const fe = exampleCountry();
    // Crafted from pieces, so this file carries no literal a secret scanner would flag.
    const github = ['gh', 'p_', 'Q9w8E7r6'.repeat(5)].join('');
    const slack = ['xo', 'xb-', '2468013579-', 'kLmNoPqRsT'].join('');
    const branding = path.join(fe, 'info', 'assets', 'branding', 'zz.json');
    fs.writeFileSync(
      branding,
      read(fe, 'info/assets/branding/zz.json').replace(
        '"country.motto": "',
        `"country.motto": "${github} `,
      ),
    );
    const overrides = path.join(fe, 'text-overrides', 'en.json');
    fs.writeFileSync(
      overrides,
      read(fe, 'text-overrides/en.json').replace('"Waters"', `"${slack}"`),
    );
    const lines = [];
    await assert.rejects(
      setup({
        fe,
        stub: path.join(fe, 'setup.mjs'),
        source: FE,
        version: '4.0.0-test',
        sha256: null,
        provenance: 'local: the test @ no git',
        args: [],
        env: {},
        log: (line) => lines.push(line),
        warn: (line) => lines.push(line),
        fetchJson: registry,
        now: NOW,
      }),
      /check\(s\) failed; nothing was staged or generated/,
    );
    const text = lines.join('\n');
    assert.match(
      text,
      /error: secrets: info\/assets\/branding\/zz\.json:\d+: github-token \(ghp_…, 44 characters\)/,
    );
    assert.match(
      text,
      /error: secrets: text-overrides\/en\.json:\d+: slack-token \(xoxb…, 26 characters\)/,
    );
    assert.ok(!text.includes(github.slice(4, 12)) && !text.includes(slack.slice(5)));
    assert.equal(fs.existsSync(path.join(fe, '.niord', 'build')), false);
    assert.equal(fs.existsSync(path.join(fe, 'angular.json')), false);
  });
});

describe('a country without overrides', () => {
  it('has no internal alias in any generated tsconfig', async () => {
    const fe = exampleCountry();
    const manifest = JSON.parse(read(fe, 'niord-country.json'));
    fs.writeFileSync(
      path.join(fe, 'niord-country.json'),
      JSON.stringify({ ...manifest, overrides: [] }, null, 2),
    );
    fs.rmSync(path.join(fe, 'admin', 'overrides'), { recursive: true });
    await setup({
      fe,
      stub: path.join(fe, 'setup.mjs'),
      source: FE,
      version: '4.0.0-test',
      sha256: null,
      provenance: 'local: the test @ no git',
      args: [],
      env: {},
      log: quiet,
      warn: quiet,
      fetchJson: registry,
      now: NOW,
    });
    for (const relative of [
      'tsconfig.json',
      '.niord/tsconfig.admin.app.json',
      '.niord/tsconfig.admin.spec.json',
      '.niord/tsconfig.info.app.json',
      '.niord/tsconfig.info.spec.json',
    ])
      assert.doesNotMatch(read(fe, relative), /internal/, relative);
    assert.match(read(fe, 'tsconfig.json'), /"@niord\/admin\/api"/);
  });
});

/** Every file under `dir`, relative with `/`, sorted. */
function gitless(dir) {
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(dir, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}
