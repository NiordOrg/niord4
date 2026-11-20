/**
 * Negative tests of scripts/info-layout-check.mjs's options, which it reads before it starts
 * Chromium: none of these runs gets that far. And the fixture's answers to the site's /rest
 * calls, read from the script imported, which then runs nothing.
 *
 *   npm run test:guards      # from fe/
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'info-layout-check.mjs');

let dir;

function run(...args) {
  const result = spawnSync(process.execPath, [SCRIPT, ...args], {
    cwd: dir,
    encoding: 'utf8',
    timeout: 60_000,
  });
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

describe('info-layout-check options', () => {
  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'info-layout-check-'));
    fs.mkdirSync(path.join(dir, 'browser'));
    fs.writeFileSync(
      path.join(dir, 'browser', 'index.html'),
      '<!doctype html><app-root></app-root>',
    );
    fs.mkdirSync(path.join(dir, 'empty'));
    fs.mkdirSync(path.join(dir, 'no-policy'));
    fs.writeFileSync(
      path.join(dir, 'no-policy', 'niord-security-headers.conf'),
      'add_header X-Content-Type-Options "nosniff" always;\n',
    );
  });
  after(() => fs.rmSync(dir, { recursive: true, force: true }));

  it('exits 2 when --nginx names a folder without niord-security-headers.conf', () => {
    const result = run('--dist', 'browser', '--nginx', 'empty');
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /no niord-security-headers\.conf in .*empty/);
    assert.doesNotMatch(result.output, /Chromium/);
  });

  it('exits 2 when the header file carries no Content-Security-Policy', () => {
    const result = run('--dist', 'browser', '--nginx', 'no-policy');
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /no Content-Security-Policy header in /);
    assert.doesNotMatch(result.output, /Chromium/);
  });

  it('exits 2 on a dist without index.html, before it reads the policy', () => {
    const result = run('--dist', 'empty', '--nginx', 'empty');
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /no index\.html in /);
  });

  it('exits 2 on an unknown option and on --nginx without a value', () => {
    for (const args of [
      ['--policy', 'x'],
      ['--dist', 'browser', '--nginx'],
    ]) {
      const result = run(...args);
      assert.equal(result.status, 2, result.output);
      assert.match(result.output, /usage: info-layout-check\.mjs/);
    }
  });
});

describe("info-layout-check's fixture", () => {
  const area = async (mrn) => {
    const { answerRest } = await import('./info-layout-check.mjs');
    const answer = answerRest(
      new URL(`http://niord-info.layout-check/rest/public/v1/area/${encodeURIComponent(mrn)}`),
    );
    return { status: answer.status, body: JSON.parse(answer.body) };
  };

  it("answers any country's root areas, each under its own MRN with a fixture root's id", async () => {
    const first = await area('urn:mrn:iho:country:xx');
    const second = await area('urn:mrn:iho:country:yy');
    assert.equal(first.status, 200);
    assert.equal(second.status, 200);
    assert.equal(first.body.mrn, 'urn:mrn:iho:country:xx');
    assert.equal(second.body.mrn, 'urn:mrn:iho:country:yy');
    assert.notEqual(first.body.id, second.body.id);
    assert.deepEqual((await area('urn:mrn:iho:country:xx')).body, first.body);
    for (const { body } of [first, second]) assert.ok(body.descs.length > 0);
  });

  // The fixture is a base product's, so it names no country: a country's MRN is answered like any
  // other, by turn, with one of the fixture's fictitious roots.
  it('answers every MRN by turn with a fictitious root, and names no country', async () => {
    const COUNTRY_NAMES = /Danmark|Denmark|Grønland|Greenland/;
    const answers = [];
    for (const mrn of [
      'urn:mrn:iho:country:gl',
      'urn:mrn:iho:country:dk',
      'urn:mrn:iho:country:zz',
    ]) {
      const { status, body } = await area(mrn);
      assert.equal(status, 200);
      assert.equal(body.mrn, mrn);
      assert.doesNotMatch(JSON.stringify(body.descs), COUNTRY_NAMES);
      answers.push(body.id);
    }
    assert.notEqual(answers[0], answers[1]);
    assert.equal(answers[2], answers[0]);

    const { answerRest } = await import('./info-layout-check.mjs');
    const messages = answerRest(new URL('http://niord-info.layout-check/rest/public/v1/messages'));
    assert.equal(messages.status, 200);
    assert.doesNotMatch(messages.body, COUNTRY_NAMES);
  });

  // The browse page reads the issue itself to name it in the filter bar.
  it('answers the issue Browse opens, and refuses one it does not hold', async () => {
    const { answerRest } = await import('./info-layout-check.mjs');
    const issue = answerRest(
      new URL('http://niord-info.layout-check/rest/public/v1/publication/layout-check-efs'),
    );
    assert.equal(issue.status, 200);
    assert.equal(JSON.parse(issue.body).publicationId, 'layout-check-efs');
    const unknown = answerRest(
      new URL('http://niord-info.layout-check/rest/public/v1/publication/elsewhere'),
    );
    assert.equal(unknown.status, 404);
  });

  // A message's dialog opened by its address reads the message itself, by id or short id; one
  // message is answered there and in no list, so the dialog's close has no row to go back to.
  it('answers a message by its id or short id, and holds one that no list holds', async () => {
    const { answerRest, UNLISTED_MESSAGE } = await import('./info-layout-check.mjs');
    const read = (id) =>
      answerRest(
        new URL(`http://niord-info.layout-check/rest/public/v1/message/${encodeURIComponent(id)}`),
      );
    const listed = JSON.parse(
      answerRest(new URL('http://niord-info.layout-check/rest/public/v1/messages')).body,
    );
    assert.equal(JSON.parse(read(listed[2].id).body).id, listed[2].id);
    assert.equal(JSON.parse(read(listed[2].shortId).body).id, listed[2].id);
    assert.equal(JSON.parse(read(UNLISTED_MESSAGE.id).body).id, UNLISTED_MESSAGE.id);
    assert.ok(!listed.some((m) => m.id === UNLISTED_MESSAGE.id));
    assert.equal(read('elsewhere').status, 404);
  });
});
