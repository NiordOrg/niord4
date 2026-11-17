import { computed, inject, Injectable, Injector, Signal } from '@angular/core';

import { DomainService } from '../../domain/services/domain.service';
import {
  DATE_TIME_FORMAT,
  formatDate as fmtDate,
  formatDateTime as fmtDateTime,
  formatTime as fmtTime,
  formatWeekdayDateTime as fmtWeekdayDateTime,
} from './date-time-format';
import { LanguageService } from './language.service';

/** How a value should be zoned when formatting a date/time. */
export interface FormatOptions {
  /** IANA timezone override. Defaults to the active session domain's timezone. */
  readonly timeZone?: string;
  /** Format the raw local wall-clock value with NO timezone conversion (for editable time inputs). */
  readonly local?: boolean;
}

/**
 * The single entry point for formatting dates, times and numbers in the app. Date/time layout is
 * owned entirely by `date-time-format.ts` (the one config); this service just supplies the active
 * locale and — by default — the session domain's timezone, so every temporal value shows the same
 * wall-clock.
 */
@Injectable({ providedIn: 'root' })
export class LocaleFormatService {
  private readonly languageService = inject(LanguageService);
  private readonly injector = inject(Injector);
  // Resolved lazily: only date/time formatting needs the session timezone, so a component (or
  // spec) that formats ONLY numbers never constructs the heavier DomainService dependency graph.
  private domainService: DomainService | null = null;

  private readonly numberFormatters = new Map<string, Intl.NumberFormat>();

  /** 12-hour (AM/PM) vs 24-hour clock for the active locale — sourced from the one format config. */
  readonly uses12HourClock: Signal<boolean> = computed(() =>
    DATE_TIME_FORMAT.twelveHourLanguages.includes(
      this.languageService.activeLocale().split('-')[0],
    ),
  );

  /**
   * The active session-domain timezone (or `undefined`) — the ONE default this service applies.
   * Exposed for callers that must feed a PURE formatter (e.g. `translateDateInterval`) and so pass
   * the zone explicitly rather than relying on this service's own `format*` methods.
   */
  sessionTimeZone(): string | undefined {
    return this.zone();
  }

  formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
    return this.numberFormatter(this.languageService.activeLocale(), options).format(value);
  }

  /** Date only, in the active locale + session-domain timezone. */
  formatDate(value: Date | string | number, options?: FormatOptions): string {
    return fmtDate(value, this.languageService.activeLocale(), this.zone(options));
  }

  /**
   * Time only, in the active locale + session-domain timezone.
   * `withZoneName` appends the short zone name (e.g. " CET").
   */
  formatTime(
    value: Date | string | number,
    options?: FormatOptions & { withZoneName?: boolean },
  ): string {
    return fmtTime(
      value,
      this.languageService.activeLocale(),
      this.zone(options),
      options?.withZoneName,
    );
  }

  /** Date + time, in the active locale + session-domain timezone. */
  formatDateTime(value: Date | string | number, options?: FormatOptions): string {
    return fmtDateTime(value, this.languageService.activeLocale(), this.zone(options));
  }

  /**
   * Weekday + day/month + time, in the active locale + session-domain timezone.
   *
   * For a deadline close enough that the weekday is the fact a reader acts on.
   */
  formatWeekdayDateTime(value: Date | string | number, options?: FormatOptions): string {
    return fmtWeekdayDateTime(value, this.languageService.activeLocale(), this.zone(options));
  }

  /**
   * Low-level Intl passthrough — for STRUCTURAL widget labels only (the calendar's month header
   * and screen-reader full-date labels), NOT for displaying a date/time as data. Everything a user
   * reads as a date/time must go through `formatDate` / `formatTime` / `formatDateTime` so it obeys
   * the one format config.
   */
  formatWith(
    value: Date | string | number,
    intlOptions: Intl.DateTimeFormatOptions,
    options?: FormatOptions,
  ): string {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    const zone = this.zone(options);
    return new Intl.DateTimeFormat(this.languageService.activeLocale() || undefined, {
      ...intlOptions,
      ...(zone ? { timeZone: zone } : {}),
    }).format(date);
  }

  private zone(options?: FormatOptions): string | undefined {
    if (options?.local) {
      return undefined; // render the raw local wall-clock (editable time inputs)
    }
    if (options?.timeZone) {
      return options.timeZone;
    }
    this.domainService ??= this.injector.get(DomainService);
    return this.domainService.sessionDomain()?.timeZone;
  }

  private numberFormatter(locale: string, options?: Intl.NumberFormatOptions): Intl.NumberFormat {
    const key = `${locale}|${options ? JSON.stringify(options) : ''}`;
    let formatter = this.numberFormatters.get(key);
    if (!formatter) {
      formatter = new Intl.NumberFormat(locale, options);
      this.numberFormatters.set(key, formatter);
    }
    return formatter;
  }
}
