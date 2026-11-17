/**
 * A country's own npm packages, merged on top of the base product's dependencies.
 *
 * The country commits two files in `fe/country/`, which npm and Dependabot read as a real
 * manifest and lockfile:
 *
 *   package.json        { name, private: true, description?, dependencies: { <name>: <x.y.z> },
 *                         niord: { dependencies: { <name>: { app, reason } },
 *                                  installScripts?: { <name>: <reason> } } }
 *                       every version exact; per package the application it is for (`admin`,
 *                       `info` or `both`) and why the country needs it; a package that runs an
 *                       install script names why it may. Nothing else: no dev, optional, peer or
 *                       bundled dependencies, no overrides, scripts or workspaces.
 *   package-lock.json   lockfileVersion 3: `packages[""]` with the manifest's name and
 *                       dependencies, then exactly the entries the merged tree adds to the base
 *                       product's lockfile, which `node setup.mjs --update-country-lock` writes
 *                       (updateCountryLock)
 *
 * Neither file means no country packages; one without the other fails. Set-up checks them
 * (checkCountryDependencies) and fails on each of:
 *
 *   shape            either file outside its shape above, a version that is not exact, a name
 *                    that is no npm name or is `@niord/…`
 *   base package     a country package the base product has at another version (the same
 *                    version passes, and is listed)
 *   base entry       a lock entry at a path the base product's lockfile has, with another
 *                    version, resolved URL or integrity: a base product entry of the merged tree
 *                    would differ from the pinned lockfile
 *   tree             a package the manifest lists or an entry depends on that the merged tree
 *                    lacks or holds at a version outside the range, or an entry nothing reaches
 *   registry         a resolved URL outside https://registry.npmjs.org/, an integrity that is not
 *                    sha512, a link
 *   install script   an entry with an install script that niord.installScripts gives no reason for
 *   peers            a peer range of an added entry that the merged tree's version of that peer
 *                    does not satisfy, or a peer that is not optional and absent: the workspace
 *                    installs with legacy-peer-deps, which would hide both, so they are checked
 *                    here (lib/semver.mjs; a range it cannot read fails)
 *   age              an added entry published less than 7 days ago (publishTimes asks the
 *                    registry, and caches each answer once it is older than that)
 *
 * The merged tree is the generated workspace's (mergeDependencies): the base product's
 * package.json dependencies plus the country's, and the base product's lockfile plus the
 * country's entries.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { satisfies } from './semver.mjs';

const FOLDER = 'country';
const MANIFEST = `${FOLDER}/package.json`;
const LOCK = `${FOLDER}/package-lock.json`;
const MANIFEST_FIELDS = ['dependencies', 'description', 'name', 'niord', 'private'];
const NIORD_FIELDS = ['dependencies', 'installScripts'];
const META_FIELDS = ['app', 'reason'];
const LOCK_FIELDS = ['lockfileVersion', 'name', 'packages', 'requires', 'version'];
const ROOT_FIELDS = ['dependencies', 'name', 'version'];
const APP_VALUES = ['admin', 'info', 'both'];
const EXACT = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$/;
const NAME = /^(?:@[a-z0-9-~][a-z0-9-._~]*\/)?[a-z0-9-~][a-z0-9-._~]*$/;
const MAX_NAME = 214;
const SEGMENT = '(?:@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*';
const LOCK_PATH = new RegExp(`^node_modules/${SEGMENT}(?:/node_modules/${SEGMENT})*$`);
export const REGISTRY = 'https://registry.npmjs.org/';
const INTEGRITY = 'sha512-';
const MIN_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const CACHE_FORMAT = 1;
const COMPARED = ['version', 'resolved', 'integrity'];

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isText = (value) => typeof value === 'string' && value.trim() !== '';
const sortedObject = (object) =>
  Object.fromEntries(
    Object.keys(object)
      .sort()
      .map((key) => [key, object[key]]),
  );
const sameEntries = (a, b) => JSON.stringify(sortedObject(a)) === JSON.stringify(sortedObject(b));
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** Whether a text is an npm package name a country may add. */
const isPackageName = (name) =>
  typeof name === 'string' && name.length <= MAX_NAME && NAME.test(name) && !/\.\.|\/\./.test(name);

