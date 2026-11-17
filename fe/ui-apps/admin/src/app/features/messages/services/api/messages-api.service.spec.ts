import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { APP_CONFIG } from '../../../../core/config/app-config.token';

import { MessagesApiService } from './messages-api.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';

describe('MessagesApiService', () => {
  let service: MessagesApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: LanguageService,
          useValue: {
            selectedLanguage: () => 'en',
            modelLanguages: [{ value: 'en' }, { value: 'da' }],
          },
        },
      ],
    });
    service = TestBed.inject(MessagesApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('updateStatuses', () => {
    it('PUTs the [{messageId, status}] batch to /messages/update-statuses', () => {
      const updates = [
        { messageId: 'uuid-1', status: 'VERIFIED' as const },
        { messageId: 'uuid-2', status: 'PUBLISHED' as const },
      ];
      service.updateStatuses(updates).subscribe();
      const req = httpMock.expectOne('/rest/messages/update-statuses');
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(updates);
      req.flush(null);
    });
  });

  describe('getMessageAllLanguages', () => {
    it('GETs /messages/message/{id} with NO lang param (all-languages)', () => {
      service.getMessageAllLanguages('uuid-1').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/uuid-1');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.has('lang')).toBe(false);
      req.flush({ id: 'uuid-1' });
    });

    it('encodes the id (accepts a shortId)', () => {
      service.getMessageAllLanguages('NW-001-25').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/NW-001-25');
      expect(req.request.method).toBe('GET');
      req.flush({ id: 'x' });
    });
  });

  describe('searchMessageIds', () => {
    it('should call correct endpoint with query, language, and deleted=false by default', () => {
      service.searchMessageIds('NW-001').subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/messages/search-message-ids');
      expect(req.request.params.get('txt')).toBe('NW-001');
      expect(req.request.params.get('lang')).toBe('en');
      expect(req.request.params.get('deleted')).toBe('false');
      req.flush([]);
    });

    it('should send deleted=true when includeDeleted is set', () => {
      service.searchMessageIds('test', { includeDeleted: true }).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/messages/search-message-ids');
      expect(req.request.params.get('deleted')).toBe('true');
      req.flush([]);
    });

    it('should use explicit language when provided', () => {
      service.searchMessageIds('test', { language: 'da' }).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/rest/messages/search-message-ids');
      expect(req.request.params.get('lang')).toBe('da');
      req.flush([]);
    });
  });

  describe('searchMessages', () => {
    it('issues GET with params, lang, page, maxSize and no map overlay by default', () => {
      service.searchMessages('status=PUBLISHED&type=PERMANENT_NOTICE', 0, 200).subscribe();

      const req = httpMock.expectOne(
        '/rest/messages/search?status=PUBLISHED&type=PERMANENT_NOTICE&lang=en&page=0&maxSize=200',
      );
      expect(req.request.method).toBe('GET');
      req.flush({ data: [], total: 0 });
    });

    it('omits the params segment when empty', () => {
      service.searchMessages('', 0, 200).subscribe();

      const req = httpMock.expectOne('/rest/messages/search?lang=en&page=0&maxSize=200');
      req.flush({ data: [], total: 0 });
    });

    it('appends viewMode=map and includeNoPos=true when both flags are set', () => {
      service
        .searchMessages('status=PUBLISHED', 1, 1000, { viewModeMap: true, includeNoPos: true })
        .subscribe();

      const req = httpMock.expectOne(
        '/rest/messages/search?status=PUBLISHED&lang=en&page=1&maxSize=1000&viewMode=map&includeNoPos=true',
      );
      req.flush({ data: [], total: 0 });
    });

    it('honors an explicit language override', () => {
      service.searchMessages('', 0, 200, { language: 'da' }).subscribe();

      const req = httpMock.expectOne('/rest/messages/search?lang=da&page=0&maxSize=200');
      req.flush({ data: [], total: 0 });
    });

    it('returns the typed response with data and total', () => {
      let result: { data: readonly unknown[]; total: number } | undefined;
      service.searchMessages('', 0, 200).subscribe((r) => (result = r));

      const req = httpMock.expectOne((r) => r.url.startsWith('/rest/messages/search'));
      req.flush({ data: [{ id: 'msg-1' }], total: 1 });

      expect(result).toBeDefined();
      expect(result?.total).toBe(1);
      expect(result?.data.length).toBe(1);
    });
  });

  describe('editableDetails', () => {
    it('GETs the editable-message endpoint with lang', () => {
      service.editableDetails('m-1').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/editable-message/m-1');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('lang')).toBe('en');
      req.flush({ id: 'm-1' });
    });

    it('preserves @class on promulgations so the message can round-trip on save', () => {
      let result: { promulgations?: readonly Record<string, unknown>[] } | undefined;
      service.editableDetails('m-1').subscribe((r) => (result = r as never));
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/editable-message/m-1');
      req.flush({
        id: 'm-1',
        promulgations: [{ '@class': 'X', type: { serviceId: 'navtex' }, promulgate: true }],
      });
      expect(result!.promulgations![0]['@class']).toBe('X');
    });
  });

  describe('newMessageTemplate', () => {
    it('GETs new-message-template with mainType and no lang', () => {
      service.newMessageTemplate('NW').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/new-message-template');
      expect(req.request.params.get('mainType')).toBe('NW');
      expect(req.request.params.has('lang')).toBe(false);
      req.flush({ id: 'tmp' });
    });

    it('adds populate=true when requested', () => {
      service.newMessageTemplate('NM', { populate: true }).subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/new-message-template');
      expect(req.request.params.get('populate')).toBe('true');
      req.flush({ id: 'tmp' });
    });
  });

  describe('copyMessageTemplate', () => {
    it('GETs copy-message-template with lang and referenceType', () => {
      service.copyMessageTemplate('src-1', 'CANCELLATION').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/copy-message-template/src-1');
      expect(req.request.params.get('lang')).toBe('en');
      expect(req.request.params.get('referenceType')).toBe('CANCELLATION');
      req.flush({ id: 'copy' });
    });

    it('omits referenceType when not provided', () => {
      service.copyMessageTemplate('src-1').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/copy-message-template/src-1');
      expect(req.request.params.has('referenceType')).toBe(false);
      req.flush({ id: 'copy' });
    });
  });

  describe('saveMessage (POST/PUT dispatch — canonical)', () => {
    it('POSTs /message when the message has never been saved (no created)', () => {
      service.saveMessage({ id: 'tmp-id' }).subscribe();
      const req = httpMock.expectOne('/rest/messages/message');
      expect(req.request.method).toBe('POST');
      req.flush({ id: 'real-id', created: '2026-06-01T00:00:00Z' });
    });

    it('PUTs /message/:id when the message has a created timestamp', () => {
      service.saveMessage({ id: 'real-id', created: '2026-06-01T00:00:00Z' }).subscribe();
      const req = httpMock.expectOne('/rest/messages/message/real-id');
      expect(req.request.method).toBe('PUT');
      req.flush({ id: 'real-id', created: '2026-06-01T00:00:00Z' });
    });

    it('sends promulgations back WITH their @class discriminator (round-trip)', () => {
      service
        .saveMessage({
          id: 'real-id',
          created: '2026-06-01T00:00:00Z',
          promulgations: [
            {
              '@class': 'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
              type: { typeId: 'navtex', serviceId: 'navtex', name: 'NAVTEX' },
              promulgate: true,
            },
          ],
        })
        .subscribe();
      const req = httpMock.expectOne('/rest/messages/message/real-id');
      expect(req.request.body.promulgations?.[0]?.['@class']).toBe(
        'org.niord.core.promulgation.vo.NavtexMessagePromulgationVo',
      );
      req.flush({ id: 'real-id', created: '2026-06-01T00:00:00Z' });
    });
  });

  describe('updateMessageStatus', () => {
    it('PUTs the raw status string as a JSON body and no cancel flag by default', () => {
      service.updateMessageStatus('m-1', 'VERIFIED').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/m-1/status');
      expect(req.request.method).toBe('PUT');
      // The endpoint reads the unquoted string body under an application/json content type.
      expect(req.request.body).toBe('VERIFIED');
      expect(req.request.headers.get('Content-Type')).toBe('application/json');
      expect(req.request.params.has('status')).toBe(false);
      expect(req.request.params.has('cancelReferencedMessages')).toBe(false);
      req.flush({ id: 'm-1', status: 'VERIFIED' });
    });

    it('adds cancelReferencedMessages=true for a cascade-cancel publish', () => {
      service
        .updateMessageStatus('m-1', 'PUBLISHED', { cancelReferencedMessages: true })
        .subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/m-1/status');
      expect(req.request.params.get('cancelReferencedMessages')).toBe('true');
      req.flush({ id: 'm-1', status: 'PUBLISHED' });
    });
  });

  describe('referencedMessages', () => {
    it('GETs with status=PUBLISHED default and repeated referenceType params', () => {
      service.referencedMessages('m-1', ['CANCELLATION', 'REPETITION']).subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/referenced-messages/m-1');
      expect(req.request.params.get('status')).toBe('PUBLISHED');
      expect(req.request.params.getAll('referenceType')).toEqual(['CANCELLATION', 'REPETITION']);
      req.flush([]);
    });
  });

  describe('comments', () => {
    it('POSTs a new comment to /comment with lang', () => {
      service.createComment('m-1', { comment: 'hi' }).subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/m-1/comment');
      expect(req.request.method).toBe('POST');
      expect(req.request.params.get('lang')).toBe('en');
      req.flush({ id: 'c-1', comment: 'hi' });
    });

    it('PUTs an existing comment to /comment/:cid', () => {
      service.updateComment('m-1', { id: 'c-1', comment: 'edit' }).subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/m-1/comment/c-1');
      expect(req.request.method).toBe('PUT');
      req.flush({ id: 'c-1', comment: 'edit' });
    });

    it('PUTs the acknowledge endpoint', () => {
      service.acknowledgeComment('m-1', 'c-1').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/message/m-1/comment/c-1/ack');
      expect(req.request.method).toBe('PUT');
      req.flush({ id: 'c-1', comment: 'x' });
    });
  });

  describe('formatMessageGeometry', () => {
    it('POSTs the FEATURE COLLECTION (not the message) with lang/template/format and returns HTML text', () => {
      const geometry = { type: 'FeatureCollection' as const, features: [] };
      let result: string | undefined;
      service
        .formatMessageGeometry(geometry, {
          lang: 'da',
          template: 'numbered-list',
          format: 'navtex',
        })
        .subscribe((html) => (result = html));

      const req = httpMock.expectOne((r) => r.url === '/rest/messages/format-message-geometry');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(geometry);
      expect(req.request.params.get('lang')).toBe('da');
      expect(req.request.params.get('template')).toBe('numbered-list');
      expect(req.request.params.get('format')).toBe('navtex');
      // text/html endpoint — the response is a raw string, never JSON-parsed.
      expect(req.request.responseType).toBe('text');
      req.flush("<ol><li>55° 00.0'N</li></ol>");
      expect(result).toBe("<ol><li>55° 00.0'N</li></ol>");
    });
  });

  describe('extractMessagePublication', () => {
    it('POSTs a pruned publication-only template (not the full message) with publicationId + lang', () => {
      service
        .extractMessagePublication(
          {
            id: 'm-1',
            descs: [
              { lang: 'en', title: 'T', publication: '<p>pub</p>', internalPublication: 'i' },
            ],
          },
          'pub-42',
        )
        .subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/extract-message-publication');
      expect(req.request.method).toBe('POST');
      // The publication template: only descs' publication HTML (no id/title), one per model language —
      // en keeps its content, the missing da is seeded blank so an all-languages update can reach it.
      const body = req.request.body as { id?: string; descs: Record<string, unknown>[] };
      expect(body.id).toBeUndefined();
      expect(body.descs).toEqual([
        { lang: 'en', publication: '<p>pub</p>', internalPublication: 'i' },
        { lang: 'da', publication: undefined, internalPublication: undefined },
      ]);
      expect(req.request.params.get('publicationId')).toBe('pub-42');
      expect(req.request.params.get('lang')).toBe('en');
      req.flush({ parameters: 'p', link: 'http://x' });
    });

    it('honors an explicit lang override', () => {
      service.extractMessagePublication({ id: 'm-1' }, 'pub-42', 'da').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/extract-message-publication');
      expect(req.request.params.get('lang')).toBe('da');
      req.flush(null);
    });

    it('treats a 200/null body as "nothing to prefill"', () => {
      let result: unknown = 'unset';
      service.extractMessagePublication({ id: 'm-1' }, 'unknown').subscribe((r) => (result = r));
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/extract-message-publication');
      req.flush(null);
      expect(result).toBeNull();
    });
  });

  describe('updateMessagePublications', () => {
    it('sends only publicationId when parameters/link/lang are omitted (= all languages)', () => {
      service
        .updateMessagePublications(
          { id: 'm-1', descs: [{ lang: 'en', publication: 'p', internalPublication: 'i' }] },
          { publicationId: 'pub-42' },
        )
        .subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/update-message-publications');
      expect(req.request.method).toBe('POST');
      // Pruned publication-only template — no full-message fields, one desc per model language.
      const body = req.request.body as { id?: string; descs: Record<string, unknown>[] };
      expect(body.id).toBeUndefined();
      expect(body.descs).toEqual([
        { lang: 'en', publication: 'p', internalPublication: 'i' },
        { lang: 'da', publication: undefined, internalPublication: undefined },
      ]);
      expect(req.request.params.get('publicationId')).toBe('pub-42');
      expect(req.request.params.has('parameters')).toBe(false);
      expect(req.request.params.has('link')).toBe(false);
      expect(req.request.params.has('lang')).toBe(false);
      req.flush({ id: 'm-1' });
    });

    it('appends parameters, link and lang when provided', () => {
      service
        .updateMessagePublications(
          { id: 'm-1' },
          { publicationId: 'pub-42', parameters: 'a=1', link: 'http://x', lang: 'da' },
        )
        .subscribe();
      const req = httpMock.expectOne((r) => r.url === '/rest/messages/update-message-publications');
      expect(req.request.params.get('parameters')).toBe('a=1');
      expect(req.request.params.get('link')).toBe('http://x');
      expect(req.request.params.get('lang')).toBe('da');
      req.flush({ id: 'm-1' });
    });
  });

  describe('deleteRepoFile', () => {
    it('DELETEs /repo/file/<path> WITHOUT re-encoding the already-encoded repo path', () => {
      // Backend-encoded path: embedded slashes stay literal, %20 stays single-encoded.
      const path = 'messages/temp/2026/foo%20bar/custom_thumb_256.png';
      service.deleteRepoFile(path).subscribe();
      const req = httpMock.expectOne(`/rest/repo/file/${path}`);
      expect(req.request.method).toBe('DELETE');
      expect(req.request.responseType).toBe('text');
      // No double-encoding: slashes are not turned into %2F and %20 is not turned into %2520.
      expect(req.request.url).toBe(
        '/rest/repo/file/messages/temp/2026/foo%20bar/custom_thumb_256.png',
      );
      expect(req.request.url).not.toContain('%2F');
      expect(req.request.url).not.toContain('%2520');
      req.flush('File deleted');
    });

    it('keeps a # or a ? in a file name part of the path it DELETEs', () => {
      service.deleteRepoFile('messages/temp/2026/uid/Søkort #12?.png').subscribe();
      const req = httpMock.expectOne(
        '/rest/repo/file/messages/temp/2026/uid/S%C3%B8kort%20%2312%3F.png',
      );
      expect(req.request.method).toBe('DELETE');
      req.flush('File deleted');
    });
  });

  describe('updateMessageMapImage', () => {
    it('POSTs a multipart form (field "file") to /message-map-image/<editRepoPath>/<revision> and returns the path text', () => {
      const file = new File(['x'], 'thumb.png', { type: 'image/png' });
      let result: string | undefined;
      service
        .updateMessageMapImage({ id: 'm-1', editRepoPath: 'messages/temp/xyz', revision: 3 }, file)
        .subscribe((path) => (result = path));

      const req = httpMock.expectOne('/rest/message-map-image/messages/temp/xyz/3');
      expect(req.request.method).toBe('POST');
      expect(req.request.responseType).toBe('text');
      const body = req.request.body as FormData;
      expect(body instanceof FormData).toBe(true);
      expect((body.get('file') as File).name).toBe('thumb.png');
      req.flush('messages/temp/xyz/3/custom_thumb_256.png');
      expect(result).toBe('messages/temp/xyz/3/custom_thumb_256.png');
    });
  });

  describe('changeMessageMapImage', () => {
    it('PUTs the PNG data URL (Content-Type application/json) to /message-map-image/<editRepoPath>/<revision> and returns the path text', () => {
      const dataUrl = 'data:image/png;base64,AAAA';
      let result: string | undefined;
      service
        .changeMessageMapImage(
          { id: 'm-1', editRepoPath: 'messages/temp/xyz', revision: 3 },
          dataUrl,
        )
        .subscribe((path) => (result = path));

      const req = httpMock.expectOne('/rest/message-map-image/messages/temp/xyz/3');
      expect(req.request.method).toBe('PUT');
      expect(req.request.responseType).toBe('text');
      // Backend @Consumes application/json; the raw data-URL string is the body (not multipart).
      expect(req.request.headers.get('Content-Type')).toBe('application/json');
      expect(req.request.body).toBe(dataUrl);
      req.flush('messages/temp/xyz/3/custom_thumb_256.png');
      expect(result).toBe('messages/temp/xyz/3/custom_thumb_256.png');
    });
  });
});
