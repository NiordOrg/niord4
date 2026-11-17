import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { Setting, SettingsApiService } from './settings-api.service';

describe('SettingsApiService', () => {
  let service: SettingsApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
        SettingsApiService,
      ],
    });
    service = TestBed.inject(SettingsApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('GETs the editable settings list and preserves an absent type as undefined', () => {
    let result: Setting[] | undefined;
    service.getEditableSettings().subscribe((settings) => (result = settings));
    const req = httpTesting.expectOne('/rest/settings/editable-settings');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('lang')).toBe(false);
    req.flush([
      { key: 'a.b', type: 'Boolean', value: true },
      { key: 'untyped', value: 'x' }, // no `type` field at all
    ]);
    expect(result).toEqual([
      { key: 'a.b', description: undefined, type: 'Boolean', value: true },
      { key: 'untyped', description: undefined, type: undefined, value: 'x' },
    ]);
  });

  it('PUTs to /setting/{key} with a URL-encoded dotted key (path == body key)', () => {
    service.updateSetting({ key: 'mail.delete.after.days', type: 'Long', value: 30 }).subscribe();
    const req = httpTesting.expectOne('/rest/settings/setting/mail.delete.after.days');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.key).toBe('mail.delete.after.days');
    req.flush({ key: 'mail.delete.after.days', type: 'Long', value: 30 });
  });

  it('URL-encodes a key containing a slash', () => {
    service.updateSetting({ key: 'a/b', value: 'x' }).subscribe();
    const req = httpTesting.expectOne('/rest/settings/setting/a%2Fb');
    expect(req.request.method).toBe('PUT');
    req.flush({ key: 'a/b', value: 'x' });
  });

  it('exposes the bare export URL (no ticket) and the upload URL', () => {
    expect(service.exportUrl()).toBe('/rest/settings/editable-settings');
    expect(service.uploadUrl()).toBe('/rest/settings/upload-settings');
  });

  it('GETs the web settings map, carries no lang param and passes the map through', () => {
    let result: Readonly<Record<string, unknown>> | undefined;
    service.getWebSettings().subscribe((settings) => (result = settings));
    const req = httpTesting.expectOne('/rest/settings/web');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('lang')).toBe(false);
    const map = { adminIntegrationPageEnabled: 'true', s124Enabled: true, secomEnabled: false };
    req.flush(map);
    expect(result).toEqual(map);
  });
});
