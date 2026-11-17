import { describe, expect, it } from 'vitest';

import { formatDistance } from './distance-format';

describe('formatDistance', () => {
  it('formats metres (grouped, whole), km and nm', () => {
    const d = formatDistance(1852, 'en');
    expect(d.meters).toBe('1,852 m');
    expect(d.kilometers).toBe('1.85 km');
    expect(d.nauticalMiles).toBe('1.00 nm');
  });

  it('rounds metres to whole numbers', () => {
    expect(formatDistance(1234.7, 'en').meters).toBe('1,235 m');
  });

  it('uses the locale separators (da: comma decimal, dot grouping)', () => {
    const d = formatDistance(1500, 'da');
    // da groups thousands with a dot and uses a comma decimal.
    expect(d.meters).toBe('1.500 m');
    expect(d.kilometers).toBe('1,50 km');
  });
});
