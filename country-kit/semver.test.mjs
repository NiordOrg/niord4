/**
 * Tests for country-kit/lib/semver.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * The cases are node-semver's own (its range-include and range-exclude fixtures, not loose, no
 * includePrerelease), so the kit's subset decides a peer range as npm does; then the ranges it
 * must refuse to read.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { compare, parseVersion, satisfies } from './lib/semver.mjs';

const INCLUDED = [
  ['1.0.0 - 2.0.0', '1.2.3'],
  ['^1.2.3+build', '1.2.3'],
  ['^1.2.3+build', '1.3.0'],
  ['1.2.3-pre+asdf - 2.4.3-pre+asdf', '1.2.3'],
  ['1.2.3-pre+asdf - 2.4.3-pre+asdf', '1.2.3-pre.2'],
  ['1.2.3-pre+asdf - 2.4.3-pre+asdf', '2.4.3-alpha'],
  ['1.2.3+asdf - 2.4.3+asdf', '1.2.3'],
  ['1.0.0', '1.0.0'],
  ['>=*', '0.2.4'],
  ['', '1.0.0'],
  ['*', '1.2.3'],
  ['>=1.0.0', '1.0.0'],
  ['>=1.0.0', '1.0.1'],
  ['>=1.0.0', '1.1.0'],
  ['>1.0.0', '1.0.1'],
  ['<=2.0.0', '2.0.0'],
  ['<=2.0.0', '1.9999.9999'],
  ['<2.0.0', '0.2.9'],
  ['>= 1.0.0', '1.0.0'],
  ['>=  1.0.0', '1.1.0'],
  ['<=  2.0.0', '0.2.9'],
  ['0.1.20 || 1.2.4', '1.2.4'],
  ['>=0.2.3 || <0.0.1', '0.0.0'],
  ['>=0.2.3 || <0.0.1', '0.2.3'],
  ['||', '1.3.4'],
  ['2.x.x', '2.1.3'],
  ['1.2.x', '1.2.3'],
  ['1.2.x || 2.x', '2.1.3'],
  ['x', '1.2.3'],
  ['2.*.*', '2.1.3'],
  ['1.2.*', '1.2.3'],
  ['2', '2.1.2'],
  ['2.3', '2.3.1'],
  ['~0.0.1', '0.0.1'],
  ['~0.0.1', '0.0.2'],
  ['~x', '0.0.9'],
  ['~2', '2.0.9'],
  ['~2.4', '2.4.0'],
  ['~2.4', '2.4.5'],
  ['~>3.2.1', '3.2.2'],
  ['~1', '1.2.3'],
  ['~>1', '1.2.3'],
  ['~> 1', '1.2.3'],
  ['~1.0', '1.0.2'],
  ['~ 1.0', '1.0.2'],
  ['~ 1.0.3', '1.0.12'],
  ['~ 1.0.3alpha', '1.0.12', 'throws'],
  ['>=1', '1.0.0'],
  ['>= 1', '1.0.0'],
  ['<1.2', '1.1.1'],
  ['< 1.2', '1.1.1'],
  ['~v0.5.4-pre', '0.5.5'],
  ['~v0.5.4-pre', '0.5.4'],
  ['=0.7.x', '0.7.2'],
  ['<=0.7.x', '0.7.2'],
  ['>=0.7.x', '0.7.2'],
  ['<=0.7.x', '0.6.2'],
  ['~1.2.1 >=1.2.3', '1.2.3'],
  ['~1.2.1 =1.2.3', '1.2.3'],
  ['~1.2.1 1.2.3', '1.2.3'],
  ['~1.2.1 >=1.2.3 1.2.3', '1.2.3'],
  ['>=1.2.1 1.2.3', '1.2.3'],
  ['1.2.3 >=1.2.1', '1.2.3'],
  ['>=1.2.3 >=1.2.1', '1.2.3'],
  ['>=1.2.1 >=1.2.3', '1.2.3'],
  ['>=1.2', '1.2.8'],
  ['^1.2.3', '1.8.1'],
  ['^0.1.2', '0.1.2'],
  ['^0.1', '0.1.2'],
  ['^0.0.1', '0.0.1'],
  ['^1.2', '1.4.2'],
  ['^1.2 ^1', '1.4.2'],
  ['^1.2.3-alpha', '1.2.3-pre'],
  ['^1.2.0-alpha', '1.2.0-pre'],
  ['^0.0.1-alpha', '0.0.1-beta'],
  ['^0.0.1-alpha', '0.0.1'],
  ['^0.1.1-alpha', '0.1.1-beta'],
  ['^x', '1.2.3'],
  ['x - 1.0.0', '0.9.7'],
  ['x - 1.x', '0.9.7'],
  ['1.0.0 - x', '1.9.7'],
  ['1.x - x', '1.9.7'],
  ['<=7.x', '7.9.9'],
  ['2.x', '2.0.0-pre.0', false],
  ['>=1.0.0 <1.1.0-0', '1.0.0-pre.0', false],
];

const EXCLUDED = [
  ['1.0.0 - 2.0.0', '2.2.3'],
  ['1.2.3+asdf - 2.4.3+asdf', '1.2.3-pre.2'],
  ['1.2.3+asdf - 2.4.3+asdf', '2.4.3-alpha'],
  ['^1.2.3+build', '2.0.0'],
  ['^1.2.3+build', '1.2.0'],
  ['^1.2.3', '1.2.3-pre'],
  ['^1.2', '1.2.0-pre'],
  ['>1.2', '1.3.0-beta'],
  ['<=1.2.3', '1.2.3-beta'],
  ['^1.2.3', '1.2.3-beta'],
  ['=0.7.x', '0.7.0-asdf'],
  ['>=0.7.x', '0.7.0-asdf'],
  ['<=0.7.x', '0.7.0-asdf'],
  ['1.0.0', '1.0.1'],
  ['>=1.0.0', '0.0.0'],
  ['>=1.0.0', '0.0.1'],
  ['>=1.0.0', '0.1.0'],
  ['>1.0.0', '0.0.1'],
  ['>1.0.0', '0.1.0'],
  ['<=2.0.0', '3.0.0'],
  ['<=2.0.0', '2.9999.9999'],
  ['<=2.0.0', '2.2.9'],
  ['<2.0.0', '2.9999.9999'],
  ['<2.0.0', '2.2.9'],
  ['>=0.1.97', '0.1.93'],
  ['0.1.20 || 1.2.4', '1.2.3'],
  ['>=0.2.3 || <0.0.1', '0.0.3'],
  ['>=0.2.3 || <0.0.1', '0.2.2'],
  ['2.x.x', '1.1.3'],
  ['2.x.x', '3.1.3'],
  ['1.2.x', '1.3.3'],
  ['1.2.x || 2.x', '3.1.3'],
  ['1.2.x || 2.x', '1.1.3'],
  ['2.*.*', '1.1.3'],
  ['2.*.*', '3.1.3'],
  ['1.2.*', '1.3.3'],
  ['2', '1.1.2'],
  ['2.3', '2.4.1'],
  ['~0.0.1', '0.1.0-alpha'],
  ['~0.0.1', '0.1.0'],
  ['~2.4', '2.5.0'],
  ['~2.4', '2.3.9'],
  ['~>3.2.1', '3.3.2'],
  ['~>3.2.1', '3.2.0'],
  ['~1', '0.2.3'],
  ['~>1', '2.2.3'],
  ['~1.0', '1.1.0'],
  ['<1', '1.0.0'],
  ['>=1.2', '1.1.1'],
  ['~v0.5.4-beta', '0.5.4-alpha'],
  ['=0.7.x', '0.8.2'],
  ['>=0.7.x', '0.6.2'],
  ['<0.7.x', '0.7.2'],
  ['<1.2.3', '1.2.3-beta'],
  ['=1.2.3', '1.2.3-beta'],
  ['>1.2', '1.2.8'],
  ['^0.0.1', '0.0.2'],
  ['^1.2.3', '2.0.0-alpha'],
  ['^1.2.3', '1.2.2'],
  ['^1.2', '1.1.9'],
  ['*', '1.2.3-foo'],
  ['^1.0.0', '2.0.0-rc1'],
  ['1 - 2', '2.0.0-pre'],
  ['1 - 2', '1.0.0-pre'],
  ['1.1.x', '1.0.0-a'],
  ['1.1.x', '1.1.0-a'],
  ['1.1.x', '1.2.0-a'],
  ['1.x', '1.0.0-a'],
  ['1.x', '1.1.0-a'],
  ['1.x', '1.2.0-a'],
  ['>=1.0.0 <1.1.0', '1.1.0'],
  ['>=1.0.0 <1.1.0', '1.1.0-pre'],
  ['>=1.0.0 <1.1.0-pre', '1.1.0-pre'],
  ['== 1.0.0 || foo', '2.0.0', 'throws'],
  ['>*', '1.0.0'],
  ['<*', '1.0.0'],
];

describe('semver', () => {
  it("decides node-semver's included cases as npm does", () => {
    for (const [range, version, expected = true] of INCLUDED) {
      if (expected === 'throws') {
        assert.throws(() => satisfies(version, range), undefined, `${range} must not be read`);
        continue;
      }
      assert.equal(satisfies(version, range), expected, `${version} in "${range}"`);
    }
  });

  it("decides node-semver's excluded cases as npm does", () => {
    for (const [range, version, expected] of EXCLUDED) {
      if (expected === 'throws') {
        assert.throws(() => satisfies(version, range), undefined, `${range} must not be read`);
        continue;
      }
      assert.equal(satisfies(version, range), false, `${version} not in "${range}"`);
    }
  });

  it("rejects TypeScript 6 against typescript-eslint 8.56's peer range", () => {
    const range = '>=4.8.4 <6.0.0';
    assert.equal(satisfies('6.0.3', range), false);
    assert.equal(satisfies('6.0.0-beta', range), false);
    assert.equal(satisfies('5.9.3', range), true);
    assert.equal(satisfies('4.8.4', range), true);
    assert.equal(satisfies('4.8.3', range), false);
  });

  it("decides the framework's own peer ranges", () => {
    assert.equal(satisfies('7.8.2', '^6.5.3 || ^7.4.0'), true);
    assert.equal(satisfies('22.2.2', '22.2.2'), true);
    assert.equal(satisfies('22.2.3', '22.2.2'), false);
    assert.equal(satisfies('22.2.2', '^22.0.0 || ^23.0.0'), true);
    assert.equal(satisfies('22.2.2', '>=21.0.0 <22.0.0'), false);
    assert.equal(satisfies('0.16.0', '~0.15.0 || ~0.16.0'), true);
  });

  it('throws on a range it cannot read, so a check fails rather than passes', () => {
    for (const range of [
      'latest',
      'next',
      'https://example.org/x.tgz',
      'git+https://github.com/a/b.git',
      'github:a/b',
      'a/b',
      'file:../x',
      'workspace:*',
      'workspace:^1.0.0',
      'npm:other@^1.0.0',
      '1.2.3.4',
      '>=1.2.3<2',
      '1 | 2',
      '!1.0.0',
    ])
      assert.throws(() => satisfies('1.0.0', range), /not a range|operator/, range);
    for (const version of ['1.2', 'latest', '01.2.3', '1.2.3-01', ''])
      assert.throws(() => satisfies(version, '*'), /not a version/, version);
  });

  it('orders prereleases as the specification does', () => {
    const ordered = [
      '1.0.0-0',
      '1.0.0-alpha',
      '1.0.0-alpha.1',
      '1.0.0-alpha.beta',
      '1.0.0-beta',
      '1.0.0-beta.2',
      '1.0.0-beta.11',
      '1.0.0-rc.1',
      '1.0.0',
      '1.0.1',
    ].map(parseVersion);
    for (let i = 1; i < ordered.length; i++) {
      assert.equal(compare(ordered[i - 1], ordered[i]), -1);
      assert.equal(compare(ordered[i], ordered[i - 1]), 1);
    }
    assert.equal(compare(parseVersion('1.0.0+a'), parseVersion('1.0.0+b')), 0);
  });
});
