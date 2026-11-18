import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { MessagesStore } from './messages.store';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../testing/test-config';
import { settle } from '../../testing/settle';
import { Area, Message } from '../../core/models/message.model';

const ANCHORLAND: Area = { id: 453, descs: [{ lang: 'en', name: 'Anchorland' }] };
const BUOYLAND: Area = { id: 454, descs: [{ lang: 'en', name: 'Buoyland' }] };

/** Two messages in two different sub-areas of the same root. */
const SOUND: Area = { id: 11, parent: ANCHORLAND, descs: [{ lang: 'en', name: 'The Sound' }] };
const BELT: Area = { id: 22, parent: ANCHORLAND, descs: [{ lang: 'en', name: 'The Belt' }] };
const MESSAGES: Message[] = [
  { id: 'msg-1', mainType: 'NW', areas: [SOUND], messageSeries: { seriesId: 'dma-nw' } },
  { id: 'msg-2', mainType: 'NM', areas: [BELT], messageSeries: { seriesId: 'dma-nm' } },
];

/** A message whose only event interval starts on 1 January 2100: not in force today. */
const LATER: Message = {
  id: 'later',
  mainType: 'NW',
  areas: [SOUND],
  parts: [{ type: 'DETAILS', eventDates: [{ fromDate: 4102444800000 }] }],
};

