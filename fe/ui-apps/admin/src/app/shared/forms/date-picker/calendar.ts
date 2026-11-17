import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  viewChildren,
} from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { Icon } from '../../icons/icon';
import { firstDayOfWeek, weekdayShortNames } from './date-locale';

interface DayCell {
  readonly date: Date;
  readonly day: number;
  readonly inMonth: boolean;
  readonly isToday: boolean;
  readonly isSelected: boolean;
  readonly isFocused: boolean;
  readonly disabled: boolean;
  readonly key: string;
  /** Full localized date, precomputed for the cell's aria-label (computed with `weeks`, not per CD). */
  readonly label: string;
}

interface YearCell {
  readonly year: number;
  readonly isSelected: boolean;
  readonly isFocused: boolean;
  readonly disabled: boolean;
}

/** Years shown per page of the year-selection grid (3 columns × 4 rows). */
const YEAR_PAGE_SIZE = 12;

/** The two calendar surfaces: the month day-grid, and the year-selection grid reached via the header. */
type CalendarView = 'days' | 'years';

/**
 * A locale-aware month calendar grid — the picker UI a native `<input type="date">`
 * can't localize. Month + weekday names come from `Intl` in the ACTIVE app locale
 * (reacting to the language toggle), and the first day of the week follows the
 * locale. Presentational only: `[selected]` in, `(dateSelected)` out; the parent
 * `DatePicker` owns the value + the popover.
 *
 * The header label is a button: it flips to a year-selection grid so a distant year
 * (e.g. 2017) is one click + one page away instead of dozens of month steps. Keyboard
 * (day grid): arrows move the day, Home/End the week ends, PageUp/Down the month
 * (±Shift = year), Enter/Space select. Keyboard (year grid): arrows move the year
 * (±1 / ±3 columns), Home/End the page ends, PageUp/Down page ±12, Enter/Space select.
 */
