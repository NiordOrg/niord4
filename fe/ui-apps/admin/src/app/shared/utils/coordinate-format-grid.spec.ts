import { describe, expect, it } from 'vitest';

import { formatLatLon, formatLatitude, formatLongitude, parseLatLon } from './coordinate-format';

/**
 * The position formatter and parser of the geometry editor (`formatLatLon` / `parseLatLon`, the
 * position title and its inline edit in the geometry tree) over a grid of positions, as the backend's
 * `PositionFormatterRoundingTest` checks the backend's formatter: every printed position is well formed,
 * no minute reaches 60, and reading it back gives the position within half the last printed unit.
 */

/** Minutes just below a whole minute at every precision the formatter offers (0 to 4 decimals). */
const MINUTES = [
  0, 0.04, 0.4, 0.5, 29.96, 59.4, 59.5, 59.94, 59.95, 59.96, 59.994, 59.995, 59.996, 59.9994,
  59.9995, 59.9996, 59.99994, 59.99996, 59.999999,
];

const SHAPE = /^(\d{2,3})° (\d{2}(?:[.,]\d+)?)'([NSEW])$/;

function check(
  value: number,
  isLatitude: boolean,
  decimals: number,
  locale: string,
): string | null {
  const printed = isLatitude
    ? formatLatitude(value, { decimals, locale })
    : formatLongitude(value, { decimals, locale });
  const m = SHAPE.exec(printed);
  if (!m) return `"${printed}" is not DD° MM.m'X`;
  const degrees = Number(m[1]);
  const minuteText = m[2];
  const minutes = Number(minuteText.replace(',', '.'));
  if (m[1].length !== (isLatitude ? 2 : 3)) return `"${printed}": degrees not padded`;
  const fraction = minuteText.includes('.') || minuteText.includes(',') ? minuteText.length - 3 : 0;
  if (fraction !== decimals) return `"${printed}": ${fraction} decimals, not ${decimals}`;
  if (minutes >= 60) return `"${printed}": 60 minutes`;
  const max = isLatitude ? 90 : 180;
  if (degrees > max || (degrees === max && minutes > 0)) return `"${printed}": beyond ${max}°`;
  const hemisphere = isLatitude ? (value < 0 ? 'S' : 'N') : value < 0 ? 'W' : 'E';
  if (m[3] !== hemisphere) return `"${printed}": hemisphere is not ${hemisphere}`;
  const readBack = degrees + minutes / 60;
  const halfUnit = (0.5 * 10 ** -decimals) / 60;
  if (Math.abs(readBack - Math.abs(value)) > halfUnit + 1e-9)
    return `"${printed}": reads back as ${readBack}`;
  return null;
}

