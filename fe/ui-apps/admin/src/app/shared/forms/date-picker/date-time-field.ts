import { Component, computed, inject, input, model, output } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { translateSignal } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import {
  getZonedDateParts,
  zonedDateToUtc,
  zonedWallClockAsLocalDate,
} from '../../utils/zoned-date';
import { TimeField } from '../time-field/time-field';
import { DatePicker } from './date-picker';
import { isValidDate } from './date-locale';

/** A time of day, as the field reads and writes it in the effective zone. */
interface WallClock {
  readonly hour: number;
  readonly minute: number;
}

/**
 * Locale-aware date + time field — a Signal Forms `FormValueControl<Date | null>` replacing the
 * native `<input type="datetime-local">`. Composes {@link DatePicker} (date part) and
 * {@link TimeField} (time part) over a single `Date`: picking a date keeps the current time and
 * vice-versa. Bind with `[formField]`, or standalone via `[value]`/`(valueChange)`.
 *
 * When the FIRST date is picked (no prior value) the time part is seeded to midnight, unless
 * {@link defaultToCurrentTime} is set — then it seeds to the current wall-clock time, which suits
 * "acting now" fields like the message publish From/To range.
 *
 * **Time zone.** The value is an instant; the wall clock shown and typed is that instant read in
 * the session domain's zone (or the zone named by `[timeZone]`). So "12:00 on 5 March" is 12:00
 * *for the domain*, whatever the editor's own clock says. `[local]="true"` opts out, keeping the
 * browser's wall clock unconverted for a consumer that owns the conversion or that carries a bare
 * time. With no zone available and no `[local]`, the control disables itself: a date-time with a
 * guessed zone is not a date-time. All of {@link defaultToCurrentTime}, the midnight default and
 * the time part read the effective zone, so the two halves never disagree.
 */
@Component({
  selector: 'app-date-time-field',
  imports: [DatePicker, TimeField],
  template: `
    <div
      class="flex items-center gap-2"
      [attr.title]="zoneMissing() ? noTimeZoneLabel() : null"
      [attr.aria-disabled]="zoneMissing() ? 'true' : null"
    >
      <app-date-picker
        class="min-w-0 flex-1"
        [value]="value()"
        (valueChange)="onDateChange($event)"
        [disabled]="disabled()"
        [timeZone]="timeZone()"
        [local]="local()"
        [ariaLabel]="ariaLabel()"
        [inputId]="inputId()"
        [placeholder]="placeholder()"
        [showClear]="showClear()"
        [clearLabel]="clearLabel()"
      />
      <app-time-field
        class="w-28 shrink-0"
        [value]="timeValue()"
        (valueChange)="onTimeChange($event)"
        [disabled]="isDisabled()"
        [ariaLabel]="ariaLabel()"
      />
    </div>
  `,
  host: { class: 'block' },
})
export class DateTimeField implements FormValueControl<Date | null> {
  private readonly localeFormat = inject(LocaleFormatService);

  readonly value = model<Date | null>(null);
  readonly disabled = input(false);
  readonly ariaLabel = input('');
  readonly inputId = input('');
  readonly placeholder = input('');
  readonly showClear = input(false);
  readonly clearLabel = input('');
  /** IANA zone the shown/typed wall clock belongs to; defaults to the session domain's. */
  readonly timeZone = input<string | undefined>(undefined);
  /** Carry the browser's own wall clock, unconverted — for a consumer that owns the conversion. */
  readonly local = input(false);
  /** Seed the time to the current wall-clock time (not 00:00) when the first date is picked. */
  readonly defaultToCurrentTime = input(false);
  readonly touch = output<void>();

  protected readonly noTimeZoneLabel = translateSignal('common.dateTime.noTimeZone');

  /** The zone the value is read and written in, or `undefined` in local mode / with no domain. */
  protected readonly effectiveZone = computed<string | undefined>(() =>
    this.local() ? undefined : (this.timeZone() ?? this.localeFormat.sessionTimeZone()),
  );
  protected readonly zoneMissing = computed(() => !this.local() && !this.effectiveZone());
  protected readonly isDisabled = computed(() => this.disabled() || this.zoneMissing());

  /**
   * The time part as {@link TimeField} reads it — that control is a bare wall-clock carrier, so
   * an instant is handed to it as a browser-local Date holding the effective zone's clock.
   */
  protected readonly timeValue = computed<Date | null>(() => {
    const value = this.value();
    if (!isValidDate(value)) return null;
    const zone = this.effectiveZone();
    if (!zone) return value;
    return zonedWallClockAsLocalDate(value, zone);
  });

  protected onDateChange(date: Date | null): void {
    if (date === null) {
      this.commit(null);
      return;
    }
    const zone = this.effectiveZone();
    if (!zone) {
      const merged = new Date(date);
      const time = this.localTimeOfDay();
      merged.setHours(time.hour, time.minute, 0, 0);
      this.commit(merged);
      return;
    }
    // The picker emits the zone's 00:00 of the day that was clicked; only its day survives.
    const day = getZonedDateParts(date, zone);
    const time = this.zonedTimeOfDay(zone);
    this.commit(zonedDateToUtc(day.year, day.month, day.day, time.hour, time.minute, 0, zone));
  }

  protected onTimeChange(time: Date | null): void {
    const current = this.value();
    const zone = this.effectiveZone();
    if (!zone) {
      if (time === null) {
        if (isValidDate(current)) {
          const midnight = new Date(current);
          midnight.setHours(0, 0, 0, 0);
          this.commit(midnight);
        }
        return;
      }
      const base = isValidDate(current) ? new Date(current) : new Date();
      base.setHours(time.getHours(), time.getMinutes(), 0, 0);
      this.commit(base);
      return;
    }
    if (time === null && !isValidDate(current)) {
      return;
    }
    // A cleared time falls back to the zone's midnight on the day already held.
    const day = getZonedDateParts(isValidDate(current) ? current : new Date(), zone);
    const hour = time === null ? 0 : time.getHours();
    const minute = time === null ? 0 : time.getMinutes();
    this.commit(zonedDateToUtc(day.year, day.month, day.day, hour, minute, 0, zone));
  }

  /** The time of day a newly picked date inherits, read in `zone`. */
  private zonedTimeOfDay(zone: string): WallClock {
    const current = this.value();
    if (isValidDate(current)) {
      return getZonedDateParts(current, zone);
    }
    if (this.defaultToCurrentTime()) {
      return getZonedDateParts(new Date(), zone);
    }
    return { hour: 0, minute: 0 };
  }

  /** {@link zonedTimeOfDay} for local mode, where the browser's own clock is the wall clock. */
  private localTimeOfDay(): WallClock {
    const current = this.value();
    if (isValidDate(current)) {
      return { hour: current.getHours(), minute: current.getMinutes() };
    }
    if (this.defaultToCurrentTime()) {
      const now = new Date();
      return { hour: now.getHours(), minute: now.getMinutes() };
    }
    return { hour: 0, minute: 0 };
  }

  private commit(value: Date | null): void {
    this.value.set(value);
    this.touch.emit();
  }
}
