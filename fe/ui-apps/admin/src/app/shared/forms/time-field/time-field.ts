import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  viewChild,
  viewChildren,
} from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { translateSignal } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { Icon } from '../../icons/icon';
import { PickerPopover } from '../../components/picker-popover/picker-popover';
import { isValidDate } from '../date-picker/date-locale';

/** A selectable value in the hour / minute columns: the number, with its locale-padded label. */
interface TimeOption {
  readonly value: number;
  readonly label: string;
}

/** 12-hour clock face order (12 leads, as on a clock), mapped to a 24h hour via the meridiem. */
const TWELVE_HOUR_ROW: readonly number[] = [12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];

let valueSeq = 0;

/**
 * Locale-aware time field — a Signal Forms `FormValueControl<Date | null>` replacing the native
 * `<input type="time">` (which renders 12h/24h in the browser locale, not the app language). The
 * value is a `Date` carrying only the hours/minutes (its date part is preserved from the previous
 * value / today). The trigger shows the time via {@link LocaleFormatService} — 12h AM/PM for
 * English, 24h otherwise — and clicking opens a {@link PickerPopover} with hour + minute columns
 * (plus an AM/PM toggle in 12h locales). Bind with `[formField]`, or via `[value]`/`(valueChange)`.
 *
 * **This control has no time zone, and deliberately takes none.** It carries a bare wall clock:
 * the hours and minutes go in and come back out unconverted, on whatever date part the value
 * already had. A time of day with no date is not an instant, so there is nothing for a zone to
 * resolve — which is exactly what an `HH:mm` setting (a cut-off time, a scheduled send time)
 * needs. A caller that owns a real instant converts on either side of this field; that is what
 * {@link DateTimeField} does, handing this control the wall clock its own zone produces.
 */
@Component({
  selector: 'app-time-field',
  imports: [Icon, PickerPopover],
  templateUrl: './time-field.html',
  styleUrl: './time-field.css',
  host: { class: 'block' },
})
export class TimeField implements FormValueControl<Date | null> {
  private readonly localeFormat = inject(LocaleFormatService);

  readonly value = model<Date | null>(null);
  readonly disabled = input(false);
  readonly ariaLabel = input('');
  readonly touch = output<void>();

  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  // Non-required so the template's `@if (popover()?.shown())` gate reads safely before view init.
  protected readonly popover = viewChild(PickerPopover);

  protected readonly use12h = computed(() => this.localeFormat.uses12HourClock());

  // Distinct accessible names so the two adjacent column groups aren't both announced as
  // the (single) field label — the popover owns their translation, like the calendar's arrows.
  protected readonly hoursLabel = translateSignal('common.timePicker.hours');
  protected readonly minutesLabel = translateSignal('common.timePicker.minutes');

  protected readonly displayValue = computed(() => {
    const value = this.value();
    if (!isValidDate(value)) return '';
    return this.localeFormat.formatTime(value, { local: true });
  });
  protected readonly hasValue = computed(() => this.displayValue() !== '');

  protected readonly valueId = `app-time-field-value-${valueSeq++}`;
  /**
   * The field's `ariaLabel` replaces the button's content as its accessible name, so the chosen
   * time is tied to the button as its description. Without a name the content already is the
   * name, and an empty field has no time to describe.
   */
  protected readonly describedBy = computed(() =>
    this.hasValue() && this.ariaLabel() !== '' ? this.valueId : null,
  );
  // Hint mirrors the display separator: Danish (24h) shows a period ("09.35"), English AM/PM a colon.
  protected readonly placeholder = computed(() => (this.use12h() ? 'h:mm AM/PM' : 'HH.mm'));

  // 12h shows the clock hours (12, 1…11, no padding); 24h shows 00…23, zero-padded to align.
  protected readonly hours = computed<readonly TimeOption[]>(() => {
    const use12 = this.use12h();
    const values = use12 ? TWELVE_HOUR_ROW : range(0, 24);
    return values.map((value) => ({ value, label: use12 ? String(value) : pad2(value) }));
  });
  // Minutes are locale-independent — a single static list of 00…59.
  protected readonly minutes: readonly TimeOption[] = range(0, 60).map((value) => ({
    value,
    label: pad2(value),
  }));

