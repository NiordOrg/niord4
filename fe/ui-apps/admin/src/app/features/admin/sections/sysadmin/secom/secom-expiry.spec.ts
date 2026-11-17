import { describe, expect, it } from 'vitest';

import { expiryToneClass, isExpired } from './secom-expiry';

describe('expiryToneClass', () => {
  it('is danger at and below the 14-day critical threshold', () => {
    expect(expiryToneClass(14)).toBe('text-danger');
    expect(expiryToneClass(0)).toBe('text-danger');
  });

  it('stays danger once already expired (negative days)', () => {
    expect(expiryToneClass(-1)).toBe('text-danger');
    expect(expiryToneClass(-400)).toBe('text-danger');
  });

  it('is warning between the critical and 60-day warning thresholds', () => {
    expect(expiryToneClass(15)).toBe('text-warning');
    expect(expiryToneClass(60)).toBe('text-warning');
  });

  it('is untinted beyond the warning threshold', () => {
    expect(expiryToneClass(61)).toBe('');
    expect(expiryToneClass(3650)).toBe('');
  });
});

describe('isExpired', () => {
  it('is true only for a lapsed certificate', () => {
    expect(isExpired(-1)).toBe(true);
    // The expiry day itself has not lapsed yet.
    expect(isExpired(0)).toBe(false);
    expect(isExpired(30)).toBe(false);
  });
});
