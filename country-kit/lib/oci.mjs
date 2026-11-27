#!/usr/bin/env node
/**
 * The kit's OCI client: publishes, pulls and withdraws language packs on GHCR
 * (fe-country-split-plan §4.5, §4.11 rule 6). One file, Node's built-ins and the global fetch only,
 * importing no other file of the kit, so that pack.yml's validate job can hand this one verified
 * file to the jobs that hold the package token, which check its sha256 and run nothing else of the
 * kit and no npm.
 *
 *   node oci.mjs publish --archive <file> --sha256 <hex>   # pack.yml's publish job
 *   node oci.mjs withdraw                                  # pack.yml's withdraw job
 *
 * Every value reaches the commands through the environment: PACK_LANG (^[a-z]{2,3}$), PACK_TOKEN,
 * GITHUB_ACTOR, GITHUB_REPOSITORY_OWNER (lower-cased, the package's namespace), GITHUB_REPOSITORY,
 * GITHUB_RUN_NUMBER (publish: the tag), PACK_TAG and PACK_REASON (withdraw), GITHUB_STEP_SUMMARY,
 * and PACK_REGISTRY (a loopback registry for the example's harness; ghcr.io when unset). Exit 0
 * when done, 1 on a refusal or a failure, 2 on a usage error.
 *
 * What a pack is on the registry, `<registry>/<owner>/niord-lang-<lang>`:
 *
 *   :<n>           an OCI image manifest (application/vnd.oci.image.manifest.v1+json) whose config
 *                  is application/vnd.niord.lang-pack.config.v1+json, {"format":1,"lang":"<lang>"},
 *                  and whose one layer, application/vnd.niord.lang-pack.v1+zip, is the canonical
 *                  archive pack.mjs validate built, unchanged
 *   :withdrawn     the withdrawn marker: the same config, and one layer
 *                  application/vnd.niord.lang-pack.withdrawn.v1+json,
 *                  {"format":1,"lang":"<lang>","withdrawn":[{"digest","tag","reason","date"}]};
 *                  the first publish pushes it empty, before the pack, a withdrawal pushes it
 *                  whole; so a tag that exists is a completed publish, and a first publish broken
 *                  between the two pushes leaves only a marker, which a re-run of the job finishes
 *
 * The rules this client keeps: a registry is ghcr.io over https or a loopback host over http (the
 * tests' and the harness's), never another host; a 401 is answered through the registry's
 * `WWW-Authenticate: Bearer` challenge, whose token endpoint must be https on the registry's host
 * (or loopback), anonymously unless the caller passes credentials, which only publish and
 * withdraw do — a pull never reads a token from anywhere — and a client with credentials asks for
 * pull,push on every request, an anonymous one for pull; a manifest is at most 64 KiB and must
 * hash to the digest it was asked for; a blob must hash to its descriptor's digest, be the size
 * the descriptor gives and stay under a cap checked before anything is read (for a pack's layer
 * MAX_ARCHIVE, 16 MiB on the wire; see there); a blob redirect to another host is followed
 * without the Authorization header (fetch drops it on a cross-origin redirect); an upload's
 * Location stays on the registry's host, and a write (POST, PUT) follows no redirect, so its body
 * reaches that host only; a pushed manifest's answered Docker-Content-Digest, when the registry
 * gives one, must be the sha256 of the bytes sent, and without one the manifest is confirmed by a
 * HEAD of that digest; and a tag list is read whole, or not at all. Every function takes `fetch`,
 * so a test can refuse every non-loopback URL.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const MANIFEST_TYPE = 'application/vnd.oci.image.manifest.v1+json';
export const CONFIG_TYPE = 'application/vnd.niord.lang-pack.config.v1+json';
export const PACK_TYPE = 'application/vnd.niord.lang-pack.v1+zip';
export const WITHDRAWN_TYPE = 'application/vnd.niord.lang-pack.withdrawn.v1+json';
export const WITHDRAWN_TAG = 'withdrawn';
export const GHCR = 'ghcr.io';

export const LANG = /^[a-z]{2,3}$/;
export const TAG = /^[0-9]+$/;
export const DIGEST = /^sha256:[0-9a-f]{64}$/;
const OWNER = /^[a-z0-9](?:[a-z0-9-]{0,38})$/;
const REPOSITORY_NAME = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
const LOOPBACK_HOST = /^(?:localhost|127\.0\.0\.1|\[::1\]):([1-9][0-9]{0,4})$/;
/** Whether a text is a loopback `<host>:<port>`, the port 1-65535. */
const isLoopbackHost = (text) => {
  const match = LOOPBACK_HOST.exec(text);
  return Boolean(match) && Number(match[1]) <= 65535;
};
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;
/** Whether a text holds a control character or a line or paragraph separator. */
const hasControl = (text) =>
  [...text].some((c) => {
    const n = c.codePointAt(0);
    return n < 0x20 || (n >= 0x7f && n <= 0x9f) || n === 0x2028 || n === 0x2029;
  });

