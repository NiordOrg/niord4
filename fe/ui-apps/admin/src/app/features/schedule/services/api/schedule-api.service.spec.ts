import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { FiringRunResult } from '../../../../shared/models/firing-run-result.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { FiringAreaPeriods } from '../../models/firing-area-periods.model';
import { ScheduleApiService } from './schedule-api.service';

const API_BASE = '/rest';
const SEARCH = `${API_BASE}/firing-schedules/search-firing-area-periods`;

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  return {
    api: TestBed.inject(ScheduleApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('ScheduleApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it('sends date + lang, omits query when empty, and never sends inactive', () => {
    s.api.searchFiringAreaPeriods({ date: 1000, areaIds: [] }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === SEARCH);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('date')).toBe('1000');
    expect(req.request.params.has('lang')).toBe(true);
    expect(req.request.params.has('query')).toBe(false);
    expect(req.request.params.has('inactive')).toBe(false);
    expect(req.request.params.has('area')).toBe(false);
    req.flush([]);
  });

  it('sends the query param when non-empty and one area param per selected id', () => {
    s.api.searchFiringAreaPeriods({ date: 5, query: 'east', areaIds: [7, 9] }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === SEARCH);
    expect(req.request.params.get('query')).toBe('east');
    expect(req.request.params.getAll('area')).toEqual(['7', '9']);
    req.flush([]);
  });

  it('PUTs one area’s periods with date + lang and the body', () => {
    const fap: FiringAreaPeriods = {
      area: { id: 3 },
      firingPeriods: [{ fromDate: '2026-06-15T08:00:00Z', toDate: '2026-06-15T12:00:00Z' }],
    };
    s.api.updateFiringAreaPeriods(fap, 1234).subscribe();
    const req = s.httpTesting.expectOne(
      (r) => r.url === `${API_BASE}/firing-schedules/firing-area-periods`,
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.params.get('date')).toBe('1234');
    expect(req.request.params.has('lang')).toBe(true);
    expect(req.request.body).toEqual(fap);
    req.flush(fap);
  });

  it('PUTs the ids of the periods the editor loaded under loadedPeriodIds', () => {
    // The backend deletes a period of the day that the body leaves out only when its id is here.
    const fap: FiringAreaPeriods = {
      area: { id: 3 },
      firingPeriods: [{ id: 5, fromDate: '2026-06-15T08:00:00Z', toDate: '2026-06-15T12:00:00Z' }],
      loadedPeriodIds: [5, 6],
    };
    s.api.updateFiringAreaPeriods(fap, 1234).subscribe();
    const req = s.httpTesting.expectOne(
      (r) => r.url === `${API_BASE}/firing-schedules/firing-area-periods`,
    );
    expect(req.request.body.loadedPeriodIds).toEqual([5, 6]);
    expect(req.request.body.firingPeriods).toEqual(fap.firingPeriods);
    req.flush(fap);
  });

  it('PUTs update-firing-exercises and hands on the run’s failure counts', () => {
    let received: FiringRunResult | undefined;
    s.api.updateFiringExercises().subscribe((result) => (received = result));
    const req = s.httpTesting.expectOne(
      (r) => r.url === `${API_BASE}/firing-schedules/update-firing-exercises`,
    );
    expect(req.request.method).toBe('PUT');
    req.flush({ failedSchedules: 1, failedAreas: 2, failedCancellations: 3 });
    expect(received).toEqual({ failedSchedules: 1, failedAreas: 2, failedCancellations: 3 });
  });
});
