import { TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, Routes } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { messagesRoutes } from './messages.routes';
import { MessagesStore } from './messages.store';
import { Area, Message } from '../../core/models/message.model';
import { provideInfoIcons } from '../../shared/icons/info-icons';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../testing/test-config';
import { settle } from '../../testing/settle';

const routes: Routes = [{ path: 'messages', children: messagesRoutes }];

/** What the Browse link of a message report on the publications page opens. */
const PUBLICATION_ID = '6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b';
const BROWSE_URL = `/messages/details?publicationId=${PUBLICATION_ID}`;

const ANCHORLAND: Area = { id: 453, descs: [{ lang: 'en', name: 'Anchorland' }] };
const WEEK_38: Message[] = [
  { id: 'msg-1', mainType: 'NM', areas: [ANCHORLAND], messageSeries: { seriesId: 'dma-nm' } },
];

const isMessagesRead = (request: HttpRequest<unknown>) =>
  request.url === '/rest/public/v1/messages';
const isPublicationRead = (request: HttpRequest<unknown>) =>
  request.url === `/rest/public/v1/publication/${PUBLICATION_ID}`;

/** The issue the Browse link opened, as the public API answers for it. */
const NTM_38 = {
  publicationId: PUBLICATION_ID,
  type: 'MESSAGE_REPORT',
  descs: [{ lang: 'en', title: 'NtM 38 2026', link: '/rest/repo/file/publications/x.pdf' }],
};

/**
 * Browsing one publication, driven through the router: the address the publications page links
 * to is what scopes the list, and the address without it is the current list again.
 */
describe('browsing a publication as a message list', () => {
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [getTranslocoTestingModule()],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideInfoIcons(),
        provideTestNiordConfig({
          rootAreas: [{ areaId: 'urn:xa', latitude: 56, longitude: 11, zoomLevel: 7 }],
        }),
      ],
    });
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  /** The page on a browsed publication, its three reads answered. */
  async function openBrowsed(url = BROWSE_URL, publication: object = NTM_38) {
    const harness = await RouterTestingHarness.create(url);
    await settle();
    httpTesting.match(isMessagesRead).forEach((request) => request.flush(WEEK_38));
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/area/')).flush(ANCHORLAND);
    httpTesting.expectOne(isPublicationRead).flush(publication);
    await settle();
    harness.detectChanges();
    return harness;
  }

  /** The control that leaves the publication, by its accessible name. */
  function closeButton(harness: RouterTestingHarness): HTMLButtonElement | null {
    return harness.routeNativeElement!.querySelector<HTMLButtonElement>(
      'button[aria-label="Close"]',
    );
  }

  /** The chips the filter bar shows, by label. */
  function chipLabels(harness: RouterTestingHarness): string[] {
    return [...harness.routeNativeElement!.querySelectorAll('button.chip')].map((chip) =>
      chip.textContent!.trim(),
    );
  }

  it('reads the messages of the publication the address names, and only those', async () => {
    const harness = await RouterTestingHarness.create(BROWSE_URL);
    await settle();

    const reads = httpTesting.match(isMessagesRead);
    expect(reads).toHaveLength(1);
    const params = reads[0].request.params;
    expect(params.get('publication')).toBe(PUBLICATION_ID);
    expect(params.get('lang')).toBe('en');
    expect(params.has('mainType')).toBe(false);
    expect(params.has('areaId')).toBe(false);
    reads[0].flush(WEEK_38);
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/area/')).flush(ANCHORLAND);
    httpTesting.expectOne(isPublicationRead).flush(NTM_38);
    await settle();
    harness.detectChanges();

    const store = harness.routeDebugElement!.injector.get(MessagesStore);
    expect(store.messages().map((message) => message.id)).toEqual(['msg-1']);
    expect(chipLabels(harness)).not.toContain('Navigational Warnings');
    expect(chipLabels(harness)).toContain('Print');
  });

  it('is the current list again once the address no longer names the publication', async () => {
    const harness = await openBrowsed();
    const store = harness.routeDebugElement!.injector.get(MessagesStore);

    // An address that no longer names the publication: the same page, with no query.
    await harness.navigateByUrl('/messages/details');
    await settle();
    harness.detectChanges();

    expect(harness.routeDebugElement!.injector.get(MessagesStore)).toBe(store);
    const reads = httpTesting.match(isMessagesRead);
    expect(reads).toHaveLength(1);
    expect(reads[0].request.params.has('publication')).toBe(false);
    expect(reads[0].request.params.get('areaId')).toBe('453');
    expect(reads[0].request.params.getAll('mainType')).toEqual(['NW', 'NM']);
    reads[0].flush([]);
    await settle();
    expect(chipLabels(harness)).toContain('Navigational Warnings');
  });

  it('names the publication it browses, with its document', async () => {
    const harness = await openBrowsed();
    const bar = harness.routeNativeElement!.querySelector('.filter-bar')!;

    expect(bar.querySelector('#browsed-publication-title')!.textContent!.trim()).toBe(
      'NtM 38 2026',
    );
    const download = [...bar.querySelectorAll('a')].find(
      (link) => link.textContent!.trim() === 'Download',
    );
    expect(download?.getAttribute('href')).toBe('/rest/repo/file/publications/x.pdf');
    expect(closeButton(harness)?.getAttribute('aria-describedby')).toBe(
      'browsed-publication-title',
    );
  });

  // The two reads resolve the id alike, so one failing alone is a passing failure or a
  // publication withdrawn between them: the list and the way out of it stay.
  it('lists the messages and offers the way out when the publication itself is not read', async () => {
    const harness = await RouterTestingHarness.create(BROWSE_URL);
    await settle();
    httpTesting.match(isMessagesRead).forEach((request) => request.flush(WEEK_38));
    httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/area/')).flush(ANCHORLAND);
    httpTesting
      .expectOne(isPublicationRead)
      .flush('no', { status: 500, statusText: 'Server Error' });
    await settle();
    harness.detectChanges();

    const store = harness.routeDebugElement!.injector.get(MessagesStore);
    expect(store.failed()).toBe(false);
    expect(store.messages().map((message) => message.id)).toEqual(['msg-1']);
    expect(harness.routeNativeElement!.querySelector('#browsed-publication-title')).toBeNull();
    expect(closeButton(harness)).not.toBeNull();
    expect(closeButton(harness)!.hasAttribute('aria-describedby')).toBe(false);
  });

  it('returns the same view to the current list when the publication is closed', async () => {
    const harness = await openBrowsed(`/messages/table?publicationId=${PUBLICATION_ID}`);
    const router = TestBed.inject(Router);

    closeButton(harness)!.click();
    // The navigation resolves on a later turn than the click; waiting for the page to be stable
    // would wait for the list read below, which nothing answers until it is flushed.
    await vi.waitFor(() => expect(router.url).toBe('/messages/table'));
    await settle();
    expect(router.url).toBe('/messages/table');

    const reads = httpTesting.match(isMessagesRead);
    expect(reads).toHaveLength(1);
    expect(reads[0].request.params.has('publication')).toBe(false);
    expect(reads[0].request.params.get('areaId')).toBe('453');
    expect(reads[0].request.params.getAll('mainType')).toEqual(['NW', 'NM']);
    reads[0].flush(WEEK_38);
    await settle();
    harness.detectChanges();
    expect(closeButton(harness)).toBeNull();
    expect(chipLabels(harness)).toContain('Navigational Warnings');
  });
});
