import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import {
  OneOffApiService,
  type OneOffPublication,
  type OneOffRequest,
} from './one-off-api.service';
import type { PublicationSeries } from './publication-series-api.service';

const API_BASE = '/rest';

/**
 * Every method this service exposes, and the test that pins it.
 *
 * The guard at the bottom walks the prototype against this list, so a method
 * added without a spec fails rather than shipping with its URL, its verb and its
 * body unchecked — which is the only thing standing between this file and a
 * request nobody has ever looked at.
 */
const PINNED_METHODS = ['list', 'get', 'create', 'update', 'setPublicWindow'] as const;

function series(over: Partial<PublicationSeries> = {}): PublicationSeries {
  return {
    seriesId: 'fyrliste',
    status: 'DRAFT',
    descs: [{ lang: 'en', name: 'Danish list of lights' }],
    cadence: 'NONE',
    kind: 'ONE_OFF',
    contentMode: 'EXTERNAL_LINK',
    domainId: 'niord-annex',
    languages: ['en'],
    ...over,
  };
}

function request(over: Partial<OneOffRequest> = {}): OneOffRequest {
  return {
    series: series(),
    active: true,
    links: [{ lang: 'en', value: 'https://example.test/doc.pdf' }],
    ...over,
  };
}

describe('OneOffApiService', () => {
  let service: OneOffApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(OneOffApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  /** The trailing slash is the backend path, not a typo — `@Path("/")` on the resource. */
  it('list GETs the collection path, trailing slash included, with no parameters', () => {
    service.list().subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush([]);
  });

  /**
   * One publication by id, and nothing narrows it.
   *
   * The page that uses this shows exactly one publication, so it reads exactly
   * one rather than the estate. No `domain` parameter: reads are unscoped, so a
   * deep link resolves from whichever desk the reader happens to be on, and the
   * server answers 404 for an id that is missing or is not a one-off.
   */
  it('get GETs the series id, with no parameters', () => {
    service.get('fyrliste').subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({});
  });

  /**
   * ISO on the wire, epoch milliseconds on the model.
   *
   * The public period is read as two numbers — compared against now, and against
   * each other. A string arriving unconverted is not a type error at runtime; it
   * is an answer, and every comparison it takes part in is the wrong one.
   */
  it('converts the ISO instants in the answer to epoch milliseconds', () => {
    let publication: OneOffPublication | undefined;
    service.get('fyrliste').subscribe((result) => (publication = result));
    httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste`).flush({
      series: series(),
      active: true,
      links: [],
      fileNames: [],
      publishable: true,
      publicFrom: '2018-12-31T23:00:00.000+00:00',
      publicTo: '2019-12-31T22:59:59.999+00:00',
    });

    expect(publication?.publicFrom).toBe(1_546_297_200_000);
    expect(publication?.publicTo).toBe(1_577_833_199_999);
  });

  it('get encodes an id that is not URL-safe into the path', () => {
    service.get('dansk fyrliste/2026').subscribe();
    httpTesting.expectOne(`${API_BASE}/one-off-publications/dansk%20fyrliste%2F2026`).flush({});
  });

  it('create POSTs the whole request to the collection path', () => {
    const body = request();
    service.create(body).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  /**
   * The reason travels in the body here, unlike the issue-level retire.
   *
   * `active: false` on a live publication retires the issue behind it, so the
   * transition and the reason it records are one request rather than a flip
   * followed by a second call that can fail on its own.
   */
  it('update PUTs to the series id, carrying the deactivation reason in the body', () => {
    const body = request({ active: false, reason: 'superseded by the 2027 edition' });
    service.update('fyrliste', body).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.params.keys()).toHaveLength(0);
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  /**
   * ONE revision for both rows, and it is the series'.
   *
   * A one-off is a series and its single issue edited through one form: they are
   * only ever written together, from here, so there is no second token for the
   * issue — it would be a second thing to get wrong with nothing to gain. It
   * rides inside the series rather than beside it, which is why nothing in this
   * service has a parameter for it.
   */
  it('update carries the series’ own revision, in the series', () => {
    const body = request({ series: series({ version: 5 }) });
    service.update('fyrliste', body).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste`);
    expect((req.request.body as OneOffRequest).series.version).toBe(5);
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({});
  });

  it('update encodes an id that is not URL-safe into the path', () => {
    service.update('dansk fyrliste/2026', request()).subscribe();
    httpTesting.expectOne(`${API_BASE}/one-off-publications/dansk%20fyrliste%2F2026`).flush({});
  });

  /**
   * The public period is set through its OWN endpoint, and BOTH ends travel.
   *
   * The period is one of the three independent facts that decide whether a
   * publication is on the public site, and it is hand-set at every point in the
   * publication's life. Sending both ends every time is what makes the call
   * describe the period rather than a delta against one the caller cannot see.
   */
  it('sets both ends of the public period through the public-window endpoint', () => {
    service.setPublicWindow('fyrliste', 1_700_000_000_000, 1_766_000_000_000, 5).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste/public-window`);
    expect(req.request.method).toBe('PUT');
    // The series' revision, the one revision a one-off is written against.
    expect(req.request.body).toEqual({
      publicFrom: 1_700_000_000_000,
      publicTo: 1_766_000_000_000,
      version: 5,
    });
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({});
  });

  /** `null` on the end is the open-ended period, not an omitted field. */
  it('clears the end of the period as an explicit null', () => {
    service.setPublicWindow('fyrliste', 1_700_000_000_000, null, 5).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste/public-window`);
    expect(req.request.body).toEqual({
      publicFrom: 1_700_000_000_000,
      publicTo: null,
      version: 5,
    });
    req.flush({});
  });

  /** And `null` on the start is a start not chosen yet — the server allows it while the issue is open. */
  it('sends a start nobody has chosen as an explicit null', () => {
    service.setPublicWindow('fyrliste', null, null, 5).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/one-off-publications/fyrliste/public-window`);
    expect(req.request.body).toEqual({ publicFrom: null, publicTo: null, version: 5 });
    req.flush({});
  });

  it('encodes an id that is not URL-safe into the public-window path', () => {
    service.setPublicWindow('dansk fyrliste/2026', null, null, 1).subscribe();
    httpTesting
      .expectOne(`${API_BASE}/one-off-publications/dansk%20fyrliste%2F2026/public-window`)
      .flush({});
  });

  /**
   * A method with no spec is a request nobody has checked.
   *
   * Reflection rather than a reviewer's memory: the list above is the contract,
   * and adding a method without adding it here is red before it is reviewed.
   * A private helper counts too — write it as a module function instead.
   */
  it('pins every method the service declares', () => {
    const declared = Object.getOwnPropertyNames(OneOffApiService.prototype).filter(
      (name) => name !== 'constructor',
    );
    expect(new Set(declared)).toEqual(new Set(PINNED_METHODS));
  });
});
