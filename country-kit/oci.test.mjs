import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  CONFIG_TYPE,
  digestOf,
  main,
  MANIFEST_TYPE,
  MAX_ARCHIVE,
  PACK_TYPE,
  packTags,
  parseReference,
  pullPack,
  pushPack,
  pushWithdrawn,
  readWithdrawn,
  registryHost,
  tagDigest,
  WITHDRAWN_TYPE,
  withdrawnBytes,
} from './lib/oci.mjs';
import { fakeRegistry } from './test-fixtures/fake-registry.mjs';

const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]']);
/** fetch, refusing every URL that is not on a loopback host: no test reaches ghcr.io. */
const guard = (url, init) => {
  const { hostname, origin } = new URL(url);
  if (!LOOPBACK.has(hostname)) throw new Error(`the test refused a request to ${origin}`);
  return fetch(url, init);
};

const ARCHIVE = Buffer.from('PK\u0003\u0004 a canonical archive stands in here');
const CREDENTIALS = { username: 'example', password: 'secret-token' };
const REPOSITORY = 'example/niord-lang-zz';

/** A pack pushed to `registry` as `tag`; returns its digest reference. */
async function pushed(registry, tag = '1', archive = ARCHIVE, fetch = guard) {
  const digest = await pushPack({
    registry: registry.host,
    repository: REPOSITORY,
    tag,
    archive,
    lang: 'zz',
    credentials: CREDENTIALS,
    source: 'https://github.com/Example/niord-xx',
    fetch,
  });
  return `${registry.host}/${REPOSITORY}:${tag}@${digest}`;
}

/**
 * A manifest of a config and the layers given, stored as `tag`; returns the reference. A layer's
 * `size` and `digest`, and the config's `configType`, override what the data gives.
 */
function stored(
  registry,
  { layers, config = { format: 1, lang: 'zz' }, configType = CONFIG_TYPE, tag = '9' },
) {
  const configBytes = Buffer.from(JSON.stringify(config));
  const manifest = {
    schemaVersion: 2,
    mediaType: MANIFEST_TYPE,
    config: {
      mediaType: configType,
      digest: registry.putBlob(configBytes),
      size: configBytes.length,
    },
    layers: layers.map(({ mediaType, data, size = data.length, digest }) => ({
      mediaType,
      digest: digest ?? registry.putBlob(data),
      size,
    })),
  };
  const digest = registry.putManifest(REPOSITORY, Buffer.from(JSON.stringify(manifest)), tag);
  return `${registry.host}/${REPOSITORY}:${tag}@${digest}`;
}