export const MAX_MANIFEST = 64 * 1024;
export const MAX_CONFIG = 4 * 1024;
// Two caps bound a pack's archive: this one its layer's bytes on the wire, checked against the
// descriptor before a byte is read and while reading; lib/pack-archive.mjs's MAX_ARCHIVE_BYTES,
// 8 MiB, the inflated total of its entries, checked when the archive is read. The transport cap
// sits above, since a stored archive of nearly 8 MiB of content is a little over 8 MiB; never
// make them equal, or a valid archive would trip this one.
export const MAX_ARCHIVE = 16 * 1024 * 1024;
export const MAX_WITHDRAWN = 256 * 1024;
export const MAX_REASON = 500;
const MAX_TOKEN = 64 * 1024;
const MAX_TAG_PAGES = 100;

/** The digest of some bytes, `sha256:<64 hex>`. */
export const digestOf = (bytes) =>
  `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isLoopback = (hostname) => ['localhost', '127.0.0.1', '[::1]', '::1'].includes(hostname);

/** An error the CLI reports with exit 2. */
class UsageError extends Error {}

/** The error of a pack whose withdrawn marker the registry does not have. */
export class NoMarkerError extends Error {
  constructor(repository) {
    super(`${repository} has no withdrawn marker (:${WITHDRAWN_TAG})`);
    this.code = 'NO_MARKER';
  }
}

/**
 * A registry as a host the client talks to: `ghcr.io`, or a loopback `<host>:<port>` given as
 * itself or as `http://<host>:<port>`. Anything else is refused: a pack is pulled from GHCR, and
 * from a local registry only in a test or the example's harness.
 */
export function registryHost(value) {
  const text = String(value ?? '');
  if (text === GHCR || text === `https://${GHCR}`) return GHCR;
  const host = text.replace(/^http:\/\//, '').replace(/\/$/, '');
  if (isLoopbackHost(host)) return host;
  throw new Error(
    `the registry "${text}" is refused: a pack is on ${GHCR}, or on a loopback registry (http://127.0.0.1:<port>) in a test`,
  );
}

/** The registry's origin: https for GHCR, http for a loopback host (port 80 as the URL writes it). */
const originOf = (host) => (host === GHCR ? `https://${GHCR}` : new URL(`http://${host}`).origin);

/**
 * A pack's reference, `<registry>/<owner>/niord-lang-<lang>[:<tag>][@sha256:<hex>]`:
 * `{ registry, owner, lang, repository, tag, digest }`, the last two undefined when absent.
 * Throws on anything else.
 */
export function parseReference(text) {
  const match =
    /^(ghcr\.io|(?:localhost|127\.0\.0\.1|\[::1\]):[0-9]{1,5})\/([a-z0-9-]+)\/niord-lang-([a-z]{2,3})(?::([0-9]+|withdrawn))?(?:@(sha256:[0-9a-f]{64}))?$/.exec(
      String(text ?? ''),
    );
  if (!match || (match[1] !== GHCR && !isLoopbackHost(match[1])) || !OWNER.test(match[2]))
    throw new Error(
      `"${text}" is not a pack reference, <registry>/<owner>/niord-lang-<lang>:<tag>@sha256:<64 hex>`,
    );
  const [, registry, owner, lang, tag, digest] = match;
  return { registry, owner, lang, repository: `${owner}/niord-lang-${lang}`, tag, digest };
}

/** A pack's reference as text, the tag and the digest each when given. */
export const formatReference = ({ registry, owner, lang, tag, digest }) =>
  `${registry}/${owner}/niord-lang-${lang}${tag ? `:${tag}` : ''}${digest ? `@${digest}` : ''}`;

/** The `key="value"` pairs of a `WWW-Authenticate: Bearer …` header, or undefined. */
export function parseChallenge(header) {
  const match = /^\s*Bearer\s+(.*)$/i.exec(header ?? '');
  if (!match) return undefined;
  const values = {};
  for (const pair of match[1].matchAll(/([A-Za-z]+)="([^"]*)"/g))
    values[pair[1].toLowerCase()] = pair[2];
  return values.realm ? values : undefined;
}

/**
 * The body of a response, at most `max` bytes: a larger declared or actual length is refused
 * before more is read.
 */
async function readBody(response, max, what) {
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > max) {
    await response.body?.cancel().catch(() => {});
    throw new Error(`${what} is ${declared} bytes, over the ${max} it may be`);
  }
  const chunks = [];
  let total = 0;
  if (response.body)
    for await (const chunk of response.body) {
      total += chunk.length;
      if (total > max) throw new Error(`${what} is over the ${max} bytes it may be`);
      chunks.push(Buffer.from(chunk));
    }
  return Buffer.concat(chunks);
}

/** Discards a response's body. */
const discard = async (response) => {
  await response.body?.cancel().catch(() => {});
};

/**
 * A client of one registry. `credentials`, `{ username, password }`, are sent to the token
 * endpoint only, and only when given: a pull passes none and is anonymous.
 */
