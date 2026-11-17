/**
 * The public site's two print addresses and the query allow-list its container holds them to are
 * one contract written twice: `message-pdf-url.ts` composes the addresses, and two `map $args`
 * blocks in `nginx/default.conf.template` refuse every query they were not written for. A parameter
 * the site starts sending without the maps growing with it builds green, passes every unit test and
 * is a 403 in production, where the print button opens an error page instead of the sheet. So this
 * test runs the site's own composer with every field of its filter filled, in each of a few sample
 * languages, and requires the maps to accept what it writes; and it feeds the maps crafted queries
 * they must refuse, so a map widened past what the site sends fails here as well.
 *
 *   npm run test:guards      # from fe/
 *
 * The composer is transpiled with the TypeScript compiler and imported as a module, so the addresses
 * are the ones the site writes, not a copy of its logic. The maps are read from the template and
 * matched the way nginx matches a map: an exact key first, then each regex in the order written,
 * then the default. The container test proves the same maps inside nginx; this test proves them
 * against the site, without building an image.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const COMPOSER = 'ui-apps/info/src/app/features/messages/message-pdf-url.ts';
const TEMPLATE = 'ui-apps/info/nginx/default.conf.template';
/**
 * Sample languages, not a country's: the maps admit any language code, so these stand for
 * whichever languages a build ships.
 */
const LANGUAGES = ['da', 'en', 'kl'];

const composerSource = fs.readFileSync(path.join(FE, COMPOSER), 'utf8');
const composerFile = ts.createSourceFile(COMPOSER, composerSource, ts.ScriptTarget.Latest, true);
const template = fs.readFileSync(path.join(FE, TEMPLATE), 'utf8');

/**
 * The composer as a module. A data: URL has no folder to resolve an import from, so the composer
 * must import nothing that survives compilation; a type-only import is erased and is fine.
 */
