import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { PublicationRef } from '../../models/messages-filter-params.model';
import { PublicationsApiService, type PublicationSearchPage } from './publications-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  return {
    service: TestBed.inject(PublicationsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const isPicker = (req: HttpRequest<unknown>) => req.url === '/rest/publication-issues/picker';
const isLegacySearch = (req: HttpRequest<unknown>) => req.url === '/rest/publications/search';

/** An issue row as the picker endpoint emits it. */
function issueVo(publicId: string, over: Record<string, unknown> = {}) {
  return {
    publicId,
    publicationSeriesId: 'weekly-ntm',
    seriesNames: { en: 'Notices to Mariners', da: 'Efterretninger for Søfarende' },
    type: 'MESSAGE_REPORT',
    status: 'PUBLISHED',
    messagePublication: 'EXTERNAL',
    publicFrom: 1_700_000_000_000,
    languageSpecific: true,
    descs: [{ lang: 'en', title: `Issue ${publicId}` }],
    ...over,
  };
}

describe('PublicationsApiService.search', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  /**
   * ISO on the wire, epoch milliseconds on the row.
   *
   * `publicFrom` is a number everywhere it is read. The internal endpoints
   * serialise their instants as ISO 8601, so the string is turned into an
   * instant here or it reaches the chips and the filters as text that compares
   * false against every date they hold it up against.
   */
  it('converts an ISO instant on a picker row to epoch milliseconds', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('nav').subscribe((result) => (page = result));

    httpTesting
      .expectOne(isPicker)
      .flush({ data: [issueVo('iss-1', { publicFrom: '2019-12-31T22:59:59.999+00:00' })] });
    httpTesting.expectOne(isLegacySearch).flush({ data: [] });

    expect(page?.items[0].publicFrom).toBe(1_577_833_199_999);
  });

  it('asks both catalogues: the issue picker and the legacy search', () => {
    const { service, httpTesting } = setup();
    service.search('nav').subscribe();

    const picker = httpTesting.expectOne(isPicker);
    const legacy = httpTesting.expectOne(isLegacySearch);

    expect(picker.request.params.get('title')).toBe('nav');
    expect(legacy.request.params.get('title')).toBe('nav');

    picker.flush({ data: [], total: 0 });
    legacy.flush({ data: [], total: 0 });
  });

  it('defaults to PUBLISHED+OPEN on the picker and the same two statuses translated for the legacy half', () => {
    const { service, httpTesting } = setup();
    service.search('nav').subscribe();

    const picker = httpTesting.expectOne(isPicker);
    expect(picker.request.params.getAll('status')).toEqual(['PUBLISHED', 'OPEN']);
    expect(picker.request.params.get('type')).toBe('MESSAGE_REPORT');

    // The legacy endpoint types `status` as its own enum, which has neither
    // PUBLISHED nor OPEN — sending them untranslated fails parameter conversion
    // and breaks the picker for every user.
    const legacy = httpTesting.expectOne(isLegacySearch);
    expect(legacy.request.params.getAll('status')).toEqual(['ACTIVE', 'RECORDING']);
    expect(legacy.request.params.get('mainType')).toBe('PUBLICATION');

    picker.flush({ data: [], total: 0 });
    legacy.flush({ data: [], total: 0 });
  });

  it('the citation-shaped call sends PUBLISHED / ACTIVE, a channel, and NO type', () => {
    const { service, httpTesting } = setup();
    service
      .search('nav', { messagePublication: 'INTERNAL', type: null, statuses: ['PUBLISHED'] })
      .subscribe();

    const picker = httpTesting.expectOne(isPicker);
    expect(picker.request.params.has('type')).toBe(false);
    expect(picker.request.params.get('messagePublication')).toBe('INTERNAL');
    expect(picker.request.params.getAll('status')).toEqual(['PUBLISHED']);

    const legacy = httpTesting.expectOne(isLegacySearch);
    expect(legacy.request.params.has('type')).toBe(false);
    expect(legacy.request.params.getAll('status')).toEqual(['ACTIVE']);

    picker.flush({ data: [], total: 0 });
    legacy.flush({ data: [], total: 0 });
  });

  /**
   * The page size is `maxSize`, and nothing else.
   *
   * `limit` is not a parameter either endpoint reads. Sent under that name it is
   * ignored and the server falls back to its own default, so a caller asking for
   * more silently gets the cap and nothing anywhere says the list was cut.
   */
  it('asks for the page as maxSize + page, never as limit', () => {
    const { service, httpTesting } = setup();
    service.search('nav', { maxSize: 50, page: 2 }).subscribe();

    for (const req of [httpTesting.expectOne(isPicker), httpTesting.expectOne(isLegacySearch)]) {
      expect(req.request.params.get('maxSize')).toBe('50');
      expect(req.request.params.get('page')).toBe('2');
      expect(req.request.params.has('limit')).toBe(false);
      req.flush({ data: [], total: 0 });
    }
  });

  /** A caller that names no domain gets the whole catalogue, as a script would. */
  it('scopes neither half when no domain is asked for, and sends no lang either', () => {
    const { service, httpTesting } = setup();
    service.search('nav').subscribe();

    for (const req of [httpTesting.expectOne(isPicker), httpTesting.expectOne(isLegacySearch)]) {
      expect(req.request.params.has('domain')).toBe(false);
      expect(req.request.params.has('lang')).toBe(false);
      req.flush({ data: [], total: 0 });
    }
  });

  /**
   * `domain` is a VISIBLE-FROM filter, and it goes to BOTH halves.
   *
   * A publication reaches the picker where it is owned and wherever its owner has
   * shared it, which is what keeps the citation-only publications in every desk's
   * picker while keeping one desk's weekly notices out of the others'. The legacy
   * half applies the same rule through the series its rows were imported into,
   * so sending it to only one half would offer two different catalogues.
   */
  it('sends the caller’s domain to both halves', () => {
    const { service, httpTesting } = setup();
    service.search('nav', { domain: 'niord-nm' }).subscribe();

    for (const req of [httpTesting.expectOne(isPicker), httpTesting.expectOne(isLegacySearch)]) {
      expect(req.request.params.get('domain')).toBe('niord-nm');
      req.flush({ data: [], total: 0 });
    }
  });

  /** A null domain is a caller declining to scope, not a blank one to send. */
  it('omits the domain entirely when the caller passes null', () => {
    const { service, httpTesting } = setup();
    service.search('nav', { domain: null }).subscribe();

    for (const req of [httpTesting.expectOne(isPicker), httpTesting.expectOne(isLegacySearch)]) {
      expect(req.request.params.has('domain')).toBe(false);
      req.flush({ data: [], total: 0 });
    }
  });

  it('an empty query lists the catalogue — it sends no `title` rather than no request', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('').subscribe((p) => (page = p));

    const picker = httpTesting.expectOne(isPicker);
    const legacy = httpTesting.expectOne(isLegacySearch);
    expect(picker.request.params.has('title')).toBe(false);
    expect(legacy.request.params.has('title')).toBe(false);

    picker.flush({ data: [], total: 0 });
    legacy.flush({ data: [], total: 0 });
    expect(page?.items).toEqual([]);
  });

  it('merges issues first and drops the legacy row that collides with one', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('efs').subscribe((p) => (page = p));

    // An imported issue reuses its legacy row's id, so both halves answer with it.
    httpTesting
      .expectOne(isPicker)
      .flush({ data: [issueVo('shared'), issueVo('new-one')], total: 2 });
    httpTesting.expectOne(isLegacySearch).flush({
      data: [
        { publicationId: 'shared', descs: [{ lang: 'en', title: 'Legacy twin' }] },
        { publicationId: 'legacy-only', descs: [{ lang: 'en', title: 'Only in the old half' }] },
      ],
      total: 12,
    });

    expect(page?.items.map((p) => p.publicationId)).toEqual(['shared', 'new-one', 'legacy-only']);
    // The issue half won the collision, so the row carries a status.
    expect(page?.items[0].status).toBe('PUBLISHED');
    expect(page?.items[0].descs?.[0].title).toBe('Issue shared');
    // The legacy endpoint merges the issues into its own answer, so the larger of
    // the two totals is the one that counted both halves — never their sum.
    expect(page?.total).toBe(12);
  });

  it('caps the merged list at maxSize, after merging rather than per half', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('efs', { maxSize: 2 }).subscribe((p) => (page = p));

    httpTesting.expectOne(isPicker).flush({ data: [issueVo('i1'), issueVo('i2')], total: 2 });
    httpTesting
      .expectOne(isLegacySearch)
      .flush({ data: [{ publicationId: 'l1', descs: [] }], total: 1 });

    expect(page?.items.map((p) => p.publicationId)).toEqual(['i1', 'i2']);
    expect(page?.total).toBe(3);
  });

  it('maps the picker payload onto the shape the rest of the app reads', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('efs').subscribe((p) => (page = p));

    httpTesting.expectOne(isPicker).flush({
      data: [
        issueVo('p1', {
          type: 'LINK',
          status: 'RETIRED',
          languageSpecific: false,
          descs: [
            {
              lang: 'da',
              title: 'Vragliste',
              link: 'https://example.test/vrag',
              messagePublicationFormat: 'VragID ${parameters}',
            },
          ],
        }),
      ],
      total: 1,
    });
    httpTesting.expectOne(isLegacySearch).flush({ data: [], total: 0 });

    expect(page?.items[0]).toEqual({
      publicationId: 'p1',
      publicationSeriesId: 'weekly-ntm',
      // Every language's name, carried through untouched: the picker sends no
      // `lang`, so the endpoint leaves the map unfiltered and the field resolves
      // its own fallback rather than rendering a blank for a Danish-only series.
      seriesNames: { en: 'Notices to Mariners', da: 'Efterretninger for Søfarende' },
      type: 'LINK',
      mainType: 'PUBLICATION',
      status: 'RETIRED',
      messagePublication: 'EXTERNAL',
      publicFrom: 1_700_000_000_000,
      languageSpecific: false,
      descs: [
        {
          lang: 'da',
          title: 'Vragliste',
          link: 'https://example.test/vrag',
          messagePublicationFormat: 'VragID ${parameters}',
        },
      ],
    } satisfies PublicationRef);
  });

  it('one refusing catalogue does not empty the picker; only a refusal on both is a failure', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('efs').subscribe((p) => (page = p));

    httpTesting.expectOne(isPicker).flush('nope', { status: 500, statusText: 'Server Error' });
    httpTesting
      .expectOne(isLegacySearch)
      .flush({ data: [{ publicationId: 'l1', descs: [] }], total: 1 });

    expect(page?.items.map((p) => p.publicationId)).toEqual(['l1']);
  });

  it('reports a failure when neither catalogue answers', () => {
    const { service, httpTesting } = setup();
    let failed = false;
    service.search('efs').subscribe({ error: () => (failed = true) });

    httpTesting.expectOne(isPicker).flush('nope', { status: 500, statusText: 'Server Error' });
    httpTesting
      .expectOne(isLegacySearch)
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(failed).toBe(true);
  });

  /** The mirror of the case above: the OLD half is the one that is down. */
  it('keeps the picker working when the legacy catalogue is the half that refuses', () => {
    const { service, httpTesting } = setup();
    let page: PublicationSearchPage | undefined;
    service.search('efs').subscribe((p) => (page = p));

    httpTesting.expectOne(isPicker).flush({ data: [issueVo('i1')], total: 1 });
    httpTesting
      .expectOne(isLegacySearch)
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(page?.items.map((p) => p.publicationId)).toEqual(['i1']);
  });

  /** Both halves are reads. A verb nobody asserts is a verb that can drift. */
  it('reads both catalogues with GET', () => {
    const { service, httpTesting } = setup();
    service.search('efs').subscribe();

    for (const req of [httpTesting.expectOne(isPicker), httpTesting.expectOne(isLegacySearch)]) {
      expect(req.request.method).toBe('GET');
      req.flush({ data: [], total: 0 });
    }
  });

  /**
   * The language parameter is `lang` on both halves, never `language`.
   *
   * The pickers leave it unset — a row whose only desc is in another language
   * would come back with no title at all — but the per-message link override
   * asks for one, so the name it travels under has to be pinned.
   */
  it('sends a chosen language as `lang` on both halves', () => {
    const { service, httpTesting } = setup();
    service.search('efs', { language: 'da' }).subscribe();

    for (const req of [httpTesting.expectOne(isPicker), httpTesting.expectOne(isLegacySearch)]) {
      expect(req.request.params.get('lang')).toBe('da');
      expect(req.request.params.has('language')).toBe(false);
      req.flush({ data: [], total: 0 });
    }
  });

  /**
   * The third legacy status, and the one no other test reaches.
   *
   * `INACTIVE` is what the old enum calls a withdrawn publication; sending
   * `RETIRED` there fails parameter conversion, so the branch that translates it
   * has to be exercised rather than assumed from the other two.
   */
  it('translates RETIRED into the legacy INACTIVE, and passes an unknown token through', () => {
    const { service, httpTesting } = setup();
    service.search('efs', { statuses: ['RETIRED', 'SOMETHING_NEW'] }).subscribe();

    const picker = httpTesting.expectOne(isPicker);
    expect(picker.request.params.getAll('status')).toEqual(['RETIRED', 'SOMETHING_NEW']);

    const legacy = httpTesting.expectOne(isLegacySearch);
    expect(legacy.request.params.getAll('status')).toEqual(['INACTIVE', 'SOMETHING_NEW']);

    picker.flush({ data: [], total: 0 });
    legacy.flush({ data: [], total: 0 });
  });
});

