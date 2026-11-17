/**
 * An in-process registry for the OCI client's tests (lib/oci.mjs): node:http on 127.0.0.1, the
 * subset of the OCI distribution API a pack's push and pull use — GET /v2/, the token endpoint
 * (an anonymous token that pulls, a Basic-authenticated one that also pushes), blob HEAD and GET,
 * the upload's POST and monolithic PUT, manifest HEAD, GET and PUT by tag or digest with
 * Docker-Content-Digest, and tags/list.
 *
 *   const registry = await fakeRegistry({ auth: true });
 *   registry.host            // '127.0.0.1:<port>', what the client is given
 *   registry.requests        // every request: { method, path, search, authorization, scope }
 *   registry.knobs.<name>    // a refusal to provoke, set by the test
 *   await registry.close();
 *
 * `fakeRegistry({ auth, user, password, anonymousPull })`: `auth` answers every /v2/ request
 * without a token with a Bearer challenge; the token endpoint gives a push token only for the
 * Basic credentials `user`:`password` and a `pull,push` scope, a pull token otherwise, and none
 * to an anonymous request when `anonymousPull` is false (a private package).
 *
 * The knobs, each off by default:
 *   corruptBlob, corruptManifest  the bytes served differ from what was stored
 *   wrongDigest                   a manifest PUT answers another Docker-Content-Digest
 *   noManifestDigest              a manifest PUT answers 201 without Docker-Content-Digest
 *   failManifestPut               a number n: the n-th manifest PUT from when it is set answers
 *                                 500, once, and stores nothing
 *   foreignLocation               an upload's Location on another host
 *   relativeLocation              an upload's Location relative, with no query; by default it is
 *                                 absolute on the registry's own host with a `?_state=` query, as
 *                                 registry:2 answers, and the PUT must carry that query back
 *   redirectBlobs                 a blob GET answers 307 to a second server, which records
 *                                 whether an Authorization header reached it, as
 *                                 `registry.redirected`
 *   redirectUploads               an upload's PUT answers 307 to that second server
 *   oversize                      a manifest GET answers that many bytes more
 *   chunked                       a blob or manifest GET's body is written in two writes, with no
 *                                 Content-Length
 *   pageSize                      tags/list answers that many tags a page (as `?n=` does), in
 *                                 lexical order from `&last=`, with a relative
 *                                 `Link: </v2/<name>/tags/list?n=..&last=..>; rel="next"`
 *   selfLink                      every tags/list page links to itself as the next
 *   realm                         the challenge names this token endpoint instead of its own
 *   noPush                        the token endpoint gives a pull token whatever the credentials
 *                                 and scope
 *   denyUnknown                   a request with a pull token for a repository that has no
 *                                 manifest yet answers 403 DENIED, as a registry may for a package
 *                                 a pull-only token cannot create
 *
 * Content a test needs in a shape the client would never push — two layers, another media type,
 * a malformed marker — is put in with `putManifest` and `putBlob`; a missing marker is simply one
 * never pushed.
 *
 * Under test-fixtures/, which the source zip leaves out.
 */
import crypto from 'node:crypto';
import http from 'node:http';

