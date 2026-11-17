#!/usr/bin/env node
/**
 * spec-check — mechanical consistency checker for the publications specification.
 *
 * DATA-MODEL.md and API-CONTRACT.md must agree. They are large prose documents, and every
 * cross-document defect found so far has been mechanically detectable: the same identifier
 * described two ways, an error code with two HTTP statuses, two endpoints with the same path
 * template, a section reference pointing at nothing.
 *
 * Design bias: PRECISION over recall. A noisy checker gets ignored, and an ignored checker is
 * worse than none. Every rule below is scoped to a window around the identifier rather than the
 * whole line, and skips prose that is *discussing* a ruling rather than *stating* one. It will
 * miss things. What it reports should be real.
 *
 * A clean run is not proof of correctness — it is proof the two documents do not contradict each
 * other in the ways that have actually bitten this project.
 *
 * Usage:  node spec-check.js [--verbose]
 * Exit:   0 = no blockers, 1 = at least one blocker
 *
 * Checks 7 and 8 run the two export scripts, which read this repository's backend module
 * (`be/niord`) unless `NIORD_REPO` names another repository root. Set it when the matching backend
 * branch lives in a second checkout, or those two checks compare this specification against an
 * unrelated tree.
 */

const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, '..', '..', 'docs', 'publications');
const FILES = { model: 'DATA-MODEL.md', contract: 'API-CONTRACT.md' };
const VERBOSE = process.argv.includes('--verbose');

const docs = {};
for (const [key, name] of Object.entries(FILES)) {
  const p = path.join(DIR, name);
  if (!fs.existsSync(p)) {
    console.error(`missing ${name}`);
    process.exit(2);
  }
  const text = fs.readFileSync(p, 'utf8');
  docs[key] = { name, key, text, lines: text.split('\n') };
}

const findings = [];
const add = (severity, check, message, detail) =>
  findings.push({ severity, check, message, detail: detail || [] });

/**
 * Prose that talks ABOUT a ruling rather than stating one. These lines record history
 * ("originally ruled X, withdrawn"), summarise a register, or are struck through — and they
 * legitimately contain both polarities. They are the dominant false-positive source.
 */
const META =
  /\b(withdrawn|retired|superseded|originally|previously|used to|no longer|resolved|ratified|CLOSED|outstanding|contradiction|disagree|ruling|old reading|the other way|instead of|rather than|was ruled|now settled|open question)\b|~~/i;

/** Identifiers too generic to reason about. */
const STOP = new Set([
  'null',
  'type',
  'name',
  'value',
  'status',
  'source',
  'kind',
  'size',
  'link',
  'lang',
  'count',
  'data',
  'code',
  'true',
  'false',
  'text',
  'date',
  'page',
  'from',
  'this',
  'that',
  'with',
  'when',
  'each',
  'both',
  // generic English nouns that appear in backticks but are not field names
  'issue',
  'series',
  'member',
  'publication',
  'message',
  'document',
  'section',
  'contract',
  'model',
  'admin',
  'editor',
  'user',
  'file',
  'files',
  'entity',
  'table',
  'column',
  'field',
]);

const WINDOW = 140;

/** Occurrences of `ident` inside backticks, with a text window centred on each. */
function occurrences(ident) {
  const esc = ident.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const tick = new RegExp('`[^`]*\\b' + esc + '\\b[^`]*`', 'g');
  const out = [];
  for (const d of Object.values(docs)) {
    d.lines.forEach((text, i) => {
      if (META.test(text)) return; // skip meta/history prose entirely
      for (const m of text.matchAll(tick)) {
        const start = Math.max(0, m.index - WINDOW);
        out.push({
          doc: d.name,
          key: d.key,
          line: i + 1,
          window: text.slice(start, m.index + m[0].length + WINDOW),
          text: text.trim(),
        });
      }
    });
  }
  return out;
}

function allIdents() {
  const s = new Set();
  for (const d of Object.values(docs))
    for (const m of d.text.matchAll(/`([a-z][A-Za-z0-9]{3,})`/g)) if (!STOP.has(m[1])) s.add(m[1]);
  return s;
}

