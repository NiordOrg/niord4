import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { BatchStatusService } from './batch-status.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      BatchStatusService,
    ],
  });
  return {
    service: TestBed.inject(BatchStatusService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('BatchStatusService', () => {
  afterEach(() => vi.useRealTimers());

  it('loads immediately on start and re-polls every 10 seconds', () => {
    vi.useFakeTimers();
    const { service, httpTesting } = setup();
    service.start();
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 3 });
    expect(service.runningExecutions()).toBe(3);

    vi.advanceTimersByTime(10_000);
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 1 });
    expect(service.runningExecutions()).toBe(1);
    httpTesting.verify();
  });

  it('a poll failure keeps the last known value (badge never shows errors)', () => {
    vi.useFakeTimers();
    const { service, httpTesting } = setup();
    service.start();
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 5 });

    vi.advanceTimersByTime(10_000);
    httpTesting
      .expectOne('/rest/batch/status')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(service.runningExecutions()).toBe(5);
    httpTesting.verify();
  });

  it('pauses the poll while the tab is hidden and resumes with an immediate load', () => {
    vi.useFakeTimers();
    const { service, httpTesting } = setup();
    service.start();
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 1 });

    // Tab goes hidden → the interval stops; no background requests.
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(30_000);
    httpTesting.expectNone('/rest/batch/status');

    // Tab returns → immediate load + cadence resumes.
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 4 });
    expect(service.runningExecutions()).toBe(4);
    vi.advanceTimersByTime(10_000);
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 4 });
    httpTesting.verify();
  });

  it('start() is idempotent and destroy stops the timer', () => {
    vi.useFakeTimers();
    const { service, httpTesting } = setup();
    service.start();
    service.start(); // second call must not double-poll
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 0 });
    httpTesting.verify();

    TestBed.resetTestingModule(); // destroys the injector → DestroyRef clears the timer
    vi.advanceTimersByTime(30_000);
    // No pending timer left to fire a request against the torn-down backend.
    expect(vi.getTimerCount()).toBe(0);
  });
});
