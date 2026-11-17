import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { BatchExecution, BatchInstance } from '../../../services/api/batch-api.service';
import { BatchPage } from './batch-page';

interface PageInternals {
  canSysadmin: () => boolean;
  statusClass: (e: BatchExecution) => string;
  isStarted: (e: BatchExecution) => boolean;
  showProgress: (row: { instance: BatchInstance; execution: BatchExecution }) => boolean;
  stop: (e: BatchExecution) => void;
  restart: (e: BatchExecution) => void;
  abandon: (e: BatchExecution) => void;
  download: (instance: BatchInstance) => Promise<void>;
  jobLinkFor: (name: string) => string;
  facade: { selectedType: () => string | undefined };
}

const STATUS = { runningExecutions: 0, types: [] };

function execution(overrides: Partial<BatchExecution> = {}): BatchExecution {
  return {
    executionId: 1,
    batchStatus: 'COMPLETED',
    stoppable: false,
    restartable: false,
    abandonable: false,
    ...overrides,
  };
}

function setup(options: { batchName?: string; sysadmin?: boolean } = {}) {
  const notifications = { error: vi.fn(), info: vi.fn(), success: vi.fn(), warn: vi.fn() };
  const downloads = { openTicketed: vi.fn(() => Promise.resolve()) };
  const hasRole = signal(options.sysadmin ?? true);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
      { provide: TicketedDownloadService, useValue: downloads },
      { provide: AuthFacade, useValue: { hasRole: () => hasRole } },
      { provide: DomainService, useValue: { sessionDomain: signal(undefined) } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            paramMap: convertToParamMap(options.batchName ? { batchName: options.batchName } : {}),
          },
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(BatchPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  // Flush the facade's immediate status poll on construction.
  httpTesting.match('/rest/batch/status').forEach((req) => req.flush(STATUS));
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    notifications,
    downloads,
  };
}

