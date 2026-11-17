/**
 * Negative tests for the secret-pattern scan, lib/secrets.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Every crafted value is put together from pieces at run time, so this file carries no literal a
 * secret scanner — GitHub's push protection, or this scan over the kit — would flag. None of them
 * is a real credential.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { PATTERN_IDS, entropy, filesUnder, scanFiles, scanText } from './lib/secrets.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** One crafted value per pattern id, each in the setting it is typed into. */
const CRAFTED = {
  'private-key': ['-----BEGIN ', 'RSA PRIVATE', ' KEY-----\nMIIEow\n'].join(''),
  'aws-access-key-id': `const id = "${['AK', 'IA', 'Q3EXAMPLE7N0TREAL'.slice(0, 16)].join('')}";`,
  'github-token': `token "${['gh', 'p_', 'A1b2C3d4'.repeat(5)].join('')}"`,
  'github-fine-grained-token': `"${['github', '_pat_', '11AB0CDE', '_x9'.repeat(8)].join('')}"`,
  'gitlab-token': `PAT=${['gl', 'pat-', 'Ab12_'.repeat(5)].join('')}`,
  'slack-token': `hook: ${['xo', 'xb-', '1234567890-', 'abcdefghij'].join('')}`,
  'google-api-key': `key=${['AI', 'za', 'Xy0_-'.repeat(7)].join('')}`,
  'stripe-live-key': `${['sk', '_live_', 'Ab1'.repeat(8)].join('')}`,
  'npm-token': `//registry/:_authToken=${['np', 'm_', 'Ab12Cd34'.repeat(4), 'Ef56'].join('')}`,
  'anthropic-key': `${['sk', '-ant-', 'api03-', 'Ab12'.repeat(6)].join('')}`,
  'openai-project-key': `${['sk', '-proj-', 'Cd34'.repeat(6)].join('')}`,
  jwt: [
    'ey',
    'JhbGciOiJIUzI1NiJ9',
    '.',
    'ey',
    'JzdWIiOiIxMjM0In0',
    '.',
    'c2lnbmF0dXJlLXRlc3Q',
  ].join(''),
  'url-credentials': `"${['https', '://', 'deploy', ':', 'Hunter2wasHere', '@', 'example.org/x'].join('')}"`,
  'secret-assignment': `{ "client${'Secret'}": "${['q8Zr2', 'LmX0v', 'Tn4Kp', 'W9sYb'].join('')}" }`,
};

/** A folder holding `files` (relative path → text or bytes); returns it. */
function tree(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-secrets-'));
  temporary.push(root);
  for (const [file, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), content);
  }
  return root;
}

const scan = (files, options = {}) => {
  const root = tree(files);
  return scanFiles({ root, files: Object.keys(files), ...options });
};