describe('the OCI client against a registry with tokens', () => {
  let registry;
  before(async () => {
    registry = await fakeRegistry({ auth: true });
  });
  after(() => registry.close());
  beforeEach(() => {
    for (const knob of Object.keys(registry.knobs)) delete registry.knobs[knob];
    registry.requests.length = 0;
    registry.redirected.length = 0;
  });

  it('pushes a pack and pulls the same bytes back by its digest', async () => {
    const reference = await pushed(registry);
    const { digest } = parseReference(reference);
    assert.equal(
      await tagDigest({ registry: registry.host, repository: REPOSITORY, tag: '1', fetch: guard }),
      digest,
    );
    const pulled = await pullPack({ reference, fetch: guard });
    assert.ok(pulled.archive.equals(ARCHIVE));
    assert.equal(pulled.digest, digest);
    assert.equal(pulled.layer, digestOf(ARCHIVE));
  });

  it('pushes with Basic credentials at the token endpoint only, and a bearer token elsewhere', async () => {
    await pushed(registry, '2');
    const basic = registry.requests.filter((r) => r.authorization?.startsWith('Basic '));
    assert.ok(basic.length > 0);
    assert.deepEqual([...new Set(basic.map((r) => r.path))], ['/token']);
    assert.ok(
      registry.requests.some((r) => r.method === 'PUT' && r.authorization === 'Bearer token-push'),
    );
  });

  it('pulls anonymously: no credentials anywhere, a pull token only', async () => {
    const reference = await pushed(registry, '3');
    registry.requests.length = 0;
    await pullPack({ reference, fetch: guard });
    for (const { path: at, authorization } of registry.requests)
      assert.ok(
        authorization === undefined || authorization === 'Bearer token-pull',
        `${at}: ${authorization}`,
      );
    assert.ok(registry.requests.some((r) => r.path === '/token' && r.authorization === undefined));
  });

  it('asks for pull,push on every token request of a push, and for pull alone on a pull', async () => {
    const reference = await pushed(registry, '12');
    const pushing = registry.requests.filter((r) => r.path === '/token');
    assert.ok(pushing.length > 0);
    for (const { scope } of pushing) assert.equal(scope, `repository:${REPOSITORY}:pull,push`);
    registry.requests.length = 0;
    await pullPack({ reference, fetch: guard });
    const pulling = registry.requests.filter((r) => r.path === '/token');
    assert.ok(pulling.length > 0);
    for (const { scope } of pulling) assert.equal(scope, `repository:${REPOSITORY}:pull`);
  });

  it('follows no redirect on a write: an upload answered 307 fails, sending nothing elsewhere', async () => {
    const archive = Buffer.from('an archive the registry redirects');
    registry.knobs.redirectUploads = true;
    await assert.rejects(
      pushed(registry, '13', archive),
      /answered the PUT of \/v2\/example\/niord-lang-zz\/blobs\/uploads\/[0-9a-f-]+ with a redirect \(HTTP 307\): a write follows none/,
    );
    assert.deepEqual(registry.redirected, []);
    assert.equal(registry.blob(digestOf(archive)), undefined);
  });

  it('refuses a token endpoint on another host, asking nothing there', async () => {
    const reference = await pushed(registry, '4');
    registry.knobs.realm = 'https://evil.example/token';
    await assert.rejects(
      pullPack({ reference, fetch: guard }),
      /token endpoint https:\/\/evil\.example is not on 127\.0\.0\.1/,
    );
  });

  it('refuses a manifest whose bytes are not its digest', async () => {
    const reference = await pushed(registry, '5');
    registry.knobs.corruptManifest = true;
    await assert.rejects(
      pullPack({ reference, fetch: guard }),
      /hashes to sha256:[0-9a-f]{64}: its bytes are not the digest's/,
    );
  });

  it('refuses a blob whose bytes are not its digest', async () => {
    const reference = await pushed(registry, '6');
    registry.knobs.corruptBlob = true;
    await assert.rejects(pullPack({ reference, fetch: guard }), /does not hash to its digest/);
  });

  it('refuses a body over its size, before reading it', async () => {
    const reference = await pushed(registry, '7');
    registry.knobs.oversize = 70 * 1024;
    await assert.rejects(pullPack({ reference, fetch: guard }), /over the 65536 it may be/);
  });

  it('refuses a pushed manifest the registry answers with another digest', async () => {
    registry.knobs.wrongDigest = true;
    await assert.rejects(
      pushed(registry, '8'),
      /answered the digest sha256:[0-9a-f]{64} for the manifest 8 .*not sha256:[0-9a-f]{64}, the sha256 of the bytes sent/,
    );
  });

  it('refuses an upload Location on another host, sending nothing there', async () => {
    registry.knobs.foreignLocation = true;
    await assert.rejects(
      pushed(registry, '10', Buffer.from('another archive')),
      /upload Location on http:\/\/uploads\.example: nothing is sent there/,
    );
  });

  it('follows a blob redirect to another host without the Authorization header', async () => {
    const reference = await pushed(registry, '11');
    registry.knobs.redirectBlobs = true;
    const pulled = await pullPack({ reference, fetch: guard });
    assert.ok(pulled.archive.equals(ARCHIVE));
    assert.ok(registry.redirected.length >= 1);
    for (const { authorization } of registry.redirected) assert.equal(authorization, undefined);
  });

  it('refuses a pack of two layers, a layer of another media type, and a config of another language', async () => {
    const zip = { mediaType: PACK_TYPE, data: Buffer.from('a') };
    await assert.rejects(
      pullPack({ reference: stored(registry, { layers: [zip, zip] }), fetch: guard }),
      /has 2 layers; a pack has exactly one/,
    );
    await assert.rejects(
      pullPack({
        reference: stored(registry, {
          layers: [{ mediaType: 'application/zip', data: Buffer.from('b') }],
        }),
        fetch: guard,
      }),
      /layer is application\/zip, not application\/vnd\.niord\.lang-pack\.v1\+zip/,
    );
    await assert.rejects(
      pullPack({
        reference: stored(registry, { layers: [zip], config: { format: 1, lang: 'da' } }),
        fetch: guard,
      }),
      /config is not \{"format":1,"lang":"zz"\}/,
    );
  });

  it('refuses a layer whose descriptor the bytes do not keep, or which no pack may have', async () => {
    const zip = (text, extra = {}) => ({ mediaType: PACK_TYPE, data: Buffer.from(text), ...extra });
    const pull = (options) => pullPack({ reference: stored(registry, options), fetch: guard });
    await assert.rejects(
      pull({ layers: [zip('four', { size: 5 })] }),
      /the blob sha256:[0-9a-f]{64} of example\/niord-lang-zz is 4 bytes, not its 5/,
    );
    registry.requests.length = 0;
    const big = zip('big', { size: MAX_ARCHIVE + 1 });
    await assert.rejects(
      pull({ layers: [big] }),
      /'s archive is 16777217 bytes, over the 16777216 it may be/,
    );
    const layerDigest = digestOf(big.data);
    assert.ok(!registry.requests.some((r) => r.path.endsWith(`/blobs/${layerDigest}`)));
    await assert.rejects(
      pull({ layers: [zip('md5', { digest: 'md5:0123' })] }),
      /'s layer has no digest and size/,
    );
    await assert.rejects(
      pull({ layers: [zip('half', { size: 1.5 })] }),
      /'s layer has no digest and size/,
    );
  });

  it('refuses a config of another media type, other keys or another format', async () => {
    const zip = { mediaType: PACK_TYPE, data: Buffer.from('c') };
    const pull = (options) =>
      pullPack({ reference: stored(registry, { layers: [zip], ...options }), fetch: guard });
    await assert.rejects(
      pull({ configType: 'application/vnd.oci.image.config.v1+json' }),
      /config is application\/vnd\.oci\.image\.config\.v1\+json, not application\/vnd\.niord\.lang-pack\.config\.v1\+json/,
    );
    await assert.rejects(
      pull({ config: { format: 1, lang: 'zz', extra: true } }),
      /config is not \{"format":1,"lang":"zz"\}/,
    );
    await assert.rejects(
      pull({ config: { format: 2, lang: 'zz' } }),
      /config is not \{"format":1,"lang":"zz"\}/,
    );
  });

  it('refuses a body over its size while reading it, when no length is declared', async () => {
    const reference = await pushed(registry, '15', Buffer.from('an archive sent in two writes'));
    registry.knobs.chunked = true;
    const pulled = await pullPack({ reference, fetch: guard });
    assert.ok(pulled.archive.equals(Buffer.from('an archive sent in two writes')));
    registry.knobs.oversize = 70 * 1024;
    await assert.rejects(
      pullPack({ reference, fetch: guard }),
      /the manifest sha256:[0-9a-f]{64} of example\/niord-lang-zz is over the 65536 bytes it may be/,
    );
  });

  it('confirms a manifest the registry answers without a digest by a HEAD, and refuses it when the HEAD finds none', async () => {
    registry.knobs.noManifestDigest = true;
    const reference = await pushed(
      registry,
      '16',
      Buffer.from('an archive answered without a digest'),
    );
    const { digest } = parseReference(reference);
    assert.ok(
      registry.requests.some(
        (r) => r.method === 'HEAD' && r.path === `/v2/${REPOSITORY}/manifests/${digest}`,
      ),
    );
    // A registry that stored nothing under the digest: its HEAD answers 404.
    const noManifest = (url, init) =>
      init?.method === 'HEAD' && /\/manifests\/sha256:/.test(new URL(url).pathname)
        ? new Response(null, { status: 404 })
        : guard(url, init);
    await assert.rejects(
      pushed(registry, '17', Buffer.from('an archive the registry lost'), noManifest),
      /127\.0\.0\.1:[0-9]+ answered no digest for the manifest 17 of example\/niord-lang-zz, and has no manifest sha256:[0-9a-f]{64}/,
    );
  });

  it('puts a blob at an absolute upload Location with its query kept, and at a relative one', async () => {
    await pushed(registry, '18', Buffer.from('an archive put at an absolute Location'));
    const absolute = registry.requests.filter(
      (r) => r.method === 'PUT' && r.path.includes('/blobs/uploads/'),
    );
    assert.ok(absolute.length > 0);
    for (const { search } of absolute) {
      assert.match(search, /[?&]_state=[0-9a-f-]+/);
      assert.match(search, /[?&]digest=sha256%3A[0-9a-f]{64}/);
    }
    registry.requests.length = 0;
    registry.knobs.relativeLocation = true;
    const reference = await pushed(registry, '19', Buffer.from('an archive put at a relative one'));
    const relative = registry.requests.filter(
      (r) => r.method === 'PUT' && r.path.includes('/blobs/uploads/'),
    );
    assert.ok(relative.length > 0);
    for (const { search } of relative) assert.match(search, /^\?digest=sha256%3A[0-9a-f]{64}$/);
    assert.ok(
      (await pullPack({ reference, fetch: guard })).archive.equals(
        Buffer.from('an archive put at a relative one'),
      ),
    );
  });

  it('says a token that may not write the package is the cause of a refused push', async () => {
    registry.knobs.noPush = true;
    await assert.rejects(
      pushed(registry, '20', Buffer.from('an archive a pull token cannot push')),
      /an upload to example\/niord-lang-zz: HTTP 401 \(the token may not write this package\)/,
    );
  });

  it('reads every page of a paged tag list, and throws on a part of it', async () => {
    const name = 'example/niord-lang-pg';
    for (const tag of ['1', '2', '10', 'withdrawn'])
      registry.putManifest(name, Buffer.from(`{"tag":"${tag}"}`), tag);
    const tags = (fetch = guard) => packTags({ registry: registry.host, repository: name, fetch });
    registry.knobs.pageSize = 1;
    assert.deepEqual(await tags(), ['10', '2', '1']);
    const pages = registry.requests.filter((r) => r.path === `/v2/${name}/tags/list`);
    assert.ok(
      pages.some((r) => r.search === '?n=1&last=1'),
      JSON.stringify(pages),
    );
    // A 404 on a later page: the list is not read whole.
    const lost = (url, init) =>
      new URL(url).searchParams.has('last')
        ? new Response(null, { status: 404 })
        : guard(url, init);
    await assert.rejects(tags(lost), /page 2 of the tags of example\/niord-lang-pg: HTTP 404/);
    delete registry.knobs.pageSize;
    registry.knobs.selfLink = true;
    await assert.rejects(
      tags(),
      /127\.0\.0\.1:[0-9]+ lists over 100 pages of tags for example\/niord-lang-pg: no pin is chosen from a part of the list/,
    );
  });

  it('refuses a reference without a digest', async () => {
    await assert.rejects(
      pullPack({ reference: `${registry.host}/${REPOSITORY}:1`, fetch: guard }),
      /names no digest/,
    );
  });
});

