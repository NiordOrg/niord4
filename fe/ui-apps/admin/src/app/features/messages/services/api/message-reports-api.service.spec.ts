import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessageReportsApiService } from './message-reports-api.service';

const API_BASE = '/rest';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(MessageReportsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('MessageReportsApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it(`forList=true → GET ${API_BASE}/message-reports/reports`, () => {
    s.api.listReports({ forList: true }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/message-reports/reports`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('expandParams')).toBeNull();
    req.flush([]);
  });

  it(`forList=false → GET ${API_BASE}/message-reports/detail-reports`, () => {
    s.api.listReports({ forList: false }).subscribe();
    s.httpTesting
      .expectOne((r) => r.url === `${API_BASE}/message-reports/detail-reports`)
      .flush([]);
  });

  it('expandParams is forwarded as a query string', () => {
    s.api.listReports({ forList: true, expandParams: 3 }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/message-reports/reports`);
    expect(req.request.params.get('expandParams')).toBe('3');
    req.flush([]);
  });

  it(`expandReportParams → POST ${API_BASE}/message-reports/expand-params with the report as its body`, () => {
    const report = {
      reportId: 'nightly',
      name: 'Nightly',
      properties: {},
      params: { title: 'Week ${week}' },
    };
    let answer: unknown;
    s.api.expandReportParams(report).subscribe((r) => (answer = r));
    const req = s.httpTesting.expectOne(`${API_BASE}/message-reports/expand-params`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(report);
    req.flush({ ...report, params: { title: 'Week 46' } });
    expect(answer).toEqual({ ...report, params: { title: 'Week 46' } });
  });
});
