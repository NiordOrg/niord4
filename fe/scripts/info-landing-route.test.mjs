/**
 * The public site's first-visit gate measures the files bundle-baseline.json names as
 * `landingEntryPoints`, and it cannot tell whether those are the files the landing route loads.
 * A baseline that names the map view for a first visit that lands on the message list measures
 * the wrong route and stays green, so this test walks the route table from the site's root to
 * `landingRoute` and requires the baseline to name exactly the files the router loads lazily on
 * the way, in the order it loads them. The redirects that land a first visit on `landingRoute`
 * are pinned by the routes spec.
 *
 *   npm run test:guards      # from fe/
 *
 * The walk reads the source with the TypeScript parser and follows only what a route table here
 * uses: a pathless parent's children, a matching path, `loadChildren` into the next file and
 * `loadComponent` at the end. A route table that outgrows that fails the first test here.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ROUTE_TABLE = 'ui-apps/info/src/app/routes/create-niord-routes.ts';
const BASELINE = JSON.parse(
  fs.readFileSync(path.join(FE, 'ui-apps/info/bundle-baseline.json'), 'utf8'),
);

function parse(file) {
  const text = fs.readFileSync(path.join(FE, file), 'utf8');
  return ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
}

function property(object, name) {
  const found = object.properties.find(
    (candidate) => ts.isPropertyAssignment(candidate) && candidate.name.getText() === name,
  );
  return found?.initializer;
}

const isRoute = (node) =>
  ts.isObjectLiteralExpression(node) && property(node, 'path') !== undefined;

/** The outermost array of route objects in a file: the table it declares. */
function routeTable(file) {
  let table;
  const visit = (node) => {
    if (table) return;
    if (ts.isArrayLiteralExpression(node) && node.elements.some(isRoute)) table = node;
    else ts.forEachChild(node, visit);
  };
  visit(parse(file));
  assert.ok(table, `${file} declares no route table`);
  return table;
}

/** The file a `loadChildren` or `loadComponent` function imports, relative to fe/. */
function lazyTarget(loader, file) {
  let specifier;
  const visit = (node) => {
    if (specifier) return;
    if (
      ts.isCallExpression(node) &&
      node.expression.kind === ts.SyntaxKind.ImportKeyword &&
      node.arguments.length > 0 &&
      ts.isStringLiteral(node.arguments[0])
    )
      specifier = node.arguments[0].text;
    else ts.forEachChild(node, visit);
  };
  visit(loader);
  assert.ok(specifier, `a loader in ${file} imports no literal path`);
  const target = path.posix.join(path.posix.dirname(file), `${specifier}.ts`);
  assert.ok(fs.existsSync(path.join(FE, target)), `${target}, imported by ${file}, does not exist`);
  return target;
}

/** The files the router loads lazily to reach `segments` from `table`, or undefined if none matches. */
function lazyFilesTo(table, file, segments) {
  for (const route of table.elements.filter(isRoute)) {
    const routePath = property(route, 'path');
    if (property(route, 'redirectTo') || !ts.isStringLiteral(routePath)) continue;
    const own = routePath.text === '' ? [] : routePath.text.split('/');
    if (own.some((segment, i) => segment !== segments[i])) continue;
    const rest = segments.slice(own.length);
    const children = property(route, 'children');
    const loadChildren = property(route, 'loadChildren');
    const loadComponent = property(route, 'loadComponent');
    let files;
    if (loadChildren) {
      const target = lazyTarget(loadChildren, file);
      const inner = lazyFilesTo(routeTable(target), target, rest);
      files = inner && [target, ...inner];
    } else if (children && ts.isArrayLiteralExpression(children)) {
      files = lazyFilesTo(children, file, rest);
    } else if (rest.length === 0) {
      files = loadComponent ? [lazyTarget(loadComponent, file)] : [];
    }
    if (files) return files;
  }
  return undefined;
}

function lazyFilesFor(route) {
  const files = lazyFilesTo(routeTable(ROUTE_TABLE), ROUTE_TABLE, route.split('/').filter(Boolean));
  assert.ok(files, `no route in ${ROUTE_TABLE} matches ${route}`);
  return files;
}

describe('the first-visit gate measures the landing route', () => {
  it('bundle-baseline.json names the files the landing route loads', () => {
    assert.deepEqual(
      BASELINE.landingEntryPoints,
      lazyFilesFor(BASELINE.landingRoute),
      `landingEntryPoints must be the files ${BASELINE.landingRoute} loads; re-measure with --update-baseline after correcting them`,
    );
  });

  it('tells the message views apart, so a baseline naming another view fails', () => {
    const views = ['/messages/details', '/messages/table', '/messages/map'].map(lazyFilesFor);
    assert.equal(new Set(views.map((files) => files.at(-1))).size, views.length);
    for (const files of views)
      assert.equal(files[0], 'ui-apps/info/src/app/features/messages/messages.routes.ts');
  });
});
