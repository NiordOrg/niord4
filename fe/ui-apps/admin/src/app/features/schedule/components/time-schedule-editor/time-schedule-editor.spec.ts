import { WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { provideTestSessionZone, wallClockIn } from '../../../../shared/testing/zone-testing';
import type { FiringAreaPeriods, FiringPeriod } from '../../models/firing-area-periods.model';
import { ScheduleApiService } from '../../services/api/schedule-api.service';
import { FiringPeriodFormatter } from '../../services/firing-period-formatter';
import { TimeScheduleEditor } from './time-schedule-editor';

interface Internals {
  editing: WritableSignal<boolean>;
  periods: WritableSignal<readonly FiringPeriod[]>;
  draft: WritableSignal<
    {
      from: Date | null;
      to: Date | null;
      original?: FiringPeriod;
      fromTouched?: boolean;
      toTouched?: boolean;
      locked?: boolean;
    }[]
  >;
  rowErrors(): readonly boolean[];
  startEdit(): void;
  addInterval(): void;
  setFrom(i: number, v: Date | null): void;
  setTo(i: number, v: Date | null): void;
  clearIntervals(): void;
  cancel(): void;
  save(): void;
}

// Two zones that differ from any plausible runner, on both sides of it: Lisbon is one hour
// behind Copenhagen, Halifax several hours behind everything European.
const LISBON = 'Europe/Lisbon';
const HALIFAX = 'America/Halifax';

const SELECTED_DATE = new Date(2026, 5, 15, 12, 0, 0, 0); // the day anchor for 15 June 2026
const AREA = { id: 7 };

// `null` is the "no session domain" case — an explicit `undefined` would fall back to the default.
function setup(
  initial: readonly FiringPeriod[] = [],
  timeZone: string | null = LISBON,
  date: Date = SELECTED_DATE,
) {
  const update = vi.fn((fap: FiringAreaPeriods) => of(fap));
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideTestSessionZone(timeZone ?? undefined),
      { provide: ScheduleApiService, useValue: { updateFiringAreaPeriods: update } },
      { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: FiringPeriodFormatter,
        useValue: { format: () => '', formatInstant: (iso: string) => `<${iso}>` },
      },
      {
        provide: LocaleFormatService,
        useValue: { uses12HourClock: () => false, formatTime: () => '' },
      },
    ],
  });
  // Real text for the one key whose parameter the assertions read (the stub loader ships none).
  TestBed.inject(TranslocoService).setTranslation(
    { 'schedule.editor.storedEndBeforeStart': 'Saved ending {{end}}.' },
    'en',
    { merge: true },
  );
  const fixture = TestBed.createComponent(TimeScheduleEditor);
  const fap: FiringAreaPeriods = { area: AREA, firingPeriods: initial };
  fixture.componentRef.setInput('firingAreaPeriods', fap);
  fixture.componentRef.setInput('date', date);
  fixture.detectChanges();
  const comp = fixture.componentInstance as unknown as Internals;
  return { fixture, comp, update };
}

/** Last PUT payload's firing periods. */
function savedPeriods(update: ReturnType<typeof vi.fn>): readonly FiringPeriod[] {
  const arg = update.mock.calls.at(-1)?.[0] as FiringAreaPeriods;
  return arg.firingPeriods;
}

/** Last PUT payload as a whole. */
function savedBody(update: ReturnType<typeof vi.fn>): FiringAreaPeriods {
  return update.mock.calls.at(-1)?.[0] as FiringAreaPeriods;
}

/** A time entered in the field: only its hours and minutes are read. */
function entered(hour: number, minute: number): Date {
  return new Date(2026, 0, 1, hour, minute, 0, 0);
}

/**
 * The wall clock a draft row carries. A draft value is a browser-local Date holding the domain's
 * clock — the time field has no zone of its own — so reading it locally reads what the field shows.
 */
function shownClock(value: Date | null): string {
  if (!value) return '';
  return `${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}`;
}

/** The stored instant read as a wall clock in `timeZone`, e.g. `2026-06-15 08:00:00`. */
function clockIn(timeZone: string, iso: string | undefined): string {
  return wallClockIn(timeZone, new Date(iso ?? ''));
}

