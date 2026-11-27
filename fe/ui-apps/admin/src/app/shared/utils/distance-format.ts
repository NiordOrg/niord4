export interface FormattedDistance {
  /** Whole metres with grouping, e.g. "1,234 m". */
  readonly meters: string;
  /** Kilometres, 2 decimals, e.g. "1.23 km". */
  readonly kilometers: string;
  /** Nautical miles, 2 decimals, e.g. "0.67 nm". */
  readonly nauticalMiles: string;
}

const METERS_PER_NM = 1852;

const formatterCache = new Map<string, Intl.NumberFormat>();

/** Locale + fixed-fraction-digits `Intl.NumberFormat`, cached — `formatDistance` runs per pointer-move while measuring. */
function fixedFormatter(locale: string, digits: number): Intl.NumberFormat {
  const key = `${locale}|${digits}`;
  let formatter = formatterCache.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
    formatterCache.set(key, formatter);
  }
  return formatter;
}

/** The units a distance is given in; each has its text under `common.distanceUnit.<unit>`. */
export type DistanceUnit = 'm' | 'km' | 'nm';

/**
 * Formats a length (in metres) into metres / kilometres / nautical miles — the units the
 * measure tooltip shows. The decimal/grouping separators follow the locale; each unit's symbol
 * is the caller's text for it (the nautical mile is "sm" in Danish).
 */
export function formatDistance(
  meters: number,
  locale: string,
  unitLabel: (unit: DistanceUnit) => string,
): FormattedDistance {
  const format = (value: number, digits: number): string =>
    fixedFormatter(locale, digits).format(value);
  return {
    meters: `${format(Math.round(meters), 0)} ${unitLabel('m')}`,
    kilometers: `${format(meters / 1000, 2)} ${unitLabel('km')}`,
    nauticalMiles: `${format(meters / METERS_PER_NM, 2)} ${unitLabel('nm')}`,
  };
}
