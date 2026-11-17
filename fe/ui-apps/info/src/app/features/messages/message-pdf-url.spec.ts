import { messageListPdfUrl, messagePdfUrl } from './message-pdf-url';

describe('message-pdf-url', () => {
  describe('messageListPdfUrl', () => {
    it('spells the filter in the parameter names the report endpoint reads', () => {
      const url = messageListPdfUrl({
        language: 'da',
        mainTypes: ['NW', 'NM'],
        areaIds: ['453'],
        messageSeriesIds: ['dma-nw', 'dma-nm'],
      });

      const query = new URLSearchParams(url.slice(url.indexOf('?') + 1));
      expect(url.startsWith('/rest/message-reports/report.pdf?')).toBe(true);
      // `lang`, as the public read says it too, but `area`, not the public read's `areaId`.
      expect(query.get('lang')).toBe('da');
      expect(query.getAll('mainType')).toEqual(['NW', 'NM']);
      expect(query.getAll('area')).toEqual(['453']);
      expect(query.getAll('messageSeries')).toEqual(['dma-nw', 'dma-nm']);
    });

    it('names the public report and no page size or orientation, so the backend uses its own defaults', () => {
      const query = new URLSearchParams(
        messageListPdfUrl({
          language: 'en',
          mainTypes: ['NM'],
          areaIds: [],
          messageSeriesIds: [],
        }).split('?')[1],
      );

      // The standard layout without the editors' front page
      expect(query.getAll('report')).toEqual(['public']);
      expect(query.get('pageSize')).toBeNull();
      expect(query.get('pageOrientation')).toBeNull();
      expect(query.get('ticket')).toBeNull();
    });

    it('names the publication being browsed, and none when there is none', () => {
      const browsing = new URLSearchParams(
        messageListPdfUrl({
          language: 'da',
          mainTypes: [],
          areaIds: [],
          messageSeriesIds: ['dma-nm'],
          publicationId: '6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b',
        }).split('?')[1],
      );
      expect(browsing.getAll('publication')).toEqual(['6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b']);

      const current = messageListPdfUrl({
        language: 'da',
        mainTypes: ['NW'],
        areaIds: ['453'],
        messageSeriesIds: ['dma-nw'],
      });
      expect(current).not.toContain('publication');
    });

    it('repeats every area and leaves an empty filter out entirely', () => {
      const query = new URLSearchParams(
        messageListPdfUrl({
          language: 'kl',
          mainTypes: [],
          areaIds: ['11', '22'],
          messageSeriesIds: [],
        }).split('?')[1],
      );

      expect(query.getAll('area')).toEqual(['11', '22']);
      expect(query.getAll('mainType')).toEqual([]);
      expect(query.getAll('messageSeries')).toEqual([]);
    });
  });

  describe('messagePdfUrl', () => {
    it('names no report: the standard one prints a single message without a front page', () => {
      expect(messagePdfUrl('urn-uid-1', 'da')).not.toContain('report=');
    });

    it('puts the message id in the path, in front of the .pdf suffix', () => {
      expect(messagePdfUrl('urn-uid-1', 'da')).toBe(
        '/rest/message-reports/message/urn-uid-1.pdf?lang=da',
      );
    });

    // A short id can carry a space. None carries a slash: the image refuses an encoded one in
    // this path, so such an id would not print, and none is spelled here as though it did.
    it('encodes a short id that carries a space', () => {
      expect(messagePdfUrl('Local Warning-120-26', 'en')).toBe(
        '/rest/message-reports/message/Local%20Warning-120-26.pdf?lang=en',
      );
    });
  });
});
