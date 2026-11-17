import { describe, expect, it } from 'vitest';

import { formatLatLon, formatLatitude, formatLongitude, parseLatLon } from './coordinate-format';

describe('coordinate-format', () => {
  describe('formatLatitude', () => {
    it("formats a positive latitude as DD° MM.dd'N with 2 decimals", () => {
      // 53.119167° = 53° + (.119167 × 60) = 53° 07.15'
      expect(formatLatitude(53.119167, { decimals: 2, locale: 'en' })).toBe("53° 07.15'N");
    });

    it('uses S for negative latitudes', () => {
      expect(formatLatitude(-12.5, { decimals: 2, locale: 'en' })).toBe("12° 30.00'S");
    });

    it('zero-pads single-digit hours to 2 digits', () => {
      expect(formatLatitude(7.5, { decimals: 2, locale: 'en' })).toBe("07° 30.00'N");
    });
  });

  describe('formatLongitude', () => {
    it('formats with 3-digit hour padding (nautical convention)', () => {
      // 9.555333° = 9° + (.555333 × 60) = 9° 33.32'
      expect(formatLongitude(9.555333, { decimals: 2, locale: 'en' })).toBe("009° 33.32'E");
    });

    it('uses W for negative longitudes', () => {
      expect(formatLongitude(-45.25, { decimals: 2, locale: 'en' })).toBe("045° 15.00'W");
    });

    it('handles three-digit hours unchanged', () => {
      expect(formatLongitude(150.5, { decimals: 2, locale: 'en' })).toBe("150° 30.00'E");
    });
  });

  describe('formatLatLon', () => {
    it('joins lat then lon with " - "', () => {
      expect(formatLatLon(53.119167, 9.555333, { decimals: 2, locale: 'en' })).toBe(
        "53° 07.15'N - 009° 33.32'E",
      );
    });

    it('uses the locale decimal separator (comma for da)', () => {
      expect(formatLatLon(53.119167, 9.555333, { decimals: 2, locale: 'da' })).toBe(
        "53° 07,15'N - 009° 33,32'E",
      );
    });

    it('defaults to 3 decimals', () => {
      expect(formatLatLon(53.119167, 9.555333, { locale: 'en' })).toBe(
        "53° 07.150'N - 009° 33.320'E",
      );
    });
  });

  describe('minute rounding carry', () => {
    it('carries minutes that round up to 60 into the next degree', () => {
      // .9999999° × 60 = 59.999994', which rounds to "60.000" at 3 decimals -> carry to 02°
      expect(formatLatitude(1.9999999, { decimals: 3, locale: 'en' })).toBe("02° 00.000'N");
    });

    it('carries on longitude (179.9999999 -> 180° 00.000)', () => {
      expect(formatLongitude(179.9999999, { decimals: 3, locale: 'en' })).toBe("180° 00.000'E");
    });

    it('keeps the hemisphere when carrying a negative value', () => {
      expect(formatLatitude(-1.9999999, { decimals: 3, locale: 'en' })).toBe("02° 00.000'S");
    });

    it('carries at lower precision too (2 decimals)', () => {
      expect(formatLatitude(7.99999, { decimals: 2, locale: 'en' })).toBe("08° 00.00'N");
    });

    it('does not over-carry when minutes round just below 60', () => {
      // 59.994' rounds to "59.99" at 2 decimals -> no carry
      expect(formatLatitude(1.9999, { decimals: 2, locale: 'en' })).toBe("01° 59.99'N");
    });
  });

  describe('parseLatLon', () => {
    it('round-trips the canonical pretty format', () => {
      const parsed = parseLatLon("53° 07.150'N - 009° 33.320'E");
      expect(parsed?.lat).toBeCloseTo(53.119167, 5);
      expect(parsed?.lon).toBeCloseTo(9.555333, 5);
    });

    it('accepts the symbol-free form (DD MM.mmm X)', () => {
      const parsed = parseLatLon('53 07.150 N 9 33.320 E');
      expect(parsed?.lat).toBeCloseTo(53.119167, 5);
      expect(parsed?.lon).toBeCloseTo(9.555333, 5);
    });

    it('accepts decimal degrees with a hemisphere', () => {
      const parsed = parseLatLon('53.119167 N - 9.555333 E');
      expect(parsed?.lat).toBeCloseTo(53.119167, 5);
      expect(parsed?.lon).toBeCloseTo(9.555333, 5);
    });

    it('applies S/W as negative signs', () => {
      const parsed = parseLatLon("12° 30.000'S - 045° 15.000'W");
      expect(parsed?.lat).toBeCloseTo(-12.5, 5);
      expect(parsed?.lon).toBeCloseTo(-45.25, 5);
    });

    it('accepts a comma decimal separator', () => {
      const parsed = parseLatLon("53° 07,150'N - 009° 33,320'E");
      expect(parsed?.lat).toBeCloseTo(53.119167, 5);
    });

    it('round-trips format → parse → format', () => {
      const text = formatLatLon(53.119167, 9.555333, { locale: 'en' });
      const parsed = parseLatLon(text);
      expect(formatLatLon(parsed!.lat, parsed!.lon, { locale: 'en' })).toBe(text);
    });

    it('returns null for an unparseable component (the caller flags just that field)', () => {
      expect(parseLatLon("53° 07.150'N - garbage")).toBeNull();
      expect(parseLatLon('not a coordinate')).toBeNull();
      expect(parseLatLon('')).toBeNull();
    });

    it('accepts the edges of the range', () => {
      expect(parseLatLon('90N - 180E')).toEqual({ lat: 90, lon: 180 });
      const below = parseLatLon('56 59.999N - 179 59.999W');
      expect(below?.lat).toBeCloseTo(56 + 59.999 / 60, 9);
      expect(below?.lon).toBeCloseTo(-(179 + 59.999 / 60), 9);
      const zero = parseLatLon('00 00.0N - 000 00.0E');
      expect(zero?.lat).toBe(0);
      expect(zero?.lon).toBe(0);
    });

    it('returns null for minutes of 60 or more, degrees beyond the range and a signed degree', () => {
      expect(parseLatLon('56 75.0N - 009 17.6E')).toBeNull();
      expect(parseLatLon('90 30.0N - 009 17.6E')).toBeNull();
      expect(parseLatLon('56 30.0N - 180.5E')).toBeNull();
      expect(parseLatLon('-55 30N - 012 00E')).toBeNull();
    });
  });
});