export function registryClient({ registry, credentials, fetch: fetchImpl = globalThis.fetch }) {
  const host = registryHost(registry);
  const origin = originOf(host);
  let token;

  /** The token endpoint's URL, refused unless it is https on the registry's host, or loopback. */
  const realmOf = (realm) => {
    let url;
    try {
      url = new URL(realm);
    } catch {
      throw new Error(`the registry's token endpoint "${realm}" is not a URL`);
    }
    const ok =
      host === GHCR
        ? url.protocol === 'https:' && url.host === GHCR
        : isLoopback(url.hostname) && (url.protocol === 'http:' || url.protocol === 'https:');
    if (!ok || url.username || url.password)
      throw new Error(
        `the registry's token endpoint ${url.origin} is not on ${host}: no token is asked for there`,
      );
    return url;
  };

  /**
   * A token for `scope`, the scope the client asks for (the challenge's only when the client
   * names none): a client with credentials asks for pull,push on its first request, as `docker
   * push` does, where a registry's challenge to a read names pull alone.
   */
  const fetchToken = async (challenge, scope) => {
    const wanted = scope || challenge.scope;
    const url = realmOf(challenge.realm);
    if (challenge.service) url.searchParams.set('service', challenge.service);
    if (wanted) url.searchParams.set('scope', wanted);
    const headers = {};
    if (credentials)
      headers.Authorization = `Basic ${Buffer.from(`${credentials.username}:${credentials.password}`).toString('base64')}`;
    const response = await fetchImpl(url, { headers, redirect: 'error' });
    if (response.status !== 200) {
      await discard(response);
      throw new Error(
        `${host} refused a token for ${wanted} (HTTP ${response.status})${credentials ? '' : ': an anonymous pull needs the package to be public'}`,
      );
    }
    let body;
    try {
      body = JSON.parse((await readBody(response, MAX_TOKEN, 'the token')).toString('utf8'));
    } catch (error) {
      throw new Error(`${host}'s token endpoint did not answer with a token: ${error.message}`, {
        cause: error,
      });
    }
    const value = body?.token ?? body?.access_token;
    if (typeof value !== 'string' || !value)
      throw new Error(`${host}'s token endpoint did not answer with a token`);
    return value;
  };

  /**
   * One request to the registry: `target` a path under it or a URL on its host. A 401 with a
   * Bearer challenge is answered once with a token for `scope`. A read follows a redirect (GHCR
   * sends a blob to its CDN); a write follows none, so its body reaches the registry's host only.
   */
  const request = async (target, init = {}, scope = undefined) => {
    const url = new URL(target, origin);
    if (url.origin !== origin) throw new Error(`${url.origin} is not the registry ${host}`);
    const method = (init.method ?? 'GET').toUpperCase();
    const write = method !== 'GET' && method !== 'HEAD';
    for (let attempt = 0; ; attempt++) {
      const headers = { ...init.headers };
      if (token) headers.Authorization = `Bearer ${token}`;
      const response = await fetchImpl(url, {
        ...init,
        headers,
        redirect: write ? 'manual' : 'follow',
      });
      if (
        write &&
        (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400))
      ) {
        await discard(response);
        throw new Error(
          `${host} answered the ${method} of ${url.pathname} with a redirect (HTTP ${response.status}): a write follows none, and nothing is sent elsewhere`,
        );
      }
      if (response.status !== 401 || attempt > 0) return response;
      const challenge = parseChallenge(response.headers.get('www-authenticate'));
      await discard(response);
      if (!challenge) return response;
      token = await fetchToken(challenge, scope);
    }
  };

  const push = (name) => `repository:${name}:pull,push`;
  // A client with credentials (publish and withdraw) asks for pull,push on every request, a read
  // included: one token covers the job, and the first publish's reads of a package that does not
  // exist yet are made with the scope that creates it. An anonymous client asks for pull alone.
  const read = (name) => (credentials ? push(name) : `repository:${name}:pull`);
  const failed = async (response, what) => {
    await discard(response);
    return new Error(
      `${what}: HTTP ${response.status}${response.status === 401 || response.status === 403 ? (credentials ? ' (the token may not write this package)' : ' (is the package public?)') : ''}`,
    );
  };

  /** Whether the manifest `reference` (a tag or a digest) of `name` exists. */
  async function hasManifest(name, reference) {
    const response = await request(
      `/v2/${name}/manifests/${reference}`,
      { method: 'HEAD', headers: { Accept: MANIFEST_TYPE } },
      read(name),
    );
    await discard(response);
    if (response.status === 404) return false;
    if (response.status === 200) return true;
    throw await failed(response, `${host}/${name}:${reference}`);
  }

  /**
   * The manifest `reference` (a tag or a digest) of `name`: `{ bytes, digest, manifest }`, the
   * digest the sha256 of the bytes. A digest reference must be that sha256. A missing manifest
   * throws with `code` 'NOT_FOUND'.
   */
  async function getManifest(name, reference) {
    const response = await request(
      `/v2/${name}/manifests/${reference}`,
      { headers: { Accept: MANIFEST_TYPE } },
      read(name),
    );
    if (response.status === 404) {
      await discard(response);
      throw Object.assign(new Error(`${host}/${name} has no manifest ${reference}`), {
        code: 'NOT_FOUND',
      });
    }
    if (response.status !== 200) throw await failed(response, `${host}/${name}:${reference}`);
    const bytes = await readBody(response, MAX_MANIFEST, `the manifest ${reference} of ${name}`);
    const digest = digestOf(bytes);
    if (DIGEST.test(reference) && digest !== reference)
      throw new Error(
        `the manifest ${reference} of ${name} hashes to ${digest}: its bytes are not the digest's`,
      );
    let manifest;
    try {
      manifest = JSON.parse(bytes.toString('utf8'));
    } catch (error) {
      throw new Error(`the manifest ${reference} of ${name} is not JSON: ${error.message}`, {
        cause: error,
      });
    }
    if (!isObject(manifest) || manifest.schemaVersion !== 2 || manifest.mediaType !== MANIFEST_TYPE)
      throw new Error(`the manifest ${reference} of ${name} is not an OCI image manifest`);
    return { bytes, digest, manifest };
  }

  /** The blob `digest` of `name`, at most `max` bytes (and `size`, when given), verified. */
  async function getBlob(name, digest, { size, max }) {
    if (!DIGEST.test(digest)) throw new Error(`"${digest}" is not a sha256 digest`);
    const limit = Number.isInteger(size) && size >= 0 ? Math.min(size, max) : max;
    if (Number.isInteger(size) && size > max)
      throw new Error(`the blob ${digest} of ${name} is ${size} bytes, over the ${max} it may be`);
    const response = await request(`/v2/${name}/blobs/${digest}`, {}, read(name));
    if (response.status !== 200) throw await failed(response, `the blob ${digest} of ${name}`);
    const bytes = await readBody(response, limit, `the blob ${digest} of ${name}`);
    if (Number.isInteger(size) && bytes.length !== size)
      throw new Error(`the blob ${digest} of ${name} is ${bytes.length} bytes, not its ${size}`);
    if (digestOf(bytes) !== digest)
      throw new Error(`the blob ${digest} of ${name} does not hash to its digest`);
    return bytes;
  }

  /** Whether the blob `digest` of `name` exists. */
  async function headBlob(name, digest) {
    const response = await request(`/v2/${name}/blobs/${digest}`, { method: 'HEAD' }, push(name));
    await discard(response);
    if (response.status === 404) return false;
    if (response.status === 200) return true;
    throw await failed(response, `the blob ${digest} of ${name}`);
  }

  /** Pushes `data` as a blob of `name`, unless the registry has it; returns its digest. */
  async function pushBlob(name, data) {
    const digest = digestOf(data);
    if (await headBlob(name, digest)) return digest;
    const started = await request(`/v2/${name}/blobs/uploads/`, { method: 'POST' }, push(name));
    await discard(started);
    if (started.status !== 202) throw await failed(started, `an upload to ${name}`);
    const location = started.headers.get('location');
    if (!location) throw new Error(`${host} started an upload to ${name} without a Location`);
    const url = new URL(location, origin);
    if (url.origin !== origin)
      throw new Error(
        `${host} answered an upload Location on ${url.origin}: nothing is sent there`,
      );
    url.searchParams.set('digest', digest);
    const response = await request(
      url,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': `${data.length}` },
        body: data,
      },
      push(name),
    );
    await discard(response);
    if (response.status !== 201) throw await failed(response, `the upload of ${digest} to ${name}`);
    const answered = response.headers.get('docker-content-digest');
    if (answered && answered !== digest)
      throw new Error(`${host} stored the blob ${digest} of ${name} as ${answered}`);
    return digest;
  }

  /**
   * Pushes a manifest's bytes as `tag` of `name`; returns their digest, as the registry answered
   * it, or, when it answered none (the distribution spec requires only a Location), as a HEAD of
   * that digest confirms it.
   */
  async function putManifest(name, tag, bytes) {
    const digest = digestOf(bytes);
    const response = await request(
      `/v2/${name}/manifests/${tag}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': MANIFEST_TYPE, 'Content-Length': `${bytes.length}` },
        body: bytes,
      },
      push(name),
    );
    await discard(response);
    if (response.status !== 201) throw await failed(response, `the manifest ${tag} of ${name}`);
    const answered = response.headers.get('docker-content-digest');
    if (answered === null) {
      if (await hasManifest(name, digest)) return digest;
      throw new Error(
        `${host} answered no digest for the manifest ${tag} of ${name}, and has no manifest ${digest}`,
      );
    }
    if (answered !== digest)
      throw new Error(
        `${host} answered the digest ${answered} for the manifest ${tag} of ${name}, not ${digest}, the sha256 of the bytes sent`,
      );
    return digest;
  }

  /**
   * Every tag of `name`, following the registry's next links; an unknown repository (a 404 on the
   * first page) has none. A later page that fails, or more pages than MAX_TAG_PAGES, throws: no
   * pin is chosen from a part of the list.
   */
  async function listTags(name) {
    const tags = [];
    let next = `/v2/${name}/tags/list`;
    for (let page = 0; next; page++) {
      if (page === MAX_TAG_PAGES)
        throw new Error(
          `${host} lists over ${MAX_TAG_PAGES} pages of tags for ${name}: no pin is chosen from a part of the list`,
        );
      const response = await request(next, {}, read(name));
      if (response.status === 404 && page === 0) {
        await discard(response);
        return tags;
      }
      if (response.status !== 200)
        throw await failed(
          response,
          page === 0 ? `the tags of ${name}` : `page ${page + 1} of the tags of ${name}`,
        );
      const body = JSON.parse(
        (await readBody(response, MAX_MANIFEST * 16, `the tags of ${name}`)).toString('utf8'),
      );
      if (body?.tags !== null && body?.tags !== undefined && !Array.isArray(body.tags))
        throw new Error(`${host} answered no tag list for ${name}`);
      tags.push(...(body.tags ?? []).filter((tag) => typeof tag === 'string'));
      const link = /<([^>]+)>\s*;\s*rel="?next"?/.exec(response.headers.get('link') ?? '');
      next = link?.[1];
    }
    return tags;
  }

  return { host, hasManifest, getManifest, getBlob, headBlob, pushBlob, putManifest, listTags };
}

/** The config blob of a pack and of its marker. */
export const configBytes = (lang) => Buffer.from(JSON.stringify({ format: 1, lang }));

/** The manifest of a pack or a marker, as bytes: the config and one layer. */
function manifestBytes({ config, layer, layerType, title, source, version }) {
  const manifest = {
    schemaVersion: 2,
    mediaType: MANIFEST_TYPE,
    config: { mediaType: CONFIG_TYPE, digest: digestOf(config), size: config.length },
    layers: [
      {
        mediaType: layerType,
        digest: digestOf(layer),
        size: layer.length,
        annotations: { 'org.opencontainers.image.title': title },
      },
    ],
    annotations: {
      ...(source ? { 'org.opencontainers.image.source': source } : {}),
      'org.opencontainers.image.version': version,
    },
  };
  return Buffer.from(JSON.stringify(manifest));
}

/** Pushes a config and one layer under `tag`; returns the manifest's digest. */
async function pushArtifact({ client, repository, tag, lang, layer, layerType, title, source }) {
  const config = configBytes(lang);
  await client.pushBlob(repository, config);
  await client.pushBlob(repository, layer);
  return client.putManifest(
    repository,
    tag,
    manifestBytes({ config, layer, layerType, title, source, version: tag }),
  );
}

/**
 * The one layer of a pack's or marker's manifest, after its config was read and found to be
 * `{ format: 1, lang }`: `{ digest, size }`. Throws on another shape.
 */
async function layerOf({ client, repository, manifest, lang, layerType, what }) {
  const layers = Array.isArray(manifest.layers) ? manifest.layers : [];
  if (layers.length !== 1)
    throw new Error(`${what} has ${layers.length} layers; a pack has exactly one`);
  const [layer] = layers;
  if (layer?.mediaType !== layerType)
    throw new Error(`${what}'s layer is ${layer?.mediaType ?? 'untyped'}, not ${layerType}`);
  if (manifest.config?.mediaType !== CONFIG_TYPE)
    throw new Error(
      `${what}'s config is ${manifest.config?.mediaType ?? 'untyped'}, not ${CONFIG_TYPE}`,
    );
  if (!DIGEST.test(layer.digest ?? '') || !Number.isInteger(layer.size))
    throw new Error(`${what}'s layer has no digest and size`);
  const config = await client.getBlob(repository, manifest.config.digest, {
    size: manifest.config.size,
    max: MAX_CONFIG,
  });
  let parsed;
  try {
    parsed = JSON.parse(config.toString('utf8'));
  } catch (error) {
    throw new Error(`${what}'s config is not JSON: ${error.message}`, { cause: error });
  }
  if (
    !isObject(parsed) ||
    Object.keys(parsed).sort().join() !== 'format,lang' ||
    parsed.format !== 1 ||
    parsed.lang !== lang
  )
    throw new Error(`${what}'s config is not {"format":1,"lang":"${lang}"}`);
  return { digest: layer.digest, size: layer.size };
}

/**
 * Pushes a pack's canonical archive as `<registry>/<repository>:<tag>`; returns the manifest's
 * digest. `source` is the home repository's URL, the package's link to it.
 */
export async function pushPack({
  registry,
  repository,
  tag,
  archive,
  lang,
  credentials,
  source,
  fetch,
}) {
  if (!TAG.test(tag ?? '')) throw new Error(`"${tag}" is not a pack tag, digits only`);
  if (!LANG.test(lang ?? '')) throw new Error(`"${lang}" is not a language code`);
  const client = registryClient({ registry, credentials, fetch });
  return pushArtifact({
    client,
    repository,
    tag,
    lang,
    layer: archive,
    layerType: PACK_TYPE,
    title: `niord-lang-${lang}.zip`,
    source,
  });
}

/**
 * Pulls a pack by its digest reference, anonymously: `{ archive, digest, layer }`, the archive
 * the layer's bytes, verified against the manifest the digest names. `cache` may give
 * `{ get(layerDigest, size), put(layerDigest, bytes) }`; a cached archive is verified again.
 */
export async function pullPack({ reference, fetch, cache }) {
  const parsed = parseReference(reference);
  if (!parsed.digest)
    throw new Error(`${reference} names no digest: a pack is pulled by its digest`);
  const client = registryClient({ registry: parsed.registry, fetch });
  const { manifest } = await client.getManifest(parsed.repository, parsed.digest);
  const what = `the pack ${reference}`;
  const layer = await layerOf({
    client,
    repository: parsed.repository,
    manifest,
    lang: parsed.lang,
    layerType: PACK_TYPE,
    what,
  });
  if (layer.size > MAX_ARCHIVE)
    throw new Error(`${what}'s archive is ${layer.size} bytes, over the ${MAX_ARCHIVE} it may be`);
  const cached = cache?.get(layer.digest, layer.size);
  if (cached && cached.length === layer.size && digestOf(cached) === layer.digest)
    return { archive: cached, digest: parsed.digest, layer: layer.digest, cached: true };
  const archive = await client.getBlob(parsed.repository, layer.digest, {
    size: layer.size,
    max: MAX_ARCHIVE,
  });
  cache?.put(layer.digest, archive);
  return { archive, digest: parsed.digest, layer: layer.digest, cached: false };
}

/**
 * A withdrawn marker's entries from its bytes, held to its format: exactly `format`, `lang` and
 * `withdrawn`, each entry exactly `digest`, `tag`, `reason` and `date`, one entry per digest.
 */
export function parseWithdrawn(bytes, lang) {
  const fail = (message) => {
    throw new Error(`the withdrawn marker of niord-lang-${lang} ${message}`);
  };
  if (bytes.length > MAX_WITHDRAWN) fail(`is over ${MAX_WITHDRAWN} bytes`);
  let marker;
  try {
    marker = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`is not JSON: ${error.message}`);
  }
  if (!isObject(marker)) fail('is not an object');
  const fields = Object.keys(marker).sort().join();
  if (fields !== 'format,lang,withdrawn')
    fail(`has the fields ${fields}, not format, lang, withdrawn`);
  if (marker.format !== 1) fail('is not of format 1');
  if (marker.lang !== lang) fail(`is for "${marker.lang}"`);
  if (!Array.isArray(marker.withdrawn)) fail('has no withdrawn list');
  const seen = new Set();
  marker.withdrawn.forEach((entry, i) => {
    const at = `entry ${i + 1}`;
    if (!isObject(entry) || Object.keys(entry).sort().join() !== 'date,digest,reason,tag')
      fail(`${at} is not { digest, tag, reason, date }`);
    if (!DIGEST.test(entry.digest)) fail(`${at} has no sha256 digest`);
    if (typeof entry.tag !== 'string' || !TAG.test(entry.tag)) fail(`${at} has no numeric tag`);
    const problem = reasonProblem(entry.reason);
    if (problem) fail(`${at}: ${problem}`);
    if (typeof entry.date !== 'string' || !DATE.test(entry.date))
      fail(`${at} has no yyyy-mm-dd date`);
    if (seen.has(entry.digest)) fail(`lists ${entry.digest} twice`);
    seen.add(entry.digest);
  });
  return marker.withdrawn;
}

