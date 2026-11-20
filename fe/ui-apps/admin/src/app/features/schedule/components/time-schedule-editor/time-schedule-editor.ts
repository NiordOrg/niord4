import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { TimeField } from '../../../../shared/forms/time-field/time-field';
import { DomainService } from '../../../../core/domain/services/domain.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { DiscardConfirmService } from '../../../../shared/services/discard-confirm.service';
import {
  shiftDaysInZone,
  wallClockOf,
  wallClockToInstant,
  zonedWallClockAsLocalDate,
} from '../../../../shared/utils/zoned-date';
import type { FiringAreaPeriods, FiringPeriod } from '../../models/firing-area-periods.model';
import { ScheduleApiService } from '../../services/api/schedule-api.service';
import { FiringPeriodFormatter } from '../../services/firing-period-formatter';

/**
 * A draft interval while editing — the time-only pickers bind to Date values. A row seeded from a
 * stored period carries it whole in `original` (its id, legacy id and stored instants); the other
 * fields are absent on a blank row.
 */
interface DraftInterval {
  from: Date | null;
  to: Date | null;
  /** The stored period this row was seeded from. */
  original?: FiringPeriod;
  /**
   * The start time of the row was changed: on save that clock is put on the selected day. A
   * stored row's unchanged clock keeps the instant it was seeded from.
   */
  fromTouched?: boolean;
  /** The end time of the row was changed: on save that clock is put on the selected day. */
  toTouched?: boolean;
  /** The period starts on an earlier day: shown as text, kept, and sent back as stored. */
  locked?: boolean;
}

/** The line under a draft row that does not end after it starts. */
interface RowErrorLine {
  key: string;
  params: Record<string, string>;
}

let nextEditorId = 0;

/**
 * Inline (expand-in-row) firing-period editor for one area on the selected date.
 * View mode lists the formatted intervals + a pencil; edit mode is a repeating
 * from/to time-picker list (Add/Remove/Clear/Cancel/Save). Cancel asks the app's usual
 * "unsaved changes" question first when the rows differ in substance (see `draftChanged`). Save normalises each
 * changed or new interval onto the selected date (missing from → 00:00:00, missing
 * to → the last second of the day, an end of 00:00 → 00:00 of the next day, both-empty dropped), PUTs,
 * and shows the saved periods on success. A working copy keeps the source row
 * untouched until save.
 *
 * **Stored periods keep their identity.** A row seeded from a stored period is sent back with its
 * id and legacy id, so the backend updates it rather than deleting it and adding a new one; an
 * untouched row is sent with its stored instants, so a period that crosses midnight into the next
 * day is not moved onto the selected day. A changed row takes the typed clock on the selected day
 * for the time that was changed only, so a period that crosses midnight keeps its end on the next
 * day while its start is moved. A period that starts on an earlier day is listed on this
 * day too: it is shown locked (as text, with no time fields and no remove button), Clear keeps it,
 * and it is sent back unchanged. It is edited on the day it starts.
 *
 * **Loaded periods.** A save also names the ids of the periods the editor loaded. The backend
 * deletes a period of the day that the save leaves out only when it is one of those, so a period
 * another tab added after this one loaded the day is kept.
 *
 * **Order.** A row that does not end after it starts is flagged inline and Save is held back; this
 * includes a period stored backwards by the editor before that check existed, which is flagged the
 * moment its day is opened. The backend refuses such a period too. Such a stored row's line names
 * the stored end and asks for the end time again: its time field shows only the end's clock, which
 * may read as a valid end. The view lists such a period with a warning mark. A row whose stored
 * period ran past midnight is flagged with different advice: ending it at 00:00 would cut off the
 * part on the next day, which is entered there as a period of its own.
 *
 * **Time zone.** A firing period is announced in the session domain's zone, so that is the
 * zone the entered times belong to: an instant is seeded into the time fields as the clock
 * it reads at there, and a saved interval is the typed clock resolved there on the selected
 * day. The time fields themselves carry a bare wall clock and take no zone. With no session
 * domain there is no zone to resolve against, and editing is refused rather than falling
 * back to whatever clock the person editing happens to sit at.
 */
