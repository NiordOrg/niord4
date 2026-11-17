import { describe, expect, it } from 'vitest';

import { insertToken } from './token-insert';

/**
 * Where a picked token lands in the text.
 *
 * The whole point of the affordance is that the writer does not have to type
 * `${uge}` correctly — so the two cases that matter are the untouched field
 * (append) and the one whose caret is somewhere in the middle (splice).
 */
describe('insertToken', () => {
  it('appends when the field has never held the caret for this text', () => {
    expect(insertToken('EfS ', '${uge}', null)).toEqual({ value: 'EfS ${uge}', caret: 10 });
    expect(insertToken('', '${år}', null)).toEqual({ value: '${år}', caret: 5 });
  });

  it('splices at the caret and leaves it after the token', () => {
    const result = insertToken('EfS /2026', '${uge}', { start: 4, end: 4 });
    expect(result.value).toBe('EfS ${uge}/2026');
    expect(result.caret).toBe(10);
  });

  it('replaces a selection rather than inserting beside it', () => {
    const result = insertToken('EfS 27/2026', '${uge}', { start: 4, end: 6 });
    expect(result.value).toBe('EfS ${uge}/2026');
  });

  /**
   * The selection survives the blur an opening menu causes, so it can outlive
   * the text it was taken against. Clamped rather than trusted.
   */
  it('clamps a range that no longer fits the text', () => {
    expect(insertToken('EfS', '${uge}', { start: 99, end: 120 }).value).toBe('EfS${uge}');
    expect(insertToken('EfS', '${uge}', { start: -5, end: 1 }).value).toBe('${uge}fS');
  });

  it('treats an end before the start as an empty caret at the start', () => {
    expect(insertToken('EfS', '${uge}', { start: 2, end: 0 }).value).toBe('Ef${uge}S');
  });
});