/** What is wrong with a withdrawal's reason, or undefined. */
export function reasonProblem(reason) {
  if (typeof reason !== 'string' || !reason.trim()) return 'the reason is empty';
  if (reason.length > MAX_REASON) return `the reason is over ${MAX_REASON} characters`;
  if (hasControl(reason))
    return 'the reason is not one line of text (a control character or a line break)';
  return undefined;
}

/** A marker's bytes. */
export const withdrawnBytes = (lang, withdrawn) =>
  Buffer.from(`${JSON.stringify({ format: 1, lang, withdrawn }, null, 2)}\n`);

/**
 * The withdrawn marker of `<registry>/<repository>`, anonymously unless `credentials` are given:
 * `{ withdrawn, digest }`. A missing marker throws a NoMarkerError.
 */
export async function readWithdrawn({ registry, repository, lang, credentials, fetch }) {
  const client = registryClient({ registry, credentials, fetch });
  let found;
  try {
    found = await client.getManifest(repository, WITHDRAWN_TAG);
  } catch (error) {
    if (error.code === 'NOT_FOUND') throw new NoMarkerError(`${client.host}/${repository}`);
    throw error;
  }
  const what = `the withdrawn marker of ${client.host}/${repository}`;
  const layer = await layerOf({
    client,
    repository,
    manifest: found.manifest,
    lang,
    layerType: WITHDRAWN_TYPE,
    what,
  });
  const bytes = await client.getBlob(repository, layer.digest, {
    size: layer.size,
    max: MAX_WITHDRAWN,
  });
  return { withdrawn: parseWithdrawn(bytes, lang), digest: found.digest };
}

