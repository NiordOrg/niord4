import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { readFormatLocationSettings, writeFormatLocationSettings } from './format-location-prefs';

const KEY = 'formatLocationSettings';

describe('format-location-prefs', () => {
  beforeEach(() => localStorage.removeItem(KEY));
  afterEach(() => localStorage.removeItem(KEY));

  it('returns the defaults when nothing is stored', () => {
    expect(readFormatLocationSettings()).toEqual({ template: 'numbered-list', format: 'dec' });
  });

  it("migrates a stored 'list' template to 'numbered-list' on read", () => {
    localStorage.setItem(KEY, JSON.stringify({ template: 'list', format: 'sec' }));
    expect(readFormatLocationSettings()).toEqual({ template: 'numbered-list', format: 'sec' });
  });

  it('leaves a non-renamed template untouched', () => {
    localStorage.setItem(KEY, JSON.stringify({ template: 'from-to-table', format: 'navtex' }));
    expect(readFormatLocationSettings()).toEqual({
      template: 'from-to-table',
      format: 'navtex',
    });
  });

  it('round-trips a written value', () => {
    writeFormatLocationSettings({ template: 'table', format: 'dec-2' });
    expect(readFormatLocationSettings()).toEqual({ template: 'table', format: 'dec-2' });
  });

  it('falls back to the defaults on malformed JSON', () => {
    localStorage.setItem(KEY, '{ not json');
    expect(readFormatLocationSettings()).toEqual({ template: 'numbered-list', format: 'dec' });
  });

  it('fills missing fields from the defaults', () => {
    localStorage.setItem(KEY, JSON.stringify({ template: 'text' }));
    expect(readFormatLocationSettings()).toEqual({ template: 'text', format: 'dec' });
  });
});
