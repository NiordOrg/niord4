import { describe, expect, it } from 'vitest';

import { type DistanceUnit, formatDistance } from './distance-format';

/** The unit symbols as the English texts give them. */
const english = (unit: DistanceUnit): string => unit;

describe('formatDistance', () => {
  it('formats metres (grouped, whole), km and nm', () => {
    const d = formatDistance(1852, 'en', english);
    expect(d.meters).toBe('1,852 m');
    expect(d.kilometers).toBe('1.85 km');
    expect(d.nauticalMiles).toBe('1.00 nm');
  });

  it('rounds metres to whole numbers', () => {
    expect(formatDistance(1234.7, 'en', english).meters).toBe('1,235 m');
  });

  it('uses the locale separators (da: comma decimal, dot grouping)', () => {
    const d = formatDistance(1500, 'da', english);
    // da groups thousands with a dot and uses a comma decimal.
    expect(d.meters).toBe('1.500 m');
    expect(d.kilometers).toBe('1,50 km');
  });

  it("writes each unit with the language's text for it", () => {
    const danish = (unit: DistanceUnit): string => (unit === 'nm' ? 'sm' : unit);
    const d = formatDistance(3704, 'da', danish);
    expect(d.nauticalMiles).toBe('2,00 sm');
    expect(d.meters).toBe('3.704 m');
    expect(d.kilometers).toBe('3,70 km');
  });
});