async function importComposer() {
  const imports = composerFile.statements.filter(
    (statement) => ts.isImportDeclaration(statement) && !statement.importClause?.isTypeOnly,
  );
  assert.equal(
    imports.length,
    0,
    `${COMPOSER} now imports a module, which this guard cannot load: teach it to, rather than ` +
      'copying the composer into the test',
  );
  const { outputText } = ts.transpileModule(composerSource, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  });
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`);
}

/** The property names of an interface the composer declares. */
function interfaceFields(name) {
  const declaration = composerFile.statements.find(
    (statement) => ts.isInterfaceDeclaration(statement) && statement.name.text === name,
  );
  assert.ok(declaration, `${COMPOSER} declares no interface ${name}`);
  return declaration.members.map((member) => member.name.getText(composerFile));
}

/** One `map $args $<variable> { … }` block of the template, as its entries in the order written. */
function readMap(variable) {
  const block = template.match(
    new RegExp(`^map \\$args \\$${variable} \\{\\n([\\s\\S]*?)^\\}`, 'm'),
  );
  assert.ok(block, `${TEMPLATE} has no map $args $${variable}`);
  return block[1]
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '' && !line.startsWith('#'))
    .map((line) => {
      const entry = line.match(/^(?:"([^"]*)"|(\S+))\s+(\S+);$/);
      assert.ok(entry, `map $${variable}: cannot read the line ${line}`);
      return { key: entry[1] ?? entry[2], value: entry[3] };
    });
}

/** What nginx sets the map's variable to for this query string. */
function lookup(entries, args) {
  const exact = entries.find(
    ({ key }) => key !== 'default' && !key.startsWith('~') && key === args,
  );
  if (exact) return exact.value;
  for (const { key, value } of entries) {
    if (!key.startsWith('~')) continue;
    const caseless = key.startsWith('~*');
    if (new RegExp(key.slice(caseless ? 2 : 1), caseless ? 'i' : '').test(args)) return value;
  }
  return entries.find(({ key }) => key === 'default')?.value ?? '';
}

const LIST_MAP = readMap('niord_list_pdf_query_ok');
const MESSAGE_MAP = readMap('niord_message_pdf_query_ok');
const passes = (map, args) => lookup(map, args) === '1';

/** The path and the query string of an address the composer wrote. */
function split(url) {
  const at = url.indexOf('?');
  return at < 0 ? [url, ''] : [url.slice(0, at), url.slice(at + 1)];
}

/**
 * A list filter with every field filled and every list holding more than one entry, in the shapes
 * the public API hands out: numeric area ids, series ids, a publication's UUID. The test below
 * fails when the filter gains a field this does not fill, so a new parameter is composed here too.
 */
const FULL_FILTER = {
  mainTypes: ['NW', 'NM'],
  areaIds: ['453', '1023'],
  messageSeriesIds: ['xx-nw', 'xx-nw-local', 'xx-nm'],
  publicationId: '6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b',
};
const EMPTY_FILTER = { mainTypes: [], areaIds: [], messageSeriesIds: [] };

/** Message ids as the site holds them: a UUID, and short ids, one of them with a space in it. */
const MESSAGE_IDS = ['2f1d6a0e-8c3b-4f7a-9e21-5b6c7d8e9f01', 'Local Warning-120-26', 'NW-333-26'];

const composer = await importComposer();

describe('the print addresses the public site composes pass its container', () => {
  it('the sample filter fills every field of the filter the site composes from', () => {
    assert.deepEqual(
      [...Object.keys(FULL_FILTER), 'language'].sort(),
      interfaceFields('MessageListPdfFilter').sort(),
      'MessageListPdfFilter changed: fill the new field in FULL_FILTER, so the address it adds a ' +
        'parameter to is checked against the map',
    );
  });

  it('a printed list passes the list map, with every filter and with none', () => {
    for (const language of LANGUAGES) {
      for (const filter of [FULL_FILTER, EMPTY_FILTER]) {
        const [pathname, query] = split(composer.messageListPdfUrl({ language, ...filter }));
        assert.equal(pathname, '/rest/message-reports/report.pdf');
        assert.ok(
          passes(LIST_MAP, query),
          `the container refuses the list address the site composes: ${query} — widen ` +
            `$niord_list_pdf_query_ok in ${TEMPLATE} in the same change`,
        );
      }
    }
  });

  it('one message passes the message map', () => {
    for (const language of LANGUAGES) {
      for (const id of MESSAGE_IDS) {
        const [pathname, query] = split(composer.messagePdfUrl(id, language));
        assert.match(pathname, /^\/rest\/message-reports\/message\/[^/]+\.pdf$/);
        assert.ok(
          passes(MESSAGE_MAP, query),
          `the container refuses the message address the site composes: ${query} — widen ` +
            `$niord_message_pdf_query_ok in ${TEMPLATE} in the same change`,
        );
      }
    }
  });
});

describe('the list map takes the public report once, wherever it stands', () => {
  const acceptedLists = [
    ['report=public&lang=da', 'named first'],
    ['lang=da&mainType=NW&report=public&area=453', 'named between the filter'],
    ['lang=da&messageSeries=xx-nw&report=public', 'named last'],
    ['report=public', 'named alone'],
  ];
  for (const [query, why] of acceptedLists) {
    it(`the list map accepts the public report ${why}`, () => {
      assert.ok(passes(LIST_MAP, query), `$niord_list_pdf_query_ok refuses ${query}`);
    });
  }
});

describe('the container refuses what the site does not compose', () => {
  // Every case but the ones about the report names the public report, so each is refused for the
  // one reason it states and not merely for a missing report.
  const refusedLists = [
    ['lang=da', 'no report: the site always names the public one'],
    ['lang=da&mainType=NW', 'a filter and no report'],
    ['lang=da&publication=6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b', 'a publication and no report'],
    ['lang=da&report=public&report=public', 'the public report named twice'],
    ['report=public&lang=da&report=public', 'the public report named first and last'],
    ['lang=da&report=nm-report', 'a report the editors print'],
    ['lang=da&report=public&report=nm-report', 'a second report after the public one'],
    ['lang=da&report=fa-list', 'another report, by its name'],
    ['lang=da&report=publicity', 'a report whose name merely begins with the public one'],
    ['lang=da&report=Public', 'the public report in another case'],
    ['lang=da&report=', 'an empty report name'],
    ['lang=da&report=public&debug=true', 'the debug switch'],
    ['lang=da&report=public&%64ebug=true', 'the debug switch with a percent-encoded letter'],
    ['lang=da&report=public&param:x=y', 'a free-form template value'],
    ['lang=da&report=public&pageSize=A0', 'a page size'],
    ['lang=da&report=public&messageSeries=xx nw', 'a value with a space'],
    ['lang=da&report=public&messageSeries=xx+nw', 'a value with a space, as a form encodes it'],
    ['lang=da&report=public&messageSeries=xx%20nw', 'a value with a space, percent-encoded'],
    ['lang=da;report=public', 'a pair joined by ;'],
    ['lang=da&&report=public', 'a doubled &'],
    ['lang=da&report=public&', 'a trailing &'],
    ['', 'no query at all'],
  ];
  for (const [query, why] of refusedLists) {
    it(`the list map refuses ${why}`, () => {
      assert.ok(!passes(LIST_MAP, query), `$niord_list_pdf_query_ok accepts ${query}`);
    });
  }

  const refusedMessages = [
    ['lang=da&debug=true', 'the debug switch'],
    ['lang=da&param:x=y', 'a free-form template value'],
    ['lang=da&report=public', 'a report: one message names none'],
    ['lang=da&messageSeries=xx-nw', 'a list filter'],
    ['lang=d a', 'a value with a space'],
  ];
  for (const [query, why] of refusedMessages) {
    it(`the message map refuses ${why}`, () => {
      assert.ok(!passes(MESSAGE_MAP, query), `$niord_message_pdf_query_ok accepts ${query}`);
    });
  }
});