/** A lock entry's package name: its own `name` (an alias's), else the last part of its path. */
export const packageNameOf = (lockPath, entry) =>
  typeof entry?.name === 'string'
    ? entry.name
    : lockPath.slice(lockPath.lastIndexOf('node_modules/') + 'node_modules/'.length);

/** Where Node finds `name` from the package at `from` ('' for the root) in a lockfile's tree. */
function resolveIn(tree, from, name) {
  let base = from;
  for (;;) {
    const candidate = base ? `${base}/node_modules/${name}` : `node_modules/${name}`;
    if (Object.hasOwn(tree, candidate)) return candidate;
    if (!base) return undefined;
    const i = base.lastIndexOf('/node_modules/');
    base = i < 0 ? '' : base.slice(0, i);
  }
}

function readJson(file, label) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
}

/** A regular file's kind: 'absent', 'file'; throws on a link or anything else. */
function kindOf(file, label) {
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === 'ENOENT') return 'absent';
    throw error;
  }
  if (stat.isSymbolicLink()) throw new Error(`${label} is a link`);
  if (!stat.isFile() && !stat.isDirectory()) throw new Error(`${label} is not a regular file`);
  return stat.isDirectory() ? 'folder' : 'file';
}

/**
 * The country's manifest and lockfile from `<fe>/country/`, parsed, or null when it has neither.
 * Throws when one is there without the other, on a link and on a file that is no JSON.
 */
export function readCountryDependencies(fe) {
  const dir = path.join(fe, FOLDER);
  const folder = kindOf(dir, `${FOLDER}/`);
  if (folder === 'absent') return null;
  if (folder !== 'folder') throw new Error(`${FOLDER} is not a folder`);
  const manifestFile = path.join(dir, 'package.json');
  const lockFile = path.join(dir, 'package-lock.json');
  const hasManifest = kindOf(manifestFile, MANIFEST) === 'file';
  const hasLock = kindOf(lockFile, LOCK) === 'file';
  if (!hasManifest && !hasLock) return null;
  if (!hasLock)
    throw new Error(
      `${MANIFEST} has no package-lock.json beside it; \`node setup.mjs --update-country-lock\` makes it`,
    );
  if (!hasManifest) throw new Error(`${LOCK} has no package.json beside it`);
  return { manifest: readJson(manifestFile, MANIFEST), lock: readJson(lockFile, LOCK) };
}