describe('a private package', () => {
  let registry;
  before(async () => {
    registry = await fakeRegistry({ auth: true, anonymousPull: false });
  });
  after(() => registry.close());

  it('says an anonymous pull needs the package to be public', async () => {
    const reference = await pushed(registry);
    await assert.rejects(
      pullPack({ reference, fetch: guard }),
      /127\.0\.0\.1:[0-9]+ refused a token for repository:example\/niord-lang-zz:pull \(HTTP 401\): an anonymous pull needs the package to be public/,
    );
  });
});

describe('the token endpoint of ghcr.io', () => {
  const REFERENCE = `ghcr.io/sifa-dk/niord-lang-da:1@sha256:${'a'.repeat(64)}`;
  /** A fetch that answers a challenge naming `realm`, then 403, and touches no network. */
  const challenged = (realm) => {
    const calls = [];
    const fetch = async (url) => {
      calls.push(String(url));
      if (calls.length > 1) return new Response(null, { status: 403 });
      return new Response(null, {
        status: 401,
        headers: {
          'WWW-Authenticate': `Bearer realm="${realm}",service="ghcr.io",scope="repository:sifa-dk/niord-lang-da:pull"`,
        },
      });
    };
    return { calls, fetch };
  };

  for (const realm of [
    'http://ghcr.io/token',
    'https://ghcr.io.evil.example/token',
    'https://<user>:<password>@ghcr.io/token',
  ])
    it(`refuses ${realm}, asking nothing there`, async () => {
      const { calls, fetch } = challenged(realm);
      await assert.rejects(
        pullPack({ reference: REFERENCE, fetch }),
        /token endpoint .* is not on ghcr\.io/,
      );
      assert.equal(calls.length, 1);
    });

  it('asks https://ghcr.io/token, anonymously, for a pull', async () => {
    const { calls, fetch } = challenged('https://ghcr.io/token');
    await assert.rejects(
      pullPack({ reference: REFERENCE, fetch }),
      /ghcr\.io refused a token for repository:sifa-dk\/niord-lang-da:pull \(HTTP 403\)/,
    );
    assert.equal(calls.length, 2);
    assert.match(
      calls[1],
      /^https:\/\/ghcr\.io\/token\?service=ghcr\.io&scope=repository%3Asifa-dk%2Fniord-lang-da%3Apull$/,
    );
  });
});

