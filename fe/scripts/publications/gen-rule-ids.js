/*
 * Generates the invariant rule-id list the backend's manifest test binds against.
 *
 *   node gen-rule-ids.js                     # check only, non-zero if stale
 *   node gen-rule-ids.js --write             # regenerate
 *   node gen-rule-ids.js --out <path>        # read or write that file instead
 *   NIORD_REPO=<repo root> node gen-rule-ids.js   # same, by repository root
 *
 * The default target is the backend module of this repository (`be/niord`,
 * three levels up from this script), which is right for the ordinary mono-repo
 * layout and wrong for every other one: a worktree of the backend alone, a CI
 * layout. Both overrides exist so the check and the write can be pointed at the
 * tree the matching backend branch is actually in.
 *
 * The backend build cannot read DATA-MODEL.md -- it lives in fe/, outside the
 * Maven reactor -- so the ids are exported into it as a ~400 byte file instead
 * of mirroring the whole specification folder. The header carries the SHA-256
 * of the section-8 slice the ids came from, which is what lets the frontend
 * side detect that a rule was added without regenerating. Drift is then
 * reported where the edit happened, rather than at the next backend build.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const HERE = __dirname;
const MODEL = path.join(HERE, '..', '..', 'docs', 'publications', 'DATA-MODEL.md');
const EXPORT_PATH = path.join('be', 'niord', 'src', 'test', 'resources', 'rule-ids.txt');

/** `--out <path>`, else `$NIORD_REPO/<export path>`, else this repository's root. */
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
const start = lines.findIndex((l) => /^##\s*8[.\s]/.test(l));
const end = lines.findIndex((l, i) => i > start && /^##\s*9[.\s]/.test(l));
if (start < 0 || end < 0) {
  console.error('could not locate section 8 in DATA-MODEL.md');
  process.exit(2);
}

const slice = lines.slice(start, end).join('\n');
const sliceHash = crypto.createHash('sha256').update(slice, 'utf8').digest('hex');

const FAMILIES = ['S', 'C', 'I', 'D', 'M', 'O', 'X', 'RI'];
const ids = [...new Set(slice.match(/\b(?:S|C|I|D|M|O|X|RI)-\d{1,2}\b/g) || [])];
ids.sort((a, b) => {
  const [fa, na] = a.split('-');
  const [fb, nb] = b.split('-');
  return fa === fb ? Number(na) - Number(nb) : FAMILIES.indexOf(fa) - FAMILIES.indexOf(fb);
});

const body =
  [
    '# Generated from DATA-MODEL.md section 8 by gen-rule-ids.js. Do not hand-edit.',
    '# Every id here must be bound to a test, or the build fails.',
    '# source-sha256: ' + sliceHash,
    ...ids,
  ].join('\n') + '\n';

if (process.argv.includes('--write')) {
  fs.mkdirSync(path.dirname(TARGET), { recursive: true });
  fs.writeFileSync(TARGET, body, 'utf8');
  console.log('wrote ' + ids.length + ' rule ids to ' + TARGET);
  process.exit(0);
}

if (!fs.existsSync(TARGET)) {
  console.error('rule-ids.txt does not exist at ' + TARGET + ' -- run with --write');
  process.exit(1);
}
const committed = fs.readFileSync(TARGET, 'utf8');
if (committed !== body) {
  const was = (committed.match(/^# source-sha256: (\w+)$/m) || [])[1];
  console.error('BLOCKER  rule-ids.txt is stale.');
  console.error('  section 8 now hashes to ' + sliceHash);
  console.error('  the committed list was generated from ' + (was || '(no header)'));
  console.error(
    '  ids now: ' +
      ids.length +
      '; committed: ' +
      committed.split('\n').filter((l) => l && !l.startsWith('#')).length,
  );
  console.error('  fix: node gen-rule-ids.js --write, then commit both repos together.');
  process.exit(1);
}
console.log(
  'rule-ids.txt is current (' + ids.length + ' ids, section 8 @ ' + sliceHash.slice(0, 12) + ').',
);
