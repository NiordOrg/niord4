import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { MessagesStore } from './messages.store';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../testing/test-config';
import { settle } from '../../testing/settle';
import { Area, Message } from '../../core/models/message.model';

/**
 * When the list PDF may be offered at all.
 *
 * The report endpoint reads an absent `messageSeries` as "every published series", so a request
 * built from a scope the site does not yet hold renders notices this site never lists. The gate
 * is therefore the read itself: the sheet is offered only once the read keyed on the **current**
 * filter has settled and the series it came back with is non-empty. Rows left over from the
 * previous filter are still on screen while the next read is in flight, and they are not a scope.
 */
const ANCHORLAND: Area = { id: 453, descs: [{ lang: 'en', name: 'Anchorland' }] };
const BUOYLAND: Area = { id: 454, descs: [{ lang: 'en', name: 'Buoyland' }] };

const ANCHORLAND_ROWS: Message[] = [
  { id: 'msg-1', mainType: 'NW', areas: [ANCHORLAND], messageSeries: { seriesId: 'dma-nw' } },
];
const BUOYLAND_ROWS: Message[] = [
  { id: 'msg-9', mainType: 'NW', areas: [BUOYLAND], messageSeries: { seriesId: 'dma-nw-gl' } },
];

describe('printing the message list', () => {
  let store: MessagesStore;
  let httpTesting: HttpTestingController;

  function setUp() {
    TestBed.configureTestingModule({
      providers: [
        MessagesStore,
        provideRouter([]),
        provideTestNiordConfig({
          rootAreas: [
            { areaId: 'urn:xa', latitude: 56, longitude: 11, zoomLevel: 7 },
            { areaId: 'urn:xb', latitude: 72, longitude: -40, zoomLevel: 7 },
          ],
        }),
        provideTranslocoSpy(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    store = TestBed.inject(MessagesStore);
    httpTesting = TestBed.inject(HttpTestingController);
  }

  /** Answers the two root-area reads, which is what lets the list read go out at all. */
  async function answerTheRootAreas(): Promise<void> {
    await settle();
    const areas = httpTesting.match((r) => r.url.includes('/rest/public/v1/area/'));
    expect(areas).toHaveLength(2);
    areas[0].flush(ANCHORLAND);
    areas[1].flush(BUOYLAND);
    await settle();
  }

  const listRead = () => httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));

  afterEach(() => httpTesting.verify());

  it('offers nothing to print until the first read has answered', async () => {
    setUp();
    await answerTheRootAreas();

    const inFlight = listRead();
    expect(store.canPrintList()).toBe(false);

    inFlight.flush(ANCHORLAND_ROWS);
    await settle();
    expect(store.canPrintList()).toBe(true);
  });

  it('offers nothing to print for an area that came back empty', async () => {
    setUp();
    await answerTheRootAreas();
    listRead().flush([]);
    await settle();

    // Nothing on screen is no scope at all, and an unscoped sheet is every published series.
    expect(store.messages()).toEqual([]);
    expect(store.canPrintList()).toBe(false);
  });

  it('offers nothing to print after a read that failed', async () => {
    setUp();
    await answerTheRootAreas();
    listRead().flush('no', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(store.failed()).toBe(true);
    expect(store.canPrintList()).toBe(false);
  });

  it('closes again while the next area is in flight, whatever is still on screen', async () => {
    setUp();
    await answerTheRootAreas();
    listRead().flush(ANCHORLAND_ROWS);
    await settle();
    expect(store.canPrintList()).toBe(true);

    store.selectRootArea(BUOYLAND);
    await settle();
    const switched = listRead();
    expect(switched.request.urlWithParams).toContain('areaId=454');

    // The first root's rows stay up until the second root's arrive — that is what the reader is
    // looking at, and it is exactly not the scope the filter now asks for.
    expect(store.messages()).toHaveLength(1);
    expect(store.canPrintList()).toBe(false);

    switched.flush(BUOYLAND_ROWS);
    await settle();
  });

  it('carries the new area and its own series once that read answers', async () => {
    setUp();
    await answerTheRootAreas();
    listRead().flush(ANCHORLAND_ROWS);
    await settle();

    store.selectRootArea(BUOYLAND);
    await settle();
    listRead().flush(BUOYLAND_ROWS);
    await settle();

    expect(store.canPrintList()).toBe(true);
    const query = new URLSearchParams(store.printPdfUrl().split('?')[1]);
    expect(query.getAll('area')).toEqual(['454']);
    expect(query.getAll('messageSeries')).toEqual(['dma-nw-gl']);
  });
});
