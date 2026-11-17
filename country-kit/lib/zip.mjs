/**
 * A ZIP writer that gives the same bytes for the same files, and a reader that refuses what an
 * unpacker must never be handed. Node built-ins only: the kit runs before anything is installed.
 *
 * writeZip makes the archive the base product publishes, so that its sha256 is a fact of its
 * contents alone: the entries sorted bytewise by name; files only, no directory entries; every
 * entry dated 1980-01-01 00:00, the first time the format can hold; deflated at level 9, or
 * stored when that is smaller; Unix mode 0644 for a regular file; the UTF-8 name flag; no extra
 * field, no comment, no data descriptor. No ZIP64: more than 65,535 entries or 4 GiB is refused.
 *
 * readZip reads such an archive, and refuses — before it inflates a byte — an entry that is not a
 * regular file (a symlink, a directory, a device), an absolute name, a drive letter, a backslash,
 * an empty, `.` or `..` segment, a name given twice (also when only its case differs, which a
 * Windows or macOS checkout cannot hold), an encrypted entry, a method other than stored or
 * deflate, more entries than `maxEntries`, and more bytes in all than `maxBytes`. Each entry's
 * inflated size and CRC-32 are checked against its header.
 *
 * This is the full reader, which the kit uses once set-up has handed over. templates/setup.mjs
 * carries a smaller copy, Node built-ins in one file, that reads only the pinned zip before the
 * kit is trusted (the comment on its reader says why).
 */
import zlib from 'node:zlib';

const LOCAL = 0x04034b50;
const CENTRAL = 0x02014b50;
const END = 0x06054b50;
const UTF8_FLAG = 0x0800;
const ENCRYPTED_FLAG = 0x0001;
const STORED = 0;
const DEFLATED = 8;
// 1980-01-01 00:00:00 in MS-DOS date and time.
const DOS_DATE = (0 << 9) | (1 << 5) | 1;
const DOS_TIME = 0;
const UNIX_HOST = 3;
const MADE_BY = (UNIX_HOST << 8) | 20;
const TYPE_MASK = 0o170000;
const REGULAR = 0o100000;
const FILE_MODE = REGULAR | 0o644;
const MAX_ENTRIES = 0xffff;
const MAX_32 = 0xffffffff;

export const DEFAULT_LIMITS = { maxEntries: 20_000, maxBytes: 512 * 1024 * 1024 };

/** The order of the entries in the archive: bytewise by their UTF-8 names. */
export const byName = (a, b) => Buffer.compare(Buffer.from(a.name), Buffer.from(b.name));

/** Why a name may not be unpacked, or undefined when it may. */
export function unsafeName(name) {
  if (!name) return 'is empty';
  if (name.includes('\\')) return 'has a backslash';
  if (name.includes('\0')) return 'has a NUL character';
  if (name.startsWith('/')) return 'is absolute';
  if (/^[A-Za-z]:/.test(name)) return 'names a drive';
  const segments = name.split('/');
  if (segments.some((segment) => segment === '')) return 'has an empty segment';
  if (segments.some((segment) => segment === '.' || segment === '..'))
    return 'has a "." or ".." segment';
  return undefined;
}

/**
 * The archive of `entries`, each `{ name, data }` with `data` a Buffer. The input's order does
 * not matter; a name given twice, or one readZip would refuse, is refused here too.
 */
