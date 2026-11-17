import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, InjectionToken, effect, inject, signal, untracked } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { PublicationsFacade } from './publications.facade';

const URL = '/rest/publication-series/search-details';
const TIMELINE = '/rest/publication-issues/recent';

/** The desk-scoped form, which is what a session domain makes the facade ask. */
const DESK_TIMELINE = '/rest/publication-issues/recent-by-domain';

/** The strip request the facade makes for the series it names itself. */
function timelineRequest(httpTesting: HttpTestingController) {
  return httpTesting.expectOne((request) => request.url === TIMELINE);
}

/** No cadenced series means no strip request at all, so nothing is outstanding. */
function noTimelineRequest(httpTesting: HttpTestingController) {
  expect(httpTesting.match((request) => request.url === TIMELINE)).toEqual([]);
  expect(httpTesting.match((request) => request.url === DESK_TIMELINE)).toEqual([]);
}

/** The desk-scoped read, which answers for both kinds of series at once. */
function deskTimelineRequests(httpTesting: HttpTestingController) {
  return httpTesting.match((request) => request.url === DESK_TIMELINE);
}

/**
 * Settle the desk-scoped strip read.
 *
 * With a session domain it goes out BESIDE the list rather than after it, so it
 * is outstanding from the first tick and every scoped test has to answer it
 * before `verify()` runs.
 */
function flushDeskTimelines(httpTesting: HttpTestingController, body: readonly object[] = []) {
  const requests = deskTimelineRequests(httpTesting);
  expect(requests.length).toBe(1);
  requests[0].flush([...body]);
}

function series(over: Record<string, unknown> = {}) {
  return {
    seriesId: 's-' + Math.random().toString(36).slice(2, 8),
    status: 'ACTIVE',
    descs: [{ lang: 'en', name: 'A series' }],
    cadence: 'WEEKLY',
    kind: 'SCHEDULED',
    contentMode: 'GENERATED_FROM_QUERY',
    languages: ['en'],
    ...over,
  };
}

/**
 * The desk the admin is sitting at, which the list is scoped to.
 *
 * Undefined by default so the assertions below stay about the grouping they
 * name: with no session domain the list is unscoped and the URL carries no
 * parameter, which is the shape every existing expectation was written against.
 * The two tests that ARE about the scoping set it themselves.
 */
const sessionDomain = signal<Domain | undefined>(undefined);

function setup(domainId?: string) {
  sessionDomain.set(domainId ? ({ domainId } as Domain) : undefined);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      // Stubbed rather than real: the live facade reaches AuthFacade -> Keycloak,
      // which a spec about grouping has no business booting.
      { provide: DomainFacade, useValue: { sessionDomain } as unknown as DomainFacade },
      PublicationsFacade,
    ],
  });
  const facade = TestBed.inject(PublicationsFacade);
  // toObservable() emits through an effect — flush it so the initial fetch fires.
  TestBed.tick();
  return { facade, httpTesting: TestBed.inject(HttpTestingController) };
}

