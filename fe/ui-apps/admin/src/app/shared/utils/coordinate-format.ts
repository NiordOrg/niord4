export interface FormatLatLonOptions {
  /** Number of fractional minute digits. Default 3. */
  readonly decimals?: number;
  /** BCP-47 locale used for the decimal separator. Default 'en'. */
  readonly locale?: string;
}

/**
 * Formats a single coordinate component (latitude or longitude) in the
 * Niord notation: `DD° MM.fff'X` where X is the hemisphere letter.
 * Latitude hours are zero-padded to 2 digits, longitude to 3 (nautical
 * convention). The minute integer part is always zero-padded to 2 digits,
 * and the decimal separator follows the supplied locale.
 */
function formatDegree(deg: number, isLatitude: boolean, decimals: number, locale: string): string {
  let value = deg;
  let suffix = isLatitude ? 'N' : 'E';
  if (value < 0) {
    suffix = isLatitude ? 'S' : 'W';
    value = -value;
  }
  let hours = Math.floor(value);
  let minutes = (value - hours) * 60;
  // Carry the minute rounding into the degree: if `minutes` rounds up to 60 at the
  // requested precision (e.g. 1.9999999 -> 59.999994' -> "60.000"), roll it over to 0'
  // and increment the degree, so we render "02° 00.000'" not the invalid "01° 60.000'".
  const roundingFactor = 10 ** decimals;
  if (Math.round(minutes * roundingFactor) >= 60 * roundingFactor) {
    minutes = 0;
    hours += 1;
  }
  const minuteStr = new Intl.NumberFormat(locale, {
    minimumIntegerDigits: 2,
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
    useGrouping: false,
  }).format(minutes);
  const hourStr = String(hours).padStart(isLatitude ? 2 : 3, '0');
  return `${hourStr}° ${minuteStr}'${suffix}`;
}

/** Formats a latitude in `DD° MM.fff'N/S` form. */
export function formatLatitude(lat: number, options: FormatLatLonOptions = {}): string {
  return formatDegree(lat, true, options.decimals ?? 3, options.locale ?? 'en');
}

/** Formats a longitude in `DDD° MM.fff'E/W` form. */
export function formatLongitude(lon: number, options: FormatLatLonOptions = {}): string {
  return formatDegree(lon, false, options.decimals ?? 3, options.locale ?? 'en');
}

/**
 * Formats a (lat, lon) pair joined with " - ".
 */
export function formatLatLon(lat: number, lon: number, options: FormatLatLonOptions = {}): string {
  return `${formatLatitude(lat, options)} - ${formatLongitude(lon, options)}`;
}

export interface LatLon {
  readonly lat: number;
  readonly lon: number;
}

/**
 * Hemisphere letters — the single config point for which characters denote each
 * compass direction. Today this is the standard English maritime set; a future
 * country-specific extension (localized N/S/E/W glyphs) plugs in here. The exact
 * extended set is deferred (a larger discussion).
 */
const HEMISPHERE = { north: 'N', south: 'S', east: 'E', west: 'W' } as const;

/**
 * Parses one coordinate component. Lenient: accepts `DD° MM.mmm'X`, the same without
 * symbols (`DD MM.mmm X`), or decimal degrees with a hemisphere (`DD.dddX`). `,` is
 * accepted as the decimal separator. Returns the signed decimal degrees, or `null`
 * when the format is unparseable or the position impossible: a sign before the degrees
 * (the hemisphere letter is always required, so a sign is never needed), minutes outside
 * [0, 60), a fraction of a degree followed by minutes, or more than 90 degrees of latitude
 * or 180 of longitude. Seconds are not read at all, so minutes with a fraction followed by
 * seconds (`56 30.5' 20"N`), which the backend's `PlainTextConverter` refuses, are refused
 * here too.
 */
function parseComponent(raw: string, isLatitude: boolean): number | null {
  const cleaned = raw.replace(/[°'"]/g, ' ').replace(/,/g, '.').trim().toUpperCase();
  if (cleaned.length === 0) return null;

  const hemisphere = cleaned.slice(-1);
  const positive = isLatitude ? HEMISPHERE.north : HEMISPHERE.east;
  const negative = isLatitude ? HEMISPHERE.south : HEMISPHERE.west;
  if (hemisphere !== positive && hemisphere !== negative) return null;

  const parts = cleaned.slice(0, -1).trim().split(/\s+/).filter(Boolean);
  if (parts.length > 0 && (parts[0].startsWith('-') || parts[0].startsWith('+'))) return null;

  let degrees: number;
  if (parts.length === 1) {
    degrees = Number(parts[0]);
  } else if (parts.length === 2) {
    // A fraction of a degree followed by minutes ("56.5 30N", often a slipped decimal comma)
    // would add the minutes to the fraction: 57°, a position the editor never typed. The
    // value decides, not the text: "56.0 30N" is whole degrees, "565e-1 30N" a fraction.
    const deg = Number(parts[0]);
    const minutes = Number(parts[1]);
    if (!Number.isFinite(deg) || !Number.isFinite(minutes)) return null;
    if (!Number.isInteger(deg)) return null;
    if (minutes < 0 || minutes >= 60) return null;
    degrees = deg + minutes / 60;
  } else {
    return null;
  }
  if (!Number.isFinite(degrees)) return null;
  if (degrees > (isLatitude ? 90 : 180)) return null;
  return hemisphere === negative ? -degrees : degrees;
}

/**
 * Parses a `lat - lon` string (the inverse of {@link formatLatLon}) into decimal degrees.
 * Latitude runs up to the N/S hemisphere letter; the remainder (minus an optional ` - `
 * divider) is the longitude. Returns `null` when either component fails to parse — the
 * caller flags only that field, so a single bad position does not invalidate the rest.
 */
export function parseLatLon(value: string): LatLon | null {
  if (!value) return null;
  const upper = value.trim().toUpperCase();

  let splitAt = upper.indexOf(HEMISPHERE.north);
  if (splitAt === -1) splitAt = upper.indexOf(HEMISPHERE.south);
  if (splitAt === -1) return null;

  const lat = parseComponent(upper.slice(0, splitAt + 1), true);
  const lon = parseComponent(upper.slice(splitAt + 1).replace(/^[\s-]+/, ''), false);
  if (lat === null || lon === null) return null;
  return { lat, lon };
}
