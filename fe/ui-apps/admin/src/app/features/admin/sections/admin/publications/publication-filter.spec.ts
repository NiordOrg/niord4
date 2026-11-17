import { describe, expect, it } from 'vitest';

import { localizedNames, matchesFilter, normalizeFilterText } from './publication-filter';

/**
 * What the list filters compare, and what they refuse to make the reader spell.
 *
 * The two rules under test are the ones that decide whether a publication is
 * findable at all: a name in a language the reader is not reading, and a letter
 * they typed without its accent.
 */
describe('publication list filtering', () => {
  it('folds case and combining marks into one comparison form', () => {
    expect(normalizeFilterText('Ugentlige')).toBe('ugentlige');
    // Å decomposes to A + ring, and the ring is dropped.
    expect(normalizeFilterText('Årlig')).toBe('arlig');
    expect(normalizeFilterText('  Uge 27  ')).toBe('uge 27');
  });

  /**
   * æ and ø carry no combining mark and stay themselves.
   *
   * A fold, not a transliteration: a Danish reader typing "søkort" expects it to
   * find "Søkort", and turning ø into o would be a different promise than the
   * one an accent-insensitive filter makes.
   */
  it('leaves letters that are not accented forms alone', () => {
    expect(normalizeFilterText('Søkort')).toBe('søkort');
    expect(normalizeFilterText('Fyrliste æ')).toBe('fyrliste æ');
  });

  it('matches an accented name from an unaccented query, and the other way round', () => {
    expect(matchesFilter(['Årlig oversigt'], 'arlig')).toBe(true);
    expect(matchesFilter(['Arlig oversigt'], 'Årlig')).toBe(true);
  });

  it('matches case-insensitively on any part of the name', () => {
    expect(matchesFilter(['Weekly Notices to Mariners'], 'NOTICES')).toBe(true);
    expect(matchesFilter(['Weekly Notices to Mariners'], 'mariner')).toBe(true);
    expect(matchesFilter(['Weekly Notices to Mariners'], 'annual')).toBe(false);
  });

  /**
   * Every language, not the one being rendered.
   *
   * The archive holds series named only in Danish; a filter that looked at the
   * active language alone would report them as missing to an English reader.
   */
  it('matches a name in any of the languages the entity carries', () => {
    const names = localizedNames([
      { name: 'Efterretninger for Søfarende' },
      { name: 'Notices to Mariners' },
    ]);
    expect(matchesFilter(names, 'efterretninger')).toBe(true);
    expect(matchesFilter(names, 'mariners')).toBe(true);
  });

  it('treats an empty or blank query as no filter at all', () => {
    expect(matchesFilter(['anything'], '')).toBe(true);
    expect(matchesFilter(['anything'], '   ')).toBe(true);
    // Even a row with nothing to match against survives an empty query.
    expect(matchesFilter([], '')).toBe(true);
  });

  it('skips names an entity does not have rather than matching on them', () => {
    expect(matchesFilter([undefined, null, ''], 'uge')).toBe(false);
    expect(localizedNames(undefined)).toEqual([]);
    expect(localizedNames([{ name: 'Uge 27' }, {}])).toEqual(['Uge 27', undefined]);
  });
});
