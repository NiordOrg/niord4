import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import {
  MessageReportsAdminApiService,
  SystemMessageReport,
} from './message-reports-admin-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(MessageReportsAdminApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const report: SystemMessageReport = {
  reportId: 'standard',
  name: 'Standard',
  templatePath: 'reports/standard.ftl',
  domains: [],
  properties: {},
  params: {},
};

describe('MessageReportsAdminApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lists all reports', () => {
    const { service, httpTesting } = setup();
    service.getAll().subscribe();
    httpTesting.expectOne('/rest/message-reports/all').flush([]);
  });

  it('POSTs to the trailing-slash create endpoint', () => {
    const { service, httpTesting } = setup();
    service.create(report).subscribe();
    const req = httpTesting.expectOne('/rest/message-reports/report/');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('PUTs on the URL-encoded reportId', () => {
    const { service, httpTesting } = setup();
    service.update({ ...report, reportId: 'a/b' }).subscribe();
    const req = httpTesting.expectOne('/rest/message-reports/report/a%2Fb');
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('DELETEs on the URL-encoded reportId', () => {
    const { service, httpTesting } = setup();
    service.delete('standard').subscribe();
    const req = httpTesting.expectOne('/rest/message-reports/report/standard');
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('exposes bare export + upload URLs', () => {
    const { service } = setup();
    expect(service.exportUrl()).toBe('/rest/message-reports/all');
    expect(service.uploadUrl()).toBe('/rest/message-reports/upload-reports');
  });
});