/** Pushes the whole marker as `:withdrawn`; returns its manifest's digest. */
export async function pushWithdrawn({
  registry,
  repository,
  lang,
  withdrawn,
  credentials,
  source,
  fetch,
}) {
  const layer = withdrawnBytes(lang, withdrawn);
  parseWithdrawn(layer, lang);
  const client = registryClient({ registry, credentials, fetch });
  return pushArtifact({
    client,
    repository,
    tag: WITHDRAWN_TAG,
    lang,
    layer,
    layerType: WITHDRAWN_TYPE,
    title: 'withdrawn.json',
    source,
  });
}

/** The manifest digest of `tag`, or undefined when the registry has no such tag. */
export async function tagDigest({ registry, repository, tag, credentials, fetch }) {
  const client = registryClient({ registry, credentials, fetch });
  try {
    return (await client.getManifest(repository, tag)).digest;
  } catch (error) {
    if (error.code === 'NOT_FOUND') return undefined;
    throw error;
  }
}

/** Every numeric tag of a pack, highest first. */
export async function packTags({ registry, repository, credentials, fetch }) {
  const client = registryClient({ registry, credentials, fetch });
  return (await client.listTags(repository))
    .filter((tag) => TAG.test(tag))
    .sort((a, b) => (BigInt(b) > BigInt(a) ? 1 : BigInt(b) < BigInt(a) ? -1 : 0));
}