describe('the withdrawn marker', () => {
  let registry;
  before(async () => {
    registry = await fakeRegistry({ auth: true });
  });
  after(() => registry.close());
  const read = (repository = REPOSITORY) =>
    readWithdrawn({ registry: registry.host, repository, lang: 'zz', fetch: guard });

  it('is a typed error when the pack has none', async () => {
    await assert.rejects(read('example/niord-lang-zz'), (error) => error.code === 'NO_MARKER');
  });

  it('round-trips, and is read anonymously', async () => {
    const entry = {
      digest: digestOf(Buffer.from('x')),
      tag: '3',
      reason: 'a wrong text',
      date: '2026-10-22',
    };
    await pushWithdrawn({
      registry: registry.host,
      repository: REPOSITORY,
      lang: 'zz',
      withdrawn: [entry],
      credentials: CREDENTIALS,
      fetch: guard,
    });
    registry.requests.length = 0;
    assert.deepEqual((await read()).withdrawn, [entry]);
    assert.ok(registry.requests.every((r) => !r.authorization?.startsWith('Basic ')));
  });

  const X = digestOf(Buffer.from('x'));
  /** A marker of the entries given, each `{ digest, tag, reason, date }` with `fields` over it. */
  const entries = (...fields) => ({
    format: 1,
    lang: 'zz',
    withdrawn: fields.map((f) => ({ digest: X, tag: '1', reason: 'r', date: '2026-10-22', ...f })),
  });
  // Each row: what is wrong, the marker (text is stored as it is), and the rule that fires.
  for (const [what, marker, rule] of [
    ['not JSON', '{"format":1', /is not JSON: /],
    ['not an object', [], /is not an object$/],
    [
      'an unknown field',
      { format: 1, lang: 'zz', withdrawn: [], extra: true },
      /has the fields extra,format,lang,withdrawn, not format, lang, withdrawn$/,
    ],
    ['format 2', { format: 2, lang: 'zz', withdrawn: [] }, /is not of format 1$/],
    ['another language', { format: 1, lang: 'da', withdrawn: [] }, /is for "da"$/],
    [
      'a withdrawn list that is no list',
      { format: 1, lang: 'zz', withdrawn: {} },
      /has no withdrawn list$/,
    ],
    [
      'an entry with a field more',
      entries({ by: 'x' }),
      /entry 1 is not \{ digest, tag, reason, date \}$/,
    ],
    [
      'an entry without a sha256 digest',
      entries({ digest: 'sha256:abc' }),
      /entry 1 has no sha256 digest$/,
    ],
    ['a tag that is not a number', entries({ tag: 'latest' }), /entry 1 has no numeric tag$/],
    [
      'a reason over two lines',
      entries({ reason: 'one\ntwo' }),
      /entry 1: the reason is not one line of text/,
    ],
    [
      'a date that is not yyyy-mm-dd',
      entries({ date: '2026-10-2' }),
      /entry 1 has no yyyy-mm-dd date$/,
    ],
    ['a digest listed twice', entries({}, { tag: '2' }), /lists sha256:[0-9a-f]{64} twice$/],
  ])
    it(`refuses a malformed marker: ${what}`, async () => {
      const data = Buffer.from(typeof marker === 'string' ? marker : JSON.stringify(marker));
      stored(registry, { layers: [{ mediaType: WITHDRAWN_TYPE, data }], tag: 'withdrawn' });
      await assert.rejects(read(), (error) => {
        assert.match(error.message, /^the withdrawn marker of niord-lang-zz /);
        assert.match(error.message, rule);
        return true;
      });
    });

  it('refuses a marker over its size', async () => {
    stored(registry, {
      layers: [{ mediaType: WITHDRAWN_TYPE, data: Buffer.alloc(300 * 1024, 0x20) }],
      tag: 'withdrawn',
    });
    await assert.rejects(read(), /over the 262144 it may be/);
  });

  it('writes the format it reads', () => {
    assert.deepEqual(JSON.parse(withdrawnBytes('zz', []).toString('utf8')), {
      format: 1,
      lang: 'zz',
      withdrawn: [],
    });
  });
});