@Component({
  selector: 'app-calendar',
  imports: [Icon],
  templateUrl: './calendar.html',
  styleUrl: './calendar.css',
  host: { class: 'block select-none' },
})
export class Calendar {
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);

  readonly selected = input<Date | null>(null);
  readonly min = input<Date | null>(null);
  readonly max = input<Date | null>(null);
  /**
   * The day marked "today", as a browser-local Date. The grid is drawn in browser-local
   * days, so a parent working in another time zone passes that zone's today here rather
   * than letting the grid read the browser's clock.
   */
  readonly today = input<Date | null>(null);

  readonly dateSelected = output<Date>();

  protected readonly view = signal<CalendarView>('days');

  // Header-nav aria-labels are fixed strings, so the calendar owns their translation (consumers
  // don't pass them) — same self-translating approach as TreePicker's "untitled".
  private readonly previousMonthLabel = translateSignal('common.calendar.previousMonth');
  private readonly nextMonthLabel = translateSignal('common.calendar.nextMonth');
  private readonly previousYearsLabel = translateSignal('common.calendar.previousYears');
  private readonly nextYearsLabel = translateSignal('common.calendar.nextYears');
  protected readonly chooseYearLabel = translateSignal('common.calendar.chooseYear');
  protected readonly showCalendarLabel = translateSignal('common.calendar.showCalendar');

  protected readonly locale = computed(() => this.language.activeLocale());

  // The date with roving focus; also picks the visible month. Re-seeds from `selected`
  // (or today) whenever it changes — including when the binding first applies after
  // construction — while still accepting manual `.set()` from month/year navigation.
  protected readonly focusedDate = linkedSignal<Date>(() =>
    startOfDay(this.selected() ?? this.today() ?? new Date()),
  );

  // Formatting goes through LocaleFormatService, which caches its Intl formatters per locale+options.
  private readonly monthLabel = computed(() =>
    this.localeFormat.formatWith(
      this.focusedDate(),
      { month: 'long', year: 'numeric' },
      { local: true },
    ),
  );
  protected readonly weekdays = computed(() => weekdayShortNames(this.locale()));
  protected readonly weekStart = computed(() => firstDayOfWeek(this.locale()));
  protected readonly focusedKey = computed(() => dateKey(this.focusedDate()));

  /** First year of the 12-year page containing the focused year. */
  private readonly yearPageStart = computed(() => pageStartOf(this.focusedDate().getFullYear()));

  private readonly yearRangeLabel = computed(() => {
    const start = this.yearPageStart();
    return `${start} – ${start + YEAR_PAGE_SIZE - 1}`;
  });

  /** The header label + its prev/next aria-labels track the active view. */
  protected readonly headerLabel = computed(() =>
    this.view() === 'years' ? this.yearRangeLabel() : this.monthLabel(),
  );
  // Include the visible period in the accessible name (not just the action verb) so a screen
  // reader conveys WHICH month/range is shown — and so the aria-live header announces the new
  // period when it changes on prev/next navigation.
  protected readonly headerAriaLabel = computed(() => {
    const action = this.view() === 'years' ? this.showCalendarLabel() : this.chooseYearLabel();
    return `${this.headerLabel()}, ${action}`;
  });
  protected readonly previousLabel = computed(() =>
    this.view() === 'years' ? this.previousYearsLabel() : this.previousMonthLabel(),
  );
  protected readonly nextLabel = computed(() =>
    this.view() === 'years' ? this.nextYearsLabel() : this.nextMonthLabel(),
  );

  protected readonly weeks = computed<readonly (readonly DayCell[])[]>(() => {
    const focused = this.focusedDate();
    const weekStart = this.weekStart();
    const selected = this.selected();
    const today = startOfDay(this.today() ?? new Date());

    const firstOfMonth = new Date(focused.getFullYear(), focused.getMonth(), 1);
    const offset = (firstOfMonth.getDay() - weekStart + 7) % 7;
    const gridStart = addDays(firstOfMonth, -offset);

    const weeks: DayCell[][] = [];
    for (let w = 0; w < 6; w++) {
      const row: DayCell[] = [];
      for (let d = 0; d < 7; d++) {
        const date = addDays(gridStart, w * 7 + d);
        row.push({
          date,
          day: date.getDate(),
          inMonth: date.getMonth() === focused.getMonth(),
          isToday: sameDay(date, today),
          isSelected: selected !== null && sameDay(date, selected),
          isFocused: sameDay(date, focused),
          disabled: this.isDisabled(date),
          key: dateKey(date),
          label: this.localeFormat.formatWith(date, { dateStyle: 'full' }, { local: true }),
        });
      }
      weeks.push(row);
    }
    return weeks;
  });

  protected readonly years = computed<readonly YearCell[]>(() => {
    const start = this.yearPageStart();
    const focusedYear = this.focusedDate().getFullYear();
    const selectedYear = this.selected()?.getFullYear() ?? null;
    return Array.from({ length: YEAR_PAGE_SIZE }, (_, i) => {
      const year = start + i;
      return {
        year,
        isSelected: year === selectedYear,
        isFocused: year === focusedYear,
        disabled: this.isYearDisabled(year),
      };
    });
  });

  /** Whether `date` falls outside the [min, max] bounds — enforced on BOTH the mouse and keyboard paths. */
  private isDisabled(date: Date): boolean {
    const min = this.min();
    const max = this.max();
    return (min !== null && date < startOfDay(min)) || (max !== null && date > startOfDay(max));
  }

  /** Year-grid analogue of {@link isDisabled}: whether `year` falls outside the [min, max] bounds. */
  private isYearDisabled(year: number): boolean {
    const minYear = this.min()?.getFullYear() ?? null;
    const maxYear = this.max()?.getFullYear() ?? null;
    return (minYear !== null && year < minYear) || (maxYear !== null && year > maxYear);
  }

  private readonly dayButtons = viewChildren<ElementRef<HTMLButtonElement>>('day');
  private readonly yearButtons = viewChildren<ElementRef<HTMLButtonElement>>('yearBtn');
  // Only keyboard navigation (and a view switch) should move DOM focus into the grid —
  // clicking a header arrow must leave focus on that arrow. These flags gate the focus effects.
  private pendingFocus = true;
  private pendingYearFocus = false;

  constructor() {
    effect(() => {
      const key = this.focusedKey();
      const buttons = this.dayButtons();
      if (!this.pendingFocus) return;
      buttons.find((b) => b.nativeElement.dataset['key'] === key)?.nativeElement.focus();
      this.pendingFocus = false;
    });
    effect(() => {
      const year = String(this.focusedDate().getFullYear());
      const buttons = this.yearButtons();
      if (!this.pendingYearFocus) return;
      buttons.find((b) => b.nativeElement.dataset['year'] === year)?.nativeElement.focus();
      this.pendingYearFocus = false;
    });
  }

  protected selectDay(cell: DayCell): void {
    if (cell.disabled) return;
    this.dateSelected.emit(startOfDay(cell.date));
  }

  /** Header label click: into the year grid, or back to the day grid. */
  protected toggleYearView(): void {
    if (this.view() === 'years') {
      this.view.set('days');
      this.pendingFocus = true;
    } else {
      this.view.set('years');
      this.pendingYearFocus = true;
    }
  }

  /** Year-grid pick: adopt the year (keeping month/day) and drop back to the day grid. */
  protected selectYear(year: number): void {
    this.focusedDate.update((d) => withYear(d, year));
    this.view.set('days');
    this.pendingFocus = true;
  }

  // Header arrows page the visible period but must LEAVE focus on the arrow (so a keyboard user
  // can repeat-press to keep paging) — the same contract as the day-view month arrows. Only a
  // view SWITCH (toggleYearView) moves focus into the grid.
  protected previous(): void {
    const step = this.view() === 'years' ? -YEAR_PAGE_SIZE : 0;
    this.focusedDate.update((d) => (step ? addYears(d, step) : addMonths(d, -1)));
  }

  protected next(): void {
    const step = this.view() === 'years' ? YEAR_PAGE_SIZE : 0;
    this.focusedDate.update((d) => (step ? addYears(d, step) : addMonths(d, 1)));
  }

  protected onGridKeydown(event: KeyboardEvent): void {
    const current = this.focusedDate();
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      const date = startOfDay(current);
      if (!this.isDisabled(date)) this.dateSelected.emit(date);
      return;
    }
    const dow = (current.getDay() - this.weekStart() + 7) % 7;
    const next = nextFocusDate(event, current, dow);
    if (next) {
      event.preventDefault();
      this.pendingFocus = true;
      this.focusedDate.set(startOfDay(next));
    }
  }

  /** Year-grid roving-focus navigation — the listbox analogue of {@link onGridKeydown}. */
  protected onYearGridKeydown(event: KeyboardEvent): void {
    const year = this.focusedDate().getFullYear();
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (!this.isYearDisabled(year)) this.selectYear(year);
      return;
    }
    const next = nextFocusYear(event, year);
    if (next !== null) {
      event.preventDefault();
      this.pendingYearFocus = true;
      this.focusedDate.update((d) => withYear(d, next));
    }
  }
}

