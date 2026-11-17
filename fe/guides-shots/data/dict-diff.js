// dict-diff.js: counts the Dictionary rows of the copy that would override the base product's English UI texts.
// stdin: one JSON array of {d: dictionary, k: key, v: English value, a: asAdded} for the web and message dictionaries
// (make-guides-db.sh pipes it from psql). argv[2]: the base product's English bundle (fe/languages/en/i18n/admin/en.json).
// Prints counts and five example keys, never the values. It follows the admin app's rules:
//  - DictionaryOverridesService: a row whose value still equals what start-up added or adopted (asAdded: its sha256
//    is the DefaultsLedger's) gives no override, so the app's own text wins;
//  - mergeDictionaryOverrides: an override whose {{placeholders}} differ from the compiled value's is refused.
const fs = require('fs');
const bundle = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^﻿/, ''));
const rows = JSON.parse(fs.readFileSync(0, 'utf8').trim() || '[]') || [];
// The bundle mixes flat dotted keys with nested objects: the flat key first, then the nested path (resolveBaseValue)
const lookup = (key) =>
  key in bundle
    ? bundle[key]
    : key.split('.').reduce((n, p) => (n && typeof n === 'object' ? n[p] : undefined), bundle);
const placeholders = (s) =>
  new Set([...String(s).matchAll(/\{\{\s*([\w.]+)\s*\}\}/g)].map((m) => m[1]));
const same = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));
const out = {
  rows: rows.length,
  keyNotInBundle: 0,
  keyInBundle: 0,
  equalToBundle: 0,
  differFromBundle: 0,
  differButAsAdded: 0,
  differRefusedPlaceholders: 0,
  differAndWouldOverride: 0,
  perDictionary: {},
};
const examples = [];
for (const { d, k, v, a } of rows) {
  const per = (out.perDictionary[d] ||= {
    rows: 0,
    keyNotInBundle: 0,
    differFromBundle: 0,
    differAndWouldOverride: 0,
  });
  per.rows++;
  const base = lookup(k);
  if (typeof base !== 'string') {
    out.keyNotInBundle++;
    per.keyNotInBundle++;
    continue;
  }
  out.keyInBundle++;
  if (base === v) {
    out.equalToBundle++;
    continue;
  }
  out.differFromBundle++;
  per.differFromBundle++;
  if (a === true) {
    out.differButAsAdded++;
    continue;
  }
  if (!same(placeholders(base), placeholders(v ?? ''))) {
    out.differRefusedPlaceholders++;
    continue;
  }
  out.differAndWouldOverride++;
  per.differAndWouldOverride++;
  if (examples.length < 5) examples.push(`${d}: ${k}`);
}
// Keys that differ, whatever asAdded says (what a sysadmin would see as "not the base text")
out.exampleKeysWouldOverride = examples;
out.exampleKeysDiffer = rows
  .filter(({ k, v }) => typeof lookup(k) === 'string' && lookup(k) !== v)
  .slice(0, 5)
  .map(({ d, k }) => `${d}: ${k}`);
console.log(JSON.stringify(out, null, 1));