describe('TimeScheduleEditor', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('startEdit seeds one blank interval when there are no periods', () => {
    const { comp } = setup([]);
    comp.startEdit();
    expect(comp.editing()).toBe(true);
    expect(comp.draft()).toEqual([{ from: null, to: null }]);
  });

  it('startEdit seeds the stored instants as the domain zone wall clock', () => {
    const from = '2026-06-15T06:00:00.000Z';
    const to = '2026-06-15T10:00:00.000Z';
    const { comp } = setup([{ fromDate: from, toDate: to }]);
    comp.startEdit();
    // 07:00–11:00 in Lisbon, an hour off the Copenhagen reading of the same instants.
    expect(shownClock(comp.draft()[0].from)).toBe(clockIn(LISBON, from).slice(11, 16));
    expect(shownClock(comp.draft()[0].to)).toBe(clockIn(LISBON, to).slice(11, 16));
  });

  /**
   * The seeded clock rides a fixed carrier day, not the day the instant falls on.
   *
   * A time field reads only hours and minutes, so the date part is free — and it has to be a
   * day no zone ever skips. 02:30 on 29 March 2026 does not exist in the runner's own clock,
   * so a carrier built from the domain's calendar day would roll an hour forward and show a
   * time nobody entered.
   */
  it('seeds the clock onto a carrier day with no daylight-saving gap', () => {
    // 01:30Z is 02:30 in Lisbon, which has already moved to summer time by then.
    const { comp } = setup([{ fromDate: '2026-03-29T01:30:00.000Z' }]);
    comp.startEdit();
    const seeded = comp.draft()[0].from!;
    expect(shownClock(seeded)).toBe('02:30');
    expect(seeded.getTime()).toBe(new Date(2000, 0, 1, 2, 30, 0, 0).getTime());
  });

  it('save stores the entered times as the domain zone wall clock', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(8, 0));
    comp.setTo(0, entered(12, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(periods).toHaveLength(1);
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-06-15 08:00:00');
    expect(clockIn(LISBON, periods[0].toDate)).toBe('2026-06-15 12:00:00');
    // Not the clock of whoever is entering it: the browser's own 08:00 is a different instant.
    expect(periods[0].fromDate).not.toBe(new Date(2026, 5, 15, 8, 0, 0, 0).toISOString());
  });

  it('an untouched period is saved with its id and its stored instants', () => {
    const from = '2026-06-15T06:00:00.000Z';
    const to = '2026-06-15T10:00:00.000Z';
    const { comp, update } = setup([{ id: 5, fromDate: from, toDate: to }]);
    comp.startEdit();
    comp.save();
    expect(savedPeriods(update)).toEqual([{ id: 5, fromDate: from, toDate: to }]);
  });

  it('a changed row keeps its id', () => {
    // 07:00Z is 08:00 in Lisbon in June.
    const { comp, update } = setup([
      { id: 5, fromDate: '2026-06-15T07:00:00.000Z', toDate: '2026-06-15T09:00:00.000Z' },
    ]);
    comp.startEdit();
    comp.setTo(0, entered(11, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(periods).toHaveLength(1);
    expect(periods[0].id).toBe(5);
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-06-15 08:00:00');
    expect(clockIn(LISBON, periods[0].toDate)).toBe('2026-06-15 11:00:00');
  });

  it('the legacy id is sent back', () => {
    const { comp, update } = setup([
      {
        id: 3,
        legacyId: 'legacy-3',
        areaId: AREA.id,
        fromDate: '2026-06-15T07:00:00.000Z',
        toDate: '2026-06-15T09:00:00.000Z',
      },
    ]);
    comp.startEdit();
    comp.setFrom(0, entered(7, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(periods[0].id).toBe(3);
    expect(periods[0].legacyId).toBe('legacy-3');
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-06-15 07:00:00');
  });

  it('an end of 00:00 is stored as midnight at the end of the selected day', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(9, 0));
    comp.setTo(0, entered(0, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-06-15 09:00:00');
    expect(clockIn(LISBON, periods[0].toDate)).toBe('2026-06-16 00:00:00');
  });

  it('an end of 00:00 on the autumn change day is the next day in the zone, not 24 hours on', () => {
    // 25 October 2026: Lisbon falls back from UTC+1 to UTC+0 at 02:00, so the day lasts 25 hours.
    const changeDay = new Date(2026, 9, 25, 12, 0, 0, 0);
    const { comp, update } = setup([], LISBON, changeDay);
    comp.startEdit();
    comp.setFrom(0, entered(9, 0));
    comp.setTo(0, entered(0, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(periods[0].fromDate).toBe('2026-10-25T09:00:00.000Z');
    expect(periods[0].toDate).toBe('2026-10-26T00:00:00.000Z');
  });

  it('an end before its start holds Save back and names the row', () => {
    const { fixture, comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(13, 0));
    comp.setTo(0, entered(12, 0));
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent?.trim()).toBe('schedule.editor.endBeforeStart');
    const saveButton = [...fixture.nativeElement.querySelectorAll('button')].find((b) =>
      (b as HTMLButtonElement).textContent?.includes('schedule.editor.save'),
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);

    comp.save();
    expect(update).not.toHaveBeenCalled();
    expect(comp.editing()).toBe(true);
  });

  it('an end equal to its start is refused', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(10, 0));
    comp.setTo(0, entered(10, 0));
    comp.save();
    expect(update).not.toHaveBeenCalled();
  });

  it('a stored period that ends before it starts is flagged when the editor opens, and saved with its id once corrected', () => {
    // Stored by the old editor as "09:00 - 00:00": 09:00 to 00:00 of the same day in Lisbon.
    const stored = {
      id: 9,
      fromDate: '2026-06-15T08:00:00.000Z',
      toDate: '2026-06-14T23:00:00.000Z',
    };
    const { fixture, comp, update } = setup([stored]);
    comp.startEdit();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    comp.save();
    expect(update).not.toHaveBeenCalled();

    comp.setTo(0, entered(0, 0));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    comp.save();

    expect(savedPeriods(update)).toEqual([
      { id: 9, fromDate: '2026-06-15T08:00:00.000Z', toDate: '2026-06-15T23:00:00.000Z' },
    ]);
  });

  /**
   * Stored as 08:00 to 23:00 of the day before: the time fields show 08:00 and 23:00, which read
   * as a valid period, so the line names the stored end and asks for the end time again rather
   * than giving the advice about 00:00.
   */
  it('a stored period whose end lies on an earlier day names its stored end, and picking the end again corrects it', () => {
    const stored = {
      id: 21,
      fromDate: '2026-06-15T07:00:00.000Z',
      toDate: '2026-06-14T22:00:00.000Z',
    };
    const { fixture, comp, update } = setup([stored]);
    comp.startEdit();
    fixture.detectChanges();

    expect(shownClock(comp.draft()[0].from)).toBe('08:00');
    expect(shownClock(comp.draft()[0].to)).toBe('23:00');
    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent?.trim()).toBe('Saved ending <2026-06-14T22:00:00.000Z>.');
    const saveButton = [...fixture.nativeElement.querySelectorAll('button')].find((b) =>
      (b as HTMLButtonElement).textContent?.includes('schedule.editor.save'),
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);
    comp.save();
    expect(update).not.toHaveBeenCalled();

    comp.setTo(0, entered(23, 0));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    comp.save();
    expect(savedPeriods(update)).toEqual([
      { id: 21, fromDate: '2026-06-15T07:00:00.000Z', toDate: '2026-06-15T22:00:00.000Z' },
    ]);
  });

  it('a stored backward period keeps its own line while only its start is changed', () => {
    const { fixture, comp } = setup([
      { id: 21, fromDate: '2026-06-15T07:00:00.000Z', toDate: '2026-06-14T22:00:00.000Z' },
    ]);
    comp.startEdit();
    comp.setFrom(0, entered(9, 0));
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent?.trim()).toBe('Saved ending <2026-06-14T22:00:00.000Z>.');
  });

  it('an end typed before the start of a stored backward period gets the plain line', () => {
    const { fixture, comp, update } = setup([
      { id: 21, fromDate: '2026-06-15T07:00:00.000Z', toDate: '2026-06-14T22:00:00.000Z' },
    ]);
    comp.startEdit();
    comp.setTo(0, entered(7, 0));
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent?.trim()).toBe('schedule.editor.endBeforeStart');
    comp.save();
    expect(update).not.toHaveBeenCalled();
  });

  it('lists a stored backward period with a warning mark that says what to do, and a valid one without', () => {
    const { fixture } = setup([
      { id: 21, fromDate: '2026-06-15T07:00:00.000Z', toDate: '2026-06-14T22:00:00.000Z' },
      { id: 22, fromDate: '2026-06-15T10:00:00.000Z', toDate: '2026-06-15T12:00:00.000Z' },
    ]);
    fixture.detectChanges();

    const marks = fixture.nativeElement.querySelectorAll(
      'app-icon[role="img"]',
    ) as NodeListOf<HTMLElement>;
    expect(marks).toHaveLength(1);
    expect(marks[0].getAttribute('aria-label')).toBe('schedule.editor.storedBackwards');
    expect(marks[0].getAttribute('title')).toBe('schedule.editor.storedBackwards');
  });

  it('a period across midnight from the day before is shown locked, sent unchanged with its id, and Clear keeps it', () => {
    // Lisbon is on UTC+0 in November: 15 Nov 22:00 to 16 Nov 02:00, opened on 16 Nov.
    const crossing: FiringPeriod = {
      id: 11,
      legacyId: 'legacy-11',
      areaId: AREA.id,
      fromDate: '2026-11-15T22:00:00.000Z',
      toDate: '2026-11-16T02:00:00.000Z',
    };
    const { fixture, comp, update } = setup(
      [crossing],
      LISBON,
      new Date(2026, 10, 16, 12, 0, 0, 0),
    );
    comp.startEdit();
    expect(comp.draft()[0].locked).toBe(true);

    comp.clearIntervals();
    expect(comp.draft()).toHaveLength(1);
    expect(comp.draft()[0].locked).toBe(true);

    comp.addInterval();
    comp.setFrom(1, entered(9, 0));
    comp.setTo(1, entered(10, 0));
    fixture.detectChanges();

    // The locked row has no time fields and no remove button: only the new row has them.
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('app-time-field')).toHaveLength(2);
    expect(host.querySelectorAll('button[aria-label="common.remove"]')).toHaveLength(1);
    expect(host.textContent).toContain('schedule.editor.otherDay');

    comp.save();
    const periods = savedPeriods(update);
    expect(periods).toHaveLength(2);
    expect(periods[0]).toEqual(crossing);
    expect(periods[1].id).toBeUndefined();
    expect(clockIn(LISBON, periods[1].fromDate)).toBe('2026-11-16 09:00:00');
    expect(clockIn(LISBON, periods[1].toDate)).toBe('2026-11-16 10:00:00');
  });

  /**
   * An older period stored with its end before its start, 22:00 to 02:00 of 15 Nov, is listed on
   * 16 Nov too, for its early hours. There it starts on an earlier day: locked, never flagged, and
   * sent back exactly as stored, so the later day's save neither holds Save back nor changes it.
   */
  it('a period of the day before stored with its end before its start is locked, holds no Save back and is sent back unchanged', () => {
    const backward: FiringPeriod = {
      id: 13,
      legacyId: 'legacy-13',
      areaId: AREA.id,
      fromDate: '2026-11-15T22:00:00.000Z',
      toDate: '2026-11-15T02:00:00.000Z',
    };
    const { fixture, comp, update } = setup(
      [backward],
      LISBON,
      new Date(2026, 10, 16, 12, 0, 0, 0),
    );
    comp.startEdit();
    fixture.detectChanges();

    expect(comp.draft()[0].locked).toBe(true);
    expect(comp.rowErrors()).toEqual([false]);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelectorAll('app-time-field')).toHaveLength(0);
    expect(host.textContent).toContain('schedule.editor.otherDay');
    const saveButton = [...host.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('schedule.editor.save'),
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);

    comp.save();
    const body = savedBody(update);
    expect(body.firingPeriods).toEqual([backward]);
    expect(body.loadedPeriodIds).toEqual([13]);
  });

  it('a period across midnight into the next day is editable on its start day and saved unchanged when untouched', () => {
    const crossing: FiringPeriod = {
      id: 12,
      fromDate: '2026-11-15T22:00:00.000Z',
      toDate: '2026-11-16T02:00:00.000Z',
    };
    const { fixture, comp, update } = setup(
      [crossing],
      LISBON,
      new Date(2026, 10, 15, 12, 0, 0, 0),
    );
    comp.startEdit();
    fixture.detectChanges();

    expect(comp.draft()[0].locked).toBeUndefined();
    expect(fixture.nativeElement.querySelectorAll('app-time-field')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();

    comp.save();
    expect(savedPeriods(update)).toEqual([crossing]);
  });

  /**
   * The end field was never changed, so it still means the instant it was seeded from — 02:00 on
   * the next day — and not 02:00 on the selected day, which would put the end before the start.
   */
  it('a period across midnight keeps its end on the next day when only its start is changed', () => {
    const crossing: FiringPeriod = {
      id: 12,
      fromDate: '2026-11-15T22:00:00.000Z',
      toDate: '2026-11-16T02:00:00.000Z',
    };
    const { fixture, comp, update } = setup(
      [crossing],
      LISBON,
      new Date(2026, 10, 15, 12, 0, 0, 0),
    );
    comp.startEdit();
    comp.setFrom(0, entered(21, 0));
    fixture.detectChanges();

    expect(comp.rowErrors()[0]).toBe(false);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();

    comp.save();
    const periods = savedPeriods(update);
    expect(periods).toHaveLength(1);
    expect(periods[0].id).toBe(12);
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-11-15 21:00:00');
    expect(periods[0].toDate).toBe('2026-11-16T02:00:00.000Z');
  });

  /**
   * A changed end is read on the selected day, as every typed clock is. On a period that ran past
   * midnight the usual advice — end it at 00:00 — would cut the part after midnight off, so the
   * row says how to keep it instead.
   */
  it('a period across midnight whose end is changed to an earlier clock is flagged with the split advice', () => {
    const crossing: FiringPeriod = {
      id: 12,
      fromDate: '2026-11-15T22:00:00.000Z',
      toDate: '2026-11-16T02:00:00.000Z',
    };
    const { fixture, comp, update } = setup(
      [crossing],
      LISBON,
      new Date(2026, 10, 15, 12, 0, 0, 0),
    );
    comp.startEdit();
    comp.setTo(0, entered(1, 0));
    fixture.detectChanges();

    expect(comp.rowErrors()[0]).toBe(true);
    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement | null;
    expect(alert?.textContent?.trim()).toBe('schedule.editor.endBeforeStartPastMidnight');
    comp.save();
    expect(update).not.toHaveBeenCalled();
  });

  it('normalises a missing from→00:00 and a missing to→23:59:59 in the domain zone', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(8, 30));
    comp.addInterval();
    comp.setTo(1, entered(9, 15));
    comp.save();

    const periods = savedPeriods(update);
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-06-15 08:30:00');
    expect(clockIn(LISBON, periods[0].toDate)).toBe('2026-06-15 23:59:59');
    expect(clockIn(LISBON, periods[1].fromDate)).toBe('2026-06-15 00:00:00');
    expect(clockIn(LISBON, periods[1].toDate)).toBe('2026-06-15 09:15:00');
  });

  it('keeps the selected day for a zone west of the browser, where the day ends after midnight UTC', () => {
    const { comp, update } = setup([], HALIFAX);
    comp.startEdit();
    comp.setFrom(0, entered(8, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(clockIn(HALIFAX, periods[0].fromDate)).toBe('2026-06-15 08:00:00');
    expect(clockIn(HALIFAX, periods[0].toDate)).toBe('2026-06-15 23:59:59');
    // The last second of the Halifax day has already turned over in UTC.
    expect(periods[0].toDate?.slice(0, 10)).toBe('2026-06-16');
  });

  it('resolves both ends on the day the domain zone changes offset', () => {
    // 29 March 2026 — Lisbon moves to summer time at 01:00, so midnight and 08:00 on that
    // day sit on opposite sides of the transition and carry different offsets.
    const dstDay = new Date(2026, 2, 29, 12, 0, 0, 0);
    const { comp, update } = setup([], LISBON, dstDay);
    comp.startEdit();
    comp.setTo(0, entered(8, 0));
    comp.save();

    const periods = savedPeriods(update);
    expect(clockIn(LISBON, periods[0].fromDate)).toBe('2026-03-29 00:00:00');
    expect(clockIn(LISBON, periods[0].toDate)).toBe('2026-03-29 08:00:00');
  });

  it('drops fully-empty intervals on save', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.addInterval(); // now two blanks
    comp.setFrom(1, entered(9, 0));
    comp.save();
    expect(savedPeriods(update)).toHaveLength(1); // the all-empty first row dropped
  });

  it('save updates the shown periods from the response and exits edit', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(6, 0));
    comp.save();
    expect(update).toHaveBeenCalledTimes(1);
    expect(comp.editing()).toBe(false);
    expect(comp.periods().length).toBe(1);
  });

  it('cancel discards the draft without saving', () => {
    const { comp, update } = setup([
      { fromDate: '2026-06-15T08:00:00Z', toDate: '2026-06-15T10:00:00Z' },
    ]);
    comp.startEdit();
    comp.setFrom(0, entered(5, 0));
    comp.cancel();
    expect(comp.editing()).toBe(false);
    expect(comp.draft()).toEqual([]);
    expect(update).not.toHaveBeenCalled();
  });

  it('refuses to edit or save with no session domain zone', () => {
    const { fixture, comp, update } = setup(
      [{ fromDate: '2026-06-15T08:00:00Z', toDate: '2026-06-15T10:00:00Z' }],
      null,
    );
    comp.startEdit();
    expect(comp.editing()).toBe(false);

    const edit = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(edit.disabled).toBe(true);
    expect(edit.getAttribute('title')).toBe('common.dateTime.noTimeZone');

    // Even reached directly, a save writes nothing rather than guessing a zone.
    comp.draft.set([{ from: entered(8, 0), to: entered(12, 0) }]);
    comp.save();
    expect(update).not.toHaveBeenCalled();
  });
});

