import { describe, expect, it } from 'vitest';

import { isServableId } from './publication-id';

describe('isServableId', () => {
  it('takes the ids deployments hold today, and a blank one', () => {
    for (const id of ['dk-dma-internal-publications', 'NtM_2026', 'a.b', 'æøå', '...', '']) {
      expect(isServableId(id), id).toBe(true);
    }
  });

  it('refuses each character the request firewall refuses', () => {
    for (const id of ['a/b', 'a\\b', 'a%2Fb', 'a;b', 'a\nb', 'a\tb', 'a\u007fb', 'a\u0085b']) {
      expect(isServableId(id), JSON.stringify(id)).toBe(false);
    }
    expect(isServableId('a b')).toBe(false);
    expect(isServableId('a b')).toBe(false);
  });

  it('refuses an id of . or .., also with spaces the save trims', () => {
    expect(isServableId('.')).toBe(false);
    expect(isServableId('..')).toBe(false);
    expect(isServableId(' .. ')).toBe(false);
  });

  it('reads the id as it is sent, trimmed', () => {
    expect(isServableId('  dk-test\n')).toBe(true);
  });
});