describe('PublicationsFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the whole admin list on init', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);

    httpTesting.expectOne(URL).flush([series()]);

    expect(facade.loading()).toBe(false);
    expect(facade.series().length).toBe(1);
    expect(facade.error()).toBe(false);
  });

  /**
   * The list is one desk's, and `domain` is an OWNER filter.
   *
   * Not a visibility one: a publication another domain has shared with this one
   * is a citation target in the message editor, and listing it here would offer
   * settings, activation and issue creation the server refuses.
   */
  it('asks for the session domain’s own publications', () => {
    const { httpTesting } = setup('niord-nm');

    const request = httpTesting.expectOne((r) => r.url === URL);
    expect(request.request.params.get('domain')).toBe('niord-nm');
    request.flush([]);
    flushDeskTimelines(httpTesting);
  });

  /** Switching desk is a different list, so it is re-asked rather than filtered. */
  it('re-asks when the session domain changes', () => {
    const { httpTesting } = setup('niord-nm');
    httpTesting.expectOne((r) => r.url === URL).flush([series()]);
    flushDeskTimelines(httpTesting);

    sessionDomain.set({ domainId: 'niord-annex' } as Domain);
    TestBed.tick();

    const request = httpTesting.expectOne((r) => r.url === URL);
    expect(request.request.params.get('domain')).toBe('niord-annex');
    request.flush([]);

    // The strips are the desk's too, so they follow it rather than the list.
    const strips = deskTimelineRequests(httpTesting);
    expect(strips.length).toBe(1);
    expect(strips[0].request.params.get('domain')).toBe('niord-annex');
    strips[0].flush([]);
  });

  /**
   * The strips do not wait to be told which series the desk owns.
   *
   * The server picks them by the same rule the cards are grouped by, so the two
   * questions are independent — and keying the strip on the list made the
   * slowest request on the dashboard start only once the list had answered.
   *
   * ONE request, and it carries the count each kind of card needs: the periods a
   * scheduled series' strip draws, and how far back a cadence-less series'
   * archive summary counts. Asked as two requests split by a `cadenced` flag,
   * the dashboard paid a second round trip for an answer the server assembles
   * from the same scan.
   */
  it('asks the desk for both kinds of strip in one request, without waiting for the list', () => {
    const { httpTesting } = setup('niord-nm');

    // Read while the list is still in flight: nothing has answered it yet.
    const list = httpTesting.expectOne((r) => r.url === URL);
    const strips = deskTimelineRequests(httpTesting);
    expect(strips.length).toBe(1);

    const params = strips[0].request.params;
    expect(params.get('domain')).toBe('niord-nm');
    expect(params.get('periods')).toBe('8');
    expect(params.get('unscheduledIssues')).toBe('100');
    expect(params.get('lang')).toBe('en');
    // The split flag is gone: one answer covers both kinds.
    expect(params.has('cadenced')).toBe(false);
    // And no named request at all: the desk is the whole key.
    expect(httpTesting.match((r) => r.url === TIMELINE)).toEqual([]);

    list.flush([series({ seriesId: 'weekly' })]);
    strips[0].flush([]);
  });

  /**
   * One answer feeds every card, whichever kind it is.
   *
   * The cards look a strip up by series id and neither know nor care which
   * question produced it — so the read that answers both has to land in the one
   * lookup, or the cadence-less cards go blank the moment the two reads merge.
   */
  it('feeds both the scheduled and the cadence-less cards from the one desk read', () => {
    const { facade, httpTesting } = setup('niord-nm');
    httpTesting
      .expectOne((r) => r.url === URL)
      .flush([
        series({ seriesId: 'weekly' }),
        series({ seriesId: 'ncags', cadence: 'NONE', kind: 'UNSCHEDULED' }),
      ]);

    flushDeskTimelines(httpTesting, [
      {
        publicationSeriesId: 'weekly',
        rows: [{ week: 33, computedStatus: 'PUBLISHED', publicId: 'p-1' }],
        gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
      },
      {
        publicationSeriesId: 'ncags',
        rows: [{ label: 'NCAGS 2026', computedStatus: 'PUBLISHED', publicId: 'p-11' }],
        gapDetection: { enabled: false, reasonCode: 'NO_CADENCE', reason: '' },
      },
    ]);

    expect(facade.timeline('weekly')?.rows.length).toBe(1);
    expect(facade.timeline('ncags')?.rows.length).toBe(1);
    expect(facade.timelineError()).toBe(false);
  });

  /** The desk read failing is still "the recent periods could not be read". */
  it('records a failed desk read without failing the list', () => {
    const { facade, httpTesting } = setup('niord-nm');
    httpTesting.expectOne((r) => r.url === URL).flush([series({ seriesId: 'weekly' })]);

    const strips = deskTimelineRequests(httpTesting);
    expect(strips.length).toBe(1);
    strips[0].flush('nope', { status: 500, statusText: 'Server Error' });

    expect(facade.timelineError()).toBe(true);
    expect(facade.error()).toBe(false);
    expect(facade.cadenced().length).toBe(1);
  });

  /**
   * A desk that owns nothing with a calendar still asks.
   *
   * The named form can tell in advance that there is nothing to ask about; the
   * desk-scoped one cannot, because which series the desk owns is the very thing
   * it went to find out. The read comes back empty at the request floor, which
   * is the price of not waiting for the list.
   */
  it('asks the desk even where it turns out to own no cadenced series', () => {
    const { facade, httpTesting } = setup('niord-nm');
    httpTesting
      .expectOne((r) => r.url === URL)
      .flush([series({ seriesId: 'gone', status: 'RETIRED' })]);

    flushDeskTimelines(httpTesting);

    expect(facade.timeline('gone')).toBeUndefined();
    expect(facade.timelineError()).toBe(false);
  });

  /**
   * The dashboard's data is the dashboard's.
   *
   * Master and detail share one component, so nothing tears this facade down on
   * the way into a series — and neither the series screen nor an issue opened
   * inside it reads the list or a strip. Ungated, opening one paid for both
   * again on every visit.
   */
  it('asks for nothing while a series is open', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);

    facade.setDashboardShown(false);
    TestBed.tick();

    httpTesting.expectNone(URL);
    noTimelineRequest(httpTesting);
    expect(facade.loading()).toBe(false);
  });

  /** And it is read again on the way back, because anything may have changed it. */
  it('asks again when the dashboard comes back', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);

    facade.setDashboardShown(false);
    TestBed.tick();
    facade.setDashboardShown(true);
    TestBed.tick();

    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);
  });

  it('a failed fetch sets the error flag (no silent failure)', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush('nope', { status: 500, statusText: 'Server Error' });

    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
    expect(facade.series().length).toBe(0);
  });

  /**
   * A DRAFT series is listed whatever carried it in.
   *
   * Provenance is not a group. An imported DRAFT series filed in a group of its
   * own had no other route to it — and its status badge already says what it is.
   */
  it('lists an imported DRAFT series with the rest, one-offs excepted', () => {
    const { facade, httpTesting } = setup();

    httpTesting.expectOne(URL).flush([
      series({ seriesId: 'live-weekly', cadence: 'WEEKLY' }),
      series({ seriesId: 'live-oneoff', cadence: 'NONE', kind: 'ONE_OFF' }),
      series({
        seriesId: 'imported-weekly',
        cadence: 'WEEKLY',
        status: 'DRAFT',
        importSource: 'legacy-publications',
      }),
      series({
        seriesId: 'imported-oneoff',
        cadence: 'NONE',
        kind: 'ONE_OFF',
        status: 'DRAFT',
        importSource: 'legacy-publications',
      }),
    ]);

    expect(
      facade
        .cadenced()
        .map((s) => s.seriesId)
        .sort(),
    ).toEqual(['imported-weekly', 'live-weekly']);

    // Neither one-off appears anywhere: both are listed by the dashboard from
    // the one-off endpoint, and a publication in two places reads as two.
    expect(facade.retired()).toEqual([]);
  });

  /**
   * EVERY SERIES IS IN EXACTLY ONE GROUP.
   *
   * The groups partition the list rather than filtering it, so a series matching
   * no group is a series with no route to it at all. Counted rather than named
   * one by one, because the failure that matters is a row falling through the
   * gaps rather than a row in the wrong place.
   */
  it('files every series it loaded into exactly one group', () => {
    const { facade, httpTesting } = setup();

    const loaded = [
      series({ seriesId: 'active', status: 'ACTIVE' }),
      series({ seriesId: 'mine', status: 'DRAFT' }),
      series({ seriesId: 'carried-in', status: 'DRAFT', importSource: 'legacy-publications' }),
      series({ seriesId: 'finished', status: 'RETIRED' }),
      series({
        seriesId: 'finished-import',
        status: 'RETIRED',
        importSource: 'legacy-publications',
      }),
    ];
    httpTesting.expectOne(URL).flush(loaded);

    const grouped = [...facade.cadenced(), ...facade.retired()].map((s) => s.seriesId);
    expect(grouped.length).toBe(loaded.length);
    expect(new Set(grouped).size).toBe(loaded.length);
  });

  /**
   * The split is the KIND, never the cadence.
   *
   * Three real series have no cadence and are unmistakably series: eleven NCAGS
   * editions, eight ice-service notices and four editions of Dansk Fyrliste.
   * Reading cadence NONE as "one-off" filed an eleven-issue series under
   * one-offs, which is the bug this partition exists to prevent.
   */
  it('keeps cadence-less SERIES in the series group, and one-offs out of it', () => {
    const { facade, httpTesting } = setup();

    httpTesting
      .expectOne(URL)
      .flush([
        series({ seriesId: 'weekly' }),
        series({ seriesId: 'ncags', cadence: 'NONE', kind: 'UNSCHEDULED' }),
        series({ seriesId: 'once', cadence: 'NONE', kind: 'ONE_OFF' }),
      ]);

    expect(facade.cadenced().map((s) => s.seriesId)).toEqual(['weekly', 'ncags']);
  });

  /**
   * The cards are read in the order the work arrives in.
   *
   * The server answers in its own order, which put the once-a-year publication
   * ahead of the two weeklies an editor works every week — so the card that is
   * due next was the last one on the row. Frequency first, then the name, so
   * two series of the same cadence keep a stable place a reader can learn.
   */
  it('orders the cards by how often the series comes out, then by name', () => {
    const { facade, httpTesting } = setup();

    httpTesting.expectOne(URL).flush([
      series({
        seriesId: 'annual',
        cadence: 'YEARLY',
        descs: [{ lang: 'en', name: 'Annual NtM' }],
      }),
      series({
        seriesId: 'ncags',
        cadence: 'NONE',
        kind: 'UNSCHEDULED',
        descs: [{ lang: 'en', name: 'NCAGS' }],
      }),
      series({ seriesId: 'p-t', cadence: 'WEEKLY', descs: [{ lang: 'en', name: 'Weekly P&T' }] }),
      series({ seriesId: 'ntm', cadence: 'WEEKLY', descs: [{ lang: 'en', name: 'Weekly NtM' }] }),
      series({ seriesId: 'daily', cadence: 'DAILY', descs: [{ lang: 'en', name: 'Daily list' }] }),
    ]);

    expect(facade.cadenced().map((s) => s.seriesId)).toEqual([
      'daily',
      'ntm',
      'p-t',
      'annual',
      'ncags',
    ]);
  });

  /** A cadence this build does not know sorts last rather than jumping the queue. */
  it('files an unrecognised cadence with the ones that have no calendar', () => {
    const { facade, httpTesting } = setup();

    httpTesting
      .expectOne(URL)
      .flush([
        series({ seriesId: 'odd', cadence: 'FORTNIGHTLY' }),
        series({ seriesId: 'weekly', cadence: 'WEEKLY' }),
      ]);

    expect(facade.cadenced().map((s) => s.seriesId)).toEqual(['weekly', 'odd']);
  });

  /**
   * THE GROUP IS THE STATUS, NEVER THE PROVENANCE.
   *
   * Every real series in this estate was imported, so keying the split on
   * `importSource` showed three retired development probes while `weekly-ntm`
   * -- ACTIVE, the one series actually producing issues -- sat collapsed
   * somewhere else.
   */
  it('files an imported series by its status, never by where it came from', () => {
    const { facade, httpTesting } = setup();

    httpTesting
      .expectOne(URL)
      .flush([
        series({ seriesId: 'weekly-ntm', status: 'ACTIVE', importSource: 'legacy-publications' }),
        series({ seriesId: 'still-draft', status: 'DRAFT', importSource: 'legacy-publications' }),
        series({ seriesId: 'done', status: 'RETIRED', importSource: 'legacy-publications' }),
      ]);

    expect(
      facade
        .cadenced()
        .map((s) => s.seriesId)
        .sort(),
    ).toEqual(['still-draft', 'weekly-ntm']);
    expect(facade.retired().map((s) => s.seriesId)).toEqual(['done']);
  });

  /**
   * Retired series are collapsed away from the live list.
   *
   * Three retired probes were the entire visible content of the Series section.
   */
  it('keeps retired series out of the live list', () => {
    const { facade, httpTesting } = setup();

    httpTesting
      .expectOne(URL)
      .flush([
        series({ seriesId: 'live', status: 'ACTIVE' }),
        series({ seriesId: 'probe', status: 'RETIRED' }),
      ]);

    expect(facade.cadenced().map((s) => s.seriesId)).toEqual(['live']);
    expect(facade.retired().map((s) => s.seriesId)).toEqual(['probe']);
  });

  /** A hand-authored DRAFT is work in progress, and belongs with the live series. */
  it('keeps a hand-authored draft in the live list', () => {
    const { facade, httpTesting } = setup();

    httpTesting.expectOne(URL).flush([series({ seriesId: 'mine', status: 'DRAFT' })]);

    expect(facade.cadenced().map((s) => s.seriesId)).toEqual(['mine']);
  });

  it('refresh() refetches and shows the loading shell again', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([]);

    facade.refresh();
    TestBed.tick();
    expect(facade.loading()).toBe(true);

    httpTesting.expectOne(URL).flush([series()]);
    expect(facade.series().length).toBe(1);
  });

  /**
   * ONE request for the whole page, not one per card.
   *
   * Built per series this is roughly sixty requests on the production estate,
   * and it still could not draw a period with no issue in it: gap synthesis
   * needs exactly one named series, which a mixed-series list cannot give.
   */
  it('asks for every visible series’ strip in a single request', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne(URL)
      .flush([series({ seriesId: 'weekly-ntm' }), series({ seriesId: 'weekly-p-t' })]);
    TestBed.tick();

    const request = timelineRequest(httpTesting);
    expect(request.request.params.get('publicationSeriesId')).toBe('weekly-ntm,weekly-p-t');
    expect(request.request.params.get('periods')).toBe('8');
    expect(request.request.params.get('lang')).toBe('en');

    request.flush([
      {
        publicationSeriesId: 'weekly-ntm',
        rows: [{ week: 33, computedStatus: 'PUBLISHED', publicId: 'p-1' }],
        gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
      },
    ]);

    expect(facade.timeline('weekly-ntm')?.rows.length).toBe(1);
    // Asked for and not answered for: absent, rather than an empty strip that
    // would read as a series with no recent periods at all.
    expect(facade.timeline('weekly-p-t')).toBeUndefined();
    expect(facade.timelineError()).toBe(false);
  });

  /**
   * The endpoint answers for at most fifty named series and refuses more, so a
   * page showing more than that asks for the fifty it can have rather than
   * losing every strip to one refusal.
   */
  it('never names more series than the endpoint accepts', () => {
    const { httpTesting } = setup();
    httpTesting
      .expectOne(URL)
      .flush(Array.from({ length: 60 }, (_, i) => series({ seriesId: `s-${i}` })));
    TestBed.tick();

    const named = timelineRequest(httpTesting).request.params.get('publicationSeriesId');
    expect(named?.split(',').length).toBe(50);
    expect(named?.startsWith('s-0,s-1,')).toBe(true);
  });

  /** Nothing to decorate, nothing to ask: the endpoint refuses an empty list. */
  it('does not ask for a strip when no series is on show', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'gone', status: 'RETIRED' })]);
    TestBed.tick();

    noTimelineRequest(httpTesting);
    expect(facade.timeline('gone')).toBeUndefined();
  });

  /**
   * The strips decorate a page that works without them.
   *
   * A failed strip request must not take the series list down with it — but it
   * is not swallowed either, because cards silently missing their timelines look
   * exactly like series that have never released anything.
   */
  it('records a failed strip request without failing the list', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();

    timelineRequest(httpTesting).flush('nope', { status: 500, statusText: 'Server Error' });

    expect(facade.timelineError()).toBe(true);
    expect(facade.error()).toBe(false);
    expect(facade.cadenced().length).toBe(1);
  });

  /**
   * Re-asked on a refresh, because publishing an issue moves a cell without
   * adding or removing a series — so the set of named series is unchanged and
   * the strip would otherwise keep showing the state before the release.
   */
  it('re-asks for the strips when the page is refreshed', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);

    facade.refresh();
    TestBed.tick();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();

    TestBed.tick();
    timelineRequest(httpTesting).flush([]);
  });

  /** An empty estate is not an error, and the page has to tell them apart. */
  it('reports empty separately from error', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([]);

    expect(facade.isEmpty()).toBe(true);
    expect(facade.error()).toBe(false);
  });

  /**
   * The two reads answer different questions, and the second is what makes the
   * unscheduled card's count true.
   *
   * A cadence-less series has no periods, so the endpoint returns its most
   * recent ISSUES and truncates them to whatever was asked for. Asked at the
   * strip's eight, an eleven-edition series would report eight — not a shorter
   * answer but a wrong one.
   */
  it('asks for the strips and the cadence-less archives separately', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne(URL)
      .flush([
        series({ seriesId: 'weekly' }),
        series({ seriesId: 'ncags', cadence: 'NONE', kind: 'UNSCHEDULED' }),
      ]);
    TestBed.tick();

    const requests = httpTesting.match((request) => request.url === TIMELINE);
    expect(requests.length).toBe(2);
    const asked = new Map(
      requests.map((request) => [
        request.request.params.get('periods'),
        request.request.params.get('publicationSeriesId'),
      ]),
    );
    expect(asked.get('8')).toBe('weekly');
    expect(asked.get('100')).toBe('ncags');

    for (const request of requests) {
      request.flush(
        request.request.params.get('periods') === '8'
          ? []
          : [
              {
                publicationSeriesId: 'ncags',
                rows: [{ label: 'NCAGS 2026', computedStatus: 'PUBLISHED', publicId: 'p-11' }],
                gapDetection: { enabled: false, reasonCode: 'NO_CADENCE', reason: '' },
              },
            ],
      );
    }

    // One lookup answers from either read: a card asks for the series in front
    // of it, and which read holds it is a fact about the series, not the caller.
    expect(facade.timeline('ncags')?.rows.length).toBe(1);
    expect(facade.timeline('weekly')).toBeUndefined();
    expect(facade.timelineError()).toBe(false);
  });

  /** Either read failing is still "the recent periods could not be read". */
  it('reports a failed archive read the same way as a failed strip', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne(URL)
      .flush([
        series({ seriesId: 'weekly' }),
        series({ seriesId: 'ncags', cadence: 'NONE', kind: 'UNSCHEDULED' }),
      ]);
    TestBed.tick();

    for (const request of httpTesting.match((r) => r.url === TIMELINE)) {
      if (request.request.params.get('periods') === '100') {
        request.flush('nope', { status: 500, statusText: 'Server Error' });
      } else {
        request.flush([]);
      }
    }

    expect(facade.timelineError()).toBe(true);
    expect(facade.error()).toBe(false);
  });

  /**
   * Both bounds in ONE create, and the new issue handed back.
   *
   * Applying the end through a second write leaves an issue standing for a
   * moment with a period nobody approved — and standing there for good whenever
   * that second write is the one that fails.
   */
  it('creates the reviewed issue in one call and hands it to the caller', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'annual', cadence: 'YEARLY' })]);
    TestBed.tick();
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);

    let created: { publicId: string } | undefined;
    facade.createIssue(
      'annual',
      { intervalFrom: 10, intervalTo: 20, recovered: true },
      (issue) => (created = issue),
    );

    const request = httpTesting.expectOne('/rest/publication-issues/issue');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      seriesId: 'annual',
      intervalFrom: 10,
      intervalTo: 20,
      recovered: true,
    });
    request.flush({ publicId: 'p-new', status: 'OPEN', descs: [] });

    expect(created?.publicId).toBe('p-new');
    expect(facade.issueCreating()).toBe(false);
    expect(facade.createIssueErrorKey()).toBeUndefined();

    // Refetched: the card the create was started from now shows a different state.
    TestBed.tick();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'annual', cadence: 'YEARLY' })]);
    TestBed.tick();
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);
  });

  /**
   * A name retyped in the draft dialog has to survive the dashboard route too.
   *
   * The dialog opens from two places — the series screen and a dashboard card —
   * and only the languages somebody actually retyped travel, so the ones left
   * alone keep tracking the period they were derived from.
   */
  it('carries a retyped name from the dashboard card into the create', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'annual', cadence: 'YEARLY' })]);
    TestBed.tick();
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);

    facade.createIssue('annual', {
      intervalFrom: 10,
      intervalTo: 20,
      recovered: false,
      names: { en: 'Week 27 (Christmas)' },
    });

    const request = httpTesting.expectOne('/rest/publication-issues/issue');
    expect(request.request.body).toEqual({
      seriesId: 'annual',
      intervalFrom: 10,
      intervalTo: 20,
      recovered: false,
      names: { en: 'Week 27 (Christmas)' },
    });
    request.flush({ publicId: 'p-new', status: 'OPEN', descs: [] });

    TestBed.tick();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'annual', cadence: 'YEARLY' })]);
    TestBed.tick();
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);
  });

  it('names a refused create rather than closing on nothing', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(URL).flush([series({ seriesId: 'annual', cadence: 'YEARLY' })]);
    TestBed.tick();
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);

    let created = false;
    facade.createIssue('annual', { recovered: false }, () => (created = true));
    httpTesting
      .expectOne('/rest/publication-issues/issue')
      .flush({ code: 'NOPE' }, { status: 409, statusText: 'Conflict' });

    expect(created).toBe(false);
    expect(facade.issueCreating()).toBe(false);
    expect(facade.createIssueErrorKey()).toBeDefined();
  });
});

