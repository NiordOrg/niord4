/**
 * The parts of `image:info` (image-info.mjs) that read and write files, and the only shape in
 * which docker is ever started: an argument array, no shell.
 *
 *   parseTag             the image reference image:info builds: `[host[:port]/]path:tag`, no digest
 *   readAllowList        info-image-allowed.json: the files the image may package
 *   outputProblems       what refuses a dist: a source map, an unhashed entry, a file off the list
 *   assembleContext      the fresh build context: Dockerfile, nginx/ (five files), browser/; the
 *                        Dockerfile's copy ends with the image's labels as one LABEL instruction
 *   dockerfileLabel      that instruction, every value escaped for Dockerfile double quotes, in
 *                        physical lines of at most LABEL_LINE_BYTES
 *   imageFilesProblems   the files copied out of a built image against the dist they came from
 *   dockerBuildArgs      the argument array of `docker build`: constants, ALPINE_IMAGE with its
 *                        value when a base is given, the context, the tag
 *   labelProblems        the labels docker inspect reads from a built image against those written
 *   defaultRun           the child-process runner every docker and node call goes through
 *   runFailure           why a call failed: its exit status, its spawn error or its signal
 *
 * Node built-ins only, as the whole kit (§4.11 rule 2).
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import zlib from 'node:zlib';
import { globToRegExp } from './include.mjs';
import { where } from './where.mjs';

// The public site's precompress.mjs, from the base product the layout resolver finds.
const { precompress } = await import(
  pathToFileURL(path.join(where().base, 'ui-apps', 'info', 'nginx', 'precompress.mjs')).href
);

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const ALLOW_FILE = path.join(KIT, 'info-image-allowed.json');
export const ALLOW_FORMAT = 1;

/** The nginx files the image carries, all from `<base>/ui-apps/info/nginx/`. */
export const NGINX_FILES = [
  'default.conf.template',
  'entrypoint.sh',
  'niord-common-headers.conf',
  'niord-proxy.conf',
  'niord-security-headers.conf',
];

// ---------------------------------------------------------------------------------------------
// The tag
// ---------------------------------------------------------------------------------------------

const HOST = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9-]+)*(:[0-9]+)?$/;
const COMPONENT = /^[a-z0-9]+([._-][a-z0-9]+)*$/;
const TAG = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/;

/**
 * The parts of an image reference, or an Error: an optional registry host (with a dot or a port,
 * or `localhost`, as docker tells a host from a path), lowercase path components, and a tag,
 * which is mandatory unless `requireTag` is false (the base product's own images, which are never
 * pushed and take docker's `latest`). A digest is refused: a digest names an image that exists,
 * and this builds one. The reference becomes one argument of docker's, never part of a command
 * line.
 */
export function parseTag(text, { requireTag = true } = {}) {
  if (typeof text !== 'string' || !text) throw new Error('--tag names no image');
  if (text.length > 255) throw new Error(`the tag is ${text.length} characters long, 255 at most`);
  if (text.startsWith('-')) throw new Error(`"${text}" starts with "-"`);
  if (text.includes('@')) throw new Error(`"${text}" names a digest; image:info builds a tag`);
  const slash = text.lastIndexOf('/');
  const colon = text.lastIndexOf(':');
  const tagged = colon > slash;
  if (!tagged && requireTag)
    throw new Error(
      `"${text}" has no tag: write <registry>/<name>:<version>, e.g. niord-xx-info:1`,
    );
  const tag = tagged ? text.slice(colon + 1) : undefined;
  const name = tagged ? text.slice(0, colon) : text;
  if (tagged && !TAG.test(tag))
    throw new Error(`"${tag}" is not a tag (letters, digits, _ . -, 128 at most)`);
  const parts = name.split('/');
  let host;
  if (parts.length > 1 && (/[.:]/.test(parts[0]) || parts[0] === 'localhost')) {
    host = parts.shift();
    if (!HOST.test(host)) throw new Error(`"${host}" is not a registry host[:port]`);
  }
  for (const part of parts)
    if (!COMPONENT.test(part))
      throw new Error(
        `"${part}" is not an image name component (lowercase letters, digits, . _ -)`,
      );
  return { host, path: parts.join('/'), tag, reference: text };
}

