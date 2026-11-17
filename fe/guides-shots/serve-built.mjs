// Serves the rig's built administration app and public site on fixed ports, and passes /rest to the
// rig's backend unchanged, as the dev server's proxy (fe/proxy.conf.mjs) does. Loopback only.
// The administration app also passes /img/ and /css/templates/ to the backend, as a deployment's
// web server does (be/k8s/helm/niord/templates/nginx/configmap.yaml): a file type's icon, which
// /rest/repo/thumb/... and a batch run's thumbnail redirect to, and the stylesheet a mail or a
// report links by path. A file the build itself holds at such a path wins. The public site's web
// server passes neither (fe/ui-apps/info/nginx/default.conf.template), so this one does not either.
// start-rig.sh builds both applications once and starts this file: the pictures are taken of fixed
// files, and no dev server runs beside them (on 2026-11-11 a dev server died four times while a
// run loaded pages from it).
//
//   node serve-built.mjs <admin folder> <admin port> <public site folder> <public site port> <backend port>
import { createReadStream, statSync } from 'node:fs';
import { createServer, request } from 'node:http';
import { extname, join, resolve, sep } from 'node:path';

const [adminDir, adminPort, infoDir, infoPort, backendPort] = process.argv.slice(2);
if (!backendPort) {
  console.error(
    'usage: node serve-built.mjs <admin folder> <admin port> <public site folder> <public site port> <backend port>',
  );
  process.exit(2);
}

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.map': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml',
  '.pdf': 'application/pdf',
  '.webmanifest': 'application/manifest+json',
};

function toBackend(req, res) {
  const upstream = request(
    {
      host: '127.0.0.1',
      port: Number(backendPort),
      method: req.method,
      path: req.url,
      headers: req.headers,
    },
    (answer) => {
      res.writeHead(answer.statusCode ?? 502, answer.headers);
      answer.pipe(res);
    },
  );
  upstream.on('error', (error) => {
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' });
    res.end(`backend: ${error.message}`);
  });
  req.pipe(upstream);
}

/** The file a path names inside the root, or null (outside it, a folder, missing). */
function fileOf(root, pathname) {
  let decoded;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    return null;
  }
  const path = resolve(root, `.${decoded}`);
  if (path !== root && !path.startsWith(root + sep)) return null;
  try {
    return statSync(path).isFile() ? path : null;
  } catch {
    return null;
  }
}

/** The paths under /rest go to the backend; those under `backendFiles` only when the build has no such file. */
function site(dir, backendFiles) {
  const root = resolve(dir);
  const index = join(root, 'index.html');
  return (req, res) => {
    const { pathname } = new URL(req.url ?? '/', 'http://localhost');
    if (pathname === '/rest' || pathname.startsWith('/rest/')) return toBackend(req, res);
    let path = fileOf(root, pathname);
    if (!path && backendFiles.some((prefix) => pathname.startsWith(prefix))) {
      return toBackend(req, res);
    }
    // A deep link (/editor/edit/<id>) belongs to the router: the page itself, as the dev server
    // answers it. A missing script or image stays a 404.
    if (!path && (pathname === '/' || (req.headers.accept ?? '').includes('text/html'))) {
      path = index;
    }
    if (!path) {
      res.writeHead(404);
      res.end();
      return;
    }
    res.writeHead(200, {
      'content-type': TYPES[extname(path).toLowerCase()] ?? 'application/octet-stream',
      'cache-control': 'no-store',
    });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    createReadStream(path).pipe(res);
  };
}

for (const [dir, port, backendFiles] of [
  [adminDir, Number(adminPort), ['/img/', '/css/templates/']],
  [infoDir, Number(infoPort), []],
]) {
  statSync(join(resolve(dir), 'index.html')); // throws when the build is not there
  // Both loopback addresses: on Windows a connection to a closed ::1 waits two seconds before it
  // tries 127.0.0.1.
  for (const host of ['127.0.0.1', '::1']) {
    const server = createServer(site(dir, backendFiles));
    server.on('error', (error) => {
      if (host === '::1' && error.code === 'EADDRNOTAVAIL') return; // no IPv6 loopback here
      console.error(`${new Date().toISOString()} ${host}:${port}: ${error.message}`);
      process.exit(1);
    });
    server.listen(port, host, () =>
      console.log(`${new Date().toISOString()} ${dir} on http://localhost:${port}/ (${host})`),
    );
  }
}
