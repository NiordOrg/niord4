import { describe, expect, it } from 'vitest';

import en from '../../../../../../languages/en/i18n/admin/en.json';
import { translationValues } from './translation-values';

describe('translationValues', () => {
  it('flattens nested groups to dotted keys', () => {
    expect(translationValues({ a: { b: 'B', c: { d: 'D' } }, e: 'E' })).toEqual({
      'a.b': 'B',
      'a.c.d': 'D',
      e: 'E',
    });
  });

  it('leaves blank texts out, so a blank layer never covers a filled one', () => {
    expect(translationValues({ a: 'A', b: '', c: ' ' })).toEqual({ a: 'A' });
  });

  /**
   * The loader flattens the bundle with this function, so a blank English text never reaches
   * Transloco: the key is missing and the screen prints the key itself. A text meant to show
   * nothing is therefore not a blank value; it is a real word, or a template that shows nothing
   * without asking for a key.
   */
  it('finds no blank text in the English administration bundle', () => {
    const blank: string[] = [];
    const walk = (group: Record<string, unknown>, prefix: string): void => {
      for (const [key, value] of Object.entries(group)) {
        const path = prefix ? `${prefix}.${key}` : key;
        if (typeof value === 'string') {
          if (!value.trim()) blank.push(path);
        } else if (value && typeof value === 'object') {
          walk(value as Record<string, unknown>, path);
        }
      }
    };
    walk(en as Record<string, unknown>, '');
    expect(blank).toEqual([]);
  });
});
