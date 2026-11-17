/**
 * The part of npm's semantic versioning the kit needs: whether a version satisfies a range, as
 * npm decides it (node-semver, not loose, prereleases not included by default).
 *
 * A range is one or more comparator sets joined by `||`; a set is comparators joined by white
 * space, all of which must hold, or a hyphen range `a - b`. A comparator is an operator (`=`, `>`,
 * `>=`, `<`, `<=`, `~`, `~>`, `^` or none) and a version that may be partial (`1`, `1.2`) or have
 * `x`, `X` or `*` in place of a number; an empty range and `*` match every version. A version with
 * a prerelease satisfies a set only when some comparator of the set has a prerelease on the same
 * major.minor.patch, so `^1.2.0` does not take `1.3.0-beta.1`.
 *
 * Anything else — a dist-tag, a URL, a path, a git or `workspace:` or `npm:` range — throws: a
 * range this module cannot read must fail a check, never pass it.
 */

const NUMBER = '0|[1-9]\\d*';
const IDENTIFIER = '(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)';
const PRERELEASE = `(?:-(${IDENTIFIER}(?:\\.${IDENTIFIER})*))`;
const BUILD = '(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)';
const VERSION = new RegExp(`^v?(${NUMBER})\\.(${NUMBER})\\.(${NUMBER})${PRERELEASE}?${BUILD}?$`);
const PART = `(${NUMBER}|[xX*])`;
const PARTIAL = new RegExp(`^v?${PART}(?:\\.${PART}(?:\\.${PART}${PRERELEASE}?${BUILD}?)?)?$`);
const COMPARATOR = /^(~>|~|\^|>=|<=|>|<|=)?(.*)$/;

/** A version as `{ major, minor, patch, prerelease }`, or a thrown error. */
export function parseVersion(text) {
  const match = typeof text === 'string' ? VERSION.exec(text.trim()) : null;
  if (!match) throw new Error(`"${text}" is not a version`);
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease: match[4] ? match[4].split('.') : [],
  };
}

const isNumeric = (identifier) => /^\d+$/.test(identifier);

function comparePrerelease(a, b) {
  if (!a.length && !b.length) return 0;
  if (!a.length) return 1;
  if (!b.length) return -1;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i] === undefined) return -1;
    if (b[i] === undefined) return 1;
    if (a[i] === b[i]) continue;
    const [numericA, numericB] = [isNumeric(a[i]), isNumeric(b[i])];
    if (numericA && numericB) return Number(a[i]) < Number(b[i]) ? -1 : 1;
    if (numericA) return -1;
    if (numericB) return 1;
    return a[i] < b[i] ? -1 : 1;
  }
  return 0;
}

/** -1, 0 or 1 as `a` is lower than, equal to or higher than `b`; build metadata is ignored. */
export function compare(a, b) {
  for (const part of ['major', 'minor', 'patch'])
    if (a[part] !== b[part]) return a[part] < b[part] ? -1 : 1;
  return comparePrerelease(a.prerelease, b.prerelease);
}

const isWild = (part) => part === undefined || part === 'x' || part === 'X' || part === '*';
const version = (major, minor, patch, prerelease = []) => ({ major, minor, patch, prerelease });
// The lowest version of a release, below all its prereleases: the upper bound npm writes as `-0`.
const floor = (major, minor, patch) => version(major, minor, patch, ['0']);
const ANY = [];
const NONE = [{ op: '<', version: floor(0, 0, 0) }];

/** A partial version: its numbers, undefined where wild, and the prerelease of a full one. */
function parsePartial(text, range) {
  const match = PARTIAL.exec(text);
  if (!match) throw new Error(`"${range}" is not a range npm's semver grammar reads ("${text}")`);
  const [major, minor, patch] = [match[1], match[2], match[3]].map((part) =>
    isWild(part) ? undefined : Number(part),
  );
  // npm refuses a number after a wild part, `1.x.3`.
  if (
    (major === undefined && match[2] && !isWild(match[2])) ||
    (minor === undefined && match[3] && !isWild(match[3]))
  )
    throw new Error(`"${range}" is not a range npm's semver grammar reads ("${text}")`);
  if (major === undefined) return { major };
  if (minor === undefined) return { major, minor };
  if (patch === undefined) return { major, minor, patch };
  return { major, minor, patch, prerelease: match[4] ? match[4].split('.') : [] };
}