/** What the host was built on: a series' id, or nothing for the card grid. */
const HOST_SERIES_ID = new InjectionToken<string | undefined>('publications host series id');

/**
 * The page's construction, reduced to the part the gate depends on.
 *
 * The field order is the load-bearing half and is copied deliberately: the
 * facade is injected FIRST, so its reads are registered before anything can shut
 * them, and the gate is set from the constructor body, which runs after every
 * field initialiser and before the first effect flush. A host that set the gate
 * from an effect instead would issue all three requests and cancel them, which
 * is the shape this spec exists to catch — a cancelled request has already
 * reached the server.
 */
@Component({
  selector: 'app-publications-gate-host',
  template: '',
  providers: [PublicationsFacade],
})
class GateHost {
  readonly facade = inject(PublicationsFacade);
  private readonly selectedSeriesId = signal(inject(HOST_SERIES_ID));

  constructor() {
    this.facade.setDashboardShown(this.selectedSeriesId() === undefined);
    effect(() => {
      const onDashboard = this.selectedSeriesId() === undefined;
      untracked(() => this.facade.setDashboardShown(onDashboard));
    });
  }
}

async function setupHost(options: { seriesId?: string; domainId?: string } = {}) {
  sessionDomain.set(options.domainId ? ({ domainId: options.domainId } as Domain) : undefined);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: DomainFacade, useValue: { sessionDomain } as unknown as DomainFacade },
      { provide: HOST_SERIES_ID, useValue: options.seriesId },
    ],
  });
  const fixture = TestBed.createComponent(GateHost);
  fixture.detectChanges();
  // The reads run through toObservable, which emits from an effect: let the
  // flush and the microtask behind it settle before anything is counted.
  await fixture.whenStable();
  return { fixture, httpTesting: TestBed.inject(HttpTestingController) };
}

