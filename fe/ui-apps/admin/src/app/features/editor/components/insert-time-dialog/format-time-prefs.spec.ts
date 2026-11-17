import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { readFormatTimeSettings, writeFormatTimeSettings } from './format-time-prefs';

const KEY = 'formatTimeSettings';

describe('format-time-prefs', () => {
  beforeEach(() => localStorage.removeItem(KEY));
  afterEach(() => localStorage.removeItem(KEY));

  it('defaults the time-zone flag to on when nothing is stored', () => {
    expect(readFormatTimeSettings()).toEqual({ tz: true });
  });

  it('round-trips a written value', () => {
    writeFormatTimeSettings({ tz: false });
    expect(readFormatTimeSettings()).toEqual({ tz: false });
    expect(localStorage.getItem(KEY)).toBe('{"tz":false}');
  });

  it('falls back to the default on malformed JSON', () => {
    localStorage.setItem(KEY, 'nope');
    expect(readFormatTimeSettings()).toEqual({ tz: true });
  });

  it('ignores a non-boolean tz value', () => {
    localStorage.setItem(KEY, JSON.stringify({ tz: 'yes' }));
    expect(readFormatTimeSettings()).toEqual({ tz: true });
  });
});