/**
 * A day's save names the periods this editor loaded (FIRING8). The backend deletes a stored period
 * of the day that the body leaves out only when its id is among them, so a period another tab
 * added after this one loaded the day is kept; a body without the ids would replace the day.
 */
describe('TimeScheduleEditor (the periods it loaded)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const MORNING: FiringPeriod = {
    id: 5,
    fromDate: '2026-06-15T07:00:00.000Z',
    toDate: '2026-06-15T09:00:00.000Z',
  };
  const AFTERNOON: FiringPeriod = {
    id: 6,
    fromDate: '2026-06-15T13:00:00.000Z',
    toDate: '2026-06-15T15:00:00.000Z',
  };

  it('a save sends the ids it loaded, also of a period it removed', () => {
    const { comp, update } = setup([MORNING, AFTERNOON]);
    comp.startEdit();
    comp.draft.update((d) => d.filter((row) => row.original?.id !== AFTERNOON.id));
    comp.save();

    const body = savedBody(update);
    expect(body.firingPeriods).toEqual([MORNING]);
    expect(body.loadedPeriodIds).toEqual([5, 6]);
  });

  it('Clear sends every loaded id, so the backend deletes exactly those', () => {
    const { comp, update } = setup([MORNING, AFTERNOON]);
    comp.startEdit();
    comp.clearIntervals();
    comp.save();

    const body = savedBody(update);
    expect(body.firingPeriods).toEqual([]);
    expect(body.loadedPeriodIds).toEqual([5, 6]);
  });

  /**
   * A day that had no periods when it was opened: the body says so with an empty list, so a
   * period another tab added meanwhile is not taken for one this tab removed.
   */
  it('a day opened without periods sends an empty list of loaded ids', () => {
    const { comp, update } = setup([]);
    comp.startEdit();
    comp.setFrom(0, entered(8, 0));
    comp.setTo(0, entered(10, 0));
    comp.save();

    expect(savedBody(update).loadedPeriodIds).toEqual([]);
  });

  it('a period of the day before, shown locked, is among the loaded ids', () => {
    const crossing: FiringPeriod = {
      id: 11,
      fromDate: '2026-11-15T22:00:00.000Z',
      toDate: '2026-11-16T02:00:00.000Z',
    };
    const { comp, update } = setup([crossing], LISBON, new Date(2026, 10, 16, 12, 0, 0, 0));
    comp.startEdit();
    comp.save();

    const body = savedBody(update);
    expect(body.firingPeriods).toEqual([crossing]);
    expect(body.loadedPeriodIds).toEqual([11]);
  });

  /**
   * The answer of a save is the day as stored then, with the period another tab added and the new
   * period's id: the next save from this editor names those, so removing one of them deletes it.
   */
  it('after a save, the next save sends the ids the save answered with', () => {
    const { comp, update } = setup([MORNING]);
    const stored: FiringAreaPeriods = {
      area: AREA,
      firingPeriods: [MORNING, AFTERNOON, { ...MORNING, id: 8 }],
    };
    update.mockReturnValueOnce(of(stored));
    comp.startEdit();
    comp.addInterval();
    comp.setFrom(1, entered(17, 0));
    comp.setTo(1, entered(18, 0));
    comp.save();
    expect(savedBody(update).loadedPeriodIds).toEqual([5]);

    comp.startEdit();
    comp.save();
    expect(savedBody(update).loadedPeriodIds).toEqual([5, 6, 8]);
  });

  it('a new search result is what the next save names as loaded', () => {
    const { fixture, comp, update } = setup([MORNING]);
    fixture.componentRef.setInput('firingAreaPeriods', {
      area: AREA,
      firingPeriods: [AFTERNOON],
    } satisfies FiringAreaPeriods);
    fixture.detectChanges();
    comp.startEdit();
    comp.save();

    expect(savedBody(update).loadedPeriodIds).toEqual([6]);
  });
});