describe('the hosts a client talks to', () => {
  it('takes ghcr.io and loopback registries, and refuses any other host', () => {
    assert.equal(registryHost('ghcr.io'), 'ghcr.io');
    assert.equal(registryHost('http://127.0.0.1:5000'), '127.0.0.1:5000');
    assert.equal(registryHost('localhost:5000'), 'localhost:5000');
    assert.equal(registryHost('[::1]:5000'), '[::1]:5000');
    for (const refused of [
      'http://ghcr.io',
      'docker.io',
      'http://registry.example:5000',
      'https://127.0.0.1.example:5000',
      'http://10.0.0.1:5000',
      '127.0.0.1:99999',
      '127.0.0.1:65536',
      '127.0.0.1:0',
      'http://localhost:0',
    ])
      assert.throws(() => registryHost(refused), /is refused/, refused);
    assert.equal(registryHost('127.0.0.1:65535'), '127.0.0.1:65535');
  });

  it('reads a pack reference and refuses another shape', () => {
    const digest = `sha256:${'a'.repeat(64)}`;
    assert.deepEqual(parseReference(`ghcr.io/sifa-dk/niord-lang-da:12@${digest}`), {
      registry: 'ghcr.io',
      owner: 'sifa-dk',
      lang: 'da',
      repository: 'sifa-dk/niord-lang-da',
      tag: '12',
      digest,
    });
    for (const refused of [
      `docker.io/sifa-dk/niord-lang-da:12@${digest}`,
      `ghcr.io/SIFA-DK/niord-lang-da:12@${digest}`,
      `ghcr.io/sifa-dk/niord-lang-dansk:12@${digest}`,
      `ghcr.io/sifa-dk/niord-lang-da:latest@${digest}`,
      `ghcr.io/sifa-dk/niord-lang-da:12@sha256:abc`,
      `127.0.0.1:99999/sifa-dk/niord-lang-da:12@${digest}`,
      `127.0.0.1:0/sifa-dk/niord-lang-da:12@${digest}`,
    ])
      assert.throws(() => parseReference(refused), /is not a pack reference/, refused);
  });

  it('talks to a loopback registry on port 80 at the origin the URL writes', async () => {
    const calls = [];
    const record = async (url) => {
      calls.push(String(url));
      return new Response(null, { status: 404 });
    };
    const digest = `sha256:${'a'.repeat(64)}`;
    await assert.rejects(
      pullPack({ reference: `localhost:80/x/niord-lang-zz:1@${digest}`, fetch: record }),
      /localhost:80\/x\/niord-lang-zz has no manifest sha256:a{64}/,
    );
    assert.deepEqual(calls, [`http://localhost/v2/x/niord-lang-zz/manifests/${digest}`]);
  });

  it('never fetches from a host it refuses', async () => {
    const calls = [];
    const record = (url) => {
      calls.push(String(url));
      throw new Error('fetched');
    };
    await assert.rejects(
      pullPack({
        reference: `registry.example/x/niord-lang-zz:1@sha256:${'a'.repeat(64)}`,
        fetch: record,
      }),
      /is not a pack reference/,
    );
    assert.deepEqual(calls, []);
  });
});

