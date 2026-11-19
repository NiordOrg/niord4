import {
  Component,
  ElementRef,
  computed,
  inject,
  input,
  model,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { translateSignal } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { Icon } from '../../icons/icon';
import { PickerPopover } from '../../components/picker-popover/picker-popover';
import { dayInZoneToLocalDate, pickedDayToZone } from '../../utils/zoned-date';
import { Calendar } from './calendar';
import { isValidDate } from './date-locale';

let valueSeq = 0;

/**
 * Locale-aware date field — a Signal Forms `FormValueControl<Date | null>` replacing the
 * native `<input type="date">` (whose calendar renders in the browser locale, not the app
 * language). The trigger shows the selected date formatted via {@link LocaleFormatService};
 * clicking opens an {@link PickerPopover} holding the locale-aware {@link Calendar}. Bind
 * with `[formField]`, or standalone via `[value]`/`(valueChange)`.
 *
 * **Time zone.** The value is an instant, and the calendar day an instant falls on depends
 * on the zone it is read in. By default that zone is the session domain's: the calendar
 * highlights the domain's day, and picking a day emits that day's 00:00:00.000 *in the
 * domain's zone*. `[timeZone]` names a different zone (a record whose own zone is not the
 * session's); `[local]="true"` opts out entirely, making the control a browser wall-clock
 * carrier for a consumer that converts on its own. With neither a `[timeZone]` nor a
 * session domain to take one from, the control disables itself rather than silently
 * standing in the browser's zone — a guessed zone is as wrong as a hardcoded one.
 */
@Component({
  selector: 'app-date-picker',
  imports: [Icon, PickerPopover, Calendar],
  templateUrl: './date-picker.html',
  host: { class: 'block' },
})
export class DatePicker implements FormValueControl<Date | null> {
  private readonly localeFormat = inject(LocaleFormatService);

  /** The value — two-way bound by `[formField]`/`[value]`. */
  readonly value = model<Date | null>(null);
  /** Set by the form when disabled, or bound directly. */
  readonly disabled = input(false);
  readonly placeholder = input('');
  readonly ariaLabel = input('');
  /** Id for the trigger button, so an external `<label for>` can associate with it. */
  readonly inputId = input('');
  readonly showClear = input(false);
  /** Aria-label for the clear button; a consumer that enables showClear passes a translated string. */
  readonly clearLabel = input('');
  /** IANA zone the picked day belongs to; defaults to the session domain's. */
  readonly timeZone = input<string | undefined>(undefined);
  /** Carry the browser's own wall clock, unconverted — for a consumer that owns the conversion. */
  readonly local = input(false);
  /** Emitted on selection/clear so Signal Forms touched-tracking works. */
  readonly touch = output<void>();

  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  // Non-required so the template's `@if (popover()?.shown())` gate reads safely before view init.
  protected readonly popover = viewChild(PickerPopover);

  protected readonly noTimeZoneLabel = translateSignal('common.dateTime.noTimeZone');

  /** The zone the value is read and written in, or `undefined` in local mode / with no domain. */
  protected readonly effectiveZone = computed<string | undefined>(() =>
    this.local() ? undefined : (this.timeZone() ?? this.localeFormat.sessionTimeZone()),
  );
  /** The control needs a zone and has none — it cannot honestly interpret a picked day. */
  protected readonly zoneMissing = computed(() => !this.local() && !this.effectiveZone());
  protected readonly isDisabled = computed(() => this.disabled() || this.zoneMissing());

  protected readonly displayValue = computed(() => {
    const value = this.value();
    if (!isValidDate(value)) return '';
    const zone = this.effectiveZone();
    return this.localeFormat.formatDate(value, zone ? { timeZone: zone } : { local: true });
  });
  protected readonly hasValue = computed(() => this.displayValue() !== '');

  protected readonly valueId = `app-date-picker-value-${valueSeq++}`;
  /**
   * A field name (`ariaLabel`, or an outside `<label for>` through `inputId`) replaces the button's
   * content as its accessible name, so the chosen date is tied to the button as its description.
   * Without a name the content already is the name, and an empty field has no date to describe.
   */
  protected readonly describedBy = computed(() =>
    this.hasValue() && (this.ariaLabel() !== '' || this.inputId() !== '') ? this.valueId : null,
  );

  /** The selection as the calendar grid reads it: a browser-local Date on the zone's day. */
  protected readonly calendarValue = computed<Date | null>(() => {
    const value = this.value();
    if (!isValidDate(value)) return null;
    const zone = this.effectiveZone();
    return zone ? dayInZoneToLocalDate(value, zone) : value;
  });

  // Sampled when the calendar opens rather than derived: "today" is a reading of the clock,
  // not of a signal, so nothing would tell a cached computed that the day had turned over.
  protected readonly calendarToday = signal<Date | null>(null);

  protected toggle(): void {
    if (this.isDisabled()) return;
    const zone = this.effectiveZone();
    this.calendarToday.set(zone ? dayInZoneToLocalDate(new Date(), zone) : null);
    this.popover()?.toggle(this.trigger().nativeElement);
  }

  protected onDateSelected(date: Date): void {
    const zone = this.effectiveZone();
    this.value.set(zone ? pickedDayToZone(date, zone, 'start') : date);
    this.touch.emit();
    this.popover()?.close();
    this.trigger().nativeElement.focus();
  }

  protected clear(event: MouseEvent): void {
    event.stopPropagation();
    this.value.set(null);
    this.touch.emit();
  }
}