/**
 * The gate as the page installs it, rather than as a caller flips it later.
 *
 * Every arrival on a series is a fresh construction — a click from a card, a
 * reload, a link into an issue — and the gate has to be known before the first
 * emission, not on it. The tests above shut the gate after a completed load,
 * which is the one path where getting this wrong costs nothing.
 */
describe('PublicationsFacade under the page’s own construction', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('creates no request when the page is built straight onto a series', async () => {
    const { fixture, httpTesting } = await setupHost({
      seriesId: 'weekly-ntm',
      domainId: 'niord-nm',
    });

    // Cancelled requests are matched too, which is the whole point: an XHR the
    // client aborted a tick later is one the server has already begun to answer.
    expect(httpTesting.match(() => true)).toEqual([]);

    // And the silence is the gate rather than a host that was never wired up:
    // the same facade asks the moment the dashboard is what is on screen.
    fixture.componentInstance.facade.setDashboardShown(true);
    TestBed.tick();

    httpTesting.expectOne((r) => r.url === URL).flush([]);
    flushDeskTimelines(httpTesting);
  });

  /** The same construction with no series: the dashboard's two reads go out. */
  it('asks for the list and the desk strip when it is built on the dashboard', async () => {
    const { httpTesting } = await setupHost({ domainId: 'niord-nm' });

    httpTesting.expectOne((r) => r.url === URL).flush([series({ seriesId: 'weekly-ntm' })]);
    flushDeskTimelines(httpTesting);
  });

  /** And with no desk to ask by, the list still goes out; the strips key on it. */
  it('asks for the list when it is built on the dashboard with no desk', async () => {
    const { httpTesting } = await setupHost();

    httpTesting.expectOne(URL).flush([series({ seriesId: 'weekly-ntm' })]);
    TestBed.tick();
    timelineRequest(httpTesting).flush([]);
  });
});
