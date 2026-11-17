import { afterEach, describe, expect, it } from 'vitest';

import { readCssVar } from './css-tokens';

describe('readCssVar', () => {
  afterEach(() => {
    document.documentElement.style.removeProperty('--test-resolved');
    document.documentElement.style.removeProperty('--test-padded');
    document.documentElement.style.removeProperty('--test-empty');
  });

  it('returns the resolved value when the variable is defined on :root', () => {
    document.documentElement.style.setProperty('--test-resolved', '#abcdef');
    expect(readCssVar('--test-resolved', '#fallback')).toBe('#abcdef');
  });

  it('returns the fallback when the variable is not defined', () => {
    expect(readCssVar('--test-undefined', '#fallback')).toBe('#fallback');
  });

  it('trims whitespace from the resolved value', () => {
    document.documentElement.style.setProperty('--test-padded', '  red  ');
    expect(readCssVar('--test-padded', '#fallback')).toBe('red');
  });

  it('returns the fallback when the variable is defined as empty', () => {
    // CSS custom properties can be defined as empty strings; we treat
    // empty as "not set" so the fallback kicks in.
    document.documentElement.style.setProperty('--test-empty', '');
    expect(readCssVar('--test-empty', '#fallback')).toBe('#fallback');
  });
});
