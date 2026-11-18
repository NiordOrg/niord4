import { TestBed } from '@angular/core/testing';
import { MessageService } from './message.service';
import { Message } from '../models/message.model';
import { provideTestNiordConfig } from '../../testing/test-config';

describe('MessageService', () => {
  let service: MessageService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideTestNiordConfig({ backendHosts: ['niord.example.org'] })],
    });
    service = TestBed.inject(MessageService);
  });

  describe('parseList()', () => {
    it('points a bare attachment path at this origin', () => {
      const parsed = service.parseList([
        {
          id: '1',
          mainType: 'NW',
          attachments: [{ path: 'messages/7/24/abc/33/file.pdf', fileName: 'file.pdf' }],
        },
      ]);
      expect(parsed[0].attachments?.[0].path).toBe('/rest/repo/file/messages/7/24/abc/33/file.pdf');
    });

    it('returns an empty list unchanged', () => {
      expect(service.parseList([])).toEqual([]);
    });
  });

  describe('parseOne()', () => {
    it("reduces absolute repository URLs on this deployment's hosts, and no other", () => {
      const parsed = service.parseOne({
        id: '1',
        mainType: 'NW',
        attachments: [
          { path: 'https://niord.example.org/rest/repo/file/messages/a.pdf', fileName: 'a.pdf' },
        ],
        parts: [
          {
            type: 'DETAILS',
            descs: [
              {
                lang: 'en',
                details:
                  `<p><img src="${location.origin}/rest/repo/file/messages/a.png"></p>` +
                  '<a href="https://niord.example.org/rest/repo/file/charts/b.pdf">b</a>' +
                  '<a href="https://niord.example.net/rest/repo/file/charts/c.pdf">c</a>' +
                  '<a href="https://example.com/x">x</a>',
              },
            ],
          },
        ],
        descs: [
          {
            lang: 'en',
            publication:
              '<a href="https://niord.example.org/rest/repo/file/publications/p.pdf">p</a>',
          },
        ],
      });

      expect(parsed?.attachments?.[0].path).toBe('/rest/repo/file/messages/a.pdf');
      expect(parsed?.parts?.[0].descs?.[0].details).toBe(
        '<p><img src="/rest/repo/file/messages/a.png"></p>' +
          '<a href="/rest/repo/file/charts/b.pdf">b</a>' +
          '<a href="https://niord.example.net/rest/repo/file/charts/c.pdf">c</a>' +
          '<a href="https://example.com/x">x</a>',
      );
      expect(parsed?.descs?.[0].publication).toBe(
        '<a href="/rest/repo/file/publications/p.pdf">p</a>',
      );
    });

    // The backend answers 200 with an empty body for an id it does not know.
    it('is undefined for an empty body', () => {
      expect(service.parseOne(null)).toBeUndefined();
      expect(service.parseOne('')).toBeUndefined();
    });
  });

  // The API declares publishDateFrom a string and the backend sends epoch milliseconds; the
  // model carries a number, so the boundary is where the two are reconciled.
  describe('publishDateFrom', () => {
    it('keeps the epoch milliseconds the backend sends', () => {
      const parsed = service.parseOne({ id: '1', mainType: 'NW', publishDateFrom: 1743674834000 });
      expect(parsed?.publishDateFrom).toBe(1743674834000);
    });

    it('reads a string of digits as the same number', () => {
      const parsed = service.parseOne({
        id: '1',
        mainType: 'NW',
        publishDateFrom: '1743674834000',
      });
      expect(parsed?.publishDateFrom).toBe(1743674834000);
    });

    it('reads a date string the browser understands', () => {
      const parsed = service.parseOne({
        id: '1',
        mainType: 'NW',
        publishDateFrom: '2025-04-03T09:27:14Z',
      });
      expect(parsed?.publishDateFrom).toBe(Date.parse('2025-04-03T09:27:14Z'));
    });

    it('drops a value that is neither, rather than carrying an invalid date', () => {
      const parsed = service.parseOne({ id: '1', mainType: 'NW', publishDateFrom: 'whenever' });
      expect(parsed?.publishDateFrom).toBeUndefined();
    });

    it('leaves an absent field absent', () => {
      const parsed: Message | undefined = service.parseOne({ id: '1', mainType: 'NW' });
      expect(parsed?.publishDateFrom).toBeUndefined();
    });
  });

  // A part's event dates are what active-now judges a message by; the API declares their ends
  // strings as well.
  describe('eventDates', () => {
    it('reads both ends as numbers, whether sent as numbers or as strings of digits', () => {
      const parsed = service.parseOne({
        id: '1',
        mainType: 'NW',
        parts: [
          {
            type: 'DETAILS',
            eventDates: [{ allDay: true, fromDate: '1743674834000', toDate: 1743761234000 }],
          },
        ],
      });
      expect(parsed?.parts?.[0].eventDates).toEqual([
        { allDay: true, fromDate: 1743674834000, toDate: 1743761234000 },
      ]);
    });

    it('drops an interval left with neither end', () => {
      const parsed = service.parseOne({
        id: '1',
        mainType: 'NW',
        parts: [
          {
            type: 'DETAILS',
            eventDates: [{ allDay: false }, { fromDate: 'whenever' }, { toDate: 1743761234000 }],
          },
        ],
      });
      expect(parsed?.parts?.[0].eventDates).toEqual([{ toDate: 1743761234000 }]);
    });
  });
});