// ---------------------------------------------------------------------------------------------
// The output allow-list and the checks of a dist
// ---------------------------------------------------------------------------------------------

/** info-image-allowed.json as a predicate over paths relative to the dist, with `/`. */
export function readAllowList(file = ALLOW_FILE) {
  const label = path.basename(file);
  const list = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (list.format !== ALLOW_FORMAT) throw new Error(`${label} is not of format ${ALLOW_FORMAT}`);
  if (!Array.isArray(list.allow) || list.allow.length === 0)
    throw new Error(`${label} allows nothing`);
  const patterns = list.allow.map((entry) => {
    if (typeof entry?.glob !== 'string' || !entry.glob)
      throw new Error(`${label}: an entry has no glob`);
    if (typeof entry.reason !== 'string' || !entry.reason.trim())
      throw new Error(`${label}: "${entry.glob}" has no reason`);
    if (entry.glob.includes('**'))
      throw new Error(`${label}: "${entry.glob}" reaches any depth; name the folder`);
    return globToRegExp(entry.glob);
  });
  return (relative) => patterns.some((re) => re.test(relative));
}

/** Every entry under `dir`, relative and with `/`, each with its lstat. */
function entries(dir) {
  return fs
    .readdirSync(dir, { recursive: true })
    .map((name) => {
      const relative = String(name).replaceAll('\\', '/');
      return { relative, stat: fs.lstatSync(path.join(dir, relative)) };
    })
    .sort((a, b) => (a.relative < b.relative ? -1 : a.relative > b.relative ? 1 : 0));
}

/** Why one entry may not be packaged, or undefined. */
function entryProblem({ relative, stat }) {
  if (stat.isSymbolicLink()) return `${relative}: a link; the image packages regular files only`;
  if (stat.isDirectory()) return undefined;
  if (!stat.isFile()) return `${relative}: not a regular file`;
  if (stat.nlink > 1) return `${relative}: a hard link; the image packages regular files only`;
  return undefined;
}

/** The content hash the builder writes into the name of each bundle in production. */
const HASHED = /-[A-Za-z0-9_-]{8}\.(js|css)$/;