describe('PublicationsApiService.getByIds', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('hydrates through the issue lookup and asks the legacy one only for what is left', () => {
    const { service, httpTesting } = setup();
    let received: readonly PublicationRef[] | undefined;
    service.getByIds(['iss-1', 'old-2']).subscribe((r) => (received = r));

    httpTesting.expectNone(isPicker);
    const byIds = httpTesting.expectOne(
      (req) =>
        req.url === '/rest/publication-issues/by-ids' && req.params.get('ids') === 'iss-1,old-2',
    );
    // A chip has to render whatever it points at, so no status narrowing is
    // applied — and no domain narrowing either: a citation that exists must
    // resolve from wherever it is being read, however it was shared.
    expect(byIds.request.params.has('status')).toBe(false);
    expect(byIds.request.params.has('domain')).toBe(false);
    byIds.flush([issueVo('iss-1')]);

    const legacy = httpTesting.expectOne('/rest/publications/publication/old-2');
    legacy.flush([{ publicationId: 'old-2', descs: [{ lang: 'en', title: 'Old' }] }]);

    expect(received?.map((p) => p.publicationId)).toEqual(['iss-1', 'old-2']);
  });

  it('hydrates a retired issue rather than dropping it, and keeps the requested order', () => {
    const { service, httpTesting } = setup();
    let received: readonly PublicationRef[] | undefined;
    service.getByIds(['b', 'a']).subscribe((r) => (received = r));

    httpTesting
      .expectOne((req) => req.url === '/rest/publication-issues/by-ids')
      .flush([issueVo('a'), issueVo('b', { status: 'RETIRED' })]);

    expect(received?.map((p) => p.publicationId)).toEqual(['b', 'a']);
    expect(received?.[0].status).toBe('RETIRED');
    expect(received?.[0].descs?.[0].title).toBe('Issue b');
  });

  it('skips the legacy lookup entirely when every id resolved as an issue', () => {
    const { service, httpTesting } = setup();
    service.getByIds(['a']).subscribe();

    httpTesting
      .expectOne((req) => req.url === '/rest/publication-issues/by-ids')
      .flush([issueVo('a')]);
    httpTesting.verify();
  });

  it('falls back to the legacy lookup for EVERY id when the issue lookup refuses', () => {
    const { service, httpTesting } = setup();
    let received: readonly PublicationRef[] | undefined;
    service.getByIds(['a', 'b']).subscribe((r) => (received = r));

    httpTesting
      .expectOne((req) => req.url === '/rest/publication-issues/by-ids')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    httpTesting
      .expectOne('/rest/publications/publication/a,b')
      .flush([{ publicationId: 'a', descs: [] }]);

    expect(received?.map((p) => p.publicationId)).toEqual(['a']);
  });

  it('an empty id list short-circuits without a request', () => {
    const { service, httpTesting } = setup();
    let received: readonly PublicationRef[] | undefined;
    service.getByIds([]).subscribe((r) => (received = r));
    httpTesting.verify();
    expect(received).toEqual([]);
  });

  it('hydrates both catalogues with GET', () => {
    const { service, httpTesting } = setup();
    service.getByIds(['a']).subscribe();

    const byIds = httpTesting.expectOne((req) => req.url === '/rest/publication-issues/by-ids');
    expect(byIds.request.method).toBe('GET');
    byIds.flush([]);

    const legacy = httpTesting.expectOne('/rest/publications/publication/a');
    expect(legacy.request.method).toBe('GET');
    legacy.flush([]);
  });

  /**
   * The ids go into the PATH on the legacy lookup, so each has to be encoded.
   *
   * A publication id is free text — the imported ones carry spaces and slashes —
   * and an unencoded slash makes the server read one id as two path segments and
   * answer 404 for a citation that exists.
   */
  it('encodes each id into the legacy path, and keeps the comma that separates them', () => {
    const { service, httpTesting } = setup();
    service.getByIds(['efs uge/27', 'plain']).subscribe();

    httpTesting.expectOne((req) => req.url === '/rest/publication-issues/by-ids').flush([]);
    httpTesting.expectOne('/rest/publications/publication/efs%20uge%2F27,plain').flush([]);
  });

  /**
   * A failing legacy lookup must not blank the issues that already resolved.
   *
   * One dead citation cannot be allowed to empty the four beside it — which is
   * the whole reason the two halves keep their failures rather than throwing.
   */
  it('keeps the resolved issues when the legacy lookup refuses', () => {
    const { service, httpTesting } = setup();
    let received: readonly PublicationRef[] | undefined;
    service.getByIds(['iss-1', 'old-2']).subscribe((r) => (received = r));

    httpTesting
      .expectOne((req) => req.url === '/rest/publication-issues/by-ids')
      .flush([issueVo('iss-1')]);
    httpTesting
      .expectOne('/rest/publications/publication/old-2')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(received?.map((p) => p.publicationId)).toEqual(['iss-1']);
  });

  it('reports a failure when neither hydration answers', () => {
    const { service, httpTesting } = setup();
    let failed = false;
    service.getByIds(['a']).subscribe({ error: () => (failed = true) });

    httpTesting
      .expectOne((req) => req.url === '/rest/publication-issues/by-ids')
      .flush('nope', { status: 500, statusText: 'Server Error' });
    httpTesting
      .expectOne('/rest/publications/publication/a')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(failed).toBe(true);
  });

  /** An id the issue half resolved nothing for is simply left out of the answer. */
  it('omits an id that neither catalogue knows', () => {
    const { service, httpTesting } = setup();
    let received: readonly PublicationRef[] | undefined;
    service.getByIds(['known', 'gone']).subscribe((r) => (received = r));

    httpTesting
      .expectOne((req) => req.url === '/rest/publication-issues/by-ids')
      .flush([issueVo('known')]);
    httpTesting.expectOne('/rest/publications/publication/gone').flush([]);

    expect(received?.map((p) => p.publicationId)).toEqual(['known']);
  });
});