/** One comparator of a set, as a list of plain `{ op, version }` comparators (empty: any). */
function desugar(token, range) {
  const [, op = '', rest] = COMPARATOR.exec(token);
  const { major, minor, patch, prerelease = [] } = parsePartial(rest, range);
  const full = patch !== undefined;
  if (op === '^') {
    if (major === undefined) return ANY;
    const from = version(major, minor ?? 0, patch ?? 0, prerelease);
    let to;
    if (major > 0 || minor === undefined) to = floor(major + 1, 0, 0);
    else if (minor > 0 || patch === undefined) to = floor(0, minor + 1, 0);
    else to = floor(0, 0, patch + 1);
    return [
      { op: '>=', version: from },
      { op: '<', version: to },
    ];
  }
  if (op === '~' || op === '~>') {
    if (major === undefined) return ANY;
    const from = version(major, minor ?? 0, patch ?? 0, prerelease);
    const to = minor === undefined ? floor(major + 1, 0, 0) : floor(major, minor + 1, 0);
    return [
      { op: '>=', version: from },
      { op: '<', version: to },
    ];
  }
  if (full) return [{ op: op || '=', version: version(major, minor, patch, prerelease) }];
  // A partial version with an operator or none.
  if (major === undefined) return op === '<' || op === '>' ? NONE : ANY;
  const low = version(major, minor ?? 0, 0);
  const next = minor === undefined ? floor(major + 1, 0, 0) : floor(major, minor + 1, 0);
  const nextRelease = minor === undefined ? version(major + 1, 0, 0) : version(major, minor + 1, 0);
  switch (op) {
    case '':
    case '=':
      return [
        { op: '>=', version: low },
        { op: '<', version: next },
      ];
    case '>':
      return [{ op: '>=', version: nextRelease }];
    case '>=':
      return [{ op: '>=', version: low }];
    case '<':
      return [{ op: '<', version: floor(low.major, low.minor, 0) }];
    case '<=':
      return [{ op: '<', version: next }];
  }
  throw new Error(`"${range}" has the operator "${op}", which npm's semver grammar does not have`);
}

/** A hyphen range's two ends as plain comparators. */
function hyphen(fromText, toText, range) {
  const out = [];
  const from = parsePartial(fromText, range);
  if (from.major !== undefined)
    out.push({
      op: '>=',
      version: version(from.major, from.minor ?? 0, from.patch ?? 0, from.prerelease ?? []),
    });
  const to = parsePartial(toText, range);
  if (to.major === undefined) return out;
  if (to.minor === undefined) out.push({ op: '<', version: floor(to.major + 1, 0, 0) });
  else if (to.patch === undefined) out.push({ op: '<', version: floor(to.major, to.minor + 1, 0) });
  else out.push({ op: '<=', version: version(to.major, to.minor, to.patch, to.prerelease) });
  return out;
}

/** A range as its comparator sets, each a list of plain comparators. */
export function parseRange(range) {
  if (typeof range !== 'string') throw new Error(`${JSON.stringify(range)} is not a range`);
  const sets = range.split('||').map((alternative) => {
    // npm reads `>= 1.2.3` as `>=1.2.3`.
    const set = alternative.trim().replace(/(~>|~|\^|>=|<=|>|<|=)\s+/g, '$1');
    if (set === '') return ANY;
    const ends = /^(\S+)\s+-\s+(\S+)$/.exec(set);
    if (ends) return hyphen(ends[1], ends[2], range);
    return set.split(/\s+/).flatMap((token) => desugar(token, range));
  });
  // npm reads `>=0.0.0` as any version, which also lets a prerelease past that bound.
  for (const set of sets)
    for (let i = set.length - 1; i >= 0; i--)
      if (set[i].op === '>=' && compare(set[i].version, version(0, 0, 0)) === 0) set.splice(i, 1);
  // As npm does, an alternative that takes any version makes the whole range that one, so
  // `* || 1.2.3-beta` takes no prerelease.
  return sets.length > 1 && sets.some((set) => !set.length) ? [ANY] : sets;
}

function test(v, { op, version: bound }) {
  const order = compare(v, bound);
  switch (op) {
    case '=':
      return order === 0;
    case '>':
      return order > 0;
    case '>=':
      return order >= 0;
    case '<':
      return order < 0;
    case '<=':
      return order <= 0;
  }
  return false;
}

/** Whether a version satisfies `range`, as npm decides it. Throws on either it cannot read. */
export function satisfies(versionText, range) {
  const v = parseVersion(versionText);
  return parseRange(range).some((set) => {
    if (!set.every((comparator) => test(v, comparator))) return false;
    if (!v.prerelease.length) return true;
    // npm's prerelease rule: some comparator of the set names a prerelease of the same release.
    // An upper bound `<x.y.z-0` never lets one through: every prerelease of x.y.z is above it.
    return set.some(
      ({ version: bound }) =>
        bound.prerelease.length > 0 &&
        bound.major === v.major &&
        bound.minor === v.minor &&
        bound.patch === v.patch,
    );
  });
}