/** The script and stylesheet references of index.html. */
function indexEntries(html) {
  const found = [];
  for (const tag of html.match(/<(script|link)\b[^>]*>/gi) ?? []) {
    const isScript = /^<script/i.test(tag);
    if (!isScript && !/rel=["']?(stylesheet|modulepreload)["']?/i.test(tag)) continue;
    const ref = new RegExp(`${isScript ? 'src' : 'href'}=["']?([^"'\\s>]+)`, 'i').exec(tag);
    if (ref) found.push(ref[1]);
  }
  return found;
}

/**
 * One line per reason the dist in `browserDir` may not become an image: a source map or a
 * reference to one, an index.html missing or naming a bundle without a content hash (the shape of
 * a non-production build), a pre-compressed twin already there, a link or other non-regular file,
 * and a file no glob of the allow-list matches. Empty when the dist may be packaged.
 */
export function outputProblems(browserDir, allowed) {
  if (!fs.existsSync(browserDir) || !fs.statSync(browserDir).isDirectory())
    return [`${browserDir} is not a folder: build first`];
  const problems = [];
  for (const entry of entries(browserDir)) {
    const problem = entryProblem(entry);
    if (problem) {
      problems.push(problem);
      continue;
    }
    if (entry.stat.isDirectory()) continue;
    const { relative } = entry;
    if (relative.endsWith('.map'))
      problems.push(`${relative}: a source map; a production build carries none`);
    else if (/\.(br|gz)$/.test(relative))
      problems.push(`${relative}: pre-compressed already; image:info compresses in its context`);
    else if (!allowed(relative))
      problems.push(`${relative}: not in info-image-allowed.json, so not a file the image ships`);
    if (/\.(m?js|css)$/.test(relative)) {
      const text = fs.readFileSync(path.join(browserDir, relative), 'utf8');
      if (/(?:\/\/|\/\*)\s*[#@]\s*sourceMappingURL\s*=/.test(text))
        problems.push(
          `${relative}: names a source map (sourceMappingURL); a production build does not`,
        );
    }
  }
  const index = path.join(browserDir, 'index.html');
  if (!fs.existsSync(index)) {
    problems.push('index.html: missing; is this a browser build of niord-info?');
  } else {
    const references = indexEntries(fs.readFileSync(index, 'utf8'));
    if (references.length === 0) problems.push('index.html: names no script or stylesheet');
    for (const reference of references)
      if (!HASHED.test(reference.split(/[?#]/)[0]))
        problems.push(
          `index.html: "${reference}" carries no content hash, the shape of a non-production build (--configuration local)`,
        );
  }
  return problems;
}

// ---------------------------------------------------------------------------------------------
// The build context
// ---------------------------------------------------------------------------------------------

/**
 * Fills the empty folder `out` with exactly the image's build context: `Dockerfile` and
 * `nginx/<five files>` from the base product in `baseRoot`, and every file of the dist in
 * `browserDir` under `browser/`, each checked again as it is copied; then pre-compresses
 * `browser/` there. With `labels` (name → value, as an object or ordered pairs) the context's copy
 * of the Dockerfile ends with them as one LABEL instruction (dockerfileLabel), so no value is ever
 * part of a command line; a Dockerfile whose parser directives set another escape character is
 * refused. The base product's Dockerfile itself is only read, as is the dist.
 * Answers the precompress totals.
 */
export function assembleContext({
  browserDir,
  baseRoot,
  out,
  allowed = readAllowList(),
  labels = [],
}) {
  if (fs.existsSync(out) && fs.readdirSync(out).length > 0)
    throw new Error(`${out} is not empty; the build context starts from an empty folder`);
  const problems = outputProblems(browserDir, allowed);
  if (problems.length) throw new Error(`the dist cannot be packaged:\n  ${problems.join('\n  ')}`);
  const label = dockerfileLabel(labels);
  const nginx = path.join(baseRoot, 'ui-apps', 'info', 'nginx');
  fs.mkdirSync(path.join(out, 'nginx'), { recursive: true });
  for (const name of NGINX_FILES)
    copyRegular(path.join(nginx, name), path.join(out, 'nginx', name));
  const dockerfile = path.join(out, 'Dockerfile');
  copyRegular(path.join(baseRoot, 'ui-apps', 'info', 'Dockerfile'), dockerfile);
  if (label) {
    const text = fs.readFileSync(dockerfile, 'utf8');
    const directive = escapeDirective(text);
    if (directive)
      throw new Error(
        `the Dockerfile sets the parser directive "${directive}"; its LABEL instruction is written for the default escape character, the backslash`,
      );
    fs.appendFileSync(dockerfile, `${text === '' || text.endsWith('\n') ? '' : '\n'}${label}\n`);
  }
  const browser = path.join(out, 'browser');
  fs.mkdirSync(browser);
  for (const entry of entries(browserDir)) {
    if (entry.stat.isDirectory()) continue;
    if (!allowed(entry.relative)) throw new Error(`${entry.relative} is not allowed`);
    copyRegular(path.join(browserDir, entry.relative), path.join(browser, entry.relative));
  }
  // precompress.mjs, the sixth file of nginx/, runs here in this process, loaded from the kit's
  // resolved base product; the image never holds it.
  return precompress(browser);
}

/**
 * The longest physical line of a LABEL instruction, in UTF-8 bytes. Docker's Dockerfile parser
 * refuses a line of 64 KiB or more ("dockerfile line greater than max allowed size of 65535"),
 * and a country's build report outgrows that, so a longer instruction continues on the next line.
 */
export const LABEL_LINE_BYTES = 16 * 1024;

/**
 * The LABEL instruction of `labels` (name → value, as an object or ordered pairs), or '' when
 * there are none: `LABEL "<name>"="<value>" …`. Inside Docker's double quotes a backslash, a
 * double quote and a dollar sign are escaped with a backslash — LABEL substitutes variables, so an
 * unescaped `$` would be read as one — and every other character stands for itself, so the value
 * docker inspect reads is the value given. An instruction longer than `lineBytes` continues on the
 * next line after a backslash (continuedLines). A name or value that is not a string, an empty
 * name, and a control character (a line break would end the instruction) are refused rather than
 * written another way.
 */
export function dockerfileLabel(labels = [], { lineBytes = LABEL_LINE_BYTES } = {}) {
  const pairs = Array.isArray(labels) ? labels : Object.entries(labels);
  if (pairs.length === 0) return '';
  const quoted = (text, what) => {
    if (typeof text !== 'string') throw new Error(`a label's ${what} is not a string`);
    if (/\p{Cc}/u.test(text))
      throw new Error(
        `a label's ${what} holds a control character, which a LABEL line cannot carry`,
      );
    return `"${text.replace(/[\\"$]/g, '\\$&')}"`;
  };
  const words = pairs.map(([name, value]) => {
    if (name === '') throw new Error('a label has no name');
    return `${quoted(name, 'name')}=${quoted(value, `value (${name})`)}`;
  });
  return continuedLines(`LABEL ${words.join(' ')}`, lineBytes);
}

/**
 * `line` as physical lines of at most `lineBytes` UTF-8 bytes each, every one but the last ending
 * in the backslash that continues it. Docker joins them with nothing in between, so a value is
 * unchanged by where it breaks; a break never parts an escape from the character it escapes, no
 * continued line starts with white space or `#`, since the parser drops a continued line that
 * reads as a comment, and no line ends in an escaped backslash, since Docker reads a continuation
 * only after a character that is not a backslash. A value whose run of `#`, white space or
 * backslashes is longer than a line is refused.
 */
function continuedLines(line, lineBytes) {
  if (Buffer.byteLength(line) <= lineBytes) return line;
  const units = line.match(/\\[\s\S]|[\s\S]/gu);
  const lines = [];
  for (let start = 0; start < units.length; ) {
    let end = start;
    for (let bytes = 0; end < units.length; end++) {
      bytes += Buffer.byteLength(units[end]);
      if (bytes > lineBytes - 1) break;
    }
    if (end < units.length)
      while (end > start && (/^[\s#]/u.test(units[end]) || units[end - 1] === '\\\\')) end--;
    if (end === start)
      throw new Error(`a LABEL line cannot be broken into lines of ${lineBytes} bytes`);
    lines.push(units.slice(start, end).join(''));
    start = end;
  }
  return lines.join('\\\n');
}

/**
 * The `escape` parser directive among the comment lines that open a Dockerfile's text, or
 * undefined; a byte order mark before it counts as white space. The directive changes the
 * character dockerfileLabel escapes with.
 */
function escapeDirective(text) {
  for (const line of text.split(/\r?\n/)) {
    if (!/^\s*#/.test(line)) return undefined;
    if (/^\s*#\s*escape\s*=/i.test(line)) return line.trim();
  }
  return undefined;
}

/** Copies one file, refusing anything but a regular file with one name. */
function copyRegular(from, to) {
  const stat = fs.lstatSync(from);
  if (!stat.isFile() || stat.nlink > 1) throw new Error(`${from} is not a regular file`);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

// ---------------------------------------------------------------------------------------------
// The built image against its dist
// ---------------------------------------------------------------------------------------------

/**
 * One line per difference between the dist and the image's web root copied out of the built
 * image into `extractedDir`: every dist file must be there with the same bytes, and every other
 * file there must be the .br or .gz twin of a dist file that unpacks to it. Empty when the image
 * serves exactly the build its checks read.
 */
export function imageFilesProblems({ distDir, extractedDir }) {
  const regular = (dir) =>
    entries(dir)
      .filter((entry) => !entry.stat.isDirectory())
      .map((entry) => entry.relative);
  const dist = regular(distDir);
  const image = new Set(regular(extractedDir));
  const problems = [];
  for (const relative of dist) {
    if (!image.has(relative)) {
      problems.push(`${relative}: in the dist but not in the image`);
      continue;
    }
    const a = fs.readFileSync(path.join(distDir, relative));
    const b = fs.readFileSync(path.join(extractedDir, relative));
    if (!a.equals(b)) problems.push(`${relative}: differs between the dist and the image`);
  }
  const distSet = new Set(dist);
  for (const relative of image) {
    if (distSet.has(relative)) continue;
    const twin = /^(.*)\.(br|gz)$/.exec(relative);
    if (!twin || !distSet.has(twin[1])) {
      problems.push(`${relative}: in the image but not in the dist`);
      continue;
    }
    const packed = fs.readFileSync(path.join(extractedDir, relative));
    let unpacked;
    try {
      unpacked = twin[2] === 'br' ? zlib.brotliDecompressSync(packed) : zlib.gunzipSync(packed);
    } catch {
      unpacked = undefined;
    }
    if (!unpacked?.equals(fs.readFileSync(path.join(distDir, twin[1]))))
      problems.push(`${relative}: does not unpack to ${twin[1]} of the dist`);
  }
  return problems;
}

// ---------------------------------------------------------------------------------------------
// docker
// ---------------------------------------------------------------------------------------------

/**
 * The arguments of `docker build`: constants, the context's own paths and the tag. The labels are
 * in the context's Dockerfile (assembleContext), never here: a build report as long as a
 * country's outgrows a command line. `base` is the reference the base's build argument
 * ALPINE_IMAGE gets, `--build-arg ALPINE_IMAGE=<base>`; undefined passes no build argument and the
 * Dockerfile's default stands. The value is given, never left for docker to read from its own
 * environment, so the caller's environment (imageInfo's `env`) is the one the build uses.
 */
export function dockerBuildArgs({ context, tag, base }) {
  return [
    'build',
    '-f',
    path.join(context, 'Dockerfile'),
    ...(base === undefined ? [] : ['--build-arg', `ALPINE_IMAGE=${base}`]),
    '-t',
    tag,
    context,
  ];
}

/**
 * One line per label of `labels` (name → value, as an object or ordered pairs) that the image
 * `tag` does not carry with exactly that value, as docker inspect reads it; or the one line why
 * inspect failed. A line names the label and both lengths, never a value, which may be the whole
 * build report. Labels the base image carries itself are not compared. Empty when every label
 * reads back as written.
 */
export function labelProblems({ run, tag, labels }) {
  const result = run('docker', ['image', 'inspect', '--format', '{{json .Config.Labels}}', tag], {
    capture: true,
  });
  if (result.status !== 0)
    return [
      `docker image inspect ${tag} failed (${runFailure(result)})${stderrOf(result) ? `: ${stderrOf(result)}` : ''}`,
    ];
  let found;
  try {
    found = JSON.parse(result.stdout) ?? {};
  } catch {
    return [`docker image inspect ${tag} answered no JSON for the image's labels`];
  }
  const characters = (text) => `${text.length} character${text.length === 1 ? '' : 's'}`;
  const problems = [];
  for (const [name, value] of Array.isArray(labels) ? labels : Object.entries(labels)) {
    const read = Object.hasOwn(found, name) ? found[name] : undefined;
    if (typeof read !== 'string')
      problems.push(`${name}: not on the image (written: ${characters(value)})`);
    else if (read !== value)
      problems.push(
        `${name}: reads back other than written (read: ${characters(read)}, written: ${characters(value)})`,
      );
  }
  return problems;
}

/**
 * Starts `command` with `args` as an argument array and never through a shell, so no value can be
 * read as shell syntax. `capture` keeps the output for the caller; otherwise it reaches the
 * terminal as it is written. A command that cannot start answers no status and its spawn error.
 */
export function defaultRun(command, args, { capture = false, cwd } = {}) {
  const result = spawnSync(command, args, {
    shell: false,
    cwd,
    encoding: 'utf8',
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
    maxBuffer: 64 * 1024 * 1024,
  });
  return {
    status: result.error ? null : result.status,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? '',
    error: result.error,
    signal: result.signal ?? undefined,
  };
}

/**
 * Why a call of `run` failed, for a message: `<code>: <message>` when the process could not start
 * (a command too long, a program not found), the signal that ended it, or `exit <status>`.
 */
export function runFailure(result) {
  if (result.error) return `${result.error.code ?? 'error'}: ${result.error.message}`;
  if (result.signal) return `ended by ${result.signal}`;
  return `exit ${result.status ?? 'none'}`;
}

/** The trimmed stderr of a captured call, or ''. */
const stderrOf = (result) => (result.stderr ?? '').trim();