/** The date a grid navigation key moves roving focus to, or null for a non-navigation key. */
function nextFocusDate(event: KeyboardEvent, current: Date, dayOfWeek: number): Date | null {
  switch (event.key) {
    case 'ArrowLeft':
      return addDays(current, -1);
    case 'ArrowRight':
      return addDays(current, 1);
    case 'ArrowUp':
      return addDays(current, -7);
    case 'ArrowDown':
      return addDays(current, 7);
    case 'Home':
      return addDays(current, -dayOfWeek);
    case 'End':
      return addDays(current, 6 - dayOfWeek);
    case 'PageUp':
      return addMonths(current, event.shiftKey ? -12 : -1);
    case 'PageDown':
      return addMonths(current, event.shiftKey ? 12 : 1);
    default:
      return null;
  }
}

/** Grid columns in the year-selection page (3 × 4 = {@link YEAR_PAGE_SIZE}). */
const YEAR_GRID_COLUMNS = 3;

/** First year of the 12-year page containing `year` (pages start on multiples of the page size). */
function pageStartOf(year: number): number {
  return year - (((year % YEAR_PAGE_SIZE) + YEAR_PAGE_SIZE) % YEAR_PAGE_SIZE);
}

/** The year a year-grid navigation key moves roving focus to, or null for a non-navigation key. */
function nextFocusYear(event: KeyboardEvent, year: number): number | null {
  switch (event.key) {
    case 'ArrowLeft':
      return year - 1;
    case 'ArrowRight':
      return year + 1;
    case 'ArrowUp':
      return year - YEAR_GRID_COLUMNS;
    case 'ArrowDown':
      return year + YEAR_GRID_COLUMNS;
    case 'Home':
      return pageStartOf(year);
    case 'End':
      return pageStartOf(year) + YEAR_PAGE_SIZE - 1;
    case 'PageUp':
      return year - YEAR_PAGE_SIZE;
    case 'PageDown':
      return year + YEAR_PAGE_SIZE;
    default:
      return null;
  }
}

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}
function addDays(d: Date, n: number): Date {
  const r = new Date(d);
  r.setDate(r.getDate() + n);
  return r;
}
function addMonths(d: Date, n: number): Date {
  return withYearMonth(d, d.getFullYear(), d.getMonth() + n);
}
function addYears(d: Date, n: number): Date {
  return withYear(d, d.getFullYear() + n);
}
function withYear(d: Date, year: number): Date {
  return withYearMonth(d, year, d.getMonth());
}
/**
 * Set year+month, preserving the day-of-month clamped to the target month's length
 * (Jan 31 → Feb 28/29) rather than letting JS overflow into the following month.
 */
function withYearMonth(d: Date, year: number, month: number): Date {
  const target = new Date(year, month, 1);
  const lastDay = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
  target.setDate(Math.min(d.getDate(), lastDay));
  return target;
}
function sameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}
function dateKey(d: Date): string {
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}
