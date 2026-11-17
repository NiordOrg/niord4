import { inject, Injectable } from '@angular/core';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { DateInterval, Message } from '../models/message.model';
import type { MessageDateColumnType } from '../models/message-date-column-type.model';

@Injectable({ providedIn: 'root' })
export class MessageDateFormatterService {
  private readonly localeFormat = inject(LocaleFormatService);

  formatDateInterval(interval: DateInterval): string {
    if (!interval.fromDate && !interval.toDate) {
      return '';
    }

    const from = interval.fromDate ? this.formatDate(interval.fromDate, interval.allDay) : '';
    const to = interval.toDate ? this.formatDate(interval.toDate, interval.allDay) : '';

    if (from && to) {
      return `${from} – ${to}`;
    }
    return from || to;
  }

  formatEventDates(dates: DateInterval[] | undefined): string {
    if (!dates || dates.length === 0) {
      return '';
    }
    return dates.map((d) => this.formatDateInterval(d)).join(', ');
  }

  /**
   * Renders the date string for a message-list date column. Owns the DTO
   * traversal each column type needs — flattening `parts[].eventDates[]` for
   * DATE, and building synthetic intervals from the scalar publish/follow-up
   * fields — so the column component stays a pure renderer. `firstLine` shows
   * only the first event interval (the compact table-cell variant).
   */
  formatForColumnType(message: Message, type: MessageDateColumnType, firstLine: boolean): string {
    switch (type) {
      case 'DATE': {
        const collected = (message.parts ?? []).flatMap((part) => part.eventDates ?? []);
        if (firstLine && collected.length > 0) {
          return this.formatDateInterval(collected[0]);
        }
        return this.formatEventDates(collected);
      }
      case 'PUBLISH_DATE':
        if (!message.publishDateFrom && !message.publishDateTo) {
          return '';
        }
        return this.formatEventDates([
          { fromDate: message.publishDateFrom, toDate: message.publishDateTo, allDay: false },
        ]);
      case 'FOLLOW_UP_DATE':
        if (!message.followUpDate) {
          return '';
        }
        return this.formatEventDates([{ fromDate: message.followUpDate, allDay: true }]);
    }
  }

  // Accepts a number as well as a string: the backend serializes dates as epoch
  // milliseconds, and `new Date('1720000000000')` is an Invalid Date whose comparison is
  // always false — i.e. a silently never-overdue row.
  isOverdue(followUpDate: string | number | undefined): boolean {
    if (!followUpDate) {
      return false;
    }
    return new Date(followUpDate) < new Date();
  }

  /**
   * For the compact first-line DATE cell: ALL the message's event intervals,
   * one per line — the hover title behind the `…` more-indicator (the
   * complete newline-joined list, first interval included). Empty
   * when nothing is truncated (0 or 1 interval), so no indicator renders.
   */
  hiddenEventDatesTitle(message: Message): string {
    const collected = (message.parts ?? []).flatMap((part) => part.eventDates ?? []);
    if (collected.length <= 1) {
      return '';
    }
    return collected.map((interval) => this.formatDateInterval(interval)).join('\n');
  }

  /** Renders an ISO timestamp as a date-only string in the session domain's timezone. */
  formatDateOnly(isoDate: string | undefined): string {
    if (!isoDate) return '';
    return this.formatDate(isoDate, true);
  }

  private formatDate(isoDate: string, allDay?: boolean): string {
    // LocaleFormatService applies the session-domain timezone; all-day = date only, timed = date + time.
    return allDay
      ? this.localeFormat.formatDate(isoDate)
      : this.localeFormat.formatDateTime(isoDate);
  }
}