// ---------------------------------------------------------------------------
// 1. Column existence — "is a column" vs "is not a column", scoped to the identifier.
// ---------------------------------------------------------------------------
{
  const NEG =
    /\b(not a column|never a column|no column|not persisted|deliberately absent|derived, never stored|derived and not stored)\b/i;
  const POS =
    /\b(is a real column|persisted as (?:a |two )?real column|first-class column|stored column|real columns?)\b/i;
  for (const id of allIdents()) {
    const occ = occurrences(id);
    const neg = occ.filter((o) => NEG.test(o.window));
    const pos = occ.filter((o) => POS.test(o.window));
    if (!neg.length || !pos.length) continue;
    const negLines = new Set(neg.map((o) => `${o.doc}:${o.line}`));
    const posLines = new Set(pos.map((o) => `${o.doc}:${o.line}`));
    // If every positive statement shares a line with a negative one, it is a single nuanced rule.
    if ([...posLines].every((l) => negLines.has(l))) continue;
    add('BLOCKER', 'column-existence', `\`${id}\` is stated as a column and as not-a-column`, [
      ...pos.slice(0, 2).map((o) => `COLUMN     ${o.doc}:${o.line}  ${o.text.slice(0, 130)}`),
      ...neg.slice(0, 2).map((o) => `NOT COLUMN ${o.doc}:${o.line}  ${o.text.slice(0, 130)}`),
    ]);
  }
}

// ---------------------------------------------------------------------------
// 2. Error code -> HTTP status. One code, one status, across both documents.
// ---------------------------------------------------------------------------
{
  const byCode = new Map();
  const RESERVED =
    /^(HTTP|JSON|UTF|SQL|API|URL|UUID|TEXT|NULL|TRUE|FALSE|GET|PUT|POST|DELETE|PATCH|NOT|AND|OR)$/;
  for (const d of Object.values(docs)) {
    d.lines.forEach((text, i) => {
      if (META.test(text)) return;
      const pats = [
        /\b([45]\d{2})\s+`([A-Z][A-Z0-9_]{4,})`/g,
        /`([A-Z][A-Z0-9_]{4,})`\s*(?:->|→)\s*\**([45]\d{2})\b/g,
      ];
      for (const [pi, re] of pats.entries()) {
        for (const m of text.matchAll(re)) {
          const status = pi === 0 ? m[1] : m[2];
          const code = pi === 0 ? m[2] : m[1];
          if (RESERVED.test(code)) continue;
          if (!byCode.has(code)) byCode.set(code, new Map());
          const s = byCode.get(code);
          if (!s.has(status)) s.set(status, []);
          s.get(status).push(`${d.name}:${i + 1}`);
        }
      }
    });
  }
  for (const [code, statuses] of byCode) {
    if (statuses.size > 1) {
      add(
        'BLOCKER',
        'error-status',
        `\`${code}\` is mapped to ${[...statuses.keys()].sort().join(' and ')}`,
        [...statuses.entries()].map(([s, w]) => `${s}  ${w.slice(0, 3).join(', ')}`),
      );
    }
  }
}

// ---------------------------------------------------------------------------
// 3. Duplicate JAX-RS path templates — two routes differing only in the parameter NAME collide.
// ---------------------------------------------------------------------------
{
  const shapes = new Map();
  const re = /`((?:GET|POST|PUT|PATCH|DELETE)\s+\/[^`]+)`/g;
  for (const d of Object.values(docs)) {
    d.lines.forEach((text, i) => {
      if (META.test(text)) return;
      for (const m of text.matchAll(re)) {
        const raw = m[1].trim();
        const [verb, ...rest] = raw.split(/\s+/);
        const p = rest
          .join(' ')
          .split('?')[0]
          .replace(/\{[^}]+\}/g, '{}')
          .replace(/\/+$/, '');
        const key = `${verb} ${p}`;
        // A variant is the path WITH its parameter names, and without the query
        // string: a route written once bare and once with `?lang=` is the same
        // route documented twice, which is house style rather than a defect. Two
        // spellings that differ in a parameter NAME are what this check is for,
        // and they still differ here.
        const variant = `${verb} ${rest.join(' ').split('?')[0].replace(/\/+$/, '')}`;
        if (!shapes.has(key)) shapes.set(key, new Map());
        if (!shapes.get(key).has(variant)) shapes.get(key).set(variant, `${d.name}:${i + 1}`);
      }
    });
  }
  for (const [shape, variants] of shapes) {
    if (variants.size > 1)
      add(
        'BLOCKER',
        'path-collision',
        `${variants.size} routes share the template \`${shape}\``,
        [...variants.entries()].map(([r, w]) => `${r}  [${w}]`),
      );
  }
}

