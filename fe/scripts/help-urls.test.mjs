/**
 * The administration app's help links resolve through one map, `HELP_URLS`, read from
 * `ui-apps/admin/src/app/core/main-shell/help/help-urls.json`: section id → the user guides' page
 * and anchor, relative to the documentation base. The map is the contract between the code and the
 * guides, and the guides' build checks every page and anchor in it against the built guides. This
 * guard holds the other half: every section id the code uses is in the map, and every map key is
 * used, so a help link can neither vanish silently (an id the map lacks hides the `?`) nor point at a
 * screen that no longer exists.
 *
 *   npm run test:guards      # from fe/
 *
 * A section id is used where it is written as a literal:
 *
 * - route data, `help: { section: '<id>' }`, in any route file;
 * - a section descriptor in `features/admin/section-descriptors.ts`: the third argument of
 *   `section(…)`, and `overviewHelpSection` (the area routes copy both into route data);
 * - a `helpSection` or `overviewHelpSection` property, and the default of a `helpSection` input
 *   (the geometry editor's `position-editor`);
 * - a template: `section="<id>"` or `[section]="'<id>'"` on `<app-help-anchor>`, and
 *   `helpSection="<id>"` or `[helpSection]="'<id>'"` on any element.
 *
 * A section computed at runtime cannot be read here, so each such place is listed in `COMPUTED`
 * with where its ids come from; a new one fails until it is listed.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const APP = 'ui-apps/admin/src/app';
const MAP_FILE = `${APP}/core/main-shell/help/help-urls.json`;
const DESCRIPTORS = `${APP}/features/admin/section-descriptors.ts`;

/** The places that pass a section computed at runtime, and where the guard reads its ids instead. */
const COMPUTED = [
  // help: { section: area.overviewHelpSection } and { section: section.helpSection }: the
  // descriptors' literals, read in DESCRIPTORS.
  `${APP}/features/admin/area-routes.ts: route data help.section`,
  `${APP}/features/admin/area-routes.ts: route data help.section`,
  // <app-help-anchor [section]="helpSection()" />: the input's default and any host's literal.
  `${APP}/shared/components/geometry-editor/geometry-editor.ts: [section] on app-help-anchor`,
];

/** Every non-spec source file of the administration app, relative to fe/ with forward slashes. */
function sources() {
  return fs
    .readdirSync(path.join(FE, APP), { recursive: true })
    .map((file) => `${APP}/${String(file).replaceAll('\\', '/')}`)
    .filter((file) => /\.(ts|html)$/.test(file) && !file.endsWith('.spec.ts'))
    .sort();
}

const literal = (node) =>
  node && (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node))
    ? node.text
    : undefined;

const propertyName = (node) =>
  ts.isIdentifier(node.name) || ts.isStringLiteral(node.name) ? node.name.text : undefined;

/** The section ids a TypeScript file writes as literals, and the places it computes one. */
function scanTypeScript(file, text, used, computed) {
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  const use = (id, how) => used.push({ id, where: `${file}: ${how}` });
  const visit = (node) => {
    if (ts.isPropertyAssignment(node)) {
      const name = propertyName(node);
      if (name === 'help' && ts.isObjectLiteralExpression(node.initializer)) {
        const section = node.initializer.properties.find(
          (p) => ts.isPropertyAssignment(p) && propertyName(p) === 'section',
        );
        if (section) {
          const id = literal(section.initializer);
          if (id !== undefined) use(id, 'route data help.section');
          else computed.push(`${file}: route data help.section`);
        }
      } else if (name === 'helpSection' || name === 'overviewHelpSection') {
        const id = literal(node.initializer);
        if (id !== undefined) use(id, name);
        else computed.push(`${file}: ${name}`);
      }
    } else if (
      ts.isPropertyDeclaration(node) &&
      propertyName(node) === 'helpSection' &&
      node.initializer &&
      ts.isCallExpression(node.initializer)
    ) {
      const id = literal(node.initializer.arguments[0]);
      if (id !== undefined) use(id, 'helpSection input default');
    } else if (
      file === DESCRIPTORS &&
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      node.expression.text === 'section' &&
      node.arguments.length >= 3
    ) {
      const id = literal(node.arguments[2]);
      if (id !== undefined) use(id, `section('${literal(node.arguments[0])}', …)`);
      else computed.push(`${file}: section(…) helpSection`);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
}

/** The section ids a template (an .html file, or a component's inline template) writes. */
function scanTemplate(file, text, used, computed) {
  const use = (id, how) => used.push({ id, where: `${file}: ${how}` });
  const attribute = (attributes, name, element) => {
    for (const [, id] of attributes.matchAll(new RegExp(`(?<![\\w\\[.-])${name}="([^"]*)"`, 'g')))
      use(id, `${name}= on ${element}`);
    for (const [, expression] of attributes.matchAll(new RegExp(`\\[${name}\\]="([^"]*)"`, 'g'))) {
      const quoted = /^\s*'([^']*)'\s*$/.exec(expression);
      if (quoted) use(quoted[1], `[${name}] on ${element}`);
      else computed.push(`${file}: [${name}] on ${element}`);
    }
  };
  for (const [, attributes] of text.matchAll(/<app-help-anchor\b([^>]*)>/g))
    attribute(attributes, 'section', 'app-help-anchor');
  for (const [, element, attributes] of text.matchAll(/<([a-z][\w-]*)\b([^>]*)>/g))
    attribute(attributes, 'helpSection', element);
}