@Component({
  selector: 'app-time-schedule-editor',
  imports: [TranslocoPipe, Icon, TimeField],
  templateUrl: './time-schedule-editor.html',
  host: { class: 'block' },
})
export class TimeScheduleEditor {
  private readonly api = inject(ScheduleApiService);
  private readonly notifications = inject(NotificationService);
  private readonly formatter = inject(FiringPeriodFormatter);
  private readonly domain = inject(DomainService);
  private readonly discardConfirm = inject(DiscardConfirmService);

  readonly firingAreaPeriods = input.required<FiringAreaPeriods>();
  readonly date = input.required<Date>();

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly periods = signal<readonly FiringPeriod[]>([]);
  /**
   * The ids of the periods this editor loaded: the row's search result, or the answer of its last
   * save. An edit is seeded from them, and a save names them, so the backend deletes only those
   * of them the save leaves out and keeps a period another tab added meanwhile.
   */
  private readonly loadedPeriodIds = computed(() =>
    this.periods()
      .map((p) => p.id)
      .filter((id): id is number => typeof id === 'number'),
  );
  protected readonly draft = signal<readonly DraftInterval[]>([]);
  /** The rows the edit opened with. */
  private readonly seededDraft = signal<readonly DraftInterval[]>([]);
  /**
   * The rows differ in substance from the ones the edit opened with: a row with a time was added
   * or taken out, or a row shows another time. A new row with no time carries nothing (a save
   * drops it), and a time picked again as it was changes nothing, so neither counts.
   */
  protected readonly draftChanged = computed(() => {
    const draft = this.draft().filter(carriesSomething);
    const seeded = this.seededDraft().filter(carriesSomething);
    return draft.length !== seeded.length || draft.some((row, i) => !sameRow(row, seeded[i]));
  });

  /** The zone the entered times are read and written in. */
  protected readonly timeZone = computed(() => this.domain.sessionDomain()?.timeZone);
  /** No zone to resolve a typed clock against — the edit and save actions stay closed. */
  protected readonly zoneMissing = computed(() => !this.timeZone());
  protected readonly noTimeZoneLabel = translateSignal('common.dateTime.noTimeZone');

  /**
   * Per draft row: the error line (its key and the key's parameters) when the row, as it would be
   * saved, does not end after it starts, and `null` otherwise.
   */
  protected readonly rowErrorLines = computed<readonly (RowErrorLine | null)[]>(() => {
    const zone = this.timeZone();
    const date = this.date();
    if (!zone) return this.draft().map(() => null);
    const nextDayStart = startOfNextDay(date, zone).getTime();
    return this.draft().map((row): RowErrorLine | null => {
      if (row.locked) return null;
      const period = resolveRow(date, row, zone);
      if (period === null || endsAfterItStarts(period)) return null;
      // The end still is the stored one, and it was stored before the start: the time field shows
      // only its clock, which may read as a valid end, so the line names the stored end itself.
      const original = row.original;
      if (original?.toDate && !row.toTouched && !endsAfterItStarts(original)) {
        return {
          key: 'schedule.editor.storedEndBeforeStart',
          params: { end: this.formatter.formatInstant(original.toDate) },
        };
      }
      // A stored period that ran past midnight: "enter 00:00" would cut off its next-day part.
      return instantOf(original?.toDate) > nextDayStart
        ? { key: 'schedule.editor.endBeforeStartPastMidnight', params: {} }
        : { key: 'schedule.editor.endBeforeStart', params: {} };
    });
  });
  /** Per draft row: true when the row, as it would be saved, does not end after it starts. */
  protected readonly rowErrors = computed<readonly boolean[]>(() =>
    this.rowErrorLines().map((line) => line !== null),
  );
  protected readonly hasRowErrors = computed(() => this.rowErrors().some(Boolean));
  /** Prefix of the ids the rows' error lines carry; unique per editor instance. */
  protected readonly errorIdPrefix = `firing-period-error-${nextEditorId++}`;

