import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { buildReport, isLocal, KIT_ARTIFACT, reportText } from './lib/build-report.mjs';

const MANIFEST = {
  country: 'xx',
  name: 'X',
  apps: { admin: { contentLanguages: ['en'], uiLanguages: ['en'] }, info: { uiLanguages: ['en'] } },
};
const BASE = 'c'.repeat(64);
const KIT = 'd'.repeat(64);
const PINNED = `pinned: org.niord.web:niord-fe-src:4.0.1:zip sha256:${BASE}`;
const report = ({ provenance = PINNED, packSources = { en: 'base' }, kitSha256 } = {}) =>
  buildReport({
    version: '4.0.1',
    sha256: BASE,
    provenance,
    manifest: MANIFEST,
    packSources,
    accepted: [],
    ...(kitSha256 ? { kitSha256 } : {}),
  });

describe('the build report', () => {
  it('names no kit package when no kit hash is known, byte for byte as before', () => {
    const text = reportText(report());
    assert.equal(
      text,
      [
        '{',
        '  "format": 1,',
        '  "kit": "4.0.1",',
        '  "base": {',
        '    "version": "4.0.1",',
        `    "sha256": "${BASE}"`,
        '  },',
        `  "provenance": "${PINNED}",`,
        '  "country": {',
        '    "code": "xx",',
        '    "name": "X"',
        '  },',
        '  "packs": {',
        '    "en": {',
        '      "source": "base",',
        '      "apps": [',
        '        "admin",',
        '        "info"',
        '      ]',
        '    }',
        '  },',
        '  "escapeHatch": [],',
        '  "overrides": [],',
        '  "dependencies": [],',
        '  "textOverrides": {},',
        '  "auditAccepted": 0',
        '}',
        '',
      ].join('\n'),
    );
    assert.deepEqual(Object.keys(JSON.parse(text).base), ['version', 'sha256']);
  });

  it('records the kit hash and names both packages in the provenance', () => {
    const made = report({ kitSha256: KIT });
    assert.deepEqual(made.base, { version: '4.0.1', sha256: BASE, kit: { sha256: KIT } });
    assert.equal(
      made.provenance,
      `pinned: org.niord.web:niord-fe-src:4.0.1:zip sha256:${BASE}; org.niord:niord-country-kit:4.0.1:zip sha256:${KIT}`,
    );
    assert.equal(isLocal(made), false);
  });

  it('keeps a local source first', () => {
    const made = report({ provenance: 'local: /x/niord-ng/fe @ abc', kitSha256: KIT });
    assert.equal(
      made.provenance,
      `local: /x/niord-ng/fe @ abc; org.niord:niord-country-kit:4.0.1:zip sha256:${KIT}`,
    );
    assert.equal(isLocal(made), true);
  });

  it('says a local pack first, over both packages', () => {
    const made = report({ packSources: { en: 'local: /x' }, kitSha256: KIT });
    assert.equal(
      made.provenance,
      `local: packs from NIORD_PACK_* over ${PINNED}; org.niord:niord-country-kit:4.0.1:zip sha256:${KIT}`,
    );
    assert.equal(isLocal(made), true);
  });

  it('refuses a kit hash that is not 64 lower-case hex digits', () => {
    assert.throws(() => report({ kitSha256: 'D'.repeat(64) }), /is not a SHA-256/);
    assert.throws(() => report({ kitSha256: 'd'.repeat(63) }), /is not a SHA-256/);
  });

  it('names the kit package by its Maven coordinates, without the version', () => {
    assert.equal(KIT_ARTIFACT, 'org.niord:niord-country-kit');
  });
});
