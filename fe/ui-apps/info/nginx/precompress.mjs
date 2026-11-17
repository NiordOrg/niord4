#!/usr/bin/env node
/**
 * Pre-compresses a built niord-info artefact, once, in the image's build context.
 *
 *   node ui-apps/info/nginx/precompress.mjs <directory>
 *
 * country-kit/image-info.mjs runs it over the build context's copy of dist/niord-info/browser —
 * never over the dist itself, which the checks of the build have read and which stays as they
 * read it — before the image is built, so the image needs no Node stage of its own.
 *
 * The site's readers are on a metered satellite link, so the transfer is paid for at the highest
 * compression setting each format has — brotli quality 11 and gzip level 9 — and paid for once,
 * here, rather than on every request. nginx serves the result with brotli_static / gzip_static,
 * which is also why both twins are written: a client that speaks only gzip must not be handed an
 * uncompressed file, and a client that speaks neither must still find the original.
 *
 * A twin is written only when it is actually smaller than the original, so an already-compressed
 * file never grows the image, and the source file is always kept.
 *
 * No dependency: node:zlib produces exactly what the bundle report measures with.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

/**
 * Text formats the site serves. Fonts and images are compressed already and are skipped. A source
 * map is not among them: image-info.mjs refuses a build that has one before this runs.
 */
const COMPRESSIBLE = new Set([
  '.css',
  '.html',
  '.js',
  '.json',
  '.mjs',
  '.svg',
  '.txt',
  '.webmanifest',
  '.xml',
]);

/**
 * The floor the server configuration uses for its own dynamic compression (`gzip_min_length`).
 * One number for the whole system: below it a file is small enough that the saving is not worth
 * a second copy in the image, above it every byte is paid for once here. A twin that would not
 * be smaller is never written anyway, so this is about the image and not about correctness.
 */
const MINIMUM_BYTES = 256;

function* files(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) yield* files(full);
    else if (entry.isFile()) yield full;
  }
}

/** Writes the .br and .gz twins of every text file under `root`; answers the totals. */
export function precompress(root) {
  const totals = { files: 0, raw: 0, brotli: 0, gzip: 0 };
  for (const file of [...files(root)]) {
    if (!COMPRESSIBLE.has(path.extname(file).toLowerCase())) continue;
    const buffer = fs.readFileSync(file);
    if (buffer.length < MINIMUM_BYTES) continue;

    const brotli = zlib.brotliCompressSync(buffer, {
      params: {
        [zlib.constants.BROTLI_PARAM_QUALITY]: 11,
        [zlib.constants.BROTLI_PARAM_SIZE_HINT]: buffer.length,
      },
    });
    const gzip = zlib.gzipSync(buffer, { level: 9 });

    totals.files += 1;
    totals.raw += buffer.length;
    totals.brotli += Math.min(brotli.length, buffer.length);
    totals.gzip += Math.min(gzip.length, buffer.length);

    if (brotli.length < buffer.length) fs.writeFileSync(`${file}.br`, brotli);
    if (gzip.length < buffer.length) fs.writeFileSync(`${file}.gz`, gzip);
  }
  return totals;
}

/** The one line the image build prints about it. */
export function totalsLine(totals) {
  const kb = (bytes) => `${(bytes / 1024).toFixed(1)} kB`;
  return `precompress: ${totals.files} file(s), ${kb(totals.raw)} raw -> ${kb(totals.brotli)} brotli / ${kb(totals.gzip)} gzip`;
}

// Run as a script rather than imported by image-info.mjs; see scripts/workspace-paths-check.mjs
// for why both sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const [, , root] = process.argv;
  if (!root) {
    console.error('usage: precompress.mjs <directory>');
    process.exit(2);
  }
  if (!fs.existsSync(root)) {
    console.error(`precompress: ${root} does not exist — build the site first`);
    process.exit(2);
  }
  console.log(totalsLine(precompress(root)));
}
