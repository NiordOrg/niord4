import { computed, inject, Injectable, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  startWith,
  switchMap,
} from 'rxjs/operators';

import { LanguageService } from '../../core/main-shell/services/language.service';
import { topAreaWithinLevels } from '../../shared/utils/group-by-top-area';
import { latchWhileSettled } from '../../shared/utils/latch-while-settled';
import { noonOfLocalDay } from '../../shared/utils/zoned-date';
import type { AreaRef } from '../messages/models/messages-filter-params.model';
import type { FiringAreaPeriods } from './models/firing-area-periods.model';
import { ScheduleApiService } from './services/api/schedule-api.service';

const SEARCH_DEBOUNCE_MS = 250;
const GROUP_LEVELS = 2;
const MS_PER_DAY = 24 * 60 * 60 * 1000;

interface ScheduleState {
  readonly items: readonly FiringAreaPeriods[];
  readonly loading: boolean;
  readonly error: boolean;
}

const LOADING: ScheduleState = { items: [], loading: true, error: false };

/** A schedule table row, with the area-group heading set on the first row of each top-area. */
export interface ScheduleRow {
  readonly fap: FiringAreaPeriods;
  readonly areaHeading?: AreaRef;
}

/**
 * Page-scoped state for the Firing Area Schedule (provided in the route
 * component). Date / area-name query / selected-area filter drive a debounced
 * search; the result is grouped by top-(≤2)-level area (the shared helper) and
 * projected to the table rows + the map's area list.
 */
@Injectable()
export class ScheduleFacade {
  private readonly api = inject(ScheduleApiService);
  private readonly language = inject(LanguageService);

  /**
   * The selected schedule day, held at browser-local noon. It travels to the server as an
   * instant, and the day is resolved there in the SESSION domain's zone — the same zone the
   * firing periods themselves are written in; a firing area carries no zone of its own. The
   * anchor has to survive that reading: noon lands on the intended day for any offset up to
   * ±12 hours, which covers every zone in use. See {@link noonOfLocalDay}.
   */
  readonly date = signal<Date>(noonOfLocalDay(new Date()));
  readonly query = signal('');
  readonly selectedAreas = signal<readonly AreaRef[]>([]);
  /** Bumped by `retry()` so a failed search can re-run with unchanged filters. */
  private readonly retryTick = signal(0);

  private readonly trigger = computed(() => ({
    date: this.date().valueOf(),
    query: this.query().trim(),
    areaIds: this.selectedAreas().map((a) => a.id),
    lang: this.language.selectedLanguage(),
    tick: this.retryTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      debounceTime(SEARCH_DEBOUNCE_MS),
      distinctUntilChanged(
        (a, b) =>
          a.date === b.date &&
          a.query === b.query &&
          a.lang === b.lang &&
          a.tick === b.tick &&
          a.areaIds.length === b.areaIds.length &&
          a.areaIds.every((id, i) => id === b.areaIds[i]),
      ),
      switchMap((t) =>
        this.api
          .searchFiringAreaPeriods({
            date: t.date,
            query: t.query || undefined,
            areaIds: t.areaIds,
            language: t.lang,
          })
          .pipe(
            map((items): ScheduleState => ({ items, loading: false, error: false })),
            catchError(() => of<ScheduleState>({ items: [], loading: false, error: true })),
            startWith(LOADING),
          ),
      ),
    ),
    { initialValue: LOADING },
  );

  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);
  private readonly items = computed(() => this.state().items);

  /** The firing areas of the live result. */
  private readonly computedFiringAreas = computed<readonly AreaRef[]>(() =>
    this.items().map((f) => f.area),
  );

  /** The live table rows, each marked with an `areaHeading` when its top-area changes. */
  private readonly computedRows = computed<readonly ScheduleRow[]>(() => {
    const out: ScheduleRow[] = [];
    let lastTopId: number | undefined;
    for (const fap of this.items()) {
      const top = topAreaWithinLevels(fap.area, GROUP_LEVELS);
      if (top && top.id !== lastTopId) {
        lastTopId = top.id;
        out.push({ fap, areaHeading: top });
      } else {
        out.push({ fap });
      }
    }
    return out;
  });

  // A search is "settled" once it is no longer pending (resolved OR errored). The latch keeps the
  // previous value only WHILE loading — on error it adopts the live (empty) result so the map's
  // firing-area overlay clears rather than showing stale areas under the error banner.
  private readonly settled = computed(() => !this.loading());

  // Latch the last successfully-resolved rows/areas so the table + map update in place on a
  // filter/area/date change instead of flashing empty during the debounced refetch (the search
  // stream emits `items: []` while loading). Shared `latchWhileSettled` — pull-based, no effect/tick.

  /** The table rows shown to the user — latched across refetches. */
  readonly rows = latchWhileSettled(this.settled, this.computedRows);

  /** The firing areas feeding the map's overlay — latched across refetches (no flicker). */
  readonly firingAreas = latchWhileSettled(this.settled, this.computedFiringAreas);

  /** Shift the selected date by whole days (prev/next-day arrows), keeping local noon. */
  offsetDate(days: number): void {
    // A day of milliseconds off a noon anchor lands within an hour of noon even across a DST
    // transition, and re-anchoring absorbs that hour — so the day number moves by exactly `days`.
    this.date.update((d) => noonOfLocalDay(new Date(d.getTime() + days * MS_PER_DAY)));
  }

  /** Set the selected date from the picker, normalised to local noon. */
  setDate(date: Date): void {
    this.date.set(noonOfLocalDay(date));
  }

  /** Re-run the search after a failure (filters unchanged). */
  retry(): void {
    this.retryTick.update((n) => n + 1);
  }
}
