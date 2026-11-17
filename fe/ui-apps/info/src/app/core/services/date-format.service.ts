import {
  computed,
  inject,
  Injectable,
  Injector,
  runInInjectionContext,
  Signal,
  untracked,
} from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import type { DateNames } from '../../shared/utils/zoned-date';
import { formatInZone } from '../../shared/utils/zoned-date';
import { currentYearInZone } from '../../shared/utils/current-year';
import { AppConfigService } from './app-config.service';
import { LanguageService } from './language.service';

/** The four bundle keys that carry the month and weekday words, in every UI language. */
const MONTHS_WIDE = 'DATE_MONTHS_WIDE';
const MONTHS_ABBREVIATED = 'DATE_MONTHS_ABBREVIATED';
const DAYS_WIDE = 'DATE_DAYS_WIDE';
const DAYS_SHORT = 'DATE_DAYS_SHORT';

/**
 * A `|`-separated bundle value as a word list, or `undefined` when the key is missing, has not
 * loaded yet (Transloco echoes the key), or holds the wrong number of entries. `undefined`
 * degrades to the numeric form rather than rendering the key name to a mariner.
 */
function words(value: string, key: string, expected: number): readonly string[] | undefined {
  if (!value || value === key) return undefined;
  const list = value.split('|');
  return list.length === expected && list.every((word) => word.length > 0) ? list : undefined;
}

/** No words at all: `formatInZone` then renders the numeric month and omits the weekday. */
const NUMERIC_ONLY: DateNames = {
  monthsWide: [],
  monthsAbbreviated: [],
  daysWide: [],
  daysShort: [],
};

/**
 * Every date the public site renders, in the deployment's configured zone.
 *
 * The zone is a COUNTRY value, not the browser's and not a domain's — an anonymous site has no
 * domain. The layout comes from the active language's own pattern key, so a translator owns the
 * order of the fields as well as the words.
 */
@Injectable({ providedIn: 'root' })
export class DateFormatService {
  private readonly injector = inject(Injector);
  private readonly language = inject(LanguageService).language;
  private readonly timeZone = inject(AppConfigService).timeZone;

  // translateSignal re-emits when the bundle loads and on every language change, so the words
  // follow the language switcher without a subscription or a cache to invalidate.
  private readonly monthsWide = translateSignal<string>(MONTHS_WIDE);
  private readonly monthsAbbreviated = translateSignal<string>(MONTHS_ABBREVIATED);
  private readonly daysWide = translateSignal<string>(DAYS_WIDE);
  private readonly daysShort = translateSignal<string>(DAYS_SHORT);

  private readonly names: Signal<DateNames> = computed(() => ({
    monthsWide: words(this.monthsWide(), MONTHS_WIDE, 12) ?? NUMERIC_ONLY.monthsWide,
    monthsAbbreviated:
      words(this.monthsAbbreviated(), MONTHS_ABBREVIATED, 12) ?? NUMERIC_ONLY.monthsAbbreviated,
    daysWide: words(this.daysWide(), DAYS_WIDE, 7) ?? NUMERIC_ONLY.daysWide,
    daysShort: words(this.daysShort(), DAYS_SHORT, 7) ?? NUMERIC_ONLY.daysShort,
  }));

  /**
   * One signal per layout key, so the pattern is read reactively and not as a snapshot. They are
   * kept for as long as the application runs, which costs nothing worth counting: the keys are
   * the handful of `*_DATE_FORMAT` constants the site renders dates with.
   */
  private readonly patterns = new Map<string, Signal<string>>();

  private pattern(key: string): string {
    let pattern = this.patterns.get(key);
    if (!pattern) {
      // Creating the signal subscribes and writes it once, which a `computed()` that called this
      // would otherwise forbid. The read below is the one the caller has to track.
      pattern = untracked(() =>
        runInInjectionContext(this.injector, () => translateSignal<string>(key)),
      );
      this.patterns.set(key, pattern);
    }
    return pattern();
  }

  /**
   * Render an instant with the pattern the given translation key carries. An unloaded key
   * (Transloco echoes the key name) yields '' — the caller then omits the date rather than
   * printing "SOURCE_DATE_FORMAT" to a mariner.
   *
   * Both reads of the translations happen before either early return, so a `computed()` around
   * this call tracks them even when there is nothing to render yet. Reading them last would
   * leave a message that rendered ahead of its bundle with an empty date for good: the computed
   * would have recorded no dependency and nothing would ever call it again.
   */
  format(instant: Date | string | number | undefined, patternKey: string): string {
    const names = this.names();
    const pattern = this.pattern(patternKey);
    const date = toDate(instant);
    if (!date) return '';
    if (!pattern || pattern === patternKey) return '';
    return formatInZone(date, pattern, this.timeZone(), names);
  }

  /** The calendar year the deployment's zone is currently in — a label, never a stored instant. */
  currentYear(): number {
    return currentYearInZone(this.timeZone());
  }

  /**
   * A reactive rendering for a template or a `computed()`: it tracks the instant, the language,
   * the zone, the month and weekday words and the layout pattern — the last two whether or not
   * they have arrived — so both a language switch and a late bundle re-render without a refetch.
   */
  formatSignal(
    instant: Signal<Date | string | number | undefined>,
    patternKey: string,
  ): Signal<string> {
    return computed(() => {
      this.language();
      return this.format(instant(), patternKey);
    });
  }
}

/** The wire sends epoch millis where the API declares a string; accept both, reject nothing else. */
function toDate(value: Date | string | number | undefined): Date | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}