/** The manifest's problems, one line each. */
export function manifestProblems(manifest) {
  if (!isObject(manifest)) return [`${MANIFEST} is not an object`];
  const problems = [];
  for (const field of Object.keys(manifest))
    if (!MANIFEST_FIELDS.includes(field))
      problems.push(
        `${MANIFEST} has "${field}"; it has name, private, description, dependencies and niord only`,
      );
  if (!isPackageName(manifest.name)) problems.push(`${MANIFEST}'s name is not an npm name`);
  if (manifest.private !== true) problems.push(`${MANIFEST} is not private: true`);
  if (manifest.description !== undefined && typeof manifest.description !== 'string')
    problems.push(`${MANIFEST}'s description is not a text`);
  const dependencies = isObject(manifest.dependencies) ? manifest.dependencies : {};
  if (!isObject(manifest.dependencies))
    problems.push(`${MANIFEST} has no dependencies object of name → exact version`);
  for (const [name, version] of Object.entries(dependencies)) {
    if (!isPackageName(name)) problems.push(`${MANIFEST}: "${name}" is not an npm name`);
    else if (name.startsWith('@niord/'))
      problems.push(`${MANIFEST}: ${name} is in the base product's scope, @niord/`);
    if (typeof version !== 'string' || !EXACT.test(version))
      problems.push(
        `${MANIFEST}: ${name} is ${JSON.stringify(version)}, not an exact version x.y.z`,
      );
  }
  const niord = manifest.niord;
  if (!isObject(niord) || !isObject(niord.dependencies)) {
    problems.push(
      `${MANIFEST} has no niord.dependencies: per package { app: admin|info|both, reason }`,
    );
    return problems;
  }
  for (const field of Object.keys(niord))
    if (!NIORD_FIELDS.includes(field))
      problems.push(`${MANIFEST}: niord has "${field}"; it has dependencies and installScripts`);
  for (const [name, meta] of Object.entries(niord.dependencies)) {
    const where = `${MANIFEST}: niord.dependencies.${name}`;
    if (!Object.hasOwn(dependencies, name))
      problems.push(`${where} names a package dependencies does not list`);
    if (!isObject(meta)) {
      problems.push(`${where} is not { app, reason }`);
      continue;
    }
    const fields = Object.keys(meta).sort();
    if (fields.join() !== META_FIELDS.join())
      problems.push(`${where} has ${fields.join(', ') || 'no fields'}; it has exactly app, reason`);
    if (!APP_VALUES.includes(meta.app))
      problems.push(`${where}.app is ${JSON.stringify(meta.app)}, not ${APP_VALUES.join(', ')}`);
    if (!isText(meta.reason)) problems.push(`${where} gives no reason`);
  }
  for (const name of Object.keys(dependencies))
    if (!Object.hasOwn(niord.dependencies, name))
      problems.push(
        `${MANIFEST}: ${name} has no niord.dependencies entry naming its application and reason`,
      );
  if (niord.installScripts !== undefined) {
    if (!isObject(niord.installScripts))
      problems.push(`${MANIFEST}: niord.installScripts is not an object of name → reason`);
    else
      for (const [name, reason] of Object.entries(niord.installScripts)) {
        if (!isPackageName(name))
          problems.push(`${MANIFEST}: niord.installScripts names "${name}", not an npm name`);
        if (!isText(reason))
          problems.push(`${MANIFEST}: niord.installScripts.${name} gives no reason`);
      }
  }
  return problems;
}

/** The lockfile's problems of shape, against its manifest, one line each. */
function lockProblems(lock, manifest) {
  if (!isObject(lock)) return [`${LOCK} is not an object`];
  const problems = [];
  for (const field of Object.keys(lock))
    if (!LOCK_FIELDS.includes(field))
      problems.push(`${LOCK} has "${field}"; it has ${LOCK_FIELDS.join(', ')} only`);
  if (lock.lockfileVersion !== 3)
    problems.push(`${LOCK} is of lockfileVersion ${lock.lockfileVersion}, not 3`);
  if (lock.name !== undefined && lock.name !== manifest?.name)
    problems.push(`${LOCK} names "${lock.name}", the manifest "${manifest?.name}"`);
  if (!isObject(lock.packages)) {
    problems.push(`${LOCK} has no packages`);
    return problems;
  }
  const root = lock.packages[''];
  if (!isObject(root)) {
    problems.push(`${LOCK} has no packages[""]`);
    return problems;
  }
  for (const field of Object.keys(root))
    if (!ROOT_FIELDS.includes(field))
      problems.push(`${LOCK}: packages[""] has "${field}"; it has name and dependencies`);
  if (root.name !== manifest?.name)
    problems.push(`${LOCK}: packages[""] names "${root.name}", the manifest "${manifest?.name}"`);
  if (
    !isObject(root.dependencies ?? {}) ||
    !isObject(manifest?.dependencies) ||
    !sameEntries(root.dependencies ?? {}, manifest.dependencies)
  )
    problems.push(
      `${LOCK}: packages[""].dependencies are not ${MANIFEST}'s; \`node setup.mjs --update-country-lock\` writes them`,
    );
  return problems;
}