describe('MessagesStore', () => {
  let store: MessagesStore;
  let httpTesting: HttpTestingController;

  function setUp(rootAreas?: { areaId: string; latitude: number; longitude: number }[]) {
    TestBed.configureTestingModule({
      providers: [
        MessagesStore,
        // The store follows the query of the page it is on; with no navigation there is none.
        provideRouter([]),
        provideTestNiordConfig(
          rootAreas
            ? { rootAreas: rootAreas.map((spec) => ({ ...spec, zoomLevel: 7 })) }
            : undefined,
        ),
        provideTranslocoSpy(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    store = TestBed.inject(MessagesStore);
    httpTesting = TestBed.inject(HttpTestingController);
  }

  /** The root-area read, then the message read it unblocks. */
  async function load(messages: Message[] = MESSAGES, areas: Area[] = [ANCHORLAND]) {
    await settle();
    const areaRequests = httpTesting.match((r) => r.url.includes('/rest/public/v1/area/'));
    expect(areaRequests).toHaveLength(areas.length);
    areaRequests.forEach((request, index) => request.flush(areas[index]));
    await settle();
    const list = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    list.flush(messages);
    await settle();
    return list;
  }

  afterEach(() => {
    httpTesting.verify();
    vi.restoreAllMocks();
  });

  it('reads the root areas once and the list once', async () => {
    setUp();
    await load();
    expect(store.areaRoots()).toHaveLength(1);
    expect(store.rootArea()?.id).toBe(453);
    expect(store.messages()).toHaveLength(2);

    // The root-area specs are a build-time constant, so nothing can key a second read off them.
    await settle();
    httpTesting.verify();
  });

  it('sends one repeated mainType parameter per selected type', async () => {
    setUp();
    const list = await load();
    expect(list.request.urlWithParams).toContain('mainType=NW');
    expect(list.request.urlWithParams).toContain('mainType=NM');
    expect(list.request.urlWithParams).toContain('areaId=453');
    expect(list.request.urlWithParams).toContain('externalize=false');
    expect(list.request.urlWithParams).not.toContain('active=');
    expect(list.request.params.has('publication')).toBe(false);
  });

  // The public API binds the language as `lang` and nothing else: a read that said `language`
  // came back in every language the messages are written in, about a third more bytes.
  it('names the language the way the public API reads it', async () => {
    setUp();
    const list = await load();
    expect(list.request.params.get('lang')).toBe('en');
    expect(list.request.params.has('language')).toBe(false);
  });

  it('issues exactly one read per filter change', async () => {
    setUp();
    await load();

    store.setMainTypes({ NW: true, NM: false });
    await settle();
    const narrowed = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(narrowed.request.urlWithParams).toContain('mainType=NW');
    expect(narrowed.request.urlWithParams).not.toContain('mainType=NM');
    narrowed.flush([]);
    await settle();

    // The public API has no active filter: the chip narrows what is on screen and reads nothing.
    store.toggleActiveNow();
    await settle();
    httpTesting.verify();
  });

  it('narrows the list on screen to what is in force now, without a read', async () => {
    setUp();
    await load([LATER, { id: 'undated', mainType: 'NM', areas: [BELT] }]);
    expect(store.messages()).toHaveLength(2);

    store.toggleActiveNow();
    await settle();
    httpTesting.verify();
    expect(store.activeNow()).toBe(true);
    expect(store.messages().map((message) => message.id)).toEqual(['undated']);
    // The sub-areas offered are the ones that still have rows.
    expect(store.subAreas().map((area) => area.id)).toEqual([22]);

    store.toggleActiveNow();
    await settle();
    httpTesting.verify();
    expect(store.messages().map((message) => message.id)).toEqual(['later', 'undated']);
  });

  it('reads nothing when a sub-area is ticked, and narrows the list on the spot', async () => {
    setUp();
    await load();
    expect(store.subAreas().map((area) => area.id)).toEqual([11, 22]);

    store.toggleSubArea(SOUND);
    await settle();
    expect(store.messages().map((message) => message.id)).toEqual(['msg-1']);
    httpTesting.verify();

    store.toggleSubArea(SOUND);
    await settle();
    expect(store.messages()).toHaveLength(2);
  });

  it('forgets the ticked sub-areas when a new list arrives', async () => {
    setUp();
    await load();
    store.toggleSubArea(SOUND);
    await settle();
    expect(store.subAreaSelection().size).toBe(1);

    store.setMainTypes({ NW: true, NM: false });
    await settle();
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush(MESSAGES);
    await settle();
    expect(store.subAreaSelection().size).toBe(0);
  });

  it('keeps the rows on screen while the next read is in flight, and clears them on a failure', async () => {
    setUp();
    await load();
    expect(store.messages()).toHaveLength(2);

    store.setMainTypes({ NW: true, NM: false });
    await settle();
    const inFlight = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(store.messages()).toHaveLength(2);

    inFlight.flush('no', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(store.messages()).toEqual([]);
  });

  it('tells a read that failed from one that came back empty', async () => {
    setUp();
    await load();
    expect(store.failed()).toBe(false);

    store.setMainTypes({ NW: true, NM: false });
    await settle();
    httpTesting
      .expectOne((r) => r.url.includes('/rest/public/v1/messages'))
      .flush('no', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(store.failed()).toBe(true);

    // An area with nothing to report empties the list without saying anything went wrong.
    store.setMainTypes({ NW: true, NM: true });
    await settle();
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush([]);
    await settle();
    expect(store.failed()).toBe(false);
    expect(store.messages()).toEqual([]);
  });

  // On a slow link the first visit is seconds of nothing; the views need to tell that apart
  // from an area with nothing to report.
  it('is loading from the first moment until the list has arrived, and not after', async () => {
    setUp();
    expect(store.loading()).toBe(true);
    await settle();
    // The root area is on its way, then the list that waits for it.
    expect(store.loading()).toBe(true);
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/area/')).flush(ANCHORLAND);
    await settle();
    expect(store.loading()).toBe(true);
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush([]);
    await settle();
    expect(store.loading()).toBe(false);
    expect(store.messages()).toEqual([]);

    store.setMainTypes({ NW: true, NM: false });
    await settle();
    expect(store.loading()).toBe(true);
    httpTesting
      .expectOne((r) => r.url.includes('/rest/public/v1/messages'))
      .flush('no', { status: 500, statusText: 'Server Error' });
    await settle();
    // A failed read is not a read on its way.
    expect(store.loading()).toBe(false);
    expect(store.failed()).toBe(true);
  });

  // A build may name two root areas; the backend of one country may stop carrying the
  // other's, and a passing server error on one read must not blank the whole site.
  it('offers the root areas that answered when one of them does not', async () => {
    setUp([
      { areaId: 'urn:xa', latitude: 56, longitude: 11 },
      { areaId: 'urn:xb', latitude: 72, longitude: -40 },
    ]);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await settle();
    httpTesting.expectOne((r) => r.url.endsWith('/area/urn%3Axa')).flush(ANCHORLAND);
    httpTesting
      .expectOne((r) => r.url.endsWith('/area/urn%3Axb'))
      .flush('gone', { status: 404, statusText: 'Not Found' });
    await settle();

    expect(store.areaRoots().map((area) => area.id)).toEqual([453]);
    expect(store.areaRoots()[0].latitude).toBe(56);
    expect(store.failed()).toBe(false);
    expect(warn).toHaveBeenCalled();
    const list = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(list.request.params.get('areaId')).toBe('453');
    list.flush(MESSAGES);
    await settle();
    expect(store.messages()).toHaveLength(2);
  });

  it('says the list did not load when none of the root areas answers', async () => {
    setUp([
      { areaId: 'urn:xa', latitude: 56, longitude: 11 },
      { areaId: 'urn:xb', latitude: 72, longitude: -40 },
    ]);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await settle();
    for (const request of httpTesting.match((r) => r.url.includes('/rest/public/v1/area/'))) {
      request.flush('down', { status: 503, statusText: 'Service Unavailable' });
    }
    await settle();

    expect(store.areaRoots()).toEqual([]);
    expect(store.failed()).toBe(true);
    expect(store.loading()).toBe(false);
  });

  // The public API reads no main type as both, so a filter with neither listed every message
  // under two chips that read as off.
  it('never asks for neither main type', async () => {
    setUp();
    await load();

    store.setMainTypes({ NW: true, NM: false });
    await settle();
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush([]);
    await settle();

    store.setMainTypes({ NW: false, NM: false });
    await settle();
    httpTesting.verify();
    expect(store.mainTypes()).toEqual({ NW: true, NM: false });
    expect(localStorage.getItem('niord-info.NW')).toBe('true');
  });

  it('reads a remembered filter with neither main type as the both it has always shown', async () => {
    localStorage.setItem('niord-info.NW', 'false');
    localStorage.setItem('niord-info.NM', 'false');
    setUp();
    const list = await load([]);
    expect(store.mainTypes()).toEqual({ NW: true, NM: true });
    expect(list.request.params.getAll('mainType')).toEqual(['NW', 'NM']);
  });

  it('starts from the main types the reader last left', async () => {
    localStorage.setItem('niord-info.NM', 'false');
    setUp();
    const list = await load([]);
    expect(store.mainTypes()).toEqual({ NW: true, NM: false });
    expect(list.request.urlWithParams).not.toContain('mainType=NM');
  });

  // A reader who set the filter on the previous deployment of this site, where the key had
  // no prefix, opens on the same filter here.
  it('starts from a filter the previous deployment wrote', async () => {
    localStorage.setItem('NM', 'false');
    setUp();
    const list = await load([]);
    expect(store.mainTypes()).toEqual({ NW: true, NM: false });
    expect(list.request.urlWithParams).not.toContain('mainType=NM');
    expect(localStorage.getItem('NM')).toBeNull();
    expect(localStorage.getItem('niord-info.NM')).toBe('false');
  });

  it('remembers the root area the reader picks and keeps it across the next read', async () => {
    setUp([
      { areaId: 'urn:xa', latitude: 56, longitude: 11 },
      { areaId: 'urn:xb', latitude: 72, longitude: -40 },
    ]);
    await load(MESSAGES, [ANCHORLAND, BUOYLAND]);

    store.selectRootArea(BUOYLAND);
    await settle();
    expect(localStorage.getItem('niord-info.rootAreaId')).toBe('454');
    const switched = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(switched.request.urlWithParams).toContain('areaId=454');
    switched.flush(MESSAGES);
    await settle();

    // A list arriving after the reader chose must not re-seed the choice back to the first area.
    expect(store.rootArea()?.id).toBe(454);
  });

  it('opens on the root area the reader left, when it is still offered', async () => {
    localStorage.setItem('niord-info.rootAreaId', '454');
    setUp([
      { areaId: 'urn:xa', latitude: 56, longitude: 11 },
      { areaId: 'urn:xb', latitude: 72, longitude: -40 },
    ]);
    const list = await load(MESSAGES, [ANCHORLAND, BUOYLAND]);
    expect(store.rootArea()?.id).toBe(454);
    expect(list.request.urlWithParams).toContain('areaId=454');
  });

  it('reads the whole data set when the build has no root areas', async () => {
    setUp([]);
    await settle();
    expect(httpTesting.match((r) => r.url.includes('/rest/public/v1/area/'))).toHaveLength(0);
    const list = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
    expect(list.request.urlWithParams).not.toContain('areaId');
    list.flush([]);
    await settle();
  });

  it('carries the filter into the PDF address, in the report endpoint names', async () => {
    setUp();
    await load();

    const url = store.printPdfUrl();
    expect(url.startsWith('/rest/message-reports/report.pdf?')).toBe(true);
    const query = new URLSearchParams(url.split('?')[1]);
    expect(query.getAll('mainType')).toEqual(['NW', 'NM']);
    // The root area, under the name the report endpoint reads it by.
    expect(query.getAll('area')).toEqual(['453']);
    // Only the series the visible rows came from, so the sheet is the list on screen.
    expect(query.getAll('messageSeries')).toEqual(['dma-nw', 'dma-nm']);
  });

  it('prints the ticked sub-areas instead of the root, never both', async () => {
    setUp();
    await load();
    store.toggleSubArea(BELT);
    await settle();

    const query = new URLSearchParams(store.printPdfUrl().split('?')[1]);
    // The endpoint matches a message against the union of the areas it is given: keeping the
    // root would print everything under it, which is what the tick just narrowed away.
    expect(query.getAll('area')).toEqual(['22']);
    expect(query.getAll('messageSeries')).toEqual(['dma-nm']);
  });

  it('groups the list under the area heading of its first message', async () => {
    setUp();
    await load();
    expect([...store.areaHeadings().keys()]).toEqual(['msg-1', 'msg-2']);
    expect(store.messageIds()).toEqual(['msg-1', 'msg-2']);
  });

  describe('browsing one publication', () => {
    const PUBLICATION_ID = '6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b';

    /** The store, on a page whose address names a publication. */
    async function setUpOnPublication(): Promise<void> {
      setUp();
      await TestBed.inject(Router).navigateByUrl(`/?publicationId=${PUBLICATION_ID}`);
      store = TestBed.inject(MessagesStore);
    }

    /** The root-area read goes out as always; the publication's read does not wait for it. */
    function answerTheRootArea(): void {
      httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/area/')).flush(ANCHORLAND);
    }

    it('reads the publication and nothing the filter bar chooses', async () => {
      // A reader whose last filter was warnings only must still see a notices publication.
      localStorage.setItem('niord-info.NM', 'false');
      await setUpOnPublication();
      await settle();

      expect(store.publicationId()).toBe(PUBLICATION_ID);
      const list = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
      expect(list.request.params.get('publication')).toBe(PUBLICATION_ID);
      expect(list.request.params.get('lang')).toBe('en');
      expect(list.request.params.get('externalize')).toBe('false');
      expect(list.request.params.has('mainType')).toBe(false);
      expect(list.request.params.has('areaId')).toBe(false);
      expect(list.request.params.has('active')).toBe(false);
      list.flush(MESSAGES);
      answerTheRootArea();
      await settle();

      expect(store.messages()).toHaveLength(2);
    });

    it('does not read again when a chip the bar leaves out would have changed', async () => {
      await setUpOnPublication();
      await settle();
      httpTesting
        .expectOne((r) => r.url.includes('/rest/public/v1/messages'))
        .flush([...MESSAGES, LATER]);
      answerTheRootArea();
      await settle();

      store.toggleActiveNow();
      store.setMainTypes({ NW: true, NM: false });
      await settle();
      httpTesting.verify();
      // Nor is a publication narrowed to what is in force: it is the list it was published with.
      expect(store.messages()).toHaveLength(3);
    });

    it('prints the publication in place of the main types and the root area', async () => {
      await setUpOnPublication();
      await settle();
      httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush(MESSAGES);
      answerTheRootArea();
      await settle();

      const query = new URLSearchParams(store.printPdfUrl().split('?')[1]);
      expect(query.get('publication')).toBe(PUBLICATION_ID);
      expect(query.getAll('mainType')).toEqual([]);
      expect(query.getAll('area')).toEqual([]);
      expect(query.getAll('messageSeries')).toEqual(['dma-nw', 'dma-nm']);

      // A ticked sub-area narrows the publication's sheet as it narrows its rows.
      store.toggleSubArea(BELT);
      await settle();
      const narrowed = new URLSearchParams(store.printPdfUrl().split('?')[1]);
      expect(narrowed.get('publication')).toBe(PUBLICATION_ID);
      expect(narrowed.getAll('area')).toEqual(['22']);
      expect(narrowed.getAll('messageSeries')).toEqual(['dma-nm']);
    });

    it('brings the current list back when the address stops naming the publication', async () => {
      await setUpOnPublication();
      await settle();
      httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages')).flush([]);
      answerTheRootArea();
      await settle();

      await TestBed.inject(Router).navigateByUrl('/');
      await settle();
      expect(store.publicationId()).toBeUndefined();
      const current = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/messages'));
      expect(current.request.params.has('publication')).toBe(false);
      expect(current.request.params.get('areaId')).toBe('453');
      expect(current.request.params.getAll('mainType')).toEqual(['NW', 'NM']);
      current.flush(MESSAGES);
      await settle();
    });
  });
});
