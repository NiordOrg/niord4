import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { APP_CONFIG } from '../../core/config/app-config.token';
import { LanguageService } from '../../core/main-shell/services/language.service';
import { LangHttpHelper } from './lang-http.helper';

describe('LangHttpHelper', () => {
  let helper: LangHttpHelper;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      ],
    });
    helper = TestBed.inject(LangHttpHelper);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('getByIdsCsv', () => {
    it('short-circuits to an empty array without issuing a request when ids is empty', () => {
      let result: readonly unknown[] | undefined;
      helper.getByIdsCsv('/charts/search', []).subscribe((r) => (result = r));

      expect(result).toEqual([]);
      httpMock.expectNone(() => true);
    });

    it('joins plain ids with literal commas and sets the lang param', () => {
      helper.getByIdsCsv('/message-series/search', ['dma-nw', 'dma-nm']).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/message-series/search/dma-nw,dma-nm');
      expect(req.request.params.get('lang')).toBe('en');
      req.flush([]);
    });

    it('encodes reserved characters per segment while keeping the comma separators literal', () => {
      helper.getByIdsCsv('/charts/search', ['a/b', 'c?d', 'e&f', 'g h']).subscribe();

      // Each id segment is escaped (/, ?, &, space) but the joining commas stay literal,
      // so the backend still receives a comma-separated path list.
      const req = httpMock.expectOne(
        (r) => r.url === '/rest/charts/search/a%2Fb,c%3Fd,e%26f,g%20h',
      );
      req.flush([]);
    });

    it('leaves numeric ids unchanged (encoding is a no-op)', () => {
      helper.getByIdsCsv('/areas/search', [101, 202]).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/areas/search/101,202');
      req.flush([]);
    });

    it('passes through limit and extra params, and honours an explicit language', () => {
      helper
        .getByIdsCsv('/charts/search', ['DK101'], {
          language: 'da',
          limit: 20,
          extraParams: { geometry: 'false' },
        })
        .subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/charts/search/DK101');
      expect(req.request.params.get('lang')).toBe('da');
      expect(req.request.params.get('limit')).toBe('20');
      expect(req.request.params.get('geometry')).toBe('false');
      req.flush([]);
    });
  });
});