/**
 * The entries of the country's lock that the base product's lockfile does not have, as
 * `{ path, name, version, integrity }`: what the merged tree adds, whose publish times the age
 * rule needs. Entries outside the lock's shape are left out.
 */
export function addedEntries(country, baseLock) {
  const packages = isObject(country?.lock?.packages) ? country.lock.packages : {};
  const base = baseLock.packages ?? {};
  return Object.entries(packages)
    .filter(
      ([lockPath, entry]) =>
        lockPath !== '' &&
        LOCK_PATH.test(lockPath) &&
        isObject(entry) &&
        !Object.hasOwn(base, lockPath),
    )
    .map(([lockPath, entry]) => ({
      path: lockPath,
      name: packageNameOf(lockPath, entry),
      version: entry.version,
      integrity: entry.integrity,
    }));
}

/**
 * Checks the country's dependencies (readCountryDependencies) against the base product's
 * package.json and lockfile. `published` maps `<name>@<version>` to the time the registry
 * published it (publishTimes), `now` is when the age rule counts from. Every finding is reported:
 * returns the errors and warnings, one line each; the printed lines; the added entries; and, for
 * the upgrade report, every peer of an added entry as `{ from, name, peer, range, version,
 * rejected }` (`version` the merged tree's, null when it lacks the peer; `rejected` when the peer
 * fails the rule above, and then with `error`, its line in `errors`).
 */
