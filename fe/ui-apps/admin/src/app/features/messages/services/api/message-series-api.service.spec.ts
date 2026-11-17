import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { APP_CONFIG } from '../../../../core/config/app-config.token';

import { MessageSeriesApiService } from './message-series-api.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';

describe('MessageSeriesApiService', () => {
  let service: MessageSeriesApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: LanguageService,
          useValue: { selectedLanguage: () => 'en' },
        },
      ],
    });
    service = TestBed.inject(MessageSeriesApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('searchMessageSeries', () => {
    it('should call correct endpoint with query and default limit', () => {
      service.searchMessageSeries('NW').subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/message-series/search');
      expect(req.request.params.get('name')).toBe('NW');
      expect(req.request.params.get('lang')).toBe('en');
      expect(req.request.params.get('limit')).toBe('10');
      expect(req.request.params.has('domain')).toBe(false);
      req.flush([]);
    });

    it('should include domain when provided', () => {
      service.searchMessageSeries('NW', { domainId: 'nw' }).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/message-series/search');
      expect(req.request.params.get('domain')).toBe('nw');
      req.flush([]);
    });

    it('should use custom limit', () => {
      service.searchMessageSeries('NW', { limit: 5 }).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/message-series/search');
      expect(req.request.params.get('limit')).toBe('5');
      req.flush([]);
    });
  });

  describe('getMessageSeriesByIds', () => {
    it('should call correct endpoint with comma-separated IDs', () => {
      service.getMessageSeriesByIds(['dma-nw', 'dma-nm']).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/message-series/search/dma-nw,dma-nm');
      expect(req.request.params.get('lang')).toBe('en');
      expect(req.request.params.get('limit')).toBe('20');
      req.flush([]);
    });
  });

  describe('getAll', () => {
    it('GETs /message-series/all with messageNumbers=true and NO lang param', () => {
      let received: { seriesId: string }[] | undefined;
      service.getAll().subscribe((series) => (received = series));

      const req = httpMock.expectOne((r) => r.url === '/rest/message-series/all');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('messageNumbers')).toBe('true');
      expect(req.request.params.has('lang')).toBe(false);
      req.flush([{ seriesId: 'dma-nw' }, { seriesId: 'dma-nm' }]);
      expect(received?.map((s) => s.seriesId)).toEqual(['dma-nw', 'dma-nm']);
    });
  });
});
