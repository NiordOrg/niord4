import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { LanguageService } from '../../core/main-shell/services/language.service';
import { wallClockIn } from '../../shared/testing/zone-testing';
import type { AreaRef } from '../messages/models/messages-filter-params.model';
import type { FiringAreaPeriods } from './models/firing-area-periods.model';
import { ScheduleApiService } from './services/api/schedule-api.service';
import { ScheduleFacade } from './schedule.facade';

// R(10) ─ C(20) ─ G1(31), G2(32)
//        └ D(21) ─ G3(33)   — top-(≤2)-level of G1/G2 is C(20); of G3 is D(21).
const R: AreaRef = { id: 10 };
const C: AreaRef = { id: 20, parent: R };
const D: AreaRef = { id: 21, parent: R };
const G1: AreaRef = { id: 31, parent: C };
const G2: AreaRef = { id: 32, parent: C };
const G3: AreaRef = { id: 33, parent: D };

function fap(area: AreaRef): FiringAreaPeriods {
  return { area, firingPeriods: [] };
}

interface SearchArgs {
  readonly date: number;
  readonly query?: string;
  readonly areaIds?: readonly number[];
  readonly language?: string;
}

function setup(result: readonly FiringAreaPeriods[]) {
  const search = vi.fn(() => of(result));
  TestBed.configureTestingModule({
    providers: [
      ScheduleFacade,
      { provide: ScheduleApiService, useValue: { searchFiringAreaPeriods: search } },
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
    ],
  });
  return { facade: TestBed.inject(ScheduleFacade), search };
}

describe('ScheduleFacade', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  async function flush(): Promise<void> {
    // Let the debounced (250ms) search trigger fire + settle.
    await vi.advanceTimersByTimeAsync(300);
  }

  it('marks the first row of each new top-(≤2)-level area with an areaHeading', async () => {
    const { facade } = setup([fap(G1), fap(G2), fap(G3)]);
    await flush();
    const rows = facade.rows();
    expect(rows.map((r) => r.fap.area.id)).toEqual([31, 32, 33]);
    expect(rows[0].areaHeading?.id).toBe(20); // G1 → heads group C
    expect(rows[1].areaHeading).toBeUndefined(); // G2 → same group, no heading
    expect(rows[2].areaHeading?.id).toBe(21); // G3 → heads group D
  });

  it('exposes the result areas for the map', async () => {
    const { facade } = setup([fap(G1), fap(G3)]);
    await flush();
    expect(facade.firingAreas().map((a) => a.id)).toEqual([31, 33]);
    expect(facade.loading()).toBe(false);
    expect(facade.error()).toBe(false);
  });

  it('searches with the current date / query / area ids', async () => {
    const { facade, search } = setup([]);
    facade.query.set('east');
    facade.selectedAreas.set([C]);
    await flush();
    expect(search).toHaveBeenCalled();
    const calls = search.mock.calls as unknown as readonly (readonly [SearchArgs])[];
    const arg = calls.at(-1)?.[0];
    expect(arg?.query).toBe('east');
    expect(arg?.areaIds).toEqual([20]);
    expect(typeof arg?.date).toBe('number');
  });

  it('setDate normalises the pick to the local-noon day anchor', () => {
    const { facade } = setup([]);
    facade.setDate(new Date(2026, 5, 15, 3, 30)); // 03:30 → noon
    expect(facade.date().getTime()).toBe(new Date(2026, 5, 15, 12, 0, 0, 0).getTime());
  });

  it('the day anchor reads as the picked day in every zone the installation uses', () => {
    const { facade } = setup([]);
    facade.setDate(new Date(2026, 5, 15, 3, 30));
    // The server resolves the schedule day from this instant in the session domain's zone —
    // a firing area has none of its own; noon is the midpoint that survives that reading
    // whichever zone the desk works in.
    for (const zone of ['UTC', 'Europe/Lisbon', 'America/Halifax', 'Europe/Copenhagen']) {
      expect(wallClockIn(zone, facade.date()).slice(0, 10)).toBe('2026-06-15');
    }
  });

  it('offsetDate moves whole days, keeping the anchor across daylight-saving changes', () => {
    const { facade } = setup([]);
    // Days bracketing the European and North American transitions in both directions, so the
    // shift is exercised wherever the runner's own clock happens to jump.
    const days: readonly (readonly [number, number, number])[] = [
      [2026, 2, 7],
      [2026, 2, 8],
      [2026, 2, 28],
      [2026, 2, 29],
      [2026, 9, 24],
      [2026, 9, 25],
      [2026, 10, 1],
      [2026, 5, 15],
    ];
    for (const [year, month, day] of days) {
      facade.setDate(new Date(year, month, day, 9, 0));
      facade.offsetDate(1);
      expect(facade.date().getTime()).toBe(new Date(year, month, day + 1, 12, 0, 0, 0).getTime());
      facade.offsetDate(-1);
      expect(facade.date().getTime()).toBe(new Date(year, month, day, 12, 0, 0, 0).getTime());
    }
  });
});
