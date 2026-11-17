import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { Icon } from '../../icons/icon';

/** How much of the instant the reader needs. */
export type ExpectedDateFormat = 'dateTime' | 'date' | 'weekday';

/**
 * A date that has not happened yet, marked as the expectation it is.
 *
 * Publication dates are stamped by the act of publishing, not by the clock, so a
 * date in the future is a projection of what will be written if somebody presses
 * the button — and rendered plainly it is indistinguishable from one that was
 * actually recorded. The clock glyph, the italics and the dotted underline say
 * "not yet" three ways, none of them colour, and the hover title says it in
 * words for anyone who reads none of them.
 *
 * Renders NOTHING without a usable value: a marker on an empty cell claims a
 * date is coming when the caller has none to show. The host is
 * `display: contents` for that reason — an empty wrapper still counts as an item
 * in a flex row with a gap.
 *
 * With `to` set it marks a whole PERIOD instead of an instant — one clock over
 * "from – to" rather than one per end. A public window that has not opened yet
 * is expected at both ends, and marking each separately reads as two projections
 * that happen to sit beside each other; the range is one.
 */
@Component({
  selector: 'app-expected-date',
  imports: [Icon, TranslocoPipe],
  templateUrl: './expected-date.html',
  host: { class: 'contents' },
})
export class ExpectedDate {
  private readonly localeFormat = inject(LocaleFormatService);

  /** The instant, as epoch ms, an ISO string or a `Date`. */
  readonly value = input<Date | number | string | null | undefined>(null);

  /**
   * The far end of a period, when the marker covers one.
   *
   * Rendered through the same formatter as `value`, so the two ends of a range
   * read at the same resolution. Left unset the component marks a single date.
   */
  readonly to = input<Date | number | string | null | undefined>(null);

  /**
   * How much of it to show.
   *
   * `weekday` names the day and drops the year — for a cut-off days away, which
   * a reader plans around by weekday rather than by date.
   */
  readonly format = input<ExpectedDateFormat>('dateTime');

  protected readonly formatted = computed(() => this.render(this.value()));

  protected readonly formattedTo = computed(() => this.render(this.to()));

  private render(value: Date | number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    switch (this.format()) {
      case 'date':
        return this.localeFormat.formatDate(value);
      case 'weekday':
        return this.localeFormat.formatWeekdayDateTime(value);
      default:
        return this.localeFormat.formatDateTime(value);
    }
  }
}
