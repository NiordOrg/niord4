import { expect } from '../../auth/fixtures';

/*
 * Comparing what a screen shows with what an API holds. Both sides go through one normalisation:
 * markup removed, the zero-width spaces some views put between words removed, white space
 * collapsed. A failing compare names the field and both values.
 */

/** The zero-width spaces and joiners (U+200B to U+200D) and the byte-order mark (U+FEFF). */
const ZERO_WIDTH = new RegExp(
  `[${String.fromCharCode(0x200b)}-${String.fromCharCode(0x200d)}${String.fromCharCode(0xfeff)}]`,
  'g',
);

/** Text as the scenarios compare it. */
export function normalise(text: string | null | undefined): string {
  return (text ?? '')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(ZERO_WIDTH, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** The screen shows what the API holds. */
export function expectSame(
  field: string,
  screen: string | null | undefined,
  api: string | null | undefined,
): void {
  expect(
    normalise(screen),
    `${field}: the screen shows "${normalise(screen)}", the API holds "${normalise(api)}"`,
  ).toBe(normalise(api));
}

/** The screen's text contains what the API holds. */
export function expectShows(
  field: string,
  screen: string | null | undefined,
  api: string | null | undefined,
): void {
  expect(
    normalise(screen),
    `${field}: the screen shows "${normalise(screen)}", which lacks "${normalise(api)}"`,
  ).toContain(normalise(api));
}

/** Two lists hold the same items in the same order. */
export function expectSameList(
  field: string,
  screen: readonly (string | null | undefined)[],
  api: readonly (string | null | undefined)[],
): void {
  const shown = screen.map(normalise);
  const held = api.map(normalise);
  expect(
    shown,
    `${field}: the screen shows [${shown.join(' | ')}], the API holds [${held.join(' | ')}]`,
  ).toEqual(held);
}

/** A latitude and longitude in degrees. */
export interface Position {
  readonly lat: number;
  readonly lon: number;
}

const COORDINATE = /(\d{1,3})°\s*(\d{1,2}(?:\.(\d+))?)'\s*([NSEW])/g;

/**
 * The positions a text shows in degrees and decimal minutes ("56° 00.0'N - 012° 30.0'E"), in
 * order, each with the precision it is shown in (half of its last decimal of a minute, in degrees).
 */
export function positionsIn(text: string): (Position & { readonly precision: number })[] {
  const coordinates = [...normalise(text).matchAll(COORDINATE)].map((match) => {
    const degrees = Number(match[1]) + Number(match[2]) / 60;
    const hemisphere = match[4];
    const decimals = match[3]?.length ?? 0;
    return {
      value: hemisphere === 'S' || hemisphere === 'W' ? -degrees : degrees,
      latitude: hemisphere === 'N' || hemisphere === 'S',
      precision: (0.5 * 10 ** -decimals) / 60,
    };
  });
  const positions: (Position & { precision: number })[] = [];
  for (let i = 0; i + 1 < coordinates.length; i += 2) {
    const [lat, lon] = coordinates[i].latitude
      ? [coordinates[i], coordinates[i + 1]]
      : [coordinates[i + 1], coordinates[i]];
    positions.push({
      lat: lat.value,
      lon: lon.value,
      precision: Math.max(lat.precision, lon.precision),
    });
  }
  return positions;
}

/** The text shows the positions the API holds, in order, within the precision it shows them in. */
export function expectPositions(field: string, screen: string, api: readonly Position[]): void {
  const shown = positionsIn(screen);
  expect(
    shown.length,
    `${field}: the screen shows ${shown.length} position(s) in "${normalise(screen)}", the API holds ${api.length}`,
  ).toBe(api.length);
  shown.forEach((position, i) => {
    const held = api[i];
    const off = Math.max(Math.abs(position.lat - held.lat), Math.abs(position.lon - held.lon));
    expect(
      off,
      `${field}, position ${i + 1}: the screen shows ${position.lat.toFixed(6)}, ${position.lon.toFixed(6)}, the API holds ${held.lat}, ${held.lon}`,
    ).toBeLessThanOrEqual(position.precision + 1e-9);
  });
}

/** The point positions of GeoJSON features, in order ([lon, lat] in GeoJSON). */
export function pointsOf(
  features: readonly {
    readonly geometry: { readonly type: string; readonly coordinates: unknown };
  }[],
): Position[] {
  return features
    .filter((feature) => feature.geometry.type === 'Point')
    .map((feature) => {
      const [lon, lat] = feature.geometry.coordinates as [number, number];
      return { lat, lon };
    });
}
