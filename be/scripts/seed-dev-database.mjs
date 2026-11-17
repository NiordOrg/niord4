/*
 * Seeds a developer database with the real message corpus, pulled from the
 * public API.
 *
 *     node scripts/seed-dev-database.mjs [--container niord-db] [--database niord]
 *                                        [--user niord] [--dry-run]
 *
 * Why this exists: the tests that matter most for the resolver -- the
 * differential test in particular -- need real messages narrowed by real SQL.
 * Without them the suite can only be exercised against synthetic input, and a
 * developer machine has no data of its own. Getting a database dump means
 * waiting on someone; the corpus turns out to be publicly readable, so this
 * waits on nobody.
 *
 * It is a DEVELOPER tool. It writes to a local database, never to a deployed
 * one, and it is never run by CI. The target is the `niord-db` container from
 * be/docker-compose.yml (PostGIS 18, user/password/database all `niord`), i.e.
 * the database `start-local.sh` brings up, whose schema Liquibase has built and
 * which `start-local.sh` bootstrapped. The six DMA message series come from the
 * sample, which `./start-local.sh --sample` loads into its own database
 * niord_sample; on any other database this script inserts them. It refuses
 * `--database niord_sample` and a country launcher's `niord_sample_<cc>`: real
 * messages never go into a sample database (the safety rules of
 * docs/fe-country-split-plan.md P9). The integration suite
 * does NOT use this database -- Testcontainers gives every IT run its own.
 *
 * The one trap it has to work around: `status` is silently ignored unless
 * `domain` is also supplied. Ask for CANCELLED without a domain and the API
 * cheerfully returns the default published corpus instead -- 277 rows that look
 * entirely plausible. Every fetch here therefore sends both, and asserts the
 * result actually carries the status it asked for.
 *
 * PostgreSQL is stricter than the MySQL this was written against, and that is
 * an improvement rather than an obstacle: a row missing a NOT NULL column (a
 * message with no type, or with no message series) aborts the run under
 * ON_ERROR_STOP instead of being coerced into a half-row that only looks right.
 */
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const HOST = 'https://niord.t-dma.dk';
/* Every domain that carries a distinct corpus. Seeding only niord-nm was a
 * mistake worth recording: it silently excludes dma-fa, which is where the
 * firing-areas fixtures live AND where shortIds actually collide -- 357
 * messages under 331 distinct shortIds. A domain name the backend does not
 * recognise does not error either; it returns the 277-row default corpus, so
 * each domain is asserted to bring its own series. */
const DOMAINS = [
  { domain: 'niord-nm', expectSeries: 'dma-nm' },
  { domain: 'niord-fa', expectSeries: 'dma-fa' },
  { domain: 'niord-nw', expectSeries: 'dma-nw' },
  { domain: 'niord-almanac', expectSeries: 'dma-nm-almanac' },
];
const PAGE_SIZE = 1000;

/* The three PUBLIC statuses, and only those.
 *
 * DRAFT and VERIFIED are not readable anonymously: asking for them returns the
 * published default instead, silently, which the status guard below catches.
 * That is a permission boundary rather than a defect, and it costs nothing here
 * -- membership only ever considers Status.isPublic(), so a draft could not be a
 * member anyway. A test that needs to prove a draft is EXCLUDED must therefore
 * author its own row; it cannot seed one.
 *
 * DELETED is absent for a different reason: it is not part of any membership
 * question and only adds noise.
 */
const STATUSES = ['PUBLISHED', 'EXPIRED', 'CANCELLED'];