describe('the publish and withdraw commands', () => {
  let registry;
  let dir;
  let archive;
  let sha;
  before(async () => {
    registry = await fakeRegistry({ auth: true });
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-oci-'));
    archive = path.join(dir, 'niord-lang-zz.zip');
    fs.writeFileSync(archive, ARCHIVE);
    sha = digestOf(ARCHIVE).slice('sha256:'.length);
  });
  after(async () => {
    await registry.close();
    fs.rmSync(dir, { recursive: true, force: true });
  });

  const NOW = new Date('2026-10-22T23:30:00Z');
  const env = (extra = {}) => ({
    PACK_LANG: 'zz',
    PACK_TOKEN: 'secret-token',
    PACK_REGISTRY: `http://${registry.host}`,
    GITHUB_ACTOR: 'example',
    GITHUB_REPOSITORY_OWNER: 'Example',
    GITHUB_REPOSITORY: 'Example/niord-xx',
    GITHUB_STEP_SUMMARY: path.join(dir, 'summary.md'),
    ...extra,
  });
  const run = async (args, extra) => {
    const out = [];
    const err = [];
    const status = await main(args, {
      env: env(extra),
      fetch: guard,
      now: NOW,
      log: (l) => out.push(l),
      error: (l) => err.push(l),
    });
    return { status, out: out.join('\n'), err: err.join('\n') };
  };
  const marker = () =>
    readWithdrawn({ registry: registry.host, repository: REPOSITORY, lang: 'zz', fetch: guard });

  it('publishes the first tag with the empty marker, under the lower-cased owner, and writes the summary', async () => {
    fs.rmSync(path.join(dir, 'summary.md'), { force: true });
    const result = await run(['publish', '--archive', archive, '--sha256', sha], {
      GITHUB_RUN_NUMBER: '1',
    });
    assert.equal(result.status, 0, result.err);
    assert.match(
      result.out,
      new RegExp(
        `published ${registry.host.replace(/\./g, '\\.')}/example/niord-lang-zz:1@sha256:[0-9a-f]{64}`,
      ),
    );
    assert.match(
      result.out,
      /the first publish of niord-lang-zz: the empty withdrawn marker pushed/,
    );
    assert.deepEqual((await marker()).withdrawn, []);
    const summary = fs.readFileSync(path.join(dir, 'summary.md'), 'utf8');
    assert.match(summary, /^\| zz \| 1 \| sha256:[0-9a-f]{64} \| 2026-10-22 \|$/m);
  });

  it('publishes the first tag where a pull token may not read a package that does not exist yet', async () => {
    const strict = await fakeRegistry({ auth: true });
    try {
      strict.knobs.denyUnknown = true;
      const result = await run(['publish', '--archive', archive, '--sha256', sha], {
        GITHUB_RUN_NUMBER: '1',
        PACK_REGISTRY: `http://${strict.host}`,
        GITHUB_STEP_SUMMARY: path.join(dir, 'strict-summary.md'),
      });
      assert.equal(result.status, 0, result.err);
      assert.ok(strict.tagged(REPOSITORY, '1'));
      assert.ok(strict.tagged(REPOSITORY, 'withdrawn'));
      const scopes = strict.requests.filter((r) => r.path === '/token').map((r) => r.scope);
      assert.deepEqual([...new Set(scopes)], [`repository:${REPOSITORY}:pull,push`]);
    } finally {
      await strict.close();
    }
  });

  it('publishes a second tag and leaves the marker as it is', async () => {
    const before = (await marker()).digest;
    const result = await run(['publish', '--archive', archive, '--sha256', sha], {
      GITHUB_RUN_NUMBER: '2',
    });
    assert.equal(result.status, 0, result.err);
    assert.doesNotMatch(result.out, /first publish/);
    assert.equal((await marker()).digest, before);
  });

  it('refuses a tag that exists, and an archive of another sha256, pushing nothing', async () => {
    const again = await run(['publish', '--archive', archive, '--sha256', sha], {
      GITHUB_RUN_NUMBER: '2',
    });
    assert.equal(again.status, 1);
    assert.match(
      again.err,
      /niord-lang-zz:2 exists already: a tag is never pushed twice, and a tag that exists is a completed publish; to publish again, dispatch publish, which gets a new run number/,
    );
    const wrong = await run(['publish', '--archive', archive, '--sha256', 'f'.repeat(64)], {
      GITHUB_RUN_NUMBER: '3',
    });
    assert.equal(wrong.status, 1);
    assert.match(wrong.err, /does not hash to f{64}: nothing was pushed/);
    assert.equal(registry.tagged(REPOSITORY, '3'), undefined);
  });

  it('refuses a bad language, a registry that is not loopback, and a usage error with exit 2', async () => {
    assert.match(
      (
        await run(['publish', '--archive', archive, '--sha256', sha], {
          GITHUB_RUN_NUMBER: '4',
          PACK_LANG: 'zz;x',
        })
      ).err,
      /PACK_LANG "zz;x" is not a language code/,
    );
    assert.match(
      (
        await run(['publish', '--archive', archive, '--sha256', sha], {
          GITHUB_RUN_NUMBER: '4',
          PACK_REGISTRY: 'http://registry.example:5000',
        })
      ).err,
      /is refused/,
    );
    assert.equal((await run(['publish', '--archive', archive])).status, 2);
    assert.equal((await run(['push'])).status, 2);
    assert.equal(registry.tagged(REPOSITORY, '4'), undefined);
  });

  it('takes no inherited name for a command', async () => {
    for (const command of ['toString', 'constructor', 'valueOf', 'hasOwnProperty', '__proto__']) {
      const result = await run([command, 'a', 'b']);
      assert.equal(result.status, 2, command);
      assert.equal(result.out, '', command);
      assert.match(
        result.err,
        new RegExp(`^oci: unknown command "${command}" \\(node oci\\.mjs publish `),
        command,
      );
    }
  });

  it('finishes, on a re-run, a first publish that broke between the marker and the pack', async () => {
    const broken = await fakeRegistry({ auth: true });
    try {
      const extra = {
        GITHUB_RUN_NUMBER: '1',
        PACK_REGISTRY: `http://${broken.host}`,
        GITHUB_STEP_SUMMARY: path.join(dir, 'broken-summary.md'),
      };
      // The second manifest PUT, the pack's, fails once.
      broken.knobs.failManifestPut = 2;
      const first = await run(['publish', '--archive', archive, '--sha256', sha], extra);
      assert.equal(first.status, 1);
      assert.match(first.err, /the manifest 1 of example\/niord-lang-zz: HTTP 500/);
      assert.ok(broken.tagged(REPOSITORY, 'withdrawn'));
      assert.equal(broken.tagged(REPOSITORY, '1'), undefined);
      // "Re-run failed jobs": the same run number.
      const again = await run(['publish', '--archive', archive, '--sha256', sha], extra);
      assert.equal(again.status, 0, again.err);
      const reference = /published (\S+)/.exec(again.out)[1];
      assert.ok((await pullPack({ reference, fetch: guard })).archive.equals(ARCHIVE));
      const read = await readWithdrawn({
        registry: broken.host,
        repository: REPOSITORY,
        lang: 'zz',
        fetch: guard,
      });
      assert.deepEqual(read.withdrawn, []);
    } finally {
      await broken.close();
    }
  });

  it('withdraws a tag with its digest, the reason and the date, once', async () => {
    const digest = registry.tagged(REPOSITORY, '1');
    const result = await run(['withdraw'], { PACK_TAG: '1', PACK_REASON: 'a wrong date pattern' });
    assert.equal(result.status, 0, result.err);
    assert.deepEqual((await marker()).withdrawn, [
      { digest, tag: '1', reason: 'a wrong date pattern', date: '2026-10-22' },
    ]);
    assert.match(
      fs.readFileSync(path.join(dir, 'summary.md'), 'utf8'),
      new RegExp(`^\\| zz \\| 1 \\| ${digest} \\| 2026-10-22 \\| a wrong date pattern \\|$`, 'm'),
    );
    const before = (await marker()).digest;
    const again = await run(['withdraw'], { PACK_TAG: '1', PACK_REASON: 'again' });
    assert.equal(again.status, 0, again.err);
    assert.match(again.out, /listed in the withdrawn marker already; nothing was pushed/);
    assert.equal((await marker()).digest, before);
  });

  it('refuses a withdrawal of a tag that is not a number, an unknown tag, and an empty or multi-line reason', async () => {
    for (const [extra, message] of [
      [{ PACK_TAG: 'latest', PACK_REASON: 'r' }, /PACK_TAG "latest" is not a pack tag/],
      [{ PACK_TAG: '77', PACK_REASON: 'r' }, /has no tag 77/],
      [{ PACK_TAG: '2', PACK_REASON: '  ' }, /the reason is empty/],
      [{ PACK_TAG: '2', PACK_REASON: 'one\ntwo' }, /not one line of text/],
      [{ PACK_TAG: '2', PACK_REASON: 'x'.repeat(501) }, /over 500 characters/],
    ]) {
      const result = await run(['withdraw'], extra);
      assert.equal(result.status, 1, JSON.stringify(extra));
      assert.match(result.err, message);
    }
    assert.equal((await marker()).withdrawn.length, 1);
  });

  it('refuses a withdrawal from a pack never published: no marker', async () => {
    const result = await run(['withdraw'], { PACK_LANG: 'yy', PACK_TAG: '1', PACK_REASON: 'r' });
    assert.equal(result.status, 1);
    assert.match(result.err, /has no withdrawn marker .*publish the pack first/);
  });

  it('escapes a | in the withdrawal row and fences it longer than the reason, keeping the reason in the marker', async () => {
    const summary = path.join(dir, 'pipe-summary.md');
    const published = await run(['publish', '--archive', archive, '--sha256', sha], {
      GITHUB_RUN_NUMBER: '5',
    });
    assert.equal(published.status, 0, published.err);
    const reason = 'the | splits a row, ```and``` fences';
    const result = await run(['withdraw'], {
      PACK_TAG: '5',
      PACK_REASON: reason,
      GITHUB_STEP_SUMMARY: summary,
    });
    assert.equal(result.status, 0, result.err);
    const digest = registry.tagged(REPOSITORY, '5');
    assert.equal((await marker()).withdrawn.at(-1).reason, reason);
    const text = fs.readFileSync(summary, 'utf8');
    assert.ok(
      text.includes(
        `\n\`\`\`\`\n| zz | 5 | ${digest} | 2026-10-22 | the \\| splits a row, \`\`\`and\`\`\` fences |\n\`\`\`\`\n`,
      ),
      text,
    );
    assert.match(text, /: the \\\| splits a row/);
  });
});