describe('BatchPage', () => {
  afterEach(() => {
    // Drain any in-flight instances/status polls the facade fired.
    const httpTesting = TestBed.inject(HttpTestingController);
    httpTesting.match(() => true).forEach((req) => req.flush({ data: [], total: 0, size: 5 }));
  });

  it('pre-selects the job type from the :batchName route param ONCE on entry', () => {
    const { page, httpTesting } = setup({ batchName: 'charts-import' });
    expect(page.facade.selectedType()).toBe('charts-import');
    // The pre-selection drives an instances fetch.
    httpTesting
      .match((r) => r.url === '/rest/batch/charts-import/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));
  });

  it('no :batchName param leaves no type pre-selected', () => {
    const { page } = setup();
    expect(page.facade.selectedType()).toBeUndefined();
  });

  it('exposes the sysadmin gate from AuthFacade.hasRole', () => {
    const { page } = setup({ sysadmin: false });
    expect(page.canSysadmin()).toBe(false);
  });

  it('maps execution status to the keyed badge class', () => {
    const { page } = setup();
    expect(page.statusClass(execution({ batchStatus: 'FAILED' }))).toBe('is-batch-failed');
  });

  it('shows the progress bar only for a STARTED execution whose instance reports progress', () => {
    const { page } = setup();
    const started = execution({ batchStatus: 'STARTED' });
    expect(
      page.showProgress({
        instance: { instanceId: 1, executions: [], progress: 40 },
        execution: started,
      }),
    ).toBe(true);
    // STARTED but no progress → empty cell (no bar).
    expect(
      page.showProgress({ instance: { instanceId: 1, executions: [] }, execution: started }),
    ).toBe(false);
    // Not STARTED → end-time branch, no bar.
    expect(
      page.showProgress({
        instance: { instanceId: 1, executions: [], progress: 40 },
        execution: execution({ batchStatus: 'COMPLETED' }),
      }),
    ).toBe(false);
  });

  it('stop issues the execution-id PUT and refetches the page on success', () => {
    const { page, httpTesting } = setup({ batchName: 'x' });
    httpTesting
      .match((r) => r.url === '/rest/batch/x/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));

    page.stop(execution({ executionId: 99 }));
    const put = httpTesting.expectOne('/rest/batch/execution/99/stop');
    expect(put.request.method).toBe('PUT');
    put.flush(null);
    // Success bumps the poll tick → a fresh instances fetch.
    httpTesting
      .match((r) => r.url === '/rest/batch/x/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));
  });

  it('a failed action raises a keyed error toast', () => {
    const { page, httpTesting, notifications } = setup({ batchName: 'x' });
    httpTesting
      .match((r) => r.url === '/rest/batch/x/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));

    page.abandon(execution({ executionId: 7 }));
    httpTesting
      .expectOne('/rest/batch/execution/7/abandon')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.batch.toast.actionError');
  });

  it('a refused restart shows the server reason', () => {
    const { page, httpTesting, notifications } = setup({ batchName: 'x' });
    httpTesting
      .match((r) => r.url === '/rest/batch/x/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));

    page.restart(execution({ executionId: 8 }));
    httpTesting
      .expectOne('/rest/batch/execution/8/restart')
      .flush(
        { status: 409, detail: 'A bootstrap job is never restarted: post the bootstrap again' },
        { status: 409, statusText: 'Conflict' },
      );
    expect(notifications.error).toHaveBeenCalledWith('admin.batch.toast.restartRefused', {
      detail: 'A bootstrap job is never restarted: post the bootstrap again',
    });
  });

  it('a restart refused without a reason, or failing otherwise, keeps the keyed toast', () => {
    const { page, httpTesting, notifications } = setup({ batchName: 'x' });
    httpTesting
      .match((r) => r.url === '/rest/batch/x/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));

    page.restart(execution({ executionId: 8 }));
    httpTesting
      .expectOne('/rest/batch/execution/8/restart')
      .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });
    page.restart(execution({ executionId: 9 }));
    httpTesting
      .expectOne('/rest/batch/execution/9/restart')
      .flush({ status: 500, detail: 'boom' }, { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledTimes(2);
    expect(notifications.error).toHaveBeenNthCalledWith(1, 'admin.batch.toast.actionError');
    expect(notifications.error).toHaveBeenNthCalledWith(2, 'admin.batch.toast.actionError');
  });

  it('a failed stop keeps the keyed toast, a reason or not', () => {
    const { page, httpTesting, notifications } = setup({ batchName: 'x' });
    httpTesting
      .match((r) => r.url === '/rest/batch/x/instances')
      .forEach((req) => req.flush({ data: [], total: 0, size: 5 }));

    page.stop(execution({ executionId: 10 }));
    httpTesting
      .expectOne('/rest/batch/execution/10/stop')
      .flush({ status: 409, detail: 'not running' }, { status: 409, statusText: 'Conflict' });
    expect(notifications.error).toHaveBeenCalledWith('admin.batch.toast.actionError');
  });

  it('download fetches a role=sysadmin ticket via the ticketed download service', async () => {
    const { page, downloads } = setup();
    await page.download({ instanceId: 5, fileName: 'data.zip', executions: [] });
    expect(downloads.openTicketed).toHaveBeenCalledWith(
      '/rest/batch/instance/5/download/data.zip',
      'sysadmin',
    );
  });

  it('a failed download ticket fetch raises a keyed error toast', async () => {
    const downloads = { openTicketed: vi.fn(() => Promise.reject(new Error('no ticket'))) };
    const { page, notifications } = setupWithDownloads(downloads);
    await page.download({ instanceId: 5, fileName: 'data.zip', executions: [] });
    expect(notifications.error).toHaveBeenCalledWith('admin.batch.toast.downloadError');
  });

  it('builds the view-batch-job link target', () => {
    const { page } = setup();
    expect(page.jobLinkFor('charts-import')).toBe('/sysadmin/batch/charts-import');
  });
});

/** Variant setup that injects a failing download service (separate module config). */
function setupWithDownloads(downloads: { openTicketed: ReturnType<typeof vi.fn> }) {
  const notifications = { error: vi.fn(), info: vi.fn(), success: vi.fn(), warn: vi.fn() };
  const hasRole = signal(true);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
      { provide: TicketedDownloadService, useValue: downloads },
      { provide: AuthFacade, useValue: { hasRole: () => hasRole } },
      { provide: DomainService, useValue: { sessionDomain: signal(undefined) } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap({}) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(BatchPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  httpTesting.match('/rest/batch/status').forEach((req) => req.flush(STATUS));
  return { page: fixture.componentInstance as unknown as PageInternals, notifications };
}
