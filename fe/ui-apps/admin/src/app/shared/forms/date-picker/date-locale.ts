/**
 * Pure locale helpers for the from-scratch calendar. Native `<input type="date">`
 * renders its picker in the *browser* locale, ignoring the app language — so the
 * app-calendar draws its own month/weekday labels from `Intl` in the ACTIVE locale
 * instead. All functions are pure (locale string in, strings/numbers out) so they
 * test without Angular DI.
 */

/** Whether `value` is a usable Date — a real Date object, not an Invalid Date. */
export function isValidDate(value: unknown): value is Date {
  return value instanceof Date && !Number.isNaN(value.getTime());
}

interface WeekInfo {
  readonly firstDay: number; // 1 = Monday … 7 = Sunday (ISO-8601, per the Intl spec)
}

// `Intl.Locale` week metadata is exposed as a method (`getWeekInfo()`, current spec)
// on newer engines and as a property (`weekInfo`, older Chromium) on others.
type LocaleWithWeekInfo = Intl.Locale & {
  getWeekInfo?: () => WeekInfo;
  weekInfo?: WeekInfo;
};

/**
 * First day of the week for `locale`, as a JS day index (0 = Sunday … 6 = Saturday)
 * to match `Date.prototype.getDay()`. Falls back to Monday when the engine doesn't
 * expose week info.
 */
export function firstDayOfWeek(locale: string): number {
  try {
    const loc = new Intl.Locale(locale) as LocaleWithWeekInfo;
    const info = loc.getWeekInfo?.() ?? loc.weekInfo;
    if (info && typeof info.firstDay === 'number') {
      return info.firstDay % 7; // 7 (Sun) → 0, 1 (Mon) → 1, …
    }
  } catch {
    // Malformed locale tag — fall through to the Monday default.
  }
  return 1;
}

// A known week whose 1st is a Sunday (`getDay() === 0`), used as the reference for
// weekday-name generation regardless of the runtime's default first-day.
const SUNDAY_REFERENCE = new Date(2023, 0, 1);

/** The 7 short weekday names for `locale`, ordered from that locale's first day of week. */
export function weekdayShortNames(locale: string): readonly string[] {
  const format = new Intl.DateTimeFormat(locale, { weekday: 'short' });
  const start = firstDayOfWeek(locale);
  const names: string[] = [];
  for (let i = 0; i < 7; i++) {
    const day = new Date(SUNDAY_REFERENCE);
    day.setDate(SUNDAY_REFERENCE.getDate() + ((start + i) % 7));
    names.push(format.format(day));
  }
  return names;
}
