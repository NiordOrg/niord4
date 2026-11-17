import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { BasePromulgation, Message } from '../../models/message.model';
import { PromulgationsApiService } from './promulgations-api.service';

const API_BASE = '/rest';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(PromulgationsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const message = { id: 'm-1', mainType: 'NW' } as Message;
const promulgation = {
  '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
  type: { typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX' },
  promulgate: true,
} as BasePromulgation;

describe('PromulgationsApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => s.httpTesting.verify());

  it('generatePromulgation POSTs the full message to the PLURAL promulgations/generate/{typeId} root', () => {
    s.api.generatePromulgation('navtex', message).subscribe();
    const req = s.httpTesting.expectOne(`${API_BASE}/promulgations/generate/navtex`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBe(message);
    req.flush(promulgation);
  });

  it('getPromulgationType GETs the PLURAL promulgations/promulgation-type/{typeId} root', () => {
    s.api.getPromulgationType('navtex').subscribe();
    const req = s.httpTesting.expectOne(`${API_BASE}/promulgations/promulgation-type/navtex`);
    expect(req.request.method).toBe('GET');
    req.flush({ typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX' });
  });

  it('reformat PUTs the single promulgation to the SINGULAR promulgation/{service}/reformat-{service} root', () => {
    s.api.reformat('safetynet', promulgation).subscribe();
    const req = s.httpTesting.expectOne(`${API_BASE}/promulgation/safetynet/reformat-safetynet`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBe(promulgation);
    req.flush(promulgation);
  });
});
