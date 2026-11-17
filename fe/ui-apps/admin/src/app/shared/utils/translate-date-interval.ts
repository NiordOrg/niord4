/**
 * Turns a single date interval into an i18n key + params describing its shape;
 * the CALLER resolves the key via Transloco. The util does NO translation itself
 * — it only formats the date/time values and picks the key.
 *
 * All date/time formatting delegates to the app's single format module
 * (`core/main-shell/services/date-time-format`), so an interval renders identically
 * to every other date/time in the app.
 */
import {
  formatDate,
  formatDateTime,
  formatTime,
  zoneAbbreviation,
} from '../../core/main-shell/services/date-time-format';

export interface DateInterval {
  readonly fromDate?: Date | string | number | null;
  readonly toDate?: Date | string | number | null;
  /** All-day interval — renders date-only, no time of day. */
  readonly allDay?: boolean;
}

export interface TranslateDateIntervalOptions {
  /** BCP-47 locale for date/time formatting, e.g. `'en-GB'` / `'da'`. */
  readonly locale: string;
  /** Append the time-zone abbreviation suffix. Defaults to `true`. */
  readonly includeTimeZone?: boolean;
  /**
   * IANA time zone the wall-clock is rendered in and the zone abbreviation is
   * derived from (e.g. `'Europe/Copenhagen'`). Defaults to the runtime zone.
   */
  readonly timeZone?: string;
}

/** An i18n key + interpolation params; the caller resolves it via Transloco. */
export interface DateIntervalTranslation {
  readonly key: string;
  readonly params: Record<string, string>;
}

const KEY_PREFIX = 'shared.dateInterval';

export const DATE_INTERVAL_KEYS = {
  /** A single collapsed value (same date, or same date-time). */
  single: `${KEY_PREFIX}.single`,
  /** Same date, differing times: `{{date}}, {{fromTime}} - {{toTime}}`. */
  sameDateRange: `${KEY_PREFIX}.sameDateRange`,
  /** A range across two values: `{{from}} - {{to}}`. */
  fromTo: `${KEY_PREFIX}.fromTo`,
  /** Open-ended start: `From {{fromDate}}`. */
  fromDate: 'msg.time.from_date',
  /** Open-ended end: `Until {{toDate}}`. */
  toDate: 'msg.time.to_date',
  /** No dates at all. */
  untilFurtherNotice: 'msg.time.until_further_notice',
} as const;

function toValidDate(value: Date | string | number | null | undefined): Date | undefined {
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

/**
 * Translate a single date interval into an i18n key + params. A `null`/`undefined`
 * interval, or one with neither date, yields the "until further notice" key — the
 * atomic building block the caller reuses for an empty interval list.
 */
export function translateDateInterval(
  interval: DateInterval | null | undefined,
  options: TranslateDateIntervalOptions,
): DateIntervalTranslation {
  const { locale, timeZone } = options;
  const includeTimeZone = options.includeTimeZone ?? true;

  const from = toValidDate(interval?.fromDate);
  const to = toValidDate(interval?.toDate);
  const allDay = interval?.allDay ?? false;

  // A single value and an open-ended interval carry the zone of their one date, at the end.
  const appendZone = (text: string, zoneSource: Date): string => {
    if (!includeTimeZone) {
      return text;
    }
    const abbreviation = zoneAbbreviation(zoneSource, locale, timeZone);
    return abbreviation ? `${text} ${abbreviation}` : text;
  };

  if (from && to) {
    const fromDateTxt = formatDate(from, locale, timeZone);
    const toDateTxt = formatDate(to, locale, timeZone);
    // Each end of a range is read in its own zone. When the two ends share an abbreviation
    // one label at the end serves both; when a clock change lies between them each end
    // carries its own ("1:30 AM CET - 3:30 AM CEST"), since the start's label on the end
    // would put it an hour off. The abbreviations are compared even when no label is
    // printed: two ends that print alike in the repeated autumn hour are still two moments.
    const fromZone = zoneAbbreviation(from, locale, timeZone);
    const toZone = zoneAbbreviation(to, locale, timeZone);
    const zonesDiffer = fromZone !== toZone;
    const labelEnds = (fromText: string, toText: string): { from: string; to: string } => {
      if (!includeTimeZone) {
        return { from: fromText, to: toText };
      }
      const label = (text: string, zone: string): string => (zone ? `${text} ${zone}` : text);
      return {
        from: zonesDiffer ? label(fromText, fromZone) : fromText,
        to: label(toText, toZone),
      };
    };

    if (fromDateTxt === toDateTxt) {
      if (allDay) {
        // A day is one day, also the day a clock changes: printed once, as before.
        return { key: DATE_INTERVAL_KEYS.single, params: { value: appendZone(fromDateTxt, from) } };
      }
      const fromDateTimeTxt = formatDateTime(from, locale, timeZone);
      const toDateTimeTxt = formatDateTime(to, locale, timeZone);
      if (from.getTime() === to.getTime() || (fromDateTimeTxt === toDateTimeTxt && !zonesDiffer)) {
        return {
          key: DATE_INTERVAL_KEYS.single,
          params: { value: appendZone(fromDateTimeTxt, from) },
        };
      }
      const times = labelEnds(formatTime(from, locale, timeZone), formatTime(to, locale, timeZone));
      return {
        key: DATE_INTERVAL_KEYS.sameDateRange,
        params: { date: fromDateTxt, fromTime: times.from, toTime: times.to },
      };
    }

    return {
      key: DATE_INTERVAL_KEYS.fromTo,
      params: allDay
        ? labelEnds(fromDateTxt, toDateTxt)
        : labelEnds(formatDateTime(from, locale, timeZone), formatDateTime(to, locale, timeZone)),
    };
  }

  if (from) {
    const txt = allDay
      ? formatDate(from, locale, timeZone)
      : formatDateTime(from, locale, timeZone);
    return { key: DATE_INTERVAL_KEYS.fromDate, params: { fromDate: appendZone(txt, from) } };
  }

  if (to) {
    const txt = allDay ? formatDate(to, locale, timeZone) : formatDateTime(to, locale, timeZone);
    return { key: DATE_INTERVAL_KEYS.toDate, params: { toDate: appendZone(txt, to) } };
  }

  return { key: DATE_INTERVAL_KEYS.untilFurtherNotice, params: {} };
}
