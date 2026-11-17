import { inject, Injectable } from '@angular/core';

import {
  DATE_TIME_FORMAT,
  zoneAbbreviation,
} from '../../../core/main-shell/services/date-time-format';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import type { FiringPeriod } from '../models/firing-area-periods.model';

/**
 * Renders a firing period in the session domain's timezone. A period within one day is shown as
 * a time-only interval (`LT - LT z`, e.g. "08:00 - 12:00 CEST"). A period whose ends fall on
 * different days in that zone — one "until midnight", which ends at 00:00 of the next day, or one
 * across midnight — shows the date on both ends, so a period listed on two days reads right on
 * each. A period whose end is not after its start (an older one stored backwards, e.g. 22:00 to
 * 02:00 of one day, which is listed on its start day and, for its early hours, on the next day)
 * shows the stored date on both ends too, so it reads as what it is and not as tonight's hours.
 * The timezone abbreviation rides on the `to` end, and on the `from` end as well when a
 * clock change lies between the two ("01:30 CET - 03:30 CEST"): the end's label alone would put
 * the start an hour off. Uses the core LocaleFormatService — no messages dep.
 */
@Injectable({ providedIn: 'root' })
export class FiringPeriodFormatter {
  private readonly locale = inject(LocaleFormatService);
  private readonly language = inject(LanguageService);

  format(period: FiringPeriod): string {
    const time = (iso: string | undefined, withZone: boolean): string =>
      iso ? this.locale.formatTime(iso, { withZoneName: withZone }) : '';
    if (period.fromDate && period.toDate) {
      const from = time(period.fromDate, this.zonesDiffer(period.fromDate, period.toDate));
      const to = time(period.toDate, true);
      if (from && to) {
        const fromDay = this.locale.formatDate(period.fromDate);
        const toDay = this.locale.formatDate(period.toDate);
        const backwards = Date.parse(period.toDate) <= Date.parse(period.fromDate);
        if (fromDay !== toDay || backwards) {
          const sep = DATE_TIME_FORMAT.dateTimeSeparator;
          return `${fromDay}${sep}${from} - ${toDay}${sep}${to}`;
        }
        return `${from} - ${to}`;
      }
    }
    return time(period.fromDate, false) || time(period.toDate, true);
  }

  /** One end of a period on its own: its date and its time with the zone ("24 Nov 2026, 11:00 PM CET"). */
  formatInstant(iso: string): string {
    const time = this.locale.formatTime(iso, { withZoneName: true });
    return `${this.locale.formatDate(iso)}${DATE_TIME_FORMAT.dateTimeSeparator}${time}`;
  }

  /** Whether the two ends read different zone abbreviations in the session domain's zone. */
  private zonesDiffer(fromIso: string, toIso: string): boolean {
    const locale = this.language.activeLocale();
    const zone = this.locale.sessionTimeZone();
    return zoneAbbreviation(fromIso, locale, zone) !== zoneAbbreviation(toIso, locale, zone);
  }
}
