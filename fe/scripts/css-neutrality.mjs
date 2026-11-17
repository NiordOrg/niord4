#!/usr/bin/env node
/**
 * Proves that a change to the stylesheets left the administration application's CSS alone.
 *
 *   node scripts/css-neutrality.mjs <before-dist-dir> <after-dist-dir> [--allow-reorder]
 *
 * Moving a stylesheet, carving a slice out of one, or splitting one in two is supposed to emit the
 * same bytes. "Supposed to" is not a gate, and a visual check cannot see a rule that changed its
 * position inside a cascade layer. So the two builds are compared as files first and as CSS second:
 *
 *   1. raw byte length of the one styles-*.css in each directory, and whether they are identical;
 *   2. with --allow-reorder, the normalised comparison: every rule is reduced to a
 *      (flattened at-rule condition, selector, declaration body) triple, the triples are sorted,
 *      and the two lists are diffed. A family that moved from before a sibling to after it compares
 *      equal; a declaration that changed does not. @media and @layer are flattened into the
 *      condition, so a block split in two compares equal to the whole one it came from;
 *   3. the selector sets, lost and gained, which is the gate for a change that is meant to drop
 *      something (pinning the Tailwind scan) rather than nothing.
 *
 * Reordering is only safe when the two halves are disjoint, which the caller proves separately —
 * this script reports the reordering, it does not bless it.
 *
 * Exit codes: 0 when the two are equal under the chosen comparison, 1 when they differ, 2 for a
 * usage or input error. A missing or ambiguous stylesheet is an error, never a pass.
 */
import fs from 'node:fs';
import path from 'node:path';
import postcss from 'postcss';

const USAGE =
  'usage: css-neutrality.mjs <before-dist-dir> <after-dist-dir> [--allow-reorder]\n' +
  '       each directory is a build output; browser/styles-*.css is read from it';

function fail(message, code = 2) {
  console.error(`css-neutrality: ${message}`);
  process.exit(code);
}

const argv = process.argv.slice(2);
const allowReorder = argv.includes('--allow-reorder');
const dirs = argv.filter((a) => a !== '--allow-reorder');
if (dirs.length !== 2) fail(USAGE);

/** The one styles-*.css of a build output, whether the directory is the output or its browser/. */
function readStylesheet(dir) {
  const candidates = [path.resolve(dir, 'browser'), path.resolve(dir)].filter(
    (d) => fs.existsSync(d) && fs.statSync(d).isDirectory(),
  );
  if (candidates.length === 0) fail(`${dir} is not a directory`);
  for (const base of candidates) {
    const hits = fs.readdirSync(base).filter((f) => /^styles-.*\.css$/.test(f));
    if (hits.length === 1)
      return {
        file: path.join(base, hits[0]),
        css: fs.readFileSync(path.join(base, hits[0]), 'utf8'),
      };
    if (hits.length > 1) fail(`${base} holds ${hits.length} styles-*.css files; expected one`);
  }
  fail(`no styles-*.css under ${dir}`);
}

/**
 * A selector list split into its selectors, respecting the commas inside `:is(…)` and `:where(…)`.
 * A rule with a list of selectors is equivalent to one rule per selector, so comparing them one by
 * one is what lets a list that was split across two files compare equal to the whole one.
 */
function splitSelectors(selector) {
  const parts = [];
  let depth = 0;
  let current = '';
  for (const char of selector) {
    if (char === '(' || char === '[') depth++;
    else if (char === ')' || char === ']') depth--;
    if (char === ',' && depth === 0) {
      parts.push(current.trim());
      current = '';
    } else current += char;
  }
  parts.push(current.trim());
  return parts.filter(Boolean);
}

/**
 * Every rule as a sorted triple list, one triple per selector. The condition is the chain of
 * enclosing at-rules, so a rule inside `@layer components { @media (width < 64rem) { … } }` carries
 * both; the declaration body is the rule's own declarations, sorted, so a property reordered inside
 * a rule also compares equal. Nested rules (`&:hover`) keep their parent's selector in the chain.
 */