export function writeZip(entries) {
  const sorted = [...entries].sort(byName);
  if (sorted.length > MAX_ENTRIES) throw new Error(`${sorted.length} entries is over 65,535`);
  const seen = new Set();
  for (const { name } of sorted) {
    const why = unsafeName(name);
    if (why) throw new Error(`the entry "${name}" ${why}`);
    const folded = name.toLowerCase();
    if (seen.has(folded)) throw new Error(`the entry "${name}" is given twice`);
    seen.add(folded);
  }

  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const { name, data } of sorted) {
    const nameBytes = Buffer.from(name, 'utf8');
    const deflated = zlib.deflateRawSync(data, { level: 9 });
    const method = deflated.length < data.length ? DEFLATED : STORED;
    const body = method === DEFLATED ? deflated : data;
    const crc = zlib.crc32(data);
    if (data.length > MAX_32 || offset > MAX_32)
      throw new Error(`the entry "${name}" does not fit an archive without ZIP64`);
    const version = method === DEFLATED ? 20 : 10;

    const local = Buffer.alloc(30);
    local.writeUInt32LE(LOCAL, 0);
    local.writeUInt16LE(version, 4);
    local.writeUInt16LE(UTF8_FLAG, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(DOS_TIME, 10);
    local.writeUInt16LE(DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBytes, body);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(CENTRAL, 0);
    central.writeUInt16LE(MADE_BY, 4);
    central.writeUInt16LE(version, 6);
    central.writeUInt16LE(UTF8_FLAG, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt16LE(DOS_TIME, 12);
    central.writeUInt16LE(DOS_DATE, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE((FILE_MODE << 16) >>> 0, 38);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);

    offset += local.length + nameBytes.length + body.length;
  }
  const directory = Buffer.concat(centrals);
  if (offset > MAX_32 || directory.length > MAX_32)
    throw new Error('the archive does not fit without ZIP64');
  const end = Buffer.alloc(22);
  end.writeUInt32LE(END, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(sorted.length, 8);
  end.writeUInt16LE(sorted.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...locals, directory, end]);
}

/** The end-of-central-directory record: the last one, whose comment reaches the archive's end. */
function findEnd(buffer) {
  const lowest = Math.max(0, buffer.length - 22 - 0xffff);
  for (let at = buffer.length - 22; at >= lowest; at--)
    if (buffer.readUInt32LE(at) === END && at + 22 + buffer.readUInt16LE(at + 20) === buffer.length)
      return at;
  throw new Error('is not a ZIP archive: no end-of-central-directory record');
}

/**
 * The entries of an archive, `{ name, data }` in the archive's order, every one checked; throws
 * on the first that breaks a rule. `maxEntries` and `maxBytes` bound what it will hold.
 */
export function readZip(buffer, { maxEntries, maxBytes } = DEFAULT_LIMITS) {
  maxEntries ??= DEFAULT_LIMITS.maxEntries;
  maxBytes ??= DEFAULT_LIMITS.maxBytes;
  if (buffer.length < 22) throw new Error('is not a ZIP archive: too short');
  const end = findEnd(buffer);
  const disk = buffer.readUInt16LE(end + 4);
  const directoryDisk = buffer.readUInt16LE(end + 6);
  const onDisk = buffer.readUInt16LE(end + 8);
  const count = buffer.readUInt16LE(end + 10);
  const size = buffer.readUInt32LE(end + 12);
  const start = buffer.readUInt32LE(end + 16);
  if (disk !== 0 || directoryDisk !== 0 || onDisk !== count)
    throw new Error('spans several disks, which is not read');
  if (count === 0xffff || size === MAX_32 || start === MAX_32)
    throw new Error('is a ZIP64 archive, which is not read');
  if (count > maxEntries) throw new Error(`has ${count} entries, over the ${maxEntries} allowed`);
  if (start + size > end) throw new Error('has a central directory that overlaps its end record');

  const headers = [];
  const seen = new Set();
  let total = 0;
  let at = start;
  for (let i = 0; i < count; i++) {
    if (at + 46 > end || buffer.readUInt32LE(at) !== CENTRAL)
      throw new Error(`entry ${i + 1}: no central directory header where one should be`);
    const madeBy = buffer.readUInt16LE(at + 4);
    const flags = buffer.readUInt16LE(at + 8);
    const method = buffer.readUInt16LE(at + 10);
    const crc = buffer.readUInt32LE(at + 16);
    const compressed = buffer.readUInt32LE(at + 20);
    const inflated = buffer.readUInt32LE(at + 24);
    const nameLength = buffer.readUInt16LE(at + 28);
    const extraLength = buffer.readUInt16LE(at + 30);
    const commentLength = buffer.readUInt16LE(at + 32);
    const external = buffer.readUInt32LE(at + 38);
    const offset = buffer.readUInt32LE(at + 42);
    let name;
    try {
      name = new TextDecoder('utf-8', { fatal: true }).decode(
        buffer.subarray(at + 46, at + 46 + nameLength),
      );
    } catch {
      throw new Error(`entry ${i + 1}: its name is not UTF-8`);
    }
    const fail = (why) => {
      throw new Error(`the entry "${name}" ${why}`);
    };
    const why = unsafeName(name);
    if (why) fail(why);
    if (flags & ENCRYPTED_FLAG) fail('is encrypted');
    if (method !== STORED && method !== DEFLATED) fail(`uses compression method ${method}`);
    if (madeBy >> 8 === UNIX_HOST) {
      const type = (external >>> 16) & TYPE_MASK;
      if (type !== 0 && type !== REGULAR)
        fail(type === 0o120000 ? 'is a symbolic link' : 'is not a regular file');
    }
    // MS-DOS attributes: 0x10 is a directory, whatever the host.
    if (external & 0x10 || name.endsWith('/')) fail('is a directory');
    const folded = name.toLowerCase();
    if (seen.has(folded)) fail('is given twice (names are compared without case)');
    seen.add(folded);
    total += inflated;
    if (total > maxBytes) fail(`takes the archive over the ${maxBytes} bytes allowed`);
    headers.push({ name, method, crc, compressed, inflated, offset });
    at += 46 + nameLength + extraLength + commentLength;
  }

  return headers.map(({ name, method, crc, compressed, inflated, offset }) => {
    const fail = (why) => {
      throw new Error(`the entry "${name}" ${why}`);
    };
    if (offset + 30 > start || buffer.readUInt32LE(offset) !== LOCAL)
      fail('has no local header where its central header says');
    const localNameLength = buffer.readUInt16LE(offset + 26);
    const localExtraLength = buffer.readUInt16LE(offset + 28);
    if (buffer.subarray(offset + 30, offset + 30 + localNameLength).toString('utf8') !== name)
      fail('has another name in its local header');
    const from = offset + 30 + localNameLength + localExtraLength;
    if (from + compressed > start) fail('runs into the central directory');
    const body = buffer.subarray(from, from + compressed);
    let data;
    if (method === STORED) data = Buffer.from(body);
    else
      try {
        data = zlib.inflateRawSync(body, { maxOutputLength: Math.max(inflated, 1) });
      } catch (error) {
        fail(`does not inflate: ${error.message}`);
      }
    if (data.length !== inflated) fail(`inflates to ${data.length} bytes, not ${inflated}`);
    if (zlib.crc32(data) !== crc) fail('fails its CRC-32');
    return { name, data };
  });
}
