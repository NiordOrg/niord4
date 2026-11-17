import { HttpErrorResponse, httpResource } from '@angular/common/http';
import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { form, FormField, max, min, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { PickerPopover } from '../../../../../shared/components/picker-popover/picker-popover';
import type { NumberSequenceType } from '../../../models/admin-message-series.model';
import { MessageSeriesAdminApiService } from '../../../services/api/message-series-admin-api.service';
import { currentYearIn } from './series-rules';

/** The permitted year range (1900–2099 inclusive), enforced as numeric-input bounds. */
const MIN_YEAR = 1900;
const MAX_YEAR = 2099;

interface NextNumberFormModel {
  year: number | null;
  nextNumber: number | null;
}

/**
 * The next-sequence-number editor for an auto-numbered series — an anchored
 * popover on the drawer's next-number row (a value picker: page scroll is
 * frozen, committing takes the explicit Update button).
 *
 * The stored number is fetched per `{seriesId, year}` while the popover is
 * open — on open and again on every year change (also for CONTINUOUS series,
 * which hide the year input but still read the current year).
 * Compute fetches the number derived from the year's existing messages and
 * overwrites the input. Update PUTs and closes the POPOVER ONLY — the host
 * drawer stays open and refreshes its display + the list in the background.
 * Dismissing without Update persists nothing; a failed Update keeps the
 * popover open. The backend refuses (400) a number below one the series
 * already used, and the toast then says so and points to Compute.
 */
@Component({
  selector: 'app-next-number-popover',
  imports: [FormField, PickerPopover, TranslocoPipe],
  templateUrl: './next-number-popover.html',
  host: { class: 'contents' },
})
export class NextNumberPopover {
  private readonly api = inject(MessageSeriesAdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly localeFormat = inject(LocaleFormatService);

  readonly seriesId = input.required<string>();
  readonly sequenceType = input.required<NumberSequenceType>();
  /** A next number was persisted — the host refreshes its display + the list. */
  readonly updated = output<void>();

  protected readonly popover = viewChild(PickerPopover);
  private readonly numberField = viewChild<ElementRef<HTMLInputElement>>('numberField');

  /** The year a fresh session opens on, as the session domain's clock reads it. */
  private readonly currentYear = computed(() => currentYearIn(this.localeFormat.sessionTimeZone()));
  protected readonly saving = signal(false);

  private readonly formModel = signal<NextNumberFormModel>({ year: null, nextNumber: null });
  protected readonly form = form(this.formModel, (path) => {
    required(path.year);
    min(path.year, MIN_YEAR);
    max(path.year, MAX_YEAR);
    required(path.nextNumber);
    min(path.nextNumber, 0);
  });

  protected readonly shown = computed(() => this.popover()?.shown() ?? false);

  /**
   * The fetch key must be the year ALONE: this computed memoizes the primitive
   * so `nextNumber` edits (seed, Compute, typing) never rebuild the resource
   * request — a fresh request object would force a spurious refetch that
   * overwrites the user's input.
   */
  private readonly year = computed(() => this.formModel().year);

  /**
   * The stored next number, keyed `{seriesId, year}` — fetched while the
   * popover is open and the year is in range. `shown` is read first so the
   * required `seriesId` input is never touched before it is set.
   */
  private readonly stored = httpResource<number | null>(() => {
    const shown = this.popover()?.shown() ?? false;
    if (!shown) {
      return undefined;
    }
    const year = this.year();
    if (year === null || year < MIN_YEAR || year > MAX_YEAR) {
      return undefined;
    }
    return this.api.nextNumberRequest(this.seriesId(), year);
  });

  /**
   * Update commits only a valid non-negative integer (deliberately tightened —
   * an empty or non-integer input never PUTs).
   */
  protected readonly updateDisabled = computed(() => {
    const { nextNumber } = this.formModel();
    return (
      this.saving() || this.form().invalid() || nextNumber === null || !Number.isInteger(nextNumber)
    );
  });

  /**
   * The `{seriesId, year}` key the input was last seeded for. Guards the seed
   * effect so a stored fetch that resolves late — after the user already
   * Computed or typed a number for the SAME key — cannot clobber their value;
   * a year change (new key) or a reopen (reset to null) still re-seeds.
   */
  private lastSeededKey: string | null = null;

  constructor() {
    // Seed the input once per fetch key. Keyed on status (not value): a reopen
    // refetches through loading→resolved even when the server returns the same
    // number, so the reset input is reliably re-seeded.
    effect(() => {
      const status = this.stored.status();
      if (status !== 'resolved') {
        return;
      }
      const value = this.stored.value();
      untracked(() => {
        const key = `${this.seriesId()}|${this.year()}`;
        if (key === this.lastSeededKey) {
          return;
        }
        this.lastSeededKey = key;
        this.form.nextNumber().value.set(value ?? null);
      });
    });

    // Initial focus lands on the number input when the popover opens.
    effect(() => {
      const field = this.numberField()?.nativeElement;
      if (field) {
        setTimeout(() => field.focus(), 0);
      }
    });
  }

  /** Opens a fresh session at the current year; the stored number loads via the fetch. */
  open(anchor: HTMLElement): void {
    this.formModel.set({ year: this.currentYear(), nextNumber: null });
    this.form().reset();
    this.saving.set(false);
    this.lastSeededKey = null;
    this.popover()?.open(anchor);
  }

  /** Trigger hook: a re-click while open closes it. */
  toggle(anchor: HTMLElement): void {
    if (this.shown()) {
      this.popover()?.close();
    } else {
      this.open(anchor);
    }
  }

  protected close(): void {
    this.popover()?.close();
  }

  /** Fetches the number derived from the year's existing messages and overwrites the input. */
  protected compute(): void {
    const year = this.formModel().year;
    if (year === null) {
      return;
    }
    this.api.computeNextNumber(this.seriesId(), year).subscribe({
      next: (nextNumber) => this.form.nextNumber().value.set(nextNumber ?? null),
      error: () => this.notifications.error('admin.series.nextNumber.toast.computeError'),
    });
  }

  protected update(): void {
    const { year, nextNumber } = this.formModel();
    // Guards the Enter-submit path on the same gate as the Update button.
    if (this.updateDisabled() || year === null || nextNumber === null) {
      return;
    }
    this.saving.set(true);
    this.api.setNextNumber(this.seriesId(), year, nextNumber).subscribe({
      next: () => {
        this.saving.set(false);
        this.notifications.success('admin.series.nextNumber.toast.updated');
        this.popover()?.close();
        this.updated.emit();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        // A 400 is the backend refusing a number below one the series already used (the form
        // never sends an empty or negative one): say why; any other failure keeps the generic toast.
        this.notifications.error(
          err instanceof HttpErrorResponse && err.status === 400
            ? 'admin.series.nextNumber.toast.belowUsed'
            : 'admin.series.nextNumber.toast.updateError',
        );
      },
    });
  }
}