  constructor() {
    // When the row's source object changes (a new search result), exit edit and
    // show the fresh server periods.
    effect(() => {
      const fap = this.firingAreaPeriods();
      untracked(() => {
        this.closeEditor();
        this.periods.set(fap.firingPeriods);
      });
    });
  }

  protected formatPeriod(period: FiringPeriod): string {
    return this.formatter.format(period);
  }

  /** True for a stored period whose end lies at or before its start. */
  protected storedBackwards(period: FiringPeriod): boolean {
    return !endsAfterItStarts(period);
  }

  protected startEdit(): void {
    const zone = this.timeZone();
    if (!zone) return;
    const dayStart = wallClockToInstant(this.date(), 0, 0, 0, zone).getTime();
    const seed = this.periods().map((p): DraftInterval => {
      const row: DraftInterval = {
        from: toWallClock(p.fromDate, zone),
        to: toWallClock(p.toDate, zone),
        original: p,
      };
      return instantOf(p.fromDate) < dayStart ? { ...row, locked: true } : row;
    });
    const rows = seed.length > 0 ? seed : [{ from: null, to: null }];
    this.draft.set(rows);
    this.seededDraft.set(rows);
    this.editing.set(true);
  }

  protected addInterval(): void {
    this.draft.update((d) => [...d, { from: null, to: null }]);
  }

  protected removeInterval(index: number): void {
    // A locked row belongs to an earlier day and is not offered for removal here.
    this.draft.update((d) => d.filter((row, i) => i !== index || row.locked));
  }

  protected clearIntervals(): void {
    this.draft.update((d) => d.filter((row) => row.locked));
  }

  protected setFrom(index: number, value: Date | null): void {
    this.draft.update((d) =>
      d.map((e, i) => (i === index && !e.locked ? { ...e, from: value, fromTouched: true } : e)),
    );
  }

  protected setTo(index: number, value: Date | null): void {
    this.draft.update((d) =>
      d.map((e, i) => (i === index && !e.locked ? { ...e, to: value, toTouched: true } : e)),
    );
  }

  /** Closes the editor; rows that were changed are given up only when the person confirms it. */
  protected async cancel(): Promise<void> {
    if (this.draftChanged() && !(await this.discardConfirm.confirmDiscard())) return;
    this.closeEditor();
  }

  private closeEditor(): void {
    this.editing.set(false);
    this.draft.set([]);
    this.seededDraft.set([]);
  }

  protected save(): void {
    if (this.saving()) return;
    const zone = this.timeZone();
    if (!zone) return;
    if (this.hasRowErrors()) return; // the rows in error are named inline
    const date = this.date();
    const periods: FiringPeriod[] = [];
    for (const interval of this.draft()) {
      const period = resolveRow(date, interval, zone);
      if (period) periods.push(period); // both-empty → dropped
    }
    const payload: FiringAreaPeriods = {
      area: this.firingAreaPeriods().area,
      firingPeriods: periods,
      // Always sent, an empty list included: without it the backend replaces the day and deletes
      // a period another tab added after this one loaded.
      loadedPeriodIds: this.loadedPeriodIds(),
    };
    this.saving.set(true);
    this.api.updateFiringAreaPeriods(payload, date.valueOf()).subscribe({
      next: (saved) => {
        this.periods.set(saved.firingPeriods);
        this.saving.set(false);
        this.closeEditor();
      },
      error: () => {
        // The editor stays open with the rows as typed, so a failed save can be tried again.
        this.notifications.error('schedule.toast.periodsError');
        this.saving.set(false);
      },
    });
  }
}

/**
 * The period a draft row is saved as, or `null` for a new row with both times empty (dropped).
 *
 * A locked row, and an untouched row seeded from a stored period, is the stored period as it was
 * (its id, legacy id and stored instants). A changed stored row keeps its id and legacy id, takes
 * the typed clock on the selected day for each time that was changed, and keeps the stored instant
 * of a time that was not (one stored without an instant is normalised like a new row's); a new
 * row has no id and takes both clocks on the selected day.
 */