const today = (now) => now.toISOString().slice(0, 10);

/**
 * What the commands read from the environment, checked: the language, the namespace, the
 * registry, the credentials and the repository's URL.
 */
function packEnvironment(env) {
  const lang = env.PACK_LANG ?? '';
  if (!LANG.test(lang))
    throw new Error(`PACK_LANG "${lang}" is not a language code, two or three a-z`);
  const owner = (env.GITHUB_REPOSITORY_OWNER ?? '').toLowerCase();
  if (!OWNER.test(owner))
    throw new Error(
      `GITHUB_REPOSITORY_OWNER "${env.GITHUB_REPOSITORY_OWNER ?? ''}" is not a GitHub owner`,
    );
  if (!REPOSITORY_NAME.test(env.GITHUB_REPOSITORY ?? ''))
    throw new Error(
      `GITHUB_REPOSITORY "${env.GITHUB_REPOSITORY ?? ''}" is not <owner>/<repository>`,
    );
  if (!env.PACK_TOKEN)
    throw new Error('PACK_TOKEN is empty: the package token is bound on this step');
  if (!env.GITHUB_ACTOR) throw new Error('GITHUB_ACTOR is empty');
  const registry = env.PACK_REGISTRY ? registryHost(env.PACK_REGISTRY) : GHCR;
  if (env.PACK_REGISTRY && registry === GHCR)
    throw new Error(
      'PACK_REGISTRY names a loopback registry for a test; leave it unset for ghcr.io',
    );
  return {
    lang,
    owner,
    registry,
    repository: `${owner}/niord-lang-${lang}`,
    credentials: { username: env.GITHUB_ACTOR, password: env.PACK_TOKEN },
    source: `https://github.com/${env.GITHUB_REPOSITORY}`,
  };
}