const digestOf = (bytes) => `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;

/** Starts a server on 127.0.0.1 and an ephemeral port; resolves to it with its host. */
function listen(handler) {
  const server = http.createServer(handler);
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () =>
      resolve({ server, host: `127.0.0.1:${server.address().port}` }),
    );
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

export async function fakeRegistry({
  auth = false,
  user = 'example',
  password = 'secret-token',
  anonymousPull = true,
} = {}) {
  const blobs = new Map();
  const manifests = new Map(); // `${name}@${digest}` → bytes
  const tags = new Map(); // `${name}:${tag}` → digest
  const uploads = new Map();
  const requests = [];
  const knobs = {};
  const redirected = [];
  const TOKENS = { pull: 'token-pull', push: 'token-push' };

  // The second host a blob GET may be redirected to: it serves the blob and records the header.
  const mirror = await listen((request, response) => {
    redirected.push({
      method: request.method,
      path: request.url,
      authorization: request.headers.authorization,
    });
    const digest = decodeURIComponent(request.url.split('/').pop());
    const data = blobs.get(digest);
    response.writeHead(data ? 200 : 404, { 'Content-Length': data ? data.length : 0 });
    response.end(data);
  });

  let self;
  const challenge = (response, scope) => {
    response.writeHead(401, {
      'WWW-Authenticate': `Bearer realm="${knobs.realm ?? `http://${self}/token`}",service="fake",scope="${scope}"`,
      'Content-Type': 'application/json',
    });
    response.end('{"errors":[{"code":"UNAUTHORIZED"}]}');
  };

  /** A blob's or manifest's bytes with their headers: whole, or in two writes when chunked. */
  const serve = (request, response, headers, data) => {
    if (knobs.chunked && request.method === 'GET') {
      response.writeHead(200, headers);
      const half = Math.ceil(data.length / 2);
      response.write(data.subarray(0, half));
      return response.end(data.subarray(half));
    }
    response.writeHead(200, { ...headers, 'Content-Length': data.length });
    return response.end(request.method === 'HEAD' ? undefined : data);
  };

  const handler = async (request, response) => {
    const url = new URL(request.url, `http://${self}`);
    requests.push({
      method: request.method,
      path: url.pathname,
      search: url.search,
      authorization: request.headers.authorization,
      scope: url.searchParams.get('scope') ?? undefined,
    });
    const body = await bodyOf(request);

    if (url.pathname === '/token') {
      const basic = `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}`;
      const given = request.headers.authorization;
      if (given && given !== basic) {
        response.writeHead(401);
        return response.end();
      }
      if (!given && !anonymousPull) {
        response.writeHead(401);
        return response.end();
      }
      // As a token server grants what is asked for and allowed: push only for a pull,push scope.
      const pushScope = /:pull,push$/.test(url.searchParams.get('scope') ?? '');
      const token = given && pushScope && !knobs.noPush ? TOKENS.push : TOKENS.pull;
      response.writeHead(200, { 'Content-Type': 'application/json' });
      return response.end(JSON.stringify({ token }));
    }

    const match =
      /^\/v2\/(.+?)\/(blobs\/uploads\/?.*|blobs\/[^/]+|manifests\/[^/]+|tags\/list)$/.exec(
        url.pathname,
      );
    if (url.pathname === '/v2/' || url.pathname === '/v2') {
      response.writeHead(auth && !request.headers.authorization ? 401 : 200);
      return response.end();
    }
    if (!match) {
      response.writeHead(404);
      return response.end();
    }
    const [, name, rest] = match;
    const writing = !['GET', 'HEAD'].includes(request.method);
    if (auth) {
      const granted = request.headers.authorization;
      const ok = writing
        ? granted === `Bearer ${TOKENS.push}`
        : granted === `Bearer ${TOKENS.pull}` || granted === `Bearer ${TOKENS.push}`;
      if (!ok) return challenge(response, `repository:${name}:${writing ? 'pull,push' : 'pull'}`);
      const known = [...manifests.keys()].some((key) => key.startsWith(`${name}@`));
      if (knobs.denyUnknown && !known && granted !== `Bearer ${TOKENS.push}`) {
        response.writeHead(403, { 'Content-Type': 'application/json' });
        return response.end('{"errors":[{"code":"DENIED"}]}');
      }
    }

    if (rest === 'tags/list') {
      const list = [...tags.keys()]
        .filter((key) => key.startsWith(`${name}:`))
        .map((key) => key.slice(name.length + 1))
        .sort();
      if (!list.length) {
        response.writeHead(404);
        return response.end();
      }
      // As registry:2 pages: `n` tags in lexical order after `last`, and a next link while more remain.
      const size = Number(url.searchParams.get('n')) || knobs.pageSize || list.length;
      const last = url.searchParams.get('last');
      const after = last ? list.filter((tag) => tag > last) : list;
      const page = after.slice(0, size);
      const headers = { 'Content-Type': 'application/json' };
      if (knobs.selfLink) headers.Link = `</v2/${name}/tags/list${url.search}>; rel="next"`;
      else if (after.length > page.length)
        headers.Link = `</v2/${name}/tags/list?n=${size}&last=${encodeURIComponent(page.at(-1))}>; rel="next"`;
      response.writeHead(200, headers);
      return response.end(JSON.stringify({ name, tags: page }));
    }

    if (rest.startsWith('blobs/uploads')) {
      if (request.method === 'POST') {
        const id = crypto.randomUUID();
        const state = knobs.relativeLocation ? undefined : crypto.randomUUID();
        uploads.set(id, { name, state });
        const location = knobs.foreignLocation
          ? `http://uploads.example/v2/${name}/blobs/uploads/${id}`
          : knobs.relativeLocation
            ? `/v2/${name}/blobs/uploads/${id}`
            : `http://${self}/v2/${name}/blobs/uploads/${id}?_state=${state}`;
        response.writeHead(202, { Location: location });
        return response.end();
      }
      if (request.method === 'PUT') {
        if (knobs.redirectUploads) {
          response.writeHead(307, {
            Location: `http://${mirror.host}/v2/${name}/blobs/uploads/${crypto.randomUUID()}${url.search}`,
          });
          return response.end();
        }
        const upload = uploads.get(rest.slice('blobs/uploads/'.length));
        if (!upload || (upload.state && url.searchParams.get('_state') !== upload.state)) {
          response.writeHead(400);
          return response.end('{"errors":[{"code":"BLOB_UPLOAD_INVALID"}]}');
        }
        const digest = url.searchParams.get('digest');
        if (digestOf(body) !== digest) {
          response.writeHead(400);
          return response.end('{"errors":[{"code":"DIGEST_INVALID"}]}');
        }
        blobs.set(digest, body);
        response.writeHead(201, {
          'Docker-Content-Digest': digest,
          Location: `/v2/${name}/blobs/${digest}`,
        });
        return response.end();
      }
    }

    if (rest.startsWith('blobs/')) {
      const digest = decodeURIComponent(rest.slice('blobs/'.length));
      let data = blobs.get(digest);
      if (!data) {
        response.writeHead(404);
        return response.end();
      }
      if (request.method === 'GET' && knobs.redirectBlobs) {
        response.writeHead(307, {
          Location: `http://${mirror.host}/blobs/${encodeURIComponent(digest)}`,
        });
        return response.end();
      }
      if (knobs.corruptBlob) data = Buffer.concat([data.subarray(0, -1), Buffer.from('!')]);
      return serve(request, response, { 'Docker-Content-Digest': digest }, data);
    }

    if (rest.startsWith('manifests/')) {
      const reference = decodeURIComponent(rest.slice('manifests/'.length));
      if (request.method === 'PUT') {
        if (knobs.failManifestPut > 0 && --knobs.failManifestPut === 0) {
          delete knobs.failManifestPut;
          response.writeHead(500);
          return response.end('{"errors":[{"code":"UNKNOWN"}]}');
        }
        const digest = digestOf(body);
        manifests.set(`${name}@${digest}`, body);
        if (!reference.startsWith('sha256:')) tags.set(`${name}:${reference}`, digest);
        const headers = { Location: `/v2/${name}/manifests/${digest}` };
        if (!knobs.noManifestDigest)
          headers['Docker-Content-Digest'] = knobs.wrongDigest
            ? digestOf(Buffer.from('another'))
            : digest;
        response.writeHead(201, headers);
        return response.end();
      }
      const digest = reference.startsWith('sha256:') ? reference : tags.get(`${name}:${reference}`);
      let data = digest && manifests.get(`${name}@${digest}`);
      if (!data) {
        response.writeHead(404);
        return response.end();
      }
      if (knobs.corruptManifest)
        data = Buffer.from(
          data.toString('utf8').replace('"schemaVersion":2', '"schemaVersion": 2'),
        );
      if (knobs.oversize) data = Buffer.concat([data, Buffer.alloc(knobs.oversize, 0x20)]);
      return serve(
        request,
        response,
        {
          'Content-Type': 'application/vnd.oci.image.manifest.v1+json',
          'Docker-Content-Digest': digest,
        },
        data,
      );
    }
    response.writeHead(405);
    response.end();
  };

  const main = await listen((request, response) => {
    handler(request, response).catch((error) => {
      response.writeHead(500);
      response.end(String(error));
    });
  });
  self = main.host;

  return {
    host: main.host,
    mirror: mirror.host,
    requests,
    redirected,
    knobs,
    /** Stores a blob as it is; returns its digest. */
    putBlob(data) {
      const digest = digestOf(data);
      blobs.set(digest, data);
      return digest;
    },
    /** Stores a manifest's bytes under `tag` (when given); returns its digest. */
    putManifest(name, bytes, tag) {
      const digest = digestOf(bytes);
      manifests.set(`${name}@${digest}`, bytes);
      if (tag) tags.set(`${name}:${tag}`, digest);
      return digest;
    },
    /** The digest a tag names, or undefined. */
    tagged: (name, tag) => tags.get(`${name}:${tag}`),
    blob: (digest) => blobs.get(digest),
    close: async () => {
      await close(main.server);
      await close(mirror.server);
    },
  };
}
