import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { Message } from '../../../messages/models/message.model';
import { TemplatesApiService } from './templates-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(TemplatesApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('TemplatesApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches parameter-types in ALL languages (no lang filter) so LIST values re-localize', () => {
    const { service, httpTesting } = setup();
    service.parameterTypes().subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/templates/parameter-types');
    // A lang filter would drop every LIST value's other-language descs → the chosen value would
    // freeze in one language at execute time (never re-localizing in the preview / Status tab).
    expect(req.request.params.has('lang')).toBe(false);
    req.flush([]);
  });

  it('PUTs the inline message + templateParams to /templates/execute', () => {
    const { service, httpTesting } = setup();
    const message = { id: 'm', mainType: 'NW' } as Message;
    service.execute(message, [{ posDecimals: '1', positions: [], color: 'red' }]).subscribe();

    const req = httpTesting.expectOne('/rest/templates/execute');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.message).toEqual(message);
    expect(req.request.body.templateParams[0].color).toBe('red');
    req.flush(message);
  });

  // The payloads below are the endpoint's REAL wire shape: the verdict is `isValid`, named after
  // the response bean's getIsValid() accessor. Reading `valid` returns undefined for every answer,
  // which reads as "invalid" — so a correct call sign was rejected. Do not "tidy" these fixtures
  // to `valid`; that is what let the defect through in the first place.
  it('POSTs a value to the kind-specific validate endpoint and maps the isValid verdict', () => {
    const { service, httpTesting } = setup();
    let result: { value: string; valid: boolean } | undefined;
    service.validate('call-sign', 'oxyz').subscribe((r) => (result = r));
    const req = httpTesting.expectOne('/rest/templates/validate-call-sign');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ value: 'oxyz' });
    req.flush({ value: 'OXYZ', isValid: true });
    expect(result).toEqual({ value: 'OXYZ', valid: true });
  });

  it('maps a rejected value to valid:false rather than swallowing it', () => {
    const { service, httpTesting } = setup();
    let result: { value: string; valid: boolean } | undefined;
    service.validate('light-character', 'nonsense').subscribe((r) => (result = r));
    httpTesting
      .expectOne('/rest/templates/validate-light-character')
      .flush({ value: 'nonsense', isValid: false });
    expect(result).toEqual({ value: 'nonsense', valid: false });
  });
});