function triples(css, label) {
  const root = postcss.parse(css, { from: label });
  const out = [];
  const walk = (container, condition, selector) => {
    for (const node of container.nodes ?? []) {
      if (node.type === 'atrule') {
        if (node.nodes) walk(node, [...condition, `@${node.name} ${node.params}`.trim()], selector);
        else
          out.push([condition.join(' | '), selector, `@${node.name} ${node.params};`].join(' || '));
      } else if (node.type === 'rule') {
        const own = node.nodes
          .filter((n) => n.type === 'decl')
          .map((n) => `${n.prop}:${n.value}${n.important ? '!important' : ''}`)
          .sort();
        if (own.length)
          for (const one of splitSelectors(node.selector)) {
            const sel = [...selector, one].filter(Boolean).join(' >> ');
            out.push([condition.join(' | '), sel, own.join(';')].join(' || '));
          }
        walk(node, condition, [...selector, node.selector]);
      }
    }
  };
  walk(root, [], []);
  return out.sort();
}

/** The selector set, for the lost/gained report. Nested selectors count under their own text. */
function selectors(css, label) {
  const set = new Set();
  postcss.parse(css, { from: label }).walkRules((rule) => {
    for (const part of splitSelectors(rule.selector)) set.add(part);
  });
  return set;
}

const before = readStylesheet(dirs[0]);
const after = readStylesheet(dirs[1]);

console.log(`before  ${path.relative(process.cwd(), before.file)}  ${before.css.length} bytes`);
console.log(`after   ${path.relative(process.cwd(), after.file)}  ${after.css.length} bytes`);

const beforeSelectors = selectors(before.css, before.file);
const afterSelectors = selectors(after.css, after.file);
const lost = [...beforeSelectors].filter((s) => !afterSelectors.has(s)).sort();
const gained = [...afterSelectors].filter((s) => !beforeSelectors.has(s)).sort();
console.log(
  `selectors  before ${beforeSelectors.size}  after ${afterSelectors.size}  lost ${lost.length}  gained ${gained.length}`,
);
if (lost.length) console.log('  LOST:   ' + lost.join('  '));
if (gained.length) console.log('  GAINED: ' + gained.join('  '));

if (before.css === after.css) {
  console.log('css-neutrality: byte-identical.');
  process.exit(0);
}

if (!allowReorder) {
  let i = 0;
  while (i < Math.min(before.css.length, after.css.length) && before.css[i] === after.css[i]) i++;
  console.error(`css-neutrality: the two stylesheets differ, first at byte ${i}`);
  console.error(`  before: ${JSON.stringify(before.css.slice(Math.max(0, i - 60), i + 90))}`);
  console.error(`  after:  ${JSON.stringify(after.css.slice(Math.max(0, i - 60), i + 90))}`);
  console.error('  (--allow-reorder compares the two as sorted rule triples instead)');
  process.exit(1);
}

const beforeTriples = triples(before.css, before.file);
const afterTriples = triples(after.css, after.file);
const beforeCounts = new Map();
for (const t of beforeTriples) beforeCounts.set(t, (beforeCounts.get(t) ?? 0) + 1);
const afterCounts = new Map();
for (const t of afterTriples) afterCounts.set(t, (afterCounts.get(t) ?? 0) + 1);

const onlyBefore = [];
const onlyAfter = [];
for (const [t, n] of beforeCounts) {
  const m = afterCounts.get(t) ?? 0;
  for (let i = 0; i < n - m; i++) onlyBefore.push(t);
}
for (const [t, n] of afterCounts) {
  const m = beforeCounts.get(t) ?? 0;
  for (let i = 0; i < n - m; i++) onlyAfter.push(t);
}

console.log(`rules  before ${beforeTriples.length}  after ${afterTriples.length}`);
if (onlyBefore.length === 0 && onlyAfter.length === 0) {
  console.log('css-neutrality: identical under reordering — same rules, same declarations.');
  process.exit(0);
}

console.error(
  `css-neutrality: ${onlyBefore.length} rule(s) only before, ${onlyAfter.length} only after:`,
);
for (const t of onlyBefore.slice(0, 40)) console.error(`  -  ${t}`);
for (const t of onlyAfter.slice(0, 40)) console.error(`  +  ${t}`);
if (onlyBefore.length + onlyAfter.length > 80) console.error('  … truncated');
process.exit(1);