export function checkCountryDependencies({
  country,
  basePackage,
  baseLock,
  published = new Map(),
  now = new Date(),
}) {
  const errors = [];
  const warnings = [];
  const lines = [];
  if (!country)
    return { errors, warnings, lines: ['country dependencies: none'], added: [], peers: [] };
  const { manifest, lock } = country;
  errors.push(...manifestProblems(manifest), ...lockProblems(lock, manifest));

  const dependencies = isObject(manifest?.dependencies) ? manifest.dependencies : {};
  const installScripts = isObject(manifest?.niord?.installScripts)
    ? manifest.niord.installScripts
    : {};
  const basePackages = baseLock.packages ?? {};
  const baseDeclared = {
    ...(basePackage.devDependencies ?? {}),
    ...(basePackage.dependencies ?? {}),
  };

  // A country package the base product already has.
  const baseOwn = new Set();
  for (const [name, version] of Object.entries(dependencies)) {
    const installed = basePackages[`node_modules/${name}`];
    if (!installed && !Object.hasOwn(baseDeclared, name)) continue;
    if (installed?.version === version) baseOwn.add(name);
    else
      errors.push(
        `${MANIFEST} lists ${name} ${version}, which the base product has at ${installed?.version ?? baseDeclared[name]}: a country takes the base product's version of its packages`,
      );
  }

  // The lock's entries: a base product path unchanged, everything else from the registry.
  const packages = isObject(lock?.packages) ? lock.packages : {};
  const added = [];
  const scriptsUsed = new Set();
  for (const [lockPath, entry] of Object.entries(packages)) {
    if (lockPath === '') continue;
    const where = `${LOCK}: ${lockPath}`;
    if (!LOCK_PATH.test(lockPath)) {
      errors.push(`${where} is not a path under node_modules/ of npm names`);
      continue;
    }
    if (!isObject(entry)) {
      errors.push(`${where} is not an object`);
      continue;
    }
    if (entry.link) {
      errors.push(`${where} is a link; a country package comes from the registry`);
      continue;
    }
    if (Object.hasOwn(basePackages, lockPath)) {
      const base = basePackages[lockPath];
      const differs = COMPARED.filter((field) => entry[field] !== base[field]);
      errors.push(
        differs.length
          ? `${where} has another ${differs.join(', ')} than the base product's (${base.version}): a base product entry of the merged tree differs from the pinned lockfile`
          : `${where} repeats the base product's entry; the country lock holds only the entries it adds`,
      );
      continue;
    }
    const name = packageNameOf(lockPath, entry);
    if (typeof entry.version !== 'string' || !EXACT.test(entry.version))
      errors.push(`${where} has no version x.y.z`);
    if (typeof entry.resolved !== 'string' || !entry.resolved.startsWith(REGISTRY))
      errors.push(`${where} is resolved from ${entry.resolved ?? 'nowhere'}, not ${REGISTRY}`);
    if (typeof entry.integrity !== 'string' || !entry.integrity.startsWith(INTEGRITY))
      errors.push(`${where} has the integrity ${entry.integrity ?? 'none'}, not a sha512 one`);
    if (entry.hasInstallScript) {
      scriptsUsed.add(name);
      if (!Object.hasOwn(installScripts, name))
        errors.push(
          `${where} runs an install script, and ${MANIFEST}'s niord.installScripts gives no reason for ${name}`,
        );
    }
    added.push({ path: lockPath, name, version: entry.version, integrity: entry.integrity });
  }
  for (const name of Object.keys(installScripts))
    if (!scriptsUsed.has(name))
      warnings.push(
        `${MANIFEST}: niord.installScripts.${name} names no added entry with an install script; remove it`,
      );

  // The merged tree: every package found, every peer satisfied, every added entry reached.
  const tree = { ...basePackages };
  for (const { path: lockPath } of added) tree[lockPath] = packages[lockPath];
  const isAdded = new Set(added.map((entry) => entry.path));
  const reached = new Set();
  const queue = [];
  for (const [name, version] of Object.entries(dependencies)) {
    const found = resolveIn(tree, '', name);
    if (!found) {
      errors.push(`the merged tree has no ${name}, which ${MANIFEST} lists`);
      continue;
    }
    if (isAdded.has(found) && tree[found].version !== version)
      errors.push(`${LOCK}: ${found} is ${tree[found].version}, ${MANIFEST} lists ${version}`);
    if (isAdded.has(found) && !reached.has(found)) {
      reached.add(found);
      queue.push(found);
    }
  }
  const walk = (from, fieldName, optional) => {
    for (const [dependency, range] of Object.entries(tree[from][fieldName] ?? {})) {
      const found = resolveIn(tree, from, dependency);
      if (!found) {
        if (!optional(dependency))
          errors.push(
            `${LOCK}: ${from} depends on ${dependency} ${range}, which the merged tree lacks`,
          );
        continue;
      }
      let ok = true;
      try {
        ok = satisfies(tree[found].version, range);
      } catch {
        // An alias or another range npm resolves itself: its presence is what counts here.
      }
      if (!ok)
        errors.push(
          `${LOCK}: ${from} depends on ${dependency} ${range}, and the merged tree has ${tree[found].version}`,
        );
      if (isAdded.has(found) && !reached.has(found)) {
        reached.add(found);
        queue.push(found);
      }
    }
  };
  while (queue.length) {
    const from = queue.shift();
    walk(from, 'dependencies', () => false);
    walk(from, 'optionalDependencies', () => true);
    // A peer the country's entries hold is reached too; whether it fits is checked below.
    for (const peer of Object.keys(tree[from].peerDependencies ?? {})) {
      const found = resolveIn(tree, from, peer);
      if (found && isAdded.has(found) && !reached.has(found)) {
        reached.add(found);
        queue.push(found);
      }
    }
  }
  const peers = [];
  for (const { path: from, name } of added) {
    const entry = tree[from];
    for (const [peer, range] of Object.entries(entry.peerDependencies ?? {})) {
      const optional = entry.peerDependenciesMeta?.[peer]?.optional === true;
      const found = resolveIn(tree, from, peer);
      const relation = { from, name, peer, range, version: found ? tree[found].version : null };
      // A rejected relation carries its line, as the errors hold it.
      const reject = (error) => {
        errors.push(error);
        peers.push({ ...relation, rejected: true, error });
      };
      if (!found) {
        if (optional) peers.push({ ...relation, rejected: false });
        else
          reject(`${LOCK}: ${from} needs the peer ${peer} ${range}, which the merged tree lacks`);
        continue;
      }
      const version = tree[found].version;
      let ok;
      try {
        ok = satisfies(version, range);
      } catch (error) {
        reject(
          `${LOCK}: ${from}'s peer range for ${peer} cannot be read (${error.message}), so it cannot be held to the base product's ${version}`,
        );
        continue;
      }
      if (ok) peers.push({ ...relation, rejected: false });
      else
        reject(
          `${LOCK}: ${from} needs the peer ${peer} ${range}, and the merged tree has ${version}${isAdded.has(found) ? '' : ", the base product's"}`,
        );
    }
  }
  for (const { path: lockPath } of added)
    if (!reached.has(lockPath))
      errors.push(
        `${LOCK}: ${lockPath} is not reached from ${MANIFEST}'s dependencies; the lock holds exactly what the merged tree adds (\`node setup.mjs --update-country-lock\`)`,
      );

  // Age.
  for (const { name, version } of added) {
    if (typeof version !== 'string') continue;
    const id = `${name}@${version}`;
    const time = published.get(id);
    if (time === undefined) {
      errors.push(`no publish time is known for ${id}, so its age cannot be checked`);
      continue;
    }
    const at = Date.parse(time);
    if (Number.isNaN(at)) errors.push(`the publish time of ${id}, ${time}, is not a time`);
    else if (now.getTime() - at < MIN_AGE_MS)
      errors.push(
        `${id} was published ${time}, less than 7 days ago; a country takes a version from ${new Date(at + MIN_AGE_MS).toISOString()}`,
      );
  }

  const listed = Object.keys(dependencies).length;
  lines.push(
    `country dependencies: ${MANIFEST} lists ${plural(listed, 'package')}, and its lock adds ${plural(added.length, 'entry', 'entries')} to the base product's tree:`,
  );
  for (const row of reportDependencies(country))
    lines.push(
      `  ${row.name}@${row.version} for ${row.app} — ${row.reason}${baseOwn.has(row.name) ? " (the base product's own version)" : ''}${row.installScript ? `; install script: ${row.installScript}` : ''}`,
    );
  return { errors, warnings, lines, added, peers };
}

