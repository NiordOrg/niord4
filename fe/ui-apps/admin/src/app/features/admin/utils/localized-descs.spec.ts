import { describe, expect, it } from 'vitest';

import { currentDesc, orderDescs, padAndOrderDescs } from './localized-descs';

interface TestDesc {
  readonly lang: string;
  readonly name: string;
}

const empty = (lang: string): TestDesc => ({ lang, name: '' });

describe('orderDescs', () => {
  it('orders in configured-list order, WITHOUT padding', () => {
    const result = orderDescs(
      [
        { lang: 'da', name: 'D' },
        { lang: 'en', name: 'E' },
      ],
      ['en', 'da', 'kl'],
    );
    expect(result.map((d) => d.lang)).toEqual(['en', 'da']); // no 'kl' row invented
  });

  it('never reorders by the UI language — configured order is the only order', () => {
    // A deployment that configures [da, en]; a user browsing in English must still see da first.
    const result = orderDescs(
      [
        { lang: 'en', name: 'E' },
        { lang: 'da', name: 'D' },
      ],
      ['da', 'en'],
    );
    expect(result.map((d) => d.lang)).toEqual(['da', 'en']);
  });

  it('appends unconfigured languages last, keeping their original order', () => {
    const result = orderDescs(
      [
        { lang: 'fo', name: 'F' },
        { lang: 'kl', name: 'K' },
        { lang: 'en', name: 'E' },
      ],
      ['en', 'da'],
    );
    expect(result.map((d) => d.lang)).toEqual(['en', 'fo', 'kl']);
  });
});

describe('padAndOrderDescs', () => {
  it('pads missing languages with empty entries (create AND edit shape)', () => {
    const result = padAndOrderDescs([{ lang: 'da', name: 'Kilde' }], ['en', 'da'], empty);
    expect(result.map((d) => d.lang)).toEqual(['en', 'da']);
    expect(result[0].name).toBe('');
    expect(result[1].name).toBe('Kilde');
  });

  it('renders configured-list order regardless of the incoming desc order', () => {
    const result = padAndOrderDescs(
      [
        { lang: 'da', name: 'D' },
        { lang: 'en', name: 'E' },
      ],
      ['en', 'da'],
      empty,
    );
    expect(result.map((d) => d.lang)).toEqual(['en', 'da']);
  });

  it('is driven entirely by the configured list — a 3-language config yields 3 rows', () => {
    const result = padAndOrderDescs([{ lang: 'da', name: 'D' }], ['en', 'da', 'kl'], empty);
    expect(result.map((d) => d.lang)).toEqual(['en', 'da', 'kl']);
    expect(result.filter((d) => d.name === '').length).toBe(2);
  });

  it('preserves server-returned descs in unconfigured languages (never dropped)', () => {
    const result = padAndOrderDescs(
      [
        { lang: 'fo', name: 'Færøsk' },
        { lang: 'en', name: 'E' },
      ],
      ['en', 'da'],
      empty,
    );
    expect(result.map((d) => d.lang)).toEqual(['en', 'da', 'fo']);
    expect(result[2].name).toBe('Færøsk');
  });
});

describe('currentDesc', () => {
  it('uses the explicit default for missing fields without changing stored translations', () => {
    const descs = [
      { lang: 'en', name: 'English', abbreviation: 'EN' },
      { lang: 'da', name: 'Dansk', abbreviation: 'DA' },
      { lang: 'kl', name: '   ', abbreviation: 'KL' },
    ];
    expect(currentDesc(descs, ['en', 'kl', 'da'], 'kl', 'da')).toEqual({
      lang: 'kl',
      name: 'Dansk',
      abbreviation: 'KL',
    });
    expect(descs[2].name).toBe('   ');
    expect(currentDesc(descs.slice(0, 2), ['en', 'kl', 'da'], 'kl', 'da')?.name).toBe('Dansk');
  });

  it('picks the current UI language when present', () => {
    const desc = currentDesc(
      [
        { lang: 'en', name: 'E' },
        { lang: 'da', name: 'D' },
      ],
      ['en', 'da'],
      'da',
    );
    expect(desc?.name).toBe('D');
  });

  it('falls back to the first configured language when the current one is missing', () => {
    const desc = currentDesc([{ lang: 'en', name: 'E' }], ['en', 'da'], 'da');
    expect(desc?.name).toBe('E');
  });

  it('falls back to the first desc at all when nothing configured matches', () => {
    const desc = currentDesc([{ lang: 'fo', name: 'F' }], ['en', 'da'], 'da');
    expect(desc?.name).toBe('F');
  });

  it('returns undefined for an empty list', () => {
    expect(currentDesc([], ['en', 'da'], 'en')).toBeUndefined();
  });
});
