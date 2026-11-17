/**
 * The teaser frame document and the application read a hash address by one set of rules written
 * twice. A frame that asks for the site's root is given that document instead of the application
 * — the server cannot see the fragment that says which page the frame wants — and its script moves
 * the frame on to the teaser when the fragment names it. The rules are the application's
 * `hashUrlTarget`, which the build bundles into the application's own chunks, so the document's
 * script carries a copy. A copy drifts, and a drift here either leaves an embedded teaser empty
 * that the application would show, or moves a frame on another site to an address the application
 * reads as another page. So this test runs the application's function and the copy over the same
 * addresses and requires the same answer from both; holds the copy's decision — the teaser or
 * nothing — to the addresses it must take and the ones it must leave; and holds the document to
 * the site's policy: its script from the site itself, nothing inline, nothing of the application.
 *
 *   npm run test:guards      # from fe/
 *
 * The application's function is transpiled with the TypeScript compiler and imported as a module,
 * so it is the code the site runs, not a third copy; the frame script is imported as the browser
 * loads it.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RULES = 'ui-apps/info/src/app/routes/hash-url.ts';
const FRAME_SCRIPT = 'ui-apps/info/public/teaser-frame.js';
const FRAME_DOCUMENT = 'ui-apps/info/public/teaser-frame.html';

/** The application's rules as a module. They import nothing, so a data: URL can hold them. */
async function importRules() {
  const source = fs.readFileSync(path.join(FE, RULES), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  });
  assert.doesNotMatch(
    outputText,
    /^import /m,
    `${RULES} now imports a module this guard cannot load`,
  );
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`);
}

const application = await importRules();
const frame = await import(pathToFileURL(path.join(FE, FRAME_SCRIPT)).href);

const at = (pathname, search = '', hash = '') => ({ pathname, search, hash });

/**
 * Every shape the rules tell apart: no fragment, an ordinary one, a route in it, a query after the
 * fragment mark, both at once, a fragment that names another host or resolves through dot
 * segments, one that is no address at all — at the root, where the frame document runs, and on
 * the teaser and another page, where only the application does.
 */
const ADDRESSES = [
  at('/'),
  at('/', '?lang=da'),
  at('/', '', '#top'),
  at('/', '', '#/'),
  at('/', '', '#/teaser'),
  at('/', '', '#/teaser/'),
  at('/', '', '#/teaser?lang=da&area=x'),
  at('/', '?lang=da', '#/teaser'),
  at('/', '?lang=da', '#/teaser?area=x'),
  at('/', '?lang=da', '#/teaser#top'),
  at('/', '?lang=da', '#/teaser?area=x#?p=1'),
  at('/', '?lang=da', '#/messages/map?area=7'),
  at('/', '', '#/teaser#?lang=da'),
  at('/', '', '#/teaser?lang=da#?area=x'),
  at('/', '', '#/teaser/?lang=da'),
  at('/', '', '#/teaser/x'),
  at('/', '', '#/teasers'),
  at('/', '', '#/teaser.html'),
  at('/', '', '#/TEASER'),
  at('/', '', '#/%74easer'),
  at('/', '', '#/./teaser'),
  at('/', '', '#/x/../teaser?lang=da'),
  at('/', '', '#/teaser/../messages/map'),
  at('/', '', '#//teaser'),
  at('/', '', '#//elsewhere.example/teaser'),
  at('/', '', '#/.//elsewhere.example/teaser'),
  at('/', '', '#/messages/map'),
  at('/', '', '#/messages/details?lang=en'),
  at('/', '', '#/publications'),
  at('/', '', '#?lang=da'),
  at('/', '', '#//a b/x'),
  at('/', '', '#//['),
  at('/teaser'),
  at('/teaser', '', '#?lang=da&area=x'),
  at('/teaser', '?lang=da', '#?area=x'),
  at('/teaser', '', '#/teaser?lang=da'),
  at('/teaser', '', '#/messages/map'),
  at('/teaser/', '', '#/publications'),
  at('/messages/map', '?lang=da'),
  at('/messages/details', '', '#/messages/map'),
];

describe('the teaser frame document', () => {
  it('reads every hash address as the application does', () => {
    for (const address of ADDRESSES) {
      assert.equal(
        frame.hashUrlTarget(address),
        application.hashUrlTarget(address),
        `${JSON.stringify(address)}: the copy in ${FRAME_SCRIPT} and ${RULES} disagree`,
      );
    }
  });

  it('moves a frame at the root on to the teaser when the fragment names it', () => {
    const cases = [
      [at('/', '', '#/teaser'), '/teaser'],
      [at('/', '', '#/teaser/'), '/teaser/'],
      [at('/', '', '#/teaser?lang=da&area=x'), '/teaser?lang=da&area=x'],
      [at('/', '?lang=da', '#/teaser'), '/teaser?lang=da'],
      // The query before the fragment joins the route's own; neither is lost into the other.
      [at('/', '?lang=da', '#/teaser?area=x'), '/teaser?area=x&lang=da'],
      [at('/', '?lang=da', '#/teaser#?area=x'), '/teaser?lang=da&area=x'],
      [at('/', '', '#/teaser#?lang=da'), '/teaser?lang=da'],
      [at('/', '', '#/x/../teaser?lang=da'), '/teaser?lang=da'],
      // Only the path of a fragment that names a host is read: the teaser of this site.
      [at('/', '', '#//elsewhere.example/teaser'), '/teaser'],
    ];
    for (const [address, target] of cases) {
      assert.equal(frame.teaserFrameTarget(address), target, JSON.stringify(address));
    }
  });

  // Any other page in a frame on another site is what the frame policy exists to prevent.
  it('leaves a frame at the root empty when the fragment names anything else', () => {
    const cases = [
      at('/'),
      at('/', '?lang=da'),
      at('/', '', '#top'),
      at('/', '', '#/'),
      at('/', '', '#?lang=da'),
      at('/', '', '#/messages/map'),
      at('/', '', '#/publications'),
      at('/', '', '#/teaser/x'),
      at('/', '', '#/teasers'),
      at('/', '', '#/teaser.html'),
      at('/', '', '#/teaser/../messages/map'),
      at('/', '', '#//teaser'),
      at('/', '', '#//['),
    ];
    for (const address of cases) {
      assert.equal(frame.teaserFrameTarget(address), null, JSON.stringify(address));
    }
  });

  it('loads its script from the site itself and carries nothing inline and nothing else', () => {
    const html = fs.readFileSync(path.join(FE, FRAME_DOCUMENT), 'utf8');
    const scripts = html.match(/<script\b[^>]*>/gi) ?? [];
    assert.deepEqual(scripts, ['<script type="module" src="/teaser-frame.js">'], FRAME_DOCUMENT);
    assert.match(html, /<script\b[^>]*><\/script>/i, `${FRAME_DOCUMENT} has an inline script`);
    assert.doesNotMatch(html, /\son[a-z]+\s*=/i, `${FRAME_DOCUMENT} has an inline handler`);
    assert.doesNotMatch(html, /<app-root/i, `${FRAME_DOCUMENT} carries the application`);
    assert.match(html, /<body\b[^>]*>\s*<\/body>/i, `${FRAME_DOCUMENT} shows something`);
  });
});