/** Appends lines to the run summary when $GITHUB_STEP_SUMMARY names one. */
function summarise(env, lines) {
  if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY, `${lines.join('\n')}\n`);
}

/** The options of a command: `--name value` pairs among `allowed`, each once. */
function options(args, allowed) {
  const values = {};
  for (let i = 0; i < args.length; i += 2) {
    const name = args[i]?.replace(/^--/, '');
    if (!args[i]?.startsWith('--') || !allowed.includes(name) || Object.hasOwn(values, name))
      throw new UsageError(`unknown argument "${args[i]}"`);
    if (args[i + 1] === undefined || args[i + 1].startsWith('--'))
      throw new UsageError(`${args[i]} needs a value`);
    values[name] = args[i + 1];
  }
  for (const name of allowed)
    if (!Object.hasOwn(values, name)) throw new UsageError(`--${name} is required`);
  return values;
}

/**
 * `publish --archive <file> --sha256 <hex>`: the archive hashed again against the sha256 the
 * validate job gave, the tag refused when it exists, the empty withdrawn marker pushed on the
 * first publish, before the pack, then the pack pushed, and the reference, with its tag and
 * digest, written into the run summary as information: nothing is left to record by hand. Returns
 * the lines printed.
 */
export async function publish(args, { env = process.env, fetch } = {}) {
  const { archive: file, sha256 } = options(args, ['archive', 'sha256']);
  if (!/^[0-9a-f]{64}$/.test(sha256)) throw new UsageError('--sha256 is 64 lower-case hex digits');
  const { lang, owner, registry, repository, credentials, source } = packEnvironment(env);
  const tag = env.GITHUB_RUN_NUMBER ?? '';
  if (!TAG.test(tag)) throw new Error(`GITHUB_RUN_NUMBER "${tag}" is not a run number`);
  const archive = fs.readFileSync(file);
  if (digestOf(archive) !== `sha256:${sha256}`)
    throw new Error(`${path.basename(file)} does not hash to ${sha256}: nothing was pushed`);

  const client = registryClient({ registry, credentials, fetch });
  if (await client.hasManifest(repository, tag))
    throw new Error(
      `${registry}/${repository}:${tag} exists already: a tag is never pushed twice, and a tag that exists is a completed publish; to publish again, dispatch publish, which gets a new run number`,
    );
  // Read before anything is pushed: a malformed marker stops the publish with nothing changed.
  let marker = true;
  try {
    await readWithdrawn({ registry, repository, lang, credentials, fetch });
  } catch (error) {
    if (error.code !== 'NO_MARKER') throw error;
    marker = false;
  }
  const lines = [];
  // The marker goes first: a publish broken after it leaves a marker and no tag, which a re-run of
  // the job (the same run number) finishes; the other order would leave a tag no re-run reaches.
  if (!marker) {
    await pushWithdrawn({ registry, repository, lang, withdrawn: [], credentials, source, fetch });
    lines.push(
      `the first publish of niord-lang-${lang}: the empty withdrawn marker pushed as ${registry}/${repository}:${WITHDRAWN_TAG}`,
    );
  }
  const digest = await pushPack({
    registry,
    repository,
    tag,
    archive,
    lang,
    credentials,
    source,
    fetch,
  });
  const reference = formatReference({ registry, owner, lang, tag, digest });
  lines.unshift(`published ${reference}`);
  summarise(env, [
    `### niord-lang-${lang}:${tag} published`,
    '',
    `\`${reference}\``,
    ...(marker
      ? []
      : ['', `The first publish: the empty withdrawn marker is \`:${WITHDRAWN_TAG}\`.`]),
  ]);
  return lines;
}

