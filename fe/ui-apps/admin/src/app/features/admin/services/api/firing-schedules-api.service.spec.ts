import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { FiringRunResult } from '../../../../shared/models/firing-run-result.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { FiringSchedule, FiringSchedulesApiService } from './firing-schedules-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  return {
    service: TestBed.inject(FiringSchedulesApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('FiringSchedulesApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('the LIST fetch carries lang and maps full domain VOs to localized refs', () => {
    const { service, httpTesting } = setup();
    let received: FiringSchedule[] | undefined;
    service.getAll('da').subscribe((schedules) => (received = schedules));
    const req = httpTesting.expectOne('/rest/firing-schedules/all?lang=da');
    expect(req.request.method).toBe('GET');
    req.flush([
      {
        id: 1,
        domain: { domainId: 'niord-da', name: 'Ankerland' },
        targetDomain: { domainId: 'niord-fa', name: 'Bøjeland' },
        targetSeriesId: 'fa-nw',
        scheduleDays: 30,
        active: true,
      },
    ]);
    expect(received).toEqual([
      {
        id: 1,
        domain: { domainId: 'niord-da', name: 'Ankerland' },
        targetDomain: { domainId: 'niord-fa', name: 'Bøjeland' },
        targetSeriesId: 'fa-nw',
        scheduleDays: 30,
        active: true,
      },
    ]);
  });

  it('create POSTs to /firing-schedule/ and flattens both domains to a partial { domainId }', () => {
    const { service, httpTesting } = setup();
    service
      .create({
        domain: { domainId: 'niord-da', name: 'Ankerland' },
        targetDomain: { domainId: 'niord-fa', name: 'Bøjeland' },
        targetSeriesId: 'fa-nw',
        scheduleDays: 14,
        active: false,
      })
      .subscribe();
    const req = httpTesting.expectOne('/rest/firing-schedules/firing-schedule/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      id: undefined,
      domain: { domainId: 'niord-da' },
      targetDomain: { domainId: 'niord-fa' },
      targetSeriesId: 'fa-nw',
      scheduleDays: 14,
      active: false,
    });
    req.flush({ id: 5 });
  });

  it('update PUTs to the path id derived from the SAME model id as the body', () => {
    const { service, httpTesting } = setup();
    service
      .update({
        id: 9,
        domain: { domainId: 'niord-da' },
        targetDomain: { domainId: 'niord-fa' },
        targetSeriesId: 'fa-nw',
        active: true,
      })
      .subscribe();
    const req = httpTesting.expectOne('/rest/firing-schedules/firing-schedule/9');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as { id: number }).id).toBe(9);
    req.flush({ id: 9 });
  });

  it('delete DELETEs by id', () => {
    const { service, httpTesting } = setup();
    service.delete(3).subscribe();
    const req = httpTesting.expectOne('/rest/firing-schedules/firing-schedule/3');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('updateFiringExercises issues a bodyless PUT and hands on the run’s failure counts', () => {
    const { service, httpTesting } = setup();
    let received: FiringRunResult | undefined;
    service.updateFiringExercises().subscribe((result) => (received = result));
    const req = httpTesting.expectOne('/rest/firing-schedules/update-firing-exercises');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBeNull();
    req.flush({ failedSchedules: 0, failedAreas: 1, failedCancellations: 0 });
    expect(received).toEqual({ failedSchedules: 0, failedAreas: 1, failedCancellations: 0 });
  });
});
