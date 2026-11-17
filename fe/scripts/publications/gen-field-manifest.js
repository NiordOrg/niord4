/*
 * Extracts the entity field manifest from DATA-MODEL.md sections 3.1 to 3.10.
 *
 *   node gen-field-manifest.js                    # check only, non-zero if stale
 *   node gen-field-manifest.js --write            # regenerate
 *   node gen-field-manifest.js --out <path>       # read or write that file instead
 *   NIORD_REPO=<repo root> node gen-field-manifest.js   # same, by repository root
 *
 * The backend has a reflection test that walks the entity classes against this
 * manifest, so a column dropped from an entity is a red build rather than
 * something a reviewer has to notice. Same shape as the rule-id export, and for
 * the same reason: the specification lives in fe/, outside the Maven reactor,
 * so the exported facts travel to be/niord and the freshness check stays here,
 * where the edit happens.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const HERE = __dirname;
const MODEL = path.join(HERE, '..', '..', 'docs', 'publications', 'DATA-MODEL.md');
const EXPORT_PATH = path.join('be', 'niord', 'src', 'test', 'resources', 'entity-fields.json');

/**
 * `--out <path>`, else `$NIORD_REPO/<export path>`, else this repository's root
 * (three levels up) — the same two overrides the rule-id export takes, and for
 * the same reason: the backend branch this specification matches is not always
 * in the tree this script sits in.
 */
function target() {
  const i = process.argv.indexOf('--out');
  if (i >= 0) {
    if (!process.argv[i + 1]) {
      console.error('--out needs a path');
      process.exit(2);
    }
    return path.resolve(process.argv[i + 1]);
  }
  if (process.env.NIORD_REPO) {
    return path.join(path.resolve(process.env.NIORD_REPO), EXPORT_PATH);
  }
  return path.join(HERE, '..', '..', '..', EXPORT_PATH);
}

const TARGET = target();

const lines = fs.readFileSync(MODEL, 'utf8').split(/\r?\n/);

const start = lines.findIndex((l) => /^###\s*3\.1\s/.test(l));
const end = lines.findIndex((l, i) => i > start && /^##\s*4[.\s]/.test(l));
if (start < 0 || end < 0) {
  console.error('could not locate sections 3.1-3.10 in DATA-MODEL.md');
  process.exit(2);
}

const slice = lines.slice(start, end);
const sliceHash = crypto.createHash('sha256').update(slice.join('\n'), 'utf8').digest('hex');

const entities = [];
let current = null;

for (const line of slice) {
  const heading = line.match(/^###\s*3\.\d+\s+`?(\w+)`?/);
  if (heading) {
    current = { entity: heading[1], existing: /existing entity/i.test(line), fields: [] };
    entities.push(current);
    continue;
  }
  if (!current || !line.startsWith('| `')) continue;

  const cells = line.split('|').map((c) => c.trim());
  if (cells.length < 5) continue;

  const name = (cells[1].match(/^`([\w.]+)`$/) || [])[1];
  if (!name) continue;

  const ddl = cells[3].replace(/`/g, '');
  // The nullable column is the fourth cell: "yes" means the column is nullable.
  // Field tables carry an exact yes/no in the Nullable column. Nested legality
  // tables inside a section have prose there, which is how they are told apart --
  // without this they are scraped as though their rows were fields.
  const nullableCell = (cells[4] || '').toLowerCase().split('*').join('').trim();
  if (nullableCell !== 'yes' && nullableCell !== 'no') continue;
  const nullable = nullableCell === 'yes';

  current.fields.push({
    name,
    ddl,
    nullable,
    notNull: /NOT NULL/i.test(ddl),
    unique: /UNIQUE/i.test(ddl),
  });
}

const body =
  JSON.stringify(
    {
      note: 'Generated from DATA-MODEL.md sections 3.1-3.10 by gen-field-manifest.js. Do not hand-edit.',
      sourceSha256: sliceHash,
      entities: entities.filter((e) => e.fields.length > 0),
    },
    null,
    2,
  ) + '\n';

if (process.argv.includes('--write')) {
  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  fs.writeFileSync(TARGET, body, 'utf8');
  const total = entities.reduce((n, e) => n + e.fields.length, 0);
  console.log(
    'wrote ' +
      entities.filter((e) => e.fields.length).length +
      ' entities, ' +
      total +
      ' fields to ' +
      TARGET,
  );
  process.exit(0);
}

if (!fs.existsSync(TARGET)) {
  console.error('entity-fields.json does not exist -- run with --write');
  process.exit(1);
}
if (fs.readFileSync(TARGET, 'utf8') !== body) {
  console.error('BLOCKER  entity-fields.json is stale.');
  console.error('  sections 3.1-3.10 now hash to ' + sliceHash);
  console.error('  fix: node gen-field-manifest.js --write, then commit both repos together.');
  process.exit(1);
}
console.log(
  'entity-fields.json is current (' +
    entities.reduce((n, e) => n + e.fields.length, 0) +
    ' fields).',
);