const arg = (name, fallback) => {
  const i = process.argv.indexOf('--' + name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const DB_CONTAINER = arg('container', 'niord-db');
const DATABASE = arg('database', 'niord');
if (/^niord_sample(_[a-z]{2})?$/.test(DATABASE)) {
  console.error(
    `${DATABASE} is a sample database (niord_sample, or a country launcher's niord_sample_<cc>), whose messages `
    + 'are written for the sample: real messages never go into it (docs/fe-country-split-plan.md P9). '
    + 'Use another --database.',
  );
  process.exit(2);
}
const DB_USER = arg('user', 'niord');
const DRY_RUN = process.argv.includes('--dry-run');

function getJson(url) {
  const body = execFileSync('curl.exe', ['-sS', '--fail', '--retry', '3', url], {
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
  });
  try {
    return JSON.parse(body);
  } catch {
    throw new Error(`not JSON from ${url}: ${body.slice(0, 200)}`);
  }
}

/** Fetches every message of one status, paging until the corpus is exhausted. */
function fetchStatus(domain, status) {
  const out = [];
  let page = 0;
  let total = null;

  for (;;) {
    const url = `${HOST}/rest/messages/search?domain=${domain}&status=${status}`
      + `&maxSize=${PAGE_SIZE}&page=${page}`;
    const res = getJson(url);
    const data = res.data ?? [];
    if (total === null) total = res.total;

    // The guard. A dropped status filter returns the default corpus, which
    // looks like a perfectly ordinary answer until something downstream is
    // quietly wrong.
    const wrong = data.filter((m) => m.status !== status);
    if (wrong.length) {
      throw new Error(
        `asked for ${status} and got ${wrong.length} row(s) of other statuses `
        + `(${[...new Set(wrong.map((m) => m.status))].join(', ')}) -- the status filter was dropped`);
    }

    out.push(...data);
    if (data.length === 0 || out.length >= total) break;
    page++;
    if (page > 100) throw new Error(`${status}: paging did not terminate`);
  }

  if (out.length !== total) {
    console.warn(`  ! ${status}: collected ${out.length} of a declared ${total}`);
  }
  return out;
}

const sql = (v) => (v === null || v === undefined ? 'NULL' : `'${String(v).replace(/'/g, "''")}'`);
// to_timestamp() yields a timestamptz; the columns are timestamp(6) WITHOUT time
// zone, so the value is brought back to a naive UTC wall clock explicitly rather
// than through whatever the session zone happens to be.
const ts = (ms) => (ms === null || ms === undefined
  ? 'NULL'
  : `(to_timestamp(${Math.floor(ms / 1000)}) AT TIME ZONE 'UTC')`);

console.log(`seeding ${DATABASE} in container ${DB_CONTAINER} from ${HOST}`);
console.log();

const messages = [];
for (const { domain, expectSeries } of DOMAINS) {
  process.stdout.write(`  ${domain.padEnd(16)} `);
  const before = messages.length;
  for (const status of STATUSES) messages.push(...fetchStatus(domain, status));
  const got = messages.slice(before);

  // An unrecognised domain returns the default corpus rather than erroring, so
  // check the rows really came from the series this domain is supposed to hold.
  const series = new Set(got.map((m) => m.messageSeries && m.messageSeries.seriesId));
  if (!series.has(expectSeries)) {
    throw new Error(`${domain}: expected ${expectSeries}, got [${[...series].join(', ')}] -- domain not recognised`);
  }
  console.log(`${String(got.length).padStart(6)} messages  (${[...series].join(', ')})`);
}

// uid is the key, never shortId -- shortId is not unique and nothing prevents reuse.
const byUid = new Map();
for (const m of messages) byUid.set(m.id, m);
const unique = [...byUid.values()].sort((a, b) => (a.id < b.id ? -1 : 1));

const seriesIds = [...new Set(unique.map((m) => m.messageSeries?.seriesId).filter(Boolean))].sort();

console.log();
console.log(`  ${unique.length} distinct messages, ${seriesIds.length} message series`);
console.log(`  distinct shortIds: ${new Set(unique.map((m) => m.shortId)).size}`);

/* Ids are assigned from a high offset so they cannot collide with anything a
 * test or the running application inserts through Hibernate. Every entity draws
 * from its own sequence -- "Message_SEQ", "MessageSeries_SEQ" -- each starting
 * at 1 with INCREMENT BY 50, so the block below is far out of their reach. If a
 * sequence is ever advanced past ID_BASE (by a restore, say), move the block
 * rather than letting the two meet. */
const ID_BASE = 900_000;

const lines = [];
lines.push("SET TIME ZONE 'UTC';");
// No FOREIGN_KEY_CHECKS equivalent is needed: the series are inserted before the
// messages that point at them, and the deletes below go the other way round.
// Nothing outside this block references these rows.
lines.push(`DELETE FROM public."Message" WHERE id >= ${ID_BASE};`);
lines.push(`DELETE FROM public."MessageSeries" WHERE id >= ${ID_BASE};`);

/* A database the sample was loaded into carries the six DMA message series
 * at low ids, so a series is inserted only where it is missing
 * and the messages resolve their series by seriesId rather than by a row id
 * this script picked. Inserting a second row for the same seriesId would not
 * fail -- there is no unique constraint on it -- it would merely split the
 * corpus across two series that look identical. */
let nextSeriesId = ID_BASE;
for (const s of seriesIds) {
  const mainType = unique.find((m) => m.messageSeries?.seriesId === s)?.mainType ?? 'NM';
  lines.push(
    `INSERT INTO public."MessageSeries" (id, version, "seriesId", "mainType", "numberSequenceType") `
    + `SELECT ${nextSeriesId++}, 0, ${sql(s)}, ${sql(mainType)}, 0 `
    + `WHERE NOT EXISTS (SELECT 1 FROM public."MessageSeries" WHERE "seriesId" = ${sql(s)});`);
}

let nextId = ID_BASE + seriesIds.length;
for (const m of unique) {
  const id = nextId++;
  const seriesRef = m.messageSeries?.seriesId
    ? `(SELECT id FROM public."MessageSeries" WHERE "seriesId" = ${sql(m.messageSeries.seriesId)})`
    : 'NULL';
  lines.push(
    `INSERT INTO public."Message" (id, version, revision, "autoTitle", "hasGeometry", uid, "repoPath", "shortId", `
    + `number, year, "messageSeries_id", "publishDateFrom", "publishDateTo", status, type, "mainType") VALUES (`
    + [
      id, 0, 1, 'false', 'false',
      sql(m.id),
      sql('messages/seed/' + m.id),
      sql(m.shortId ?? null),
      m.number ?? 'NULL',
      m.year ?? 'NULL',
      seriesRef,
      ts(m.publishDateFrom),
      ts(m.publishDateTo),
      sql(m.status),
      sql(m.type ?? null),
      sql(m.mainType ?? null),
    ].join(', ') + ');');
}

const scratch = join(tmpdir(), 'niord-seed');
mkdirSync(scratch, { recursive: true });
const file = join(scratch, 'seed.sql');
writeFileSync(file, lines.join('\n') + '\n', 'utf8');
console.log(`  ${lines.length} statements written to ${file}`);

if (DRY_RUN) {
  console.log('\n--dry-run: nothing was applied.');
  process.exit(0);
}

console.log('\n  applying...');
// ON_ERROR_STOP, or psql reports success after skipping every statement that failed.
execFileSync('docker', ['exec', '-i', DB_CONTAINER,
  'psql', '-v', 'ON_ERROR_STOP=1', '-q', '-U', DB_USER, '-d', DATABASE], {
  input: lines.join('\n') + '\n',
  stdio: ['pipe', 'inherit', 'pipe'],
  maxBuffer: 256 * 1024 * 1024,
});

const count = execFileSync('docker',
  ['exec', DB_CONTAINER, 'psql', '-U', DB_USER, '-d', DATABASE, '-t', '-A', '-c',
    'SELECT (SELECT COUNT(*) FROM public."Message") || \' messages, \' '
    + '|| (SELECT COUNT(*) FROM public."MessageSeries") || \' series\';'],
  { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
console.log(`  done: ${count}`);