  private readonly selectedHour = computed(() => {
    const value = this.value();
    return isValidDate(value) ? value.getHours() : null;
  });
  private readonly selectedMinute = computed(() => {
    const value = this.value();
    return isValidDate(value) ? value.getMinutes() : null;
  });
  protected readonly meridiem = computed<'am' | 'pm' | null>(() => {
    const hour = this.selectedHour();
    return hour === null ? null : hour < 12 ? 'am' : 'pm';
  });

  private readonly hourButtons = viewChildren<ElementRef<HTMLButtonElement>>('hourBtn');
  private readonly minuteButtons = viewChildren<ElementRef<HTMLButtonElement>>('minuteBtn');

  constructor() {
    // On open, scroll the selected hour + minute into view (the columns scroll their own
    // fixed-height box; without this a late minute like :55 opens off-screen).
    effect(() => {
      if (!(this.popover()?.shown() ?? false)) return;
      centerSelected(this.hourButtons());
      centerSelected(this.minuteButtons());
    });
  }

  protected toggle(): void {
    if (this.disabled()) return;
    this.popover()?.toggle(this.trigger().nativeElement);
  }

  protected isHourSelected(hour: number): boolean {
    const current = this.selectedHour();
    if (current === null) return false;
    return this.use12h() ? to12h(current) === hour : current === hour;
  }

  protected isMinuteSelected(minute: number): boolean {
    return this.selectedMinute() === minute;
  }

  // The bare digit ("14") is ambiguous read on its own, so the accessible name carries the unit
  // ("14 Hours" / "30 Minutes") — the columns are plain button groups, not navigable listboxes.
  protected hourOptionLabel(h: TimeOption): string {
    return `${h.label} ${this.hoursLabel()}`;
  }

  protected minuteOptionLabel(m: TimeOption): string {
    return `${m.label} ${this.minutesLabel()}`;
  }

  protected selectHour(hour: number): void {
    const hours = this.use12h() ? to24h(hour, this.meridiem() ?? 'am') : hour;
    this.applyTime(hours, this.selectedMinute() ?? 0);
  }

  protected selectMinute(minute: number): void {
    this.applyTime(this.selectedHour() ?? 0, minute);
  }

  protected setMeridiem(meridiem: 'am' | 'pm'): void {
    const hour = this.selectedHour();
    // Only RE-MAP an already-chosen hour — never commit an arbitrary time (and mark the control
    // touched) when the field is still empty and no hour has been picked yet.
    if (hour === null) return;
    this.applyTime(to24h(to12h(hour), meridiem), this.selectedMinute() ?? 0);
  }

  private applyTime(hours: number, minutes: number): void {
    const base = isValidDate(this.value()) ? new Date(this.value()!) : startOfToday();
    base.setHours(hours, minutes, 0, 0);
    this.value.set(base);
    this.touch.emit();
  }
}

function range(start: number, end: number): number[] {
  return Array.from({ length: end - start }, (_, i) => start + i);
}
function pad2(n: number): string {
  return String(n).padStart(2, '0');
}
function to12h(hour24: number): number {
  return hour24 % 12 === 0 ? 12 : hour24 % 12;
}
function to24h(hour12: number, meridiem: 'am' | 'pm'): number {
  if (meridiem === 'am') return hour12 === 12 ? 0 : hour12;
  return hour12 === 12 ? 12 : hour12 + 12;
}
function startOfToday(): Date {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
}
/** Scroll the selected option in a column to the centre of its scroll box. */
function centerSelected(buttons: readonly ElementRef<HTMLButtonElement>[]): void {
  const el = buttons.find((b) => b.nativeElement.classList.contains('is-selected'));
  // Optional-chained: jsdom (unit tests) doesn't implement scrollIntoView.
  el?.nativeElement.scrollIntoView?.({ block: 'center' });
}