/**
 * `withdraw`: the marker read (missing: publish first), the tag's digest resolved and added with
 * the reason and today's date (UTC) unless it is listed already, and the whole marker pushed as
 * `:withdrawn`. Returns the lines printed.
 */
export async function withdraw(args, { env = process.env, fetch, now = new Date() } = {}) {
  if (args.length) throw new UsageError(`withdraw takes no argument: "${args[0]}"`);
  const { lang, owner, registry, repository, credentials, source } = packEnvironment(env);
  const tag = env.PACK_TAG ?? '';
  if (!TAG.test(tag)) throw new Error(`PACK_TAG "${tag}" is not a pack tag, digits only`);
  const problem = reasonProblem(env.PACK_REASON);
  if (problem) throw new Error(`PACK_REASON: ${problem}`);
  const reason = env.PACK_REASON.trim();

  let marker;
  try {
    marker = await readWithdrawn({ registry, repository, lang, credentials, fetch });
  } catch (error) {
    if (error.code === 'NO_MARKER')
      throw new Error(
        `${error.message}: publish the pack first; its first publish pushes the marker`,
        { cause: error },
      );
    throw error;
  }
  const digest = await tagDigest({ registry, repository, tag, credentials, fetch });
  if (!digest) throw new Error(`${registry}/${repository} has no tag ${tag}`);
  const reference = formatReference({ registry, owner, lang, tag, digest });
  if (marker.withdrawn.some((entry) => entry.digest === digest)) {
    summarise(env, [`### niord-lang-${lang}:${tag} was withdrawn already`, '', `\`${reference}\``]);
    return [`notice: ${reference} is listed in the withdrawn marker already; nothing was pushed`];
  }
  const date = today(now);
  const withdrawn = [...marker.withdrawn, { digest, tag, reason, date }];
  await pushWithdrawn({ registry, repository, lang, withdrawn, credentials, source, fetch });
  // The marker keeps the reason as given, and every consumer's set-up prints it from there; the
  // summary states the withdrawal as information and leaves nothing to record by hand.
  summarise(env, [
    `### niord-lang-${lang}:${tag} withdrawn`,
    '',
    `\`${reference}\`: ${reason}`,
    '',
    `The withdrawn marker \`:${WITHDRAWN_TAG}\` lists it, with the reason and the date ${date}.`,
  ]);
  return [
    `withdrew ${reference}: ${reason}`,
    `the marker ${registry}/${repository}:${WITHDRAWN_TAG} lists ${withdrawn.length} digest(s)`,
  ];
}

const USAGE = 'node oci.mjs publish --archive <file> --sha256 <hex> | node oci.mjs withdraw';
const COMMANDS = { publish, withdraw };

/** The command line; returns the exit status. */
export async function main(
  args,
  { env = process.env, fetch, now, log = console.log, error = console.error } = {},
) {
  const [command, ...rest] = args;
  const prefix = env.GITHUB_ACTIONS === 'true' ? '::error::' : '';
  try {
    // An own property only: an inherited name (toString, constructor) is no command.
    if (!Object.hasOwn(COMMANDS, command ?? ''))
      throw new UsageError(`unknown command "${command ?? ''}"`);
    const run = COMMANDS[command];
    for (const line of await run(rest, { env, fetch, now })) log(`oci ${command}: ${line}`);
    return 0;
  } catch (failure) {
    if (failure instanceof UsageError) {
      error(`${prefix}oci: ${failure.message} (${USAGE})`);
      return 2;
    }
    error(`${prefix}oci ${command}: ${failure.message}`);
    return 1;
  }
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) process.exitCode = await main(process.argv.slice(2));
