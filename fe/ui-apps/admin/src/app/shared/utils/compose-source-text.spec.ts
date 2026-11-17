import { describe, expect, it } from 'vitest';

import {
  composeSourceText,
  type ComposeSourceLanguage,
  type SelectedSource,
} from './compose-source-text';

const EN: ComposeSourceLanguage = { lang: 'en', locale: 'en-GB', and: 'and' };
const DA: ComposeSourceLanguage = { lang: 'da', locale: 'da', and: 'og' };
const FR: ComposeSourceLanguage = { lang: 'fr', locale: 'fr-FR', and: 'et' };

// Instants at midday, formatted in UTC: the day that prints is fixed by the pair, so no
// assertion below depends on the zone the suite happens to run in.
const UTC = { timeZone: 'UTC' } as const;
const may2016 = new Date('2016-05-04T12:00:00.000Z');
const jun2016 = new Date('2016-06-10T12:00:00.000Z');
const may2015 = new Date('2015-05-04T12:00:00.000Z');

const ema: SelectedSource = {
  descs: [
    { lang: 'en', abbreviation: 'EMA', name: 'Example Maritime Authority' },
    { lang: 'da', abbreviation: 'ES', name: 'Eksempelstyrelsen' },
    { lang: 'fr', abbreviation: 'AME', name: "Autorité maritime d'exemple" },
  ],
};
const iho: SelectedSource = {
  descs: [
    { lang: 'en', abbreviation: 'IHO', name: 'International Hydrographic Organization' },
    { lang: 'da', abbreviation: 'IHO', name: 'Den Internationale Hydrografiske Organisation' },
    { lang: 'fr', abbreviation: 'OHI', name: 'Organisation Hydrographique Internationale' },
  ],
};

describe('composeSourceText', () => {
  it('appends a shared year once and joins the last item with the localized "and"', () => {
    const result = composeSourceText(
      [
        { ...ema, date: may2016 },
        { ...iho, date: jun2016 },
      ],
      [EN, DA],
      UTC,
    );
    expect(result['en']).toBe('EMA 4 May and IHO 10 June 2016');
    expect(result['da']).toBe('ES 4. maj og IHO 10. juni 2016');
  });

  it('renders a per-item year when the sources span different years', () => {
    const result = composeSourceText(
      [
        { ...ema, date: may2015 },
        { ...iho, date: jun2016 },
      ],
      [EN, DA],
      UTC,
    );
    expect(result['en']).toBe('EMA 4 May 2015 and IHO 10 June 2016');
    expect(result['da']).toBe('ES 4. maj 2015 og IHO 10. juni 2016');
  });

  it('scales to N=3 configured languages', () => {
    const result = composeSourceText(
      [
        { ...ema, date: may2016 },
        { ...iho, date: jun2016 },
      ],
      [EN, DA, FR],
      UTC,
    );
    expect(Object.keys(result)).toEqual(['en', 'da', 'fr']);
    expect(result['en']).toBe('EMA 4 May and IHO 10 June 2016');
    expect(result['da']).toBe('ES 4. maj og IHO 10. juni 2016');
    expect(result['fr']).toBe('AME 4 mai et OHI 10 juin 2016');
  });

  it('omits dates and the trailing year when no source carries a date', () => {
    const result = composeSourceText([ema, iho], [EN, DA], UTC);
    expect(result['en']).toBe('EMA and IHO');
    expect(result['da']).toBe('ES og IHO');
  });

  it('treats a single source without an "and" join', () => {
    const result = composeSourceText([{ ...ema, date: may2016 }], [EN], UTC);
    expect(result['en']).toBe('EMA 4 May 2016');
  });

  it('falls back to the name when the abbreviation is empty', () => {
    const source: SelectedSource = {
      descs: [{ lang: 'en', abbreviation: '', name: 'Full Source Name' }],
    };
    const result = composeSourceText([source], [EN], UTC);
    expect(result['en']).toBe('Full Source Name');
  });

  it('skips sources with no desc in a language but keeps others', () => {
    const enOnly: SelectedSource = {
      descs: [{ lang: 'en', abbreviation: 'ENO', name: 'English Only' }],
    };
    const result = composeSourceText([enOnly], [EN, DA], UTC);
    expect(result['en']).toBe('ENO');
    expect(result['da']).toBe('');
  });

  it('drops the shared-year collapse when one source has no date', () => {
    const result = composeSourceText([{ ...ema, date: may2016 }, iho], [EN], UTC);
    // No common year: the dated item keeps its own year, the undated item has none.
    expect(result['en']).toBe('EMA 4 May 2016 and IHO');
  });

  describe('the time zone the dates are read in', () => {
    // 23:30Z on the 5th is still the 5th in UTC and already the 6th in Copenhagen.
    const lateEvening = new Date('2026-03-05T23:30:00.000Z');

    it('prints the day the instant falls on in the given zone', () => {
      expect(
        composeSourceText([{ ...ema, date: lateEvening }], [EN], { timeZone: 'UTC' })['en'],
      ).toBe('EMA 5 March 2026');
      expect(
        composeSourceText([{ ...ema, date: lateEvening }], [EN], {
          timeZone: 'Europe/Copenhagen',
        })['en'],
      ).toBe('EMA 6 March 2026');
    });

    it('keeps the zone per language, not per locale', () => {
      const result = composeSourceText([{ ...ema, date: lateEvening }], [EN, DA], {
        timeZone: 'Europe/Copenhagen',
      });
      expect(result['en']).toBe('EMA 6 March 2026');
      expect(result['da']).toBe('ES 6. marts 2026');
    });

    it('resolves the shared year in the zone too', () => {
      // 20:00Z on 31 December is already 01:30 on New Year's Day in Kolkata (UTC+5:30).
      const newYearEve = new Date('2025-12-31T20:00:00.000Z');
      const june = new Date('2026-06-10T06:00:00.000Z');
      const sources = [
        { ...ema, date: newYearEve },
        { ...iho, date: june },
      ];

      // One 2026 year, appended once at the end.
      expect(composeSourceText(sources, [EN], { timeZone: 'Asia/Kolkata' })['en']).toBe(
        'EMA 1 January and IHO 10 June 2026',
      );
      // Two years in UTC, so each item carries its own.
      expect(composeSourceText(sources, [EN], { timeZone: 'UTC' })['en']).toBe(
        'EMA 31 December 2025 and IHO 10 June 2026',
      );
    });
  });
});