/** Per country package its version, application, reason and install-script reason, for the build report. */
export function reportDependencies(country) {
  if (!country || !isObject(country.manifest?.dependencies)) return [];
  const meta = country.manifest.niord?.dependencies ?? {};
  const scripts = country.manifest.niord?.installScripts ?? {};
  return Object.keys(country.manifest.dependencies)
    .sort()
    .map((name) => ({
      name,
      version: country.manifest.dependencies[name],
      app: meta[name]?.app ?? null,
      reason: meta[name]?.reason ?? null,
      installScript: typeof scripts[name] === 'string' ? scripts[name] : null,
    }));
}

/** The registry document's URL of a package: its name encoded, the scope's `@` kept. */
export const registryUrl = (name) => `${REGISTRY}${encodeURIComponent(name).replace(/^%40/, '@')}`;

async function fetchRegistryJson(url) {
  const response = await fetch(url, {
    headers: { accept: 'application/json' },
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error(`${url} answered ${response.status}`);
  return response.json();
}

function readCache(cacheFile) {
  try {
    const cache = JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
    if (cache?.format === CACHE_FORMAT && isObject(cache.published)) return cache.published;
  } catch {
    // An absent or unreadable cache is an empty one; it is written anew.
  }
  return {};
}

/**
 * When the registry published each entry, `{ name, version, integrity }` (addedEntries): the
 * package's full registry document by `fetchJson` (Node's fetch by default), its `time[version]`.
 * An answer older than 7 days at `now` is kept in `cacheFile`, keyed by
 * `<name>@<version>+<integrity>`, so a later set-up needs no network for it. Returns
 * `{ published: Map('<name>@<version>' → ISO time), errors }`; a failed request is an error
 * naming the package.
 */
export async function publishTimes({
  entries,
  fetchJson = fetchRegistryJson,
  cacheFile,
  now = new Date(),
}) {
  const published = new Map();
  const errors = [];
  const cached = cacheFile ? readCache(cacheFile) : {};
  const cache = { ...cached };
  const wanted = new Map();
  for (const { name, version, integrity } of entries) {
    if (typeof version !== 'string') continue;
    const key = `${name}@${version}+${integrity}`;
    if (typeof cached[key] === 'string') published.set(`${name}@${version}`, cached[key]);
    else wanted.set(name, [...(wanted.get(name) ?? []), { version, key }]);
  }
  for (const [name, versions] of wanted) {
    let document;
    try {
      document = await fetchJson(registryUrl(name));
    } catch (error) {
      errors.push(
        `the registry gave no answer for ${name} (${error.message}); its publish time is needed once per new version`,
      );
      continue;
    }
    for (const { version, key } of versions) {
      const time = document?.time?.[version];
      if (typeof time !== 'string' || Number.isNaN(Date.parse(time))) {
        errors.push(`the registry has no publish time for ${name}@${version}`);
        continue;
      }
      published.set(`${name}@${version}`, time);
      if (now.getTime() - Date.parse(time) >= MIN_AGE_MS) cache[key] = time;
    }
  }
  if (cacheFile && JSON.stringify(cache) !== JSON.stringify(cached)) {
    fs.mkdirSync(path.dirname(cacheFile), { recursive: true });
    fs.writeFileSync(
      cacheFile,
      `${JSON.stringify({ format: CACHE_FORMAT, published: sortedObject(cache) }, null, 2)}\n`,
    );
  }
  return { published, errors };
}

/** A lockfile's packages, `''` first and the rest in npm's order. */
function sortedPackages(packages) {
  const keys = Object.keys(packages)
    .filter((key) => key !== '')
    .sort((a, b) => a.localeCompare(b, 'en'));
  return Object.fromEntries(
    [...(Object.hasOwn(packages, '') ? [''] : []), ...keys].map((key) => [key, packages[key]]),
  );
}

/**
 * The generated workspace's dependencies and lockfile: the base product's, plus the country's
 * packages the base product does not declare and the entries its lock adds. A null country gives
 * the base product's unchanged.
 */
export function mergeDependencies({ basePackage, baseLock, country }) {
  const baseDependencies = basePackage.dependencies ?? {};
  if (!country) return { dependencies: { ...baseDependencies }, lock: structuredClone(baseLock) };
  const declared = new Set([
    ...Object.keys(baseDependencies),
    ...Object.keys(basePackage.devDependencies ?? {}),
  ]);
  const extra = Object.fromEntries(
    Object.entries(country.manifest.dependencies ?? {}).filter(([name]) => !declared.has(name)),
  );
  const dependencies = sortedObject({ ...baseDependencies, ...extra });
  const lock = structuredClone(baseLock);
  const packages = { ...lock.packages };
  packages[''] = {
    ...packages[''],
    dependencies: sortedObject({ ...(packages['']?.dependencies ?? {}), ...extra }),
  };
  for (const [lockPath, entry] of Object.entries(country.lock?.packages ?? {}))
    if (lockPath !== '') packages[lockPath] = structuredClone(entry);
  lock.packages = sortedPackages(packages);
  return { dependencies, lock };
}

/** Runs npm with the kit's own constant arguments; throws on a non-zero exit. */
function runNpm(args, { cwd }) {
  // npm is a .cmd file on Windows, which Node starts only through a shell, given one command line
  // (arguments beside a shell are deprecated); the arguments are this file's own constants.
  const windows = process.platform === 'win32';
  const result = spawnSync(windows ? `npm ${args.join(' ')}` : 'npm', windows ? [] : args, {
    cwd,
    stdio: 'inherit',
    shell: windows,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`npm ${args.join(' ')} exited ${result.status}`);
}

const LOCK_ONLY = ['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund'];

/**
 * Makes `<fe>/country/package-lock.json` for the country's manifest: npm resolves the merged
 * manifest against the base product's lockfile (`<base>/package.json`, `package-lock.json` and
 * `.npmrc`) in `<fe>/.niord/country-lock/`, without running a script, and the entries it adds
 * become the country's lock. A result that changes any base product entry is refused. `run` runs
 * npm (tests pass their own). Returns the printed lines.
 */
export function updateCountryLock({ fe, base, run = runNpm }) {
  const manifestFile = path.join(fe, FOLDER, 'package.json');
  if (kindOf(manifestFile, MANIFEST) !== 'file')
    throw new Error(`${MANIFEST} does not exist; the lock is made from it`);
  const manifest = readJson(manifestFile, MANIFEST);
  const problems = manifestProblems(manifest);
  if (problems.length) throw new Error(problems.join('; '));
  const basePackage = readJson(path.join(base, 'package.json'), 'the base product package.json');
  const baseLock = readJson(
    path.join(base, 'package-lock.json'),
    "the base product's package-lock.json",
  );

  const scratch = path.join(fe, '.niord', 'country-lock');
  fs.rmSync(scratch, { recursive: true, force: true });
  fs.mkdirSync(scratch, { recursive: true });
  const { dependencies } = mergeDependencies({
    basePackage,
    baseLock,
    country: { manifest, lock: { packages: {} } },
  });
  // The base product's manifest with the merged dependencies; its scripts are not npm's business here.
  const scratchPackage = { ...basePackage, dependencies };
  delete scratchPackage.scripts;
  fs.writeFileSync(
    path.join(scratch, 'package.json'),
    `${JSON.stringify(scratchPackage, null, 2)}\n`,
  );
  fs.writeFileSync(
    path.join(scratch, 'package-lock.json'),
    `${JSON.stringify(baseLock, null, 2)}\n`,
  );
  const npmrc = path.join(base, '.npmrc');
  if (fs.existsSync(npmrc)) fs.copyFileSync(npmrc, path.join(scratch, '.npmrc'));

  run(LOCK_ONLY, { cwd: scratch });

  const result = readJson(path.join(scratch, 'package-lock.json'), 'the lock npm wrote');
  const packages = isObject(result?.packages) ? result.packages : {};
  const changed = Object.entries(baseLock.packages ?? {})
    .filter(([lockPath]) => lockPath !== '')
    .filter(
      ([lockPath, entry]) =>
        !Object.hasOwn(packages, lockPath) ||
        COMPARED.some((field) => packages[lockPath][field] !== entry[field]),
    )
    .map(([lockPath]) => lockPath);
  if (changed.length)
    throw new Error(
      `npm would change ${plural(changed.length, 'base product entry', 'base product entries')} to fit the country's packages (${changed.slice(0, 10).join(', ')}${changed.length > 10 ? ', …' : ''}); a country package takes the base product's versions`,
    );
  const added = Object.fromEntries(
    Object.entries(packages).filter(
      ([lockPath]) => lockPath !== '' && !Object.hasOwn(baseLock.packages ?? {}, lockPath),
    ),
  );
  const countryLock = {
    name: manifest.name,
    lockfileVersion: 3,
    requires: true,
    packages: sortedPackages({
      '': { name: manifest.name, dependencies: manifest.dependencies },
      ...added,
    }),
  };
  const text = `${JSON.stringify(countryLock, null, 2)}\n`;
  const lockFile = path.join(fe, FOLDER, 'package-lock.json');
  const before = fs.existsSync(lockFile) ? fs.readFileSync(lockFile, 'utf8') : undefined;
  if (before !== text) fs.writeFileSync(lockFile, text);
  fs.rmSync(scratch, { recursive: true, force: true });
  return [
    `country lock: ${LOCK} ${before === text ? 'unchanged' : 'written'}, ${plural(Object.keys(added).length, 'entry', 'entries')} the merged tree adds to the base product's`,
    ...Object.entries(added).map(([lockPath, entry]) => `  ${lockPath} ${entry.version}`),
  ];
}
