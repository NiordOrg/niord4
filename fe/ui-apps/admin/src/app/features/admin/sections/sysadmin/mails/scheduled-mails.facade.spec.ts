import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { ScheduledMailsFacade } from './scheduled-mails.facade';

const result = (count: number, total: number) => ({
  data: Array.from({ length: count }, (_, i) => ({
    id: i,
    status: 'SENT',
    sender: 's',
    subject: 'x',
    recipients: [],
  })),
  size: count,
  total,
});

/**
 * Drain the effect scheduler + microtask queue so the TTL httpResource resolves (zoneless).
 * The project is zoneless, so we cannot use fakeAsync — flush manually instead.
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      ScheduledMailsFacade,
    ],
  });
  const facade = TestBed.inject(ScheduledMailsFacade);
  const httpTesting = TestBed.inject(HttpTestingController);
  // On construction the facade fires the first search (toObservable) and the mail-queue
  // TTL fetch (httpResource); issue both, then resolve the TTL fetch.
  TestBed.tick();
  httpTesting.expectOne('/rest/settings/setting/mailDeleteAfterDays').flush({ value: 30 });
  await settle();
  return { facade, httpTesting };
}

describe('ScheduledMailsFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('searches page 0 on init and exposes the result', async () => {
    const { facade, httpTesting } = await setup();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=0').flush(result(20, 25));
    expect(facade.result().total).toBe(25);
    expect(facade.ttlDays()).toBe('30');
  });

  it('every param change resets to the first page and refires', async () => {
    const { facade, httpTesting } = await setup();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=0').flush(result(20, 25));

    facade.setFirst(20); // page 1
    TestBed.tick();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=1').flush(result(5, 25));

    facade.setParams({ recipient: 'a' });
    TestBed.tick();
    const req = httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=0&recipient=a');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(result(1, 1));
  });

  it('clamps a stale page to the new last page with exactly ONE corrective refetch', async () => {
    const { facade, httpTesting } = await setup();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=0').flush(result(20, 85));

    facade.setFirst(80); // page 4
    TestBed.tick();
    // The queue shrank to 41 rows → last page is 2; the response clamps page 4 → 2.
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=4').flush(result(0, 41));
    TestBed.tick();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=2').flush(result(1, 41));
    expect(facade.first()).toBe(40);
    // No further request — the corrective response is in range (verify() in afterEach).
  });

  it('a failed search sets the error flag', async () => {
    const { facade, httpTesting } = await setup();
    httpTesting
      .expectOne('/rest/scheduled-mails/search?maxSize=20&page=0')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
  });
});
