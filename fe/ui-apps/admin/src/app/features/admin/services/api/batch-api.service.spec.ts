import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { BatchApiService, BatchInstanceSearchResult, BatchStatus } from './batch-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(BatchApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('BatchApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('getStatus surfaces runningExecutions (badge) AND the per-type counts (page) additively', () => {
    const { service, httpTesting } = setup();
    let received: BatchStatus | undefined;
    service.getStatus().subscribe((status) => (received = status));
    httpTesting.expectOne('/rest/batch/status').flush({
      runningExecutions: 2,
      types: [
        { name: 'charts-import', instanceCount: 5, runningExecutions: 1 },
        { name: 'script-executor', instanceCount: 0 },
      ],
    });
    expect(received?.runningExecutions).toBe(2);
    expect(received?.types).toEqual([
      { name: 'charts-import', instanceCount: 5, runningExecutions: 1 },
      { name: 'script-executor', instanceCount: 0, runningExecutions: 0 },
    ]);
  });

  it('getStatus tolerates a missing types[] (badge-only / non-admin empty status)', () => {
    const { service, httpTesting } = setup();
    let received: BatchStatus | undefined;
    service.getStatus().subscribe((status) => (received = status));
    httpTesting.expectOne('/rest/batch/status').flush({ runningExecutions: 0 });
    expect(received).toEqual({ runningExecutions: 0, types: [] });
  });

  it('getInstances sends a 0-based page index + pageSize and normalizes the result', () => {
    const { service, httpTesting } = setup();
    let received: BatchInstanceSearchResult | undefined;
    service.getInstances('charts-import', 2, 5).subscribe((r) => (received = r));
    const req = httpTesting.expectOne((r) => r.url === '/rest/batch/charts-import/instances');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('pageSize')).toBe('5');
    req.flush({
      data: [{ instanceId: 7, executions: [{ executionId: 9, batchStatus: 'COMPLETED' }] }],
      total: 11,
      size: 5,
    });
    expect(received?.total).toBe(11);
    expect(received?.data).toHaveLength(1);
  });

  it('normalizes null instance/execution fields to safe defaults (flags default to false)', () => {
    const { service, httpTesting } = setup();
    let received: { data: { executions: { stoppable: boolean }[] }[] } | undefined;
    service.getInstances('x', 0, 5).subscribe((r) => (received = r as never));
    httpTesting
      .expectOne((r) => r.url === '/rest/batch/x/instances')
      .flush({ data: [{ instanceId: 1, executions: [{ executionId: 2 }] }] });
    expect(received?.data[0].executions[0].stoppable).toBe(false);
  });

  it('stop/restart/abandon PUT the execution-id endpoints with no body', () => {
    const { service, httpTesting } = setup();
    service.stop(10).subscribe();
    expect(httpTesting.expectOne('/rest/batch/execution/10/stop').request.method).toBe('PUT');
    service.restart(11).subscribe();
    expect(httpTesting.expectOne('/rest/batch/execution/11/restart').request.method).toBe('PUT');
    service.abandon(12).subscribe();
    expect(httpTesting.expectOne('/rest/batch/execution/12/abandon').request.method).toBe('PUT');
  });

  it('getLogFileNames fetches the instance logs list', () => {
    const { service, httpTesting } = setup();
    let names: string[] | undefined;
    service.getLogFileNames(3).subscribe((n) => (names = n));
    httpTesting.expectOne('/rest/batch/instance/3/logs').flush(['a.log', 'b.log']);
    expect(names).toEqual(['a.log', 'b.log']);
  });

  it('getLogFileContent encodeURIComponent-encodes the name with NO fromLineNo tail', () => {
    const { service, httpTesting } = setup();
    let content: string | undefined;
    service.getLogFileContent(4, 'sub dir/run #1.log').subscribe((c) => (content = c));
    const req = httpTesting.expectOne(
      `/rest/batch/instance/4/logs/${encodeURIComponent('sub dir/run #1.log')}`,
    );
    expect(req.request.params.has('fromLineNo')).toBe(false);
    req.flush('log body');
    expect(content).toBe('log body');
  });

  it('executeJavaScript posts the fixed scriptName + the code body', () => {
    const { service, httpTesting } = setup();
    service.executeJavaScript('console.log(1)').subscribe();
    const req = httpTesting.expectOne('/rest/batch/execute-javascript');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ scriptName: 'javascript.js', javaScript: 'console.log(1)' });
    req.flush('OK');
  });

  it('downloadUrl encodeURI-encodes the fileName but preserves path separators', () => {
    const { service } = setup();
    expect(service.downloadUrl(5, 'archive dir/data.zip')).toBe(
      '/rest/batch/instance/5/download/archive%20dir/data.zip',
    );
  });

  it('thumbnailUrl + uploadBatchSetUrl build the expected endpoints', () => {
    const { service } = setup();
    expect(service.thumbnailUrl(6)).toBe('/rest/batch/instance/6/thumbnail.png');
    expect(service.uploadBatchSetUrl()).toBe('/rest/batch/execute-batch-set');
  });
});