function resolveRow(date: Date, row: DraftInterval, timeZone: string): FiringPeriod | null {
  const original = row.original;
  if (original && (row.locked || (!row.fromTouched && !row.toTouched))) return { ...original };
  if (!row.from && !row.to) return null;
  const fromDate =
    original?.fromDate && !row.fromTouched
      ? original.fromDate
      : rebaseOntoDate(date, row.from, 'start', timeZone);
  const toDate =
    original?.toDate && !row.toTouched
      ? original.toDate
      : rebaseOntoDate(date, row.to, 'end', timeZone);
  return original ? { ...original, fromDate, toDate } : { fromDate, toDate };
}

/** False for a new row with neither time, which a save drops. */
function carriesSomething(row: DraftInterval): boolean {
  return !!row.original || !!row.from || !!row.to;
}

/** The same stored period (or none), the same lock and the same two clocks shown. */
function sameRow(a: DraftInterval, b: DraftInterval): boolean {
  return (
    a.original === b.original &&
    !!a.locked === !!b.locked &&
    sameClock(a.from, b.from) &&
    sameClock(a.to, b.to)
  );
}

/** Both empty, or both showing the same hour and minute. */
function sameClock(a: Date | null, b: Date | null): boolean {
  if (!a || !b) return a === b;
  const [x, y] = [wallClockOf(a), wallClockOf(b)];
  return x.hour === y.hour && x.minute === y.minute;
}

/** True unless both ends are known and the end is at or before the start. */
function endsAfterItStarts(period: FiringPeriod): boolean {
  if (!period.fromDate || !period.toDate) return true;
  return instantOf(period.toDate) > instantOf(period.fromDate);
}

/** Epoch milliseconds of a wire instant (NaN when absent, which compares false). */
function instantOf(value: string | undefined): number {
  return value ? new Date(value).getTime() : Number.NaN;
}

/**
 * Rebases a time-of-day onto the selected date, in `timeZone`: an entered time keeps its
 * hour:minute (seconds/ms zeroed); a missing `start` defaults to 00:00:00, a missing `end`
 * to the last second of the day (the next day's 00:00 in the zone, one second back). An entered
 * `end` of 00:00 means "until midnight" and is 00:00 of the next day, resolved in the zone (on a
 * day the zone changes offset that is not 24 hours on). The calendar day is taken from the date
 * the user is looking at and only the clock is resolved in the zone, so the day they chose is the
 * day they get. Returns an ISO datetime.
 *
 * A missing end is not the clock 23:59:59: a zone that changes its clock at 23:00 local
 * (Greenland's, on the EU rule) has no 23:59:59 on its spring day, which would resolve into the
 * next day, and two on its autumn day, the first of which ends the day an hour early.
 */
function rebaseOntoDate(
  date: Date,
  time: Date | null,
  edge: 'start' | 'end',
  timeZone: string,
): string {
  // A time field carries a bare wall clock, so its hours/minutes come off the carrier as they
  // were typed and are reinterpreted as belonging to the zone.
  const clock = time ? wallClockOf(time) : null;
  if (edge === 'end' && (!clock || (clock.hour === 0 && clock.minute === 0))) {
    const nextDayStart = startOfNextDay(date, timeZone);
    return (clock ? nextDayStart : new Date(nextDayStart.getTime() - 1000)).toISOString();
  }
  const [hour, minute] = clock ? [clock.hour, clock.minute] : [0, 0];
  return wallClockToInstant(date, hour, minute, 0, timeZone).toISOString();
}

/** 00:00 of the day after the selected date, in `timeZone`. */
function startOfNextDay(date: Date, timeZone: string): Date {
  return shiftDaysInZone(wallClockToInstant(date, 0, 0, 0, timeZone), 1, timeZone);
}

/**
 * A stored instant as a time field carries it: a browser-local `Date` holding the wall clock
 * the instant reads at in `timeZone`. The field has no zone of its own, so it is handed the
 * clock rather than the instant.
 */
function toWallClock(iso: string | undefined, timeZone: string): Date | null {
  if (!iso) return null;
  const instant = new Date(iso);
  if (Number.isNaN(instant.getTime())) return null;
  return zonedWallClockAsLocalDate(instant, timeZone);
}
