import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { BatchFacade } from './batch.facade';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      BatchFacade,
    ],
  });
  const httpTesting = TestBed.inject(HttpTestingController);
  // The facade polls immediately on construction.
  const facade = TestBed.inject(BatchFacade);
  return { facade, httpTesting };
}

const STATUS = {
  runningExecutions: 1,
  types: [{ name: 'charts-import', instanceCount: 7, runningExecutions: 1 }],
};

/** Flush the constructor's immediate status poll. */
function flushInitialStatus(httpTesting: HttpTestingController, status: object = STATUS): void {
  httpTesting.expectOne('/rest/batch/status').flush(status);
}

describe('BatchFacade', () => {
  afterEach(() => {
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    vi.useRealTimers();
  });

  it('polls status immediately on construction and exposes the type list', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup();
    flushInitialStatus(httpTesting);
    expect(facade.types()).toEqual(STATUS.types);
    httpTesting.verify();
  });

  it('re-polls status every 3 seconds (foreground cadence)', () => {
    vi.useFakeTimers();
    const { httpTesting } = setup();
    flushInitialStatus(httpTesting);
    vi.advanceTimersByTime(3000);
    flushInitialStatus(httpTesting, { runningExecutions: 0, types: [] });
    httpTesting.verify();
  });

  it('a failed status poll keeps the last type list (silent keep-last)', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup();
    flushInitialStatus(httpTesting);
    vi.advanceTimersByTime(3000);
    httpTesting
      .expectOne('/rest/batch/status')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.types()).toEqual(STATUS.types);
    httpTesting.verify();
  });

  it('pauses the status poll while hidden and resumes with an immediate load', () => {
    vi.useFakeTimers();
    const { httpTesting } = setup();
    flushInitialStatus(httpTesting);

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(9000);
    httpTesting.expectNone('/rest/batch/status');

    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    flushInitialStatus(httpTesting);
    httpTesting.verify();
  });

  it('destroy stops the poll timer', () => {
    vi.useFakeTimers();
    const { httpTesting } = setup();
    flushInitialStatus(httpTesting);
    TestBed.resetTestingModule();
    vi.advanceTimersByTime(9000);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('selecting a type fetches its instances 0-based and flattens into discriminated rows', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup();
    flushInitialStatus(httpTesting);

    facade.selectType('charts-import');
    TestBed.tick(); // flush the toObservable trigger
    const req = httpTesting.expectOne((r) => r.url === '/rest/batch/charts-import/instances');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('pageSize')).toBe('5');
    req.flush({
      data: [
        {
          instanceId: 1,
          jobNo: 9,
          executions: [
            { executionId: 10, batchStatus: 'COMPLETED' },
            { executionId: 11, batchStatus: 'FAILED' },
          ],
        },
      ],
      total: 7,
      size: 5,
    });

    const rows = facade.rows();
    expect(rows.map((r) => r.kind)).toEqual(['instance-header', 'execution', 'execution']);
    expect(facade.total()).toBe(7);
    httpTesting.verify();
  });

  it('switching type resets the page to 0 and clears the rows immediately', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup();
    flushInitialStatus(httpTesting);

    facade.selectType('charts-import');
    TestBed.tick();
    httpTesting
      .expectOne((r) => r.url === '/rest/batch/charts-import/instances')
      .flush({ data: [{ instanceId: 1, executions: [] }], total: 12, size: 5 });

    // Page to the 3rd page.
    facade.setFirst(10);
    TestBed.tick();
    let req = httpTesting.expectOne((r) => r.url === '/rest/batch/charts-import/instances');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ data: [{ instanceId: 2, executions: [] }], total: 12, size: 5 });

    // Switch type → rows clear and page resets to 0.
    facade.selectType('script-executor');
    expect(facade.rows()).toEqual([]);
    expect(facade.first()).toBe(0);
    TestBed.tick();
    req = httpTesting.expectOne((r) => r.url === '/rest/batch/script-executor/instances');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ data: [], total: 0, size: 5 });
    httpTesting.verify();
  });

  it('clamps to the last page when a shrunk total overruns the current page (one corrective refetch)', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup();
    flushInitialStatus(httpTesting);

    facade.selectType('charts-import');
    TestBed.tick();
    httpTesting
      .expectOne((r) => r.url === '/rest/batch/charts-import/instances')
      .flush({ data: [{ instanceId: 1, executions: [] }], total: 20, size: 5 });

    facade.setFirst(15); // page 3
    TestBed.tick();
    // The type shrank to 3 instances → only page 0 exists; clamp to page 0.
    httpTesting
      .expectOne((r) => r.url === '/rest/batch/charts-import/instances')
      .flush({ data: [], total: 3, size: 5 });
    TestBed.tick();
    const corrective = httpTesting.expectOne(
      (r) => r.url === '/rest/batch/charts-import/instances',
    );
    expect(corrective.request.params.get('page')).toBe('0');
    corrective.flush({ data: [{ instanceId: 9, executions: [] }], total: 3, size: 5 });
    expect(facade.first()).toBe(0);
    httpTesting.verify();
  });

  it('a failed instances fetch keeps the last rows (silent keep-last)', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup();
    flushInitialStatus(httpTesting);

    facade.selectType('charts-import');
    TestBed.tick();
    httpTesting
      .expectOne((r) => r.url === '/rest/batch/charts-import/instances')
      .flush({ data: [{ instanceId: 1, executions: [] }], total: 7, size: 5 });
    expect(facade.rows()).toHaveLength(1);

    facade.refreshInstances();
    TestBed.tick();
    httpTesting
      .expectOne((r) => r.url === '/rest/batch/charts-import/instances')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.rows()).toHaveLength(1);
    httpTesting.verify();
  });
});