describe('coordinate-format over a grid', () => {
  it('prints every position well formed and readable back, at 0 to 4 decimals, en and da', () => {
    const failures: string[] = [];
    let checked = 0;
    for (const isLatitude of [true, false]) {
      const max = isLatitude ? 90 : 180;
      for (let d = 0; d < max; d += d < 10 ? 1 : 7) {
        for (const minutes of MINUTES) {
          for (const sign of [1, -1]) {
            const value = sign * (d + minutes / 60);
            for (let decimals = 0; decimals <= 4; decimals++) {
              for (const locale of ['en', 'da']) {
                const problem = check(value, isLatitude, decimals, locale);
                if (problem) failures.push(`${value} @${decimals} ${locale}: ${problem}`);
                checked++;
              }
            }
          }
        }
      }
    }
    expect(checked).toBeGreaterThan(5000);
    expect(failures.slice(0, 20)).toEqual([]);
  });

  it('reads every printed position back through parseLatLon within half a unit', () => {
    const failures: string[] = [];
    for (const d of [0, 1, 9, 54, 55, 56, 66, 89]) {
      for (const minutes of MINUTES) {
        for (const sign of [1, -1]) {
          const lat = sign * (d + minutes / 60);
          const lon = -sign * (2 * d + minutes / 60);
          for (const decimals of [1, 2, 3]) {
            for (const locale of ['en', 'da']) {
              const text = formatLatLon(lat, lon, { decimals, locale });
              const back = parseLatLon(text);
              const halfUnit = (0.5 * 10 ** -decimals) / 60 + 1e-9;
              if (
                !back ||
                Math.abs(back.lat - lat) > halfUnit ||
                Math.abs(back.lon - lon) > halfUnit
              ) {
                failures.push(`${lat},${lon} -> "${text}" -> ${back?.lat},${back?.lon}`);
              }
            }
          }
        }
      }
    }
    expect(failures).toEqual([]);
  });

  it('reads every accepted typed form as the same position', () => {
    const lat = 55 + 30.5 / 60;
    const lon = 12 + 15.25 / 60;
    for (const typed of [
      "55° 30.500'N - 012° 15.250'E",
      "55° 30,500'N - 012° 15,250'E",
      '55 30.5N 12 15.25E',
      "55° 30.5' N - 12° 15.25' E",
      '55.508333333N 12.254166667E',
      "55° 30.5'n 012° 15.25'e",
    ]) {
      const back = parseLatLon(typed);
      expect(back?.lat, typed).toBeCloseTo(lat, 8);
      expect(back?.lon, typed).toBeCloseTo(lon, 8);
    }
  });

  it('refuses degrees, minutes and seconds (the field is flagged, nothing is saved)', () => {
    expect(parseLatLon(`55° 30' 30"N - 012° 15' 15"E`)).toBeNull();
  });

  it('refuses minutes of 60 or more, latitudes beyond 90, longitudes beyond 180 and a sign with a hemisphere', () => {
    // The inline edit of a position commits whatever parseLatLon returns, so each of these is flagged
    // instead of being read as a position the editor did not type ("56 75.0N" as 57° 15' N, "-55 30N" as
    // 54° 30' S). "56° 60.0'N" is the form POS1 printed, so a stored text pasted back in is refused too.
    for (const typed of [
      '56 75.0N - 009 17.6E',
      "56° 60.0'N - 009° 17.6'E",
      '95N - 200E',
      '95N - 010E',
      '56N - 200E',
      '90 00.1N - 010 00.0E',
      '56 00.0N - 180 00.1W',
      '91S - 010E',
      '56 -30.0N - 009 17.6E',
      '-55 30N - 012 00E',
      '+55 30N - 012 00E',
      '-55.5N - 012E',
    ]) {
      expect(parseLatLon(typed), typed).toBeNull();
    }
  });

  it('accepts the edges of the range: 90 N, 180 E and minutes just below 60', () => {
    expect(parseLatLon('90N - 180E')).toEqual({ lat: 90, lon: 180 });
    expect(parseLatLon("90° 00.000'S - 180° 00.000'W")).toEqual({ lat: -90, lon: -180 });
    const below = parseLatLon('56 59.999N - 179 59.999W');
    expect(below?.lat).toBeCloseTo(56 + 59.999 / 60, 9);
    expect(below?.lon).toBeCloseTo(-(179 + 59.999 / 60), 9);
  });

  /**
   * The table the backend pins in `be/niord/src/test/java/org/niord/core/util/PositionFormatterSharedTableTest.java`
   * against `PositionFormatter.LATLON_DEC_3` ("LAT - LON"): lat, lon, English, Danish. Change both together.
   */
  const SHARED_POSITIONS: readonly [number, number, string, string][] = [
    [
      56.99940309853659,
      9.294097396125087,
      "56° 59.964'N - 009° 17.646'E",
      "56° 59,964'N - 009° 17,646'E",
    ],
    [55.0, 12.0, "55° 00.000'N - 012° 00.000'E", "55° 00,000'N - 012° 00,000'E"],
    [1.9999999, 179.9999999, "02° 00.000'N - 180° 00.000'E", "02° 00,000'N - 180° 00,000'E"],
    [-0.0004, -0.0004, "00° 00.024'S - 000° 00.024'W", "00° 00,024'S - 000° 00,024'W"],
    [-12.5, -45.25, "12° 30.000'S - 045° 15.000'W", "12° 30,000'S - 045° 15,000'W"],
    [89.99999999, -179.99999999, "90° 00.000'N - 180° 00.000'W", "90° 00,000'N - 180° 00,000'W"],
    [53.119167, 9.555333, "53° 07.150'N - 009° 33.320'E", "53° 07,150'N - 009° 33,320'E"],
    [64.17491666, -51.73888, "64° 10.495'N - 051° 44.333'W", "64° 10,495'N - 051° 44,333'W"],
    [54.99999, 10.99999, "54° 59.999'N - 010° 59.999'E", "54° 59,999'N - 010° 59,999'E"],
    [54.999999, 10.999999, "55° 00.000'N - 011° 00.000'E", "55° 00,000'N - 011° 00,000'E"],
  ];

  it('prints the shared table exactly as the backend does', () => {
    for (const [lat, lon, en, da] of SHARED_POSITIONS) {
      expect(formatLatLon(lat, lon, { locale: 'en' }), `${lat},${lon} en`).toBe(en);
      expect(formatLatLon(lat, lon, { locale: 'da' }), `${lat},${lon} da`).toBe(da);
    }
  });
});
