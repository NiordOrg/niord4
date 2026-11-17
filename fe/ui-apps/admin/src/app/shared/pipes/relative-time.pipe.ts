import { Pipe, PipeTransform, inject } from '@angular/core';

import { LanguageService } from '../../core/main-shell/services/language.service';

/** Progressively coarser divisions, walked until the remaining magnitude fits one. */
const DIVISIONS: readonly {
  readonly amount: number;
  readonly unit: Intl.RelativeTimeFormatUnit;
}[] = [
  { amount: 60, unit: 'seconds' },
  { amount: 60, unit: 'minutes' },
  { amount: 24, unit: 'hours' },
  { amount: 7, unit: 'days' },
  { amount: 4.34524, unit: 'weeks' },
  { amount: 12, unit: 'months' },
  { amount: Number.POSITIVE_INFINITY, unit: 'years' },
];

/**
 * Formatters cached per locale — an impure pipe's `transform` re-runs every change-detection
 * cycle per usage, and `Intl.*Format` CONSTRUCTION (locale-data init) is the expensive part
 * (same recipe as `distance-format.ts` / `zoned-date.ts`).
 */
const FORMATTER_CACHE = new Map<string, Intl.RelativeTimeFormat>();

function formatterFor(locale: string): Intl.RelativeTimeFormat {
  let formatter = FORMATTER_CACHE.get(locale);
  if (!formatter) {
    formatter = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
    FORMATTER_CACHE.set(locale, formatter);
  }
  return formatter;
}

/**
 * Relative "time ago" label (e.g. "2 days ago", "yesterday") via `Intl.RelativeTimeFormat`
 * with `numeric: 'auto'` (so −1 day reads "yesterday", not "1 day ago"). Impure so it
 * re-formats on a language switch — the locale comes from the app's selected language.
 * A blank / unparseable value renders empty.
 */
@Pipe({ name: 'relativeTime', pure: false })
export class RelativeTimePipe implements PipeTransform {
  private readonly language = inject(LanguageService);

  transform(value: Date | string | number | null | undefined): string {
    if (value == null || value === '') {
      return '';
    }
    const date = value instanceof Date ? value : new Date(value);
    const time = date.getTime();
    if (Number.isNaN(time)) {
      return '';
    }
    const formatter = formatterFor(this.language.selectedLanguage());
    // Signed seconds relative to now — negative for the past ("… ago").
    let duration = (time - Date.now()) / 1000;
    for (const division of DIVISIONS) {
      if (Math.abs(duration) < division.amount) {
        return formatter.format(Math.round(duration), division.unit);
      }
      duration /= division.amount;
    }
    return '';
  }
}