function scan() {
  const used = [];
  const computed = [];
  for (const file of sources()) {
    const text = fs.readFileSync(path.join(FE, file), 'utf8');
    if (file.endsWith('.ts')) scanTypeScript(file, text, used, computed);
    scanTemplate(file, text, used, computed);
  }
  return { used, computed };
}

const MAP = JSON.parse(fs.readFileSync(path.join(FE, MAP_FILE), 'utf8'));
const { used, computed } = scan();

describe('the help section map', () => {
  it(`${MAP_FILE} is a flat map of section ids to a guides page and anchor`, () => {
    assert.equal(typeof MAP, 'object');
    assert.ok(!Array.isArray(MAP) && MAP !== null);
    const malformed = Object.entries(MAP)
      .filter(
        ([id, target]) =>
          !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id) ||
          typeof target !== 'string' ||
          !/^(?:\/[A-Za-z0-9._-]+)+\.html#[A-Za-z0-9_-]+$/.test(target),
      )
      .map(([id, target]) => `${id}: ${JSON.stringify(target)}`);
    assert.deepEqual(
      malformed,
      [],
      'each key is a lower-case section id and each value a page of the guides with its anchor, relative to the documentation base, such as /editor-manual/manual.html#messages',
    );
  });

  it('every section id the code uses is in the map', () => {
    const missing = used.filter(({ id }) => !Object.hasOwn(MAP, id));
    assert.deepEqual(
      missing.map(({ id, where }) => `${id} (${where})`),
      [],
      `add each to ${MAP_FILE}, pointing at an anchor the guides carry, or remove the use: an id the map lacks hides the help link`,
    );
  });

  it('every key of the map is used somewhere in the code', () => {
    const ids = new Set(used.map(({ id }) => id));
    assert.deepEqual(
      Object.keys(MAP).filter((id) => !ids.has(id)),
      [],
      `remove each from ${MAP_FILE}, or give a screen the help link it was meant for`,
    );
  });

  it('every section computed at runtime is a known place whose ids are read elsewhere', () => {
    assert.deepEqual(
      computed.sort(),
      [...COMPUTED].sort(),
      'the guard cannot read a section computed at runtime: write the id as a literal, or list the place in COMPUTED with where its ids come from',
    );
  });

  it('finds the uses of each kind, so a scanner that reads nothing cannot pass', () => {
    const where = (id) => used.filter((use) => use.id === id).map((use) => use.where);
    assert.ok(where('editor-start').some((w) => w.includes('editor.routes.ts')));
    assert.ok(where('compare-messages').some((w) => w.endsWith('section= on app-help-anchor')));
    assert.ok(where('charts').some((w) => w.startsWith(DESCRIPTORS)));
    assert.ok(where('admin-manual').some((w) => w.endsWith('overviewHelpSection')));
    assert.ok(where('position-editor').some((w) => w.endsWith('helpSection input default')));
  });
});