// ---------------------------------------------------------------------------
// 4. Field-table registry.
//
//    Free-text nullability matching does not work here: `X IS NOT NULL ⟺ Y` is a
//    CONDITIONAL INVARIANT, not a declaration, and a column is routinely nullable in DDL
//    with a conditional rule layered on top. Both are correct and they look contradictory
//    to a naive matcher.
//
//    So parse the declarations instead. The field tables are shaped
//      | `name` | java type | `name DBTYPE` | yes/no | default | validation | semantics |
//    and the same column declared twice with different nullability or a different DB type
//    is precisely the aliveAtCutoff / snapshotIntervalFrom bug class.
// ---------------------------------------------------------------------------
{
  const reg = new Map();
  for (const d of Object.values(docs)) {
    // A column name is only unique WITHIN its table: `created` on an audit entry and `created`
    // on a versioned entity are different columns. Scope every row to the nearest heading above
    // it that names an entity, so the comparison is like-for-like.
    let owner = '(top)';
    d.lines.forEach((text, i) => {
      const h = text.match(/^#{2,6}\s+(?:§?\s*[0-9.]+\s+)?(.+?)\s*$/);
      if (h) {
        const ent = h[1].match(/\b(Publication[A-Za-z]*|Issue[A-Za-z]*|[A-Z][A-Za-z]*Desc)\b/);
        owner = ent ? ent[1] : h[1].slice(0, 40);
      }
      if (!text.startsWith('|') || META.test(text)) return;
      const cells = text.split('|').map((c) => c.trim());
      if (cells.length < 6) return;
      const nameM = cells[1] && cells[1].match(/^`([a-zA-Z][A-Za-z0-9_]*)`$/);
      if (!nameM) return;
      const nullCell = cells.find((c) => /^(yes|no)$/i.test(c));
      if (!nullCell) return;
      const ddl = cells.find((c) => /^`[a-zA-Z][A-Za-z0-9_]*\s+[A-Z]/.test(c));
      const dbType = ddl
        ? ddl
            .replace(/`/g, '')
            .split(/\s+/)
            .slice(1)
            .join(' ')
            .replace(/\s+(NOT NULL|DEFAULT\b.*)$/i, '')
            .trim()
        : null;
      const key = owner + '::' + nameM[1];
      if (!reg.has(key)) reg.set(key, []);
      reg
        .get(key)
        .push({ where: `${d.name}:${i + 1}`, nullable: nullCell.toLowerCase(), dbType, owner });
    });
  }
  for (const [key, defs] of reg) {
    const name = key.split('::')[1] + ' (' + key.split('::')[0] + ')';
    if (defs.length < 2) continue;
    const nulls = new Set(defs.map((x) => x.nullable));
    const types = new Set(defs.map((x) => x.dbType).filter(Boolean));
    if (nulls.size > 1) {
      add(
        'BLOCKER',
        'field-registry',
        `\`${name}\` is declared with conflicting nullability`,
        defs.map((x) => `${x.nullable}  ${x.where}${x.dbType ? '  [' + x.dbType + ']' : ''}`),
      );
    } else if (types.size > 1) {
      add(
        'MAJOR',
        'field-registry',
        `\`${name}\` is declared with conflicting DB types`,
        defs.map((x) => `${x.dbType || '?'}  ${x.where}`),
      );
    }
  }
  if (VERBOSE)
    add('MINOR', 'field-registry', `${reg.size} distinct columns parsed from field tables`, []);
}

// ---------------------------------------------------------------------------
// 5. Cross-document section references pointing at nothing.
// ---------------------------------------------------------------------------
{
  const headings = {};
  for (const [key, d] of Object.entries(docs)) {
    const set = new Set();
    for (const m of d.text.matchAll(/^#{2,6}\s+§?\s*([0-9]+(?:\.[0-9]+)*)/gm)) set.add(m[1]);
    headings[key] = set;
  }
  for (const [key, d] of Object.entries(docs)) {
    const other = key === 'model' ? 'contract' : 'model';
    const otherName = FILES[other];
    const re = new RegExp(
      otherName.replace('.', '\\.') + '[^§\\n]{0,40}§\\s*([0-9]+(?:\\.[0-9]+)*)',
      'g',
    );
    d.lines.forEach((text, i) => {
      for (const m of text.matchAll(re)) {
        const sec = m[1];
        if (![...headings[other]].some((h) => h === sec || h.startsWith(sec + '.'))) {
          add(
            'MAJOR',
            'dangling-ref',
            `${d.name}:${i + 1} → ${otherName} §${sec} (no such heading)`,
            [text.trim().slice(0, 150)],
          );
        }
      }
    });
  }
}

// ---------------------------------------------------------------------------
// 6. Line-number citations into the other document — inherently fragile; one already drifted.
// ---------------------------------------------------------------------------
{
  for (const [key, d] of Object.entries(docs)) {
    const other = key === 'model' ? 'contract' : 'model';
    const otherLen = docs[other].lines.length;
    d.lines.forEach((text, i) => {
      for (const m of text.matchAll(/(DATA-MODEL|API-CONTRACT)(?:\.md)?:(\d+)/g)) {
        const target = m[1] === 'DATA-MODEL' ? 'model' : 'contract';
        if (target === key) continue;
        const n = parseInt(m[2], 10);
        const oor = n > otherLen;
        add(
          oor ? 'MAJOR' : 'MINOR',
          'line-citation',
          `${d.name}:${i + 1} cites ${m[0]}${oor ? ` — out of range (${otherLen} lines)` : ' — fragile, prefer a § reference'}`,
          [text.trim().slice(0, 130)],
        );
      }
    });
  }
}

// ---------------------------------------------------------------------------
// Check 7 — the exported rule-id list must be current
// ---------------------------------------------------------------------------
// The backend build cannot read this document; it reads a 92-id export instead.
// Nothing over there can tell that export has gone stale, because the repo that
// changes section 8 is not the repo holding the file. So the freshness check
// belongs here, where the edit happens.
try {
  const { execFileSync } = require('child_process');
  execFileSync(process.execPath, [require('path').join(__dirname, 'gen-rule-ids.js')], {
    stdio: 'pipe',
  });
} catch (e) {
  const detail = String(e.stderr || e.stdout || e.message || '')
    .split(String.fromCharCode(10))
    .map((l) => l.trim())
    .filter(Boolean);
  findings.push({
    severity: 'BLOCKER',
    check: 'rule-id export',
    message: 'rule-ids.txt in the backend repo does not match DATA-MODEL section 8',
    detail: detail.length ? detail : ['gen-rule-ids.js exited non-zero'],
  });
}

// Check 8 - the exported entity field manifest must be current
// ---------------------------------------------------------------------------
// Same argument as check 7: the backend walks its entity classes against this
// manifest, and nothing over there can tell it has gone stale.
try {
  const { execFileSync } = require('child_process');
  execFileSync(process.execPath, [require('path').join(__dirname, 'gen-field-manifest.js')], {
    stdio: 'pipe',
  });
} catch (e) {
  const detail = String(e.stderr || e.stdout || e.message || '')
    .split(String.fromCharCode(10))
    .map((l) => l.trim())
    .filter(Boolean);
  findings.push({
    severity: 'BLOCKER',
    check: 'entity field manifest',
    message: 'entity-fields.json in the backend repo does not match DATA-MODEL sections 3.1-3.10',
    detail: detail.length ? detail : ['gen-field-manifest.js exited non-zero'],
  });
}

// Report
// ---------------------------------------------------------------------------
const order = { BLOCKER: 0, MAJOR: 1, MINOR: 2 };
findings.sort((a, b) => order[a.severity] - order[b.severity] || a.check.localeCompare(b.check));
const counts = findings.reduce((m, f) => ((m[f.severity] = (m[f.severity] || 0) + 1), m), {});

console.log('spec-check — DATA-MODEL.md vs API-CONTRACT.md');
console.log(`  model    ${docs.model.lines.length} lines`);
console.log(`  contract ${docs.contract.lines.length} lines`);
console.log(
  `  findings: ${findings.length}  (${counts.BLOCKER || 0} blocker, ${counts.MAJOR || 0} major, ${counts.MINOR || 0} minor)\n`,
);

for (const f of VERBOSE ? findings : findings.filter((x) => x.severity !== 'MINOR')) {
  console.log(`[${f.severity}] ${f.check}: ${f.message}`);
  for (const d of f.detail) console.log(`    ${d}`);
  console.log();
}
if (!VERBOSE && counts.MINOR) console.log(`(${counts.MINOR} minor hidden — re-run with --verbose)`);
if (!findings.length) console.log('clean.');

process.exit(counts.BLOCKER ? 1 : 0);