describe('the secret-pattern scan', () => {
  it('has a crafted value for every pattern', () => {
    assert.deepEqual(Object.keys(CRAFTED).sort(), [...PATTERN_IDS].sort());
  });

  for (const [id, text] of Object.entries(CRAFTED))
    it(`finds ${id}`, () => {
      const { findings, errors } = scan({
        'info/assets/branding/xx.json': `{\n"a": 1,\n${text}\n}`,
      });
      assert.deepEqual(errors, []);
      assert.ok(
        findings.some((line) => line.startsWith(`info/assets/branding/xx.json:3: ${id} (`)),
        `no ${id} finding in:\n${findings.join('\n') || '(none)'}`,
      );
    });

  it('never prints a secret: at most its first four characters and its length', () => {
    for (const text of Object.values(CRAFTED)) {
      const [finding] = scanText(text);
      assert.ok(finding, text);
      assert.match(finding.hint, /^.{0,4}…, \d+ characters$/s);
    }
    const value = ['q8Zr2', 'LmX0v', 'Tn4Kp', 'W9sYb'].join('');
    const { findings } = scan({ 'a.ts': `const password = '${value}';` });
    assert.deepEqual(findings, ['a.ts:1: secret-assignment (q8Zr…, 20 characters)']);
    assert.ok(!findings[0].includes(value.slice(0, 5)));
  });

  it("passes the entropy rule's near misses", () => {
    const fifteen = ['Ab12', 'Cd34', 'Ef56', 'Gh7'].join('');
    assert.equal(fifteen.length, 15);
    const { findings } = scan({
      'text-overrides/en.json': JSON.stringify(
        {
          admin: {
            'login.password.hint': { text: 'Enter your password here', fp: '0123456789abcdef' },
          },
        },
        null,
        2,
      ),
      'admin/app/x.ts': [
        `const password = '${fifteen}';`,
        'const token = `${TOKEN}`;',
        "const apiKey = '<your api key here>';",
        `const secret = '${'a'.repeat(24)}';`,
        "const secret = '';",
        "const UNKNOWN_TOKEN = 'admin.publications.error.criteriaUnknownToken';",
        '"token": "PROMULGATION_RENDERERS",',
        "token: 'access-token-xyz',",
        `"admin.promulgation.twitter.accessTokenSecret": "3efeb5b0889fa14d",`,
        `const tokenData = '${['q8Zr2', 'LmX0v', 'Tn4Kp', 'W9sYb'].join('')}';`,
        `if (token === '${['q8Zr2', 'LmX0v', 'Tn4Kp', 'W9sYb'].join('')}') {}`,
      ].join('\n'),
    });
    assert.deepEqual(findings, []);
    assert.ok(entropy('abcd') === 2);
  });

  it('lets an allow entry suppress exactly its pattern in exactly its files', () => {
    const files = {
      'admin/a.json': `${CRAFTED['github-token']}\n${CRAFTED['aws-access-key-id']}`,
      'admin/b.json': CRAFTED['github-token'],
    };
    const allow = {
      allow: [
        { pattern: 'github-token', files: 'admin/a.json', scope: 'inputs', reason: 'a fixture' },
      ],
    };
    const { findings, errors } = scan(files, { allow });
    assert.deepEqual(errors, []);
    assert.deepEqual(
      findings.map((line) => line.replace(/ \(.*$/, '')),
      ['admin/a.json:2: aws-access-key-id', 'admin/b.json:1: github-token'],
    );
    // The same entry is not applied to another scope, nor to another country than its own.
    assert.equal(scan(files, { allow, scope: 'dist' }).findings.length, 3);
    const other = { allow: [{ ...allow.allow[0], country: 'gl' }] };
    assert.equal(scan(files, { allow: other, country: 'dk' }).findings.length, 3);
    assert.equal(scan(files, { allow: other, country: 'gl' }).findings.length, 2);
  });

  it('fails an allow entry without a reason, with an unknown id or scope, matching no file, or for a code that is not a country code', () => {
    const files = { 'admin/a.json': '{}' };
    const entry = { pattern: 'jwt', files: 'admin/a.json', scope: 'inputs', reason: 'why' };
    const errors = (patch) => scan(files, { allow: { allow: [{ ...entry, ...patch }] } }).errors;
    assert.match(errors({ reason: ' ' }).join('\n'), /allow\[0\] has no reason/);
    assert.match(errors({ reason: undefined }).join('\n'), /allow\[0\] has no reason/);
    assert.match(errors({ pattern: 'password' }).join('\n'), /"password" is not a pattern id/);
    assert.match(errors({ scope: 'all' }).join('\n'), /scope is inputs or dist/);
    assert.match(errors({ extra: 1 }).join('\n'), /unknown field\(s\) extra/);
    assert.match(errors({ files: 'admin/{a' }).join('\n'), /without its "\}"/);
    assert.match(errors({ files: 'info/**' }).join('\n'), /match no file of the inputs scan/);
    assert.match(
      errors({ country: 'xxx' }).join('\n'),
      /allow\[0\]: "xxx" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z/,
    );
    assert.deepEqual(errors({ country: 'gl' }), []);
    assert.deepEqual(errors({}), []);
  });

  it('skips a binary file, by its extension or a NUL byte', () => {
    const token = Buffer.from(CRAFTED['github-token']);
    const { findings, scanned } = scan({
      'info/assets/branding/logo.png': token,
      'styles/fonts/x/face.woff2': token,
      'admin/assets/blob.bin': Buffer.concat([Buffer.from([0]), token]),
      'admin/assets/text.json': token,
    });
    assert.equal(scanned, 1);
    assert.deepEqual(
      findings.map((line) => line.replace(/ \(.*$/, '')),
      ['admin/assets/text.json:1: github-token'],
    );
  });

  it('finds nothing in the kit itself, the tests and the example included', () => {
    const root = path.dirname(KIT);
    const { findings } = scanFiles({ root, files: filesUnder(root, 'country-kit') });
    assert.deepEqual(findings, []);
  });
});
