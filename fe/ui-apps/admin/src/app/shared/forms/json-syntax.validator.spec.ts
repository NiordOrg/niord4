import { describe, expect, it } from 'vitest';

import {
  formStringToJsonObject,
  jsonObjectToFormString,
  jsonStringValid,
} from './json-syntax.validator';

describe('jsonStringValid', () => {
  it('treats empty as valid (serializes to null on save)', () => {
    expect(jsonStringValid('')).toBe(true);
  });

  it('accepts well-formed JSON', () => {
    expect(jsonStringValid('{ "a": 1 }')).toBe(true);
    expect(jsonStringValid('[1, 2, 3]')).toBe(true);
  });

  it('rejects malformed JSON', () => {
    expect(jsonStringValid('{ a: }')).toBe(false);
    expect(jsonStringValid('not json')).toBe(false);
  });
});

describe('JSON object <-> form string round-trip', () => {
  it('pretty-prints an object and parses it back', () => {
    const obj = { mapThumbnails: false, nested: { x: 1 } };
    const str = jsonObjectToFormString(obj);
    expect(str).toContain('mapThumbnails');
    expect(formStringToJsonObject(str)).toEqual(obj);
  });

  it('maps nullish to empty string and empty string back to null', () => {
    expect(jsonObjectToFormString(undefined)).toBe('');
    expect(jsonObjectToFormString(null)).toBe('');
    expect(formStringToJsonObject('')).toBeNull();
  });
});
