/**
 * THE single source of truth for every date/time format the frontend renders.
 *
 * Change the layout/format here and it changes everywhere — every service, pipe and util below
 * routes through these functions. Rules:
 *  - Dates are locale-native (Intl): English "5 Jan 2026", Danish "5. jan. 2026".
 *  - Times follow each locale's separator (English colon "9:35", Danish period "09.35") — NOT forced.
 *  - `twelveHourLanguages` get an AM/PM clock with a bare hour ("9:35 AM", uppercase); every other
 *    language uses a 24-hour, zero-padded clock ("09.35"). This is the one deliberate override of a
 *    locale default (British English defaults to 24h; we keep the AM/PM users expect).
 *  - Timezone is a per-call concern (callers pass the session-domain zone); these functions never
 *    assume one.
 *
 * NOTE: this governs FRONTEND rendering only. Dates baked into backend-rendered message HTML are
 * formatted server-side and are outside this module.
 */
export interface DateTimeFormatConfig {
  /** Intl `dateStyle` for the date part. */
  readonly dateStyle: 'medium';
  /** Primary language subtags that use a 12-hour AM/PM clock; others use the 24-hour locale default. */
  readonly twelveHourLanguages: readonly string[];
  /** Render the AM/PM marker uppercase ("AM" not the Intl-default lowercase "am"). */
  readonly upperCaseMeridiem: boolean;
  /** Joiner between the date and time parts in a combined date-time. */
  readonly dateTimeSeparator: string;
}

export const DATE_TIME_FORMAT: DateTimeFormatConfig = {
  dateStyle: 'medium',
  twelveHourLanguages: ['en'],
  upperCaseMeridiem: true,
  dateTimeSeparator: ', ',
};

const uses12Hour = (locale: string): boolean =>
  DATE_TIME_FORMAT.twelveHourLanguages.includes(locale.split('-')[0]);

const timeOptions = (locale: string): Intl.DateTimeFormatOptions =>
  uses12Hour(locale)
    ? { hour: 'numeric', minute: '2-digit', hour12: true } // 12h → bare hour "9:35 AM"
    : { hour: '2-digit', minute: '2-digit', hour12: false }; // 24h → padded "09.35"

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function formatter(locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = `${locale}|${JSON.stringify(options)}`;
  let fmt = formatterCache.get(key);
  if (!fmt) {
    // An empty locale tag throws RangeError — degrade to the runtime default rather than abort a
    // whole change-detection pass when a caller formats during rendering.
    fmt = new Intl.DateTimeFormat(locale || undefined, options);
    formatterCache.set(key, fmt);
  }
  return fmt;
}

function toDate(value: Date | string | number): Date | null {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

// Modern ICU inserts a NARROW NO-BREAK SPACE (U+202F) before AM/PM ("9:35<U+202F>AM"). It is
// invisible and breaks copy/paste + the Insert-Time-into-message-body path, so normalize it (and
// the plain NO-BREAK SPACE some locales use) to a plain space for a predictable, uniform result.
const normalize = (text: string): string => text.replace(/[\u202f\u00a0]/g, ' ');

/** Date only, locale-native: "5 Jan 2026" / "5. jan. 2026". */
export function formatDate(
  value: Date | string | number,
  locale: string,
  timeZone?: string,
): string {
  const date = toDate(value);
  if (!date) return '';
  return normalize(
    formatter(locale, {
      dateStyle: DATE_TIME_FORMAT.dateStyle,
      ...(timeZone ? { timeZone } : {}),
    }).format(date),
  );
}

/** Time only: "9:35 AM" (12h locales) / "09.35" (24h locales). `withZoneName` appends e.g. " CET". */
export function formatTime(
  value: Date | string | number,
  locale: string,
  timeZone?: string,
  withZoneName = false,
): string {
  const date = toDate(value);
  if (!date) return '';
  const options: Intl.DateTimeFormatOptions = {
    ...timeOptions(locale),
    ...(timeZone ? { timeZone } : {}),
    ...(withZoneName ? { timeZoneName: 'short' } : {}),
  };
  const fmt = formatter(locale, options);
  if (uses12Hour(locale) && DATE_TIME_FORMAT.upperCaseMeridiem) {
    return normalize(
      fmt
        .formatToParts(date)
        .map((part) => (part.type === 'dayPeriod' ? part.value.toUpperCase() : part.value))
        .join(''),
    );
  }
  return normalize(fmt.format(date));
}

/** Date + time: "5 Jan 2026, 9:35 AM" / "5. jan. 2026, 09.35". */
export function formatDateTime(
  value: Date | string | number,
  locale: string,
  timeZone?: string,
): string {
  const date = toDate(value);
  if (!date) return '';
  return (
    formatDate(date, locale, timeZone) +
    DATE_TIME_FORMAT.dateTimeSeparator +
    formatTime(date, locale, timeZone)
  );
}

/**
 * Weekday + day and month + time, with no year: "Wed 2 Sep, 9:35 AM" / "ons. 2. sep, 12.00".
 *
 * The weekday is the fact being carried — a deadline a reader plans a week
 * around is read as "Wednesday", not as a number — and the year is dropped
 * because such a date is always within days of now, where repeating it only
 * lengthens a line that sits inline in a sentence. The time half reuses
 * {@link formatTime}, so the 12/24-hour rule stays stated in one place.
 */
export function formatWeekdayDateTime(
  value: Date | string | number,
  locale: string,
  timeZone?: string,
): string {
  const date = toDate(value);
  if (!date) return '';
  const day = normalize(
    formatter(locale, {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      ...(timeZone ? { timeZone } : {}),
    }).format(date),
  );
  return day + DATE_TIME_FORMAT.dateTimeSeparator + formatTime(date, locale, timeZone);
}

/** The short timezone abbreviation for the instant in the given zone, e.g. "CET" / "UTC". */
export function zoneAbbreviation(
  value: Date | string | number,
  locale: string,
  timeZone?: string,
): string {
  const date = toDate(value);
  if (!date) return '';
  const parts = formatter(locale, {
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
    ...(timeZone ? { timeZone } : {}),
  }).formatToParts(date);
  return parts.find((part) => part.type === 'timeZoneName')?.value ?? '';
}
