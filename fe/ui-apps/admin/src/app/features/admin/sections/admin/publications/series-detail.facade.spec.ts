import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { DEFAULT_NOW_TICK_MS } from '../../../../../shared/utils/now-tick';
import { ISSUE_LIST_MAX_SIZE } from '../../../services/api/publication-series-api.service';
import { SeriesDetailFacade } from './series-detail.facade';

const SERIES_URL = '/rest/publication-series/editable-series/weekly-ntm';

/**
 * The issue list carries its ceiling on the wire.
 *
 * Matched WITH the query string, so the bound cannot be dropped without a test
 * noticing: the endpoint returns every issue a series ever had, and the busiest
 * one has ~500 with two localized rows each.
 */
const ISSUES_URL = `/rest/publication-issues/series/weekly-ntm?maxSize=${ISSUE_LIST_MAX_SIZE}`;

function issue(over: Record<string, unknown> = {}) {
  return {
    publicId: 'i-' + Math.random().toString(36).slice(2, 8),
    status: 'PUBLISHED',
    descs: [{ lang: 'en', name: 'An issue' }],
    ...over,
  };
}

/**
 * The list endpoint returns an ENVELOPE, not an array.
 *
 * The default here is the open-gate shape, because that is the one where a
 * count means something. Tests about a closed gate say so explicitly, which is
 * the point: the two must never be written the same way by accident.
 */
function issueList(rows: Record<string, unknown>[], over: Record<string, unknown> = {}) {
  return {
    publicationSeriesId: 'weekly-ntm',
    data: rows,
    total: rows.filter((r) => !r['pseudo']).length,
    gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: 'an active series' },
    gapCount: rows.filter((r) => r['pseudo'] === 'MISSING').length,
    ...over,
  };
}

/** What a DRAFT series looks like: the gate closed, and NO gapCount key. */
function gateClosed(rows: Record<string, unknown>[], reasonCode: string) {
  const list = issueList(rows, {
    gapDetection: { enabled: false, reasonCode, reason: 'the gate is closed' },
  });
  delete (list as Record<string, unknown>)['gapCount'];
  return list;
}

/**
 * The domain the caller is sitting in — the same one the client names on every
 * request, which is what the write guard on the other side reads.
 */
const sessionDomain = signal<Domain | undefined>(undefined);

function setup() {
  sessionDomain.set(undefined);
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      // Stubbed rather than real, so the facade's one domain read does not pull
      // the AuthFacade → Keycloak chain into a spec about issue lists.
      { provide: DomainFacade, useValue: { sessionDomain } as unknown as DomainFacade },
      SeriesDetailFacade,
    ],
  });
  const facade = TestBed.inject(SeriesDetailFacade);
  TestBed.tick();
  return { facade, httpTesting: TestBed.inject(HttpTestingController) };
}

function loadWeekly(facade: SeriesDetailFacade) {
  facade.load('weekly-ntm');
  TestBed.tick();
}

/**
 * Answer the series read, and let the archive follow it.
 *
 * The two are separate reads now, and the archive is keyed on the series that
 * ARRIVED — which is what keeps them sequential and lets an open issue skip the
 * archive entirely. So the second request is issued on the flush AFTER the first
 * answers rather than from inside its response.
 */
function answerSeries(httpTesting: HttpTestingController, series: Record<string, unknown>) {
  httpTesting.expectOne(SERIES_URL).flush(series);
  TestBed.tick();
}

describe('SeriesDetailFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the series and its issues together', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));

    expect(facade.loading()).toBe(false);
    expect(facade.series()?.seriesId).toBe('weekly-ntm');
    expect(facade.issues().length).toBe(1);
  });

  /**
   * One request at a time, cheapest first.
   *
   * The issue list is the expensive half, and the series is what says whether it
   * is worth asking for: a series that failed, or that another desk owns, has no
   * archive to show. So the list waits for the series to arrive.
   */
  it('does not ask for the issues until the series has answered', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    httpTesting.expectNone(ISSUES_URL);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });

    const issues = httpTesting.expectOne(ISSUES_URL);
    expect(issues.request.params.get('maxSize')).toBe(String(ISSUE_LIST_MAX_SIZE));
    issues.flush(issueList([issue()]));
  });

  /**
   * A series that fails takes the page down without asking for the issues.
   *
   * There is nothing to render an archive against, so the second request would
   * cost the slowest query in the section to produce rows nothing can display.
   */
  it('never asks for the issues when the series itself failed', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    httpTesting.expectOne(SERIES_URL).flush('no', { status: 500, statusText: 'Server Error' });
    TestBed.tick();

    httpTesting.expectNone(ISSUES_URL);
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
    // Any failure but the coded 404 may pass on a second try.
    expect(facade.notFound()).toBe(false);
  });

  /**
   * A series that is not there is told apart from a read that failed.
   *
   * The screen says so without Retry: the same read can only meet the same 404.
   * A Retry afterwards that succeeds clears it again.
   */
  it('names a coded 404 SERIES_NOT_FOUND as a series that is not there', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    httpTesting
      .expectOne(SERIES_URL)
      .flush(
        { code: 'SERIES_NOT_FOUND', message: 'no series with id weekly-ntm' },
        { status: 404, statusText: 'Not Found' },
      );
    TestBed.tick();

    httpTesting.expectNone(ISSUES_URL);
    expect(facade.error()).toBe(true);
    expect(facade.notFound()).toBe(true);

    facade.refresh();
    TestBed.tick();
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));
    expect(facade.notFound()).toBe(false);
  });

  /**
   * A series another desk owns costs one read and stops.
   *
   * The screen refuses it in place of the page, and the owner arrives on the
   * series — the same answer the archive is keyed on. So the refusal is decided
   * inside the chain rather than by a flag the screen sets once it has seen that
   * answer: a flag arrives a flush later, by which time the heaviest read in the
   * section is already on the wire, and a cancelled request is not an unasked
   * one.
   */
  it('never asks for the issues of a series another desk owns', () => {
    const { facade, httpTesting } = setup();
    sessionDomain.set({ domainId: 'niord-fa' } as Domain);
    loadWeekly(facade);

    answerSeries(httpTesting, {
      seriesId: 'weekly-ntm',
      domainId: 'niord-nm',
      descs: [],
    });

    httpTesting.expectNone((request) => request.url.includes('/publication-issues/series/'));
  });

  /**
   * Either failure fails the page.
   *
   * Neither answer is useful alone — a series with no issue list cannot show its
   * archive, and an issue list with no series cannot say what cadence those
   * issues follow. Half-rendering would be worse than an error, because the half
   * that rendered looks complete.
   */
  it('a failure in either request puts the page in the error state', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush('no', { status: 500, statusText: 'Server Error' });

    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });

  /**
   * The archive is not read for a screen that does not draw it.
   *
   * An open issue takes over the whole panel and reads none of the archive; the
   * archive is the heaviest request the section makes — every issue the series
   * ever had, with its per-language rows — so a deep link straight into an issue
   * must not pay for it.
   */
  it('never reads the archive while an issue is open', () => {
    const { facade, httpTesting } = setup();
    facade.setArchiveShown(false);
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });

    httpTesting.expectNone(ISSUES_URL);
    // The series itself is still read: the workbench is drawn from it.
    expect(facade.series()?.seriesId).toBe('weekly-ntm');
    expect(facade.error()).toBe(false);
    expect(facade.loading()).toBe(false);
  });

  /**
   * The rows survive the issue that was opened on top of them.
   *
   * Coming back has to land on the archive that was there, and the card above it
   * — what is being worked on, whether a new issue may be opened — is read from
   * the same rows. Answering the gate with an empty list instead of nothing at
   * all would blank all of it the instant an issue was opened.
   */
  it('keeps the rows it already has when an issue is opened', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(issueList([issue({ publicId: 'w36', status: 'OPEN' })]));
    TestBed.tick();
    httpTesting
      .expectOne((r) => r.url.includes('publish-checklist'))
      .flush({
        rows: [],
        canPublish: true,
        blockingCodes: [],
      });

    facade.setArchiveShown(false);
    TestBed.tick();

    expect(facade.issues().length).toBe(1);
    expect(facade.currentIssue()?.publicId).toBe('w36');
    expect(facade.issueCount()).toBe(1);
    httpTesting.expectNone(ISSUES_URL);
  });

  /**
   * The series outlives the read that is replacing it.
   *
   * Everything the screen's header is drawn from comes from here — the name, the
   * badge, the cadence line, the settings button, the actions menu — and this
   * screen re-reads itself after every action taken on it. Emptying the series
   * for the length of that request unmounts the header on a screen nobody
   * navigated away from, which reads as the page having failed.
   */
  it('keeps the series it has while the same series is re-read', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
    TestBed.tick();

    facade.refresh();
    TestBed.tick();

    // The re-read is in flight, and the header still has what it draws.
    expect(facade.series()?.seriesId).toBe('weekly-ntm');
    expect(facade.loading()).toBe(true);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
    TestBed.tick();

    expect(facade.loading()).toBe(false);
  });

  /**
   * A re-read that fails takes the series with it.
   *
   * The banner says the series could not be read. A header still stating the
   * name, cadence and authority it had a moment ago would be asserting facts
   * nobody has just confirmed, beside a message saying exactly that.
   */
  it('drops the series it had when the re-read fails', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
    TestBed.tick();

    facade.refresh();
    TestBed.tick();
    expect(facade.series()?.seriesId).toBe('weekly-ntm');

    httpTesting.expectOne(SERIES_URL).flush('no', { status: 500, statusText: 'Server Error' });
    TestBed.tick();

    expect(facade.series()).toBeUndefined();
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });

  /**
   * And it is forgotten the moment the screen is pointed somewhere else.
   *
   * Keeping the previous answer through a re-read of the SAME series is a header
   * that does not flicker; keeping it across a move to another series would be a
   * header naming the wrong publication.
   */
  it('forgets the series it had when it is pointed at another', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
    TestBed.tick();

    facade.load('annual');
    TestBed.tick();

    expect(facade.series()).toBeUndefined();

    httpTesting
      .expectOne('/rest/publication-series/editable-series/annual')
      .flush({ seriesId: 'annual', status: 'ACTIVE', descs: [] });
    TestBed.tick();
    httpTesting
      .expectOne((request) => request.url.includes('/publication-issues/series/annual'))
      .flush(issueList([], { publicationSeriesId: 'annual' }));
    TestBed.tick();

    expect(facade.series()?.seriesId).toBe('annual');
  });

  /**
   * A re-read is announced without the rows underneath it being thrown away.
   *
   * The rows have to survive the request that replaces them — the card above the
   * archive is read from them — but a table shown as though it had settled while
   * a newer answer is on its way is a table that quietly lies for a moment.
   */
  it('reports the archive re-read without dropping the rows it is replacing', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ publicId: 'w35' })]));
    TestBed.tick();
    expect(facade.loading()).toBe(false);

    facade.refresh();
    TestBed.tick();
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });

    expect(facade.issues().map((row) => row.publicId)).toEqual(['w35']);
    expect(facade.loading()).toBe(true);

    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ publicId: 'w36' })]));
    TestBed.tick();

    expect(facade.issues().map((row) => row.publicId)).toEqual(['w36']);
    expect(facade.loading()).toBe(false);
  });

  /**
   * The live issue is the one whose public window contains now.
   *
   * The obvious test — "the issue with no publicTo" — is what this facade used to
   * do, and the real archive shows why it is wrong: legacy issues carry an
   * explicit week-long window, so weekly-ntm and weekly-ntm-p-t, 498 issues
   * apiece, have no open-ended issue at all while an issue is plainly live in
   * both. The fixture below is that shape.
   */
  it('identifies the live issue by its public window, not by a missing end date', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    const now = Date.now();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(
      issueList([
        issue({ publicId: 'live', publicFrom: now - 86_400_000, publicTo: now + 86_400_000 }),
        issue({
          publicId: 'lastWeek',
          publicFrom: now - 700_000_000,
          publicTo: now - 86_400_000,
        }),
      ]),
    );

    expect(facade.publicNowIssue()?.publicId).toBe('live');
  });

  /**
   * An issue still being recorded is not published, whatever its window says.
   *
   * On the real estate the OPEN issue is precisely the one whose window contains
   * now, so a rule that ignored status would confidently name the one issue the
   * public cannot yet see.
   */
  it('never names an OPEN issue as public, even inside its window', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    const now = Date.now();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(
      issueList([
        issue({
          publicId: 'recording',
          status: 'OPEN',
          publicFrom: now - 1000,
          publicTo: now + 1000,
        }),
      ]),
    );

    expect(facade.publicNowIssue()).toBeUndefined();
  });

  /**
   * The public listing is gated on the CATEGORY as well, on both halves of the
   * adapter's union: a series filed under a category the site does not publish
   * has no public listing at all, whatever its issues say.
   */
  it('names nothing as public for a series in a category the site does not publish', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    const now = Date.now();

    answerSeries(httpTesting, {
      seriesId: 'weekly-ntm',
      descs: [],
      categoryId: 'dk-dma-internal-publications',
    });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([
          issue({ publicId: 'live', publicFrom: now - 86_400_000, publicTo: now + 86_400_000 }),
        ]),
      );
    expect(facade.publicNowIssue()?.publicId).toBe('live');

    httpTesting
      .expectOne('/rest/publication-categories/all')
      .flush([{ categoryId: 'dk-dma-internal-publications', publish: false, descs: [] }]);

    expect(facade.publicNowIssue()).toBeUndefined();
  });

  /** A category the site does publish leaves the rule exactly as it was. */
  it('keeps the live issue for a series in a published category', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    const now = Date.now();

    answerSeries(httpTesting, {
      seriesId: 'weekly-ntm',
      descs: [],
      categoryId: 'dk-dma-weekly-nm-publications',
    });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([
          issue({ publicId: 'live', publicFrom: now - 86_400_000, publicTo: now + 86_400_000 }),
        ]),
      );
    httpTesting
      .expectOne('/rest/publication-categories/all')
      .flush([{ categoryId: 'dk-dma-weekly-nm-publications', publish: true, descs: [] }]);

    expect(facade.publicNowIssue()?.publicId).toBe('live');
  });

  /**
   * A refused catalogue read leaves the rule as it was.
   *
   * `undefined` is "not answered", not "not public" — a slow or failed read must
   * not silently demote an issue the site is serving.
   */
  it('keeps the live issue when the category catalogue cannot be read', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    const now = Date.now();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [], categoryId: 'dk-anything' });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([
          issue({ publicId: 'live', publicFrom: now - 86_400_000, publicTo: now + 86_400_000 }),
        ]),
      );
    httpTesting
      .expectOne('/rest/publication-categories/all')
      .flush(null, { status: 500, statusText: 'Server Error' });

    expect(facade.publicNowIssue()?.publicId).toBe('live');
  });

  /** A series that names no category asks the catalogue nothing. */
  it('does not read the category catalogue for a series with no category', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));

    httpTesting.expectNone('/rest/publication-categories/all');
    expect(facade.series()?.seriesId).toBe('weekly-ntm');
  });

  /**
   * The end of the window is inside it, matching the public adapter exactly.
   *
   * The adapter serves an issue while `publicTo >= now`. The imported archive
   * carries verbatim legacy windows whose end lands on the instant the successor
   * begins, so an exclusive test drops the live issue from this card for as long
   * as that instant lasts — the page contradicting the site it is reporting on.
   */
  it('counts an issue as public on the last instant of its window', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    const now = Date.now();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([issue({ publicId: 'closing', publicFrom: now - 86_400_000, publicTo: now })]),
      );

    expect(facade.publicNowIssue()?.publicId).toBe('closing');
  });

  /**
   * The answer moves with the clock rather than freezing at first render.
   *
   * `Date.now()` read inside a computed is not a dependency, so the card was
   * pinned to whatever was live when the page opened — on a screen an operator
   * leaves open across the very release it is watching for. Verified by moving
   * the clock rather than the data: nothing about the issues changes here.
   */
  it('re-answers "on the public site now" as the clock moves past the window', () => {
    vi.useFakeTimers();
    try {
      const { facade, httpTesting } = setup();
      loadWeekly(facade);
      const now = Date.now();

      answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
      httpTesting.expectOne(ISSUES_URL).flush(
        issueList([
          issue({
            publicId: 'expiring',
            publicFrom: now - 86_400_000,
            publicTo: now + 60_000,
          }),
        ]),
      );

      expect(facade.publicNowIssue()?.publicId).toBe('expiring');

      // Past the window, with no refetch and no new data.
      vi.advanceTimersByTime(DEFAULT_NOW_TICK_MS * 3);
      expect(facade.publicNowIssue()).toBeUndefined();
    } finally {
      vi.useRealTimers();
    }
  });

  /** An expired window is nothing public, not the newest row. */
  it('reports nothing public when every window has closed', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(
      issueList([
        issue({
          publicId: 'ended2017',
          publicFrom: 1_487_000_000_000,
          publicTo: 1_514_000_000_000,
        }),
      ]),
    );

    expect(facade.publicNowIssue()).toBeUndefined();
  });

  /** A series that has released nothing is not an error. */
  it('reports a series with no issues as empty, not failed', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));

    expect(facade.isEmpty()).toBe(true);
    expect(facade.error()).toBe(false);
  });

  /** Nothing is fetched until a series is chosen. */
  it('issues no request before a seriesId is set', () => {
    const { facade } = setup();
    expect(facade.loading()).toBe(false);
    expect(facade.series()).toBeUndefined();
  });

  /**
   * A closed gate is not zero gaps.
   *
   * The response omits gapCount entirely, and the facade must carry that
   * through as undefined rather than defaulting it. Every imported series is
   * DRAFT, so a `?? 0` anywhere on this path would tell an operator reviewing
   * the whole archive that all twenty series are complete, on the strength of a
   * check that never ran.
   */
  it('reports an unchecked series as unchecked, never as zero gaps', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(gateClosed([issue(), issue()], 'SERIES_NOT_ACTIVE'));

    expect(facade.gapDetection()?.enabled).toBe(false);
    expect(facade.gapDetection()?.reasonCode).toBe('SERIES_NOT_ACTIVE');
    expect(facade.gapCount()).toBeUndefined();
  });

  /** The other half: a real zero, from a series somebody actually checked. */
  it('reports a checked series with nothing missing as zero gaps', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue(), issue()]));

    expect(facade.gapDetection()?.enabled).toBe(true);
    expect(facade.gapCount()).toBe(0);
  });

  /**
   * A pseudo-row is a row, but it is not an issue.
   *
   * `total` counts the real ones. Counting the rows instead would report an
   * archive as larger than it is, and would call a series that has released
   * nothing -- but has an UPCOMING period -- a populated archive.
   */
  it('counts real issues, not the synthesized rows', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(
      issueList([
        {
          pseudo: 'UPCOMING',
          sortKey: 3,
          descs: [],
          suggestedDescs: [{ lang: 'en', name: 'Week 3' }],
        },
        issue({ publicId: 'real' }),
        {
          pseudo: 'MISSING',
          sortKey: 1,
          descs: [],
          suggestedDescs: [{ lang: 'en', name: 'Week 1' }],
        },
      ]),
    );

    expect(facade.issues().length).toBe(3);
    expect(facade.issueCount()).toBe(1);
    expect(facade.gapCount()).toBe(1);
    expect(facade.isEmpty()).toBe(false);
  });

  /** A series whose only row is the period being worked toward has released nothing. */
  it('treats a series with only an UPCOMING row as empty', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(issueList([{ pseudo: 'UPCOMING', sortKey: 1, descs: [] }]));

    expect(facade.isEmpty()).toBe(true);
  });

  // ------------------------------------------------------- creating an issue

  /**
   * A series with no open issue can be given one by hand.
   *
   * This is the only route into the lifecycle an admin can take: gap recovery
   * needs a MISSING row, and a series with no cadence never has one. Without it
   * a one-off publication could be defined and never receive an issue — nothing
   * to upload a file to, nothing to publish, and no error saying so.
   */
  it('offers a new issue when nothing is open', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'PUBLISHED' })]));

    expect(facade.seriesAcceptsIssues()).toBe(true);
    expect(facade.canCreateIssue()).toBe(true);
  });

  /**
   * Only an ACTIVE series takes a new issue.
   *
   * A DRAFT series has not been validated — that is what activation is — and a
   * RETIRED one is finished. An issue opened on either is one nothing will ever
   * publish, and the endpoint checks none of this, so the affordance is the
   * whole guard. Every imported series is DRAFT, which is the entire archive.
   */
  for (const status of ['DRAFT', 'RETIRED']) {
    it(`withholds a new issue on a ${status} series`, () => {
      const { facade, httpTesting } = setup();
      loadWeekly(facade);
      answerSeries(httpTesting, { seriesId: 'weekly-ntm', status, descs: [] });
      httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'PUBLISHED' })]));

      expect(facade.seriesAcceptsIssues()).toBe(false);
      expect(facade.canCreateIssue()).toBe(false);
    });
  }

  /**
   * Not while one is already open.
   *
   * A cadenced series opens its successor itself when the current issue
   * publishes, so a button standing next to that open issue invites a duplicate
   * of one the series was about to create. An affordance, not a rule — the
   * server accepts a second one, and overlapping series have them by design.
   */
  it('withholds it while an issue is already open', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'OPEN' })]));

    expect(facade.seriesAcceptsIssues()).toBe(true);
    expect(facade.canCreateIssue()).toBe(false);
  });

  /** Nothing to create on before a series has loaded. */
  it('withholds it before the series has loaded', () => {
    const { facade } = setup();
    expect(facade.canCreateIssue()).toBe(false);
  });

  /**
   * The issue somebody is in the middle of, which the card above the archive is about.
   *
   * The OPEN one and nothing else: a period nobody has released is not what a
   * reader is working on, and the newest PUBLISHED issue is finished.
   */
  it('names the open issue as the one being worked on', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([
          issue({ publicId: 'w36', status: 'OPEN' }),
          issue({ publicId: 'w35', status: 'PUBLISHED' }),
        ]),
      );

    expect(facade.currentIssue()?.publicId).toBe('w36');
  });

  it('names nothing as current while every issue is finished', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'PUBLISHED' })]));

    expect(facade.currentIssue()).toBeUndefined();
  });

  /**
   * The release rail behind the card's closing clause.
   *
   * The card says how close the open issue is to going out, and the honest
   * answer to that is the checklist the release itself is judged by. It is read
   * on its own, outside the pair the page is drawn from: an admin who may read
   * a series but not its release gate gets the screen, and one that could not ask says
   * nothing rather than claiming the issue is ready.
   */
  const CHECKLIST_URL = '/rest/publication-issues/issue/w36/publish-checklist?allowFuture=false';

  /** Load a series whose open issue is `w36`, and settle the effect that reads it. */
  function loadWithOpenIssue(httpTesting: HttpTestingController, facade: SeriesDetailFacade) {
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([
          issue({ publicId: 'w36', status: 'OPEN' }),
          issue({ publicId: 'w35', status: 'PUBLISHED' }),
        ]),
      );
    TestBed.tick();
  }

  it('reads the checklist of the issue being worked on', () => {
    const { facade, httpTesting } = setup();
    loadWithOpenIssue(httpTesting, facade);

    httpTesting.expectOne(CHECKLIST_URL).flush({
      rows: [{ code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false }],
      canPublish: true,
      blockingCodes: [],
    });

    expect(facade.currentChecklist()?.rows.length).toBe(1);
  });

  /** Nothing open, nothing to ask about — and no request for the archive's sake. */
  it('asks for nothing while every issue is finished', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'PUBLISHED' })]));
    TestBed.tick();

    httpTesting.expectNone((request) => request.url.includes('publish-checklist'));
    expect(facade.currentChecklist()).toBeUndefined();
  });

  /**
   * A refusal here is not the page's failure.
   *
   * The gate is a curator-tier read and the rest of the screen is not, so an
   * admin who may not ask still gets the series, the card and the archive — with
   * the one clause that needed the answer left off.
   */
  it('leaves the clause empty when the read is refused, and keeps the page', () => {
    const { facade, httpTesting } = setup();
    loadWithOpenIssue(httpTesting, facade);

    httpTesting.expectOne(CHECKLIST_URL).flush('no', { status: 403, statusText: 'Forbidden' });

    expect(facade.currentChecklist()).toBeUndefined();
    expect(facade.error()).toBe(false);
    expect(facade.series()?.seriesId).toBe('weekly-ntm');
  });

  /**
   * Asked again after a refresh, because the workbench is where warnings get
   * dealt with: coming back to a card still announcing the one just fixed is the
   * card being wrong.
   */
  it('asks again once the page has been refreshed', () => {
    const { facade, httpTesting } = setup();
    loadWithOpenIssue(httpTesting, facade);
    httpTesting.expectOne(CHECKLIST_URL).flush({ rows: [], canPublish: true, blockingCodes: [] });

    facade.refresh();
    TestBed.tick();
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(issueList([issue({ publicId: 'w36', status: 'OPEN' })]));
    TestBed.tick();

    httpTesting.expectOne(CHECKLIST_URL).flush({ rows: [], canPublish: true, blockingCodes: [] });
  });

  /**
   * And not asked at all while the card that says it is off screen.
   *
   * The workbench reads the same release gate for the issue it opens, so asking
   * here as well resolved the issue twice on every visit — the most expensive
   * thing either screen does, and on the busiest series by some way the slowest.
   */
  it('does not ask for the checklist while an issue is open', () => {
    const { facade, httpTesting } = setup();
    loadWithOpenIssue(httpTesting, facade);
    httpTesting.expectOne(CHECKLIST_URL).flush({ rows: [], canPublish: true, blockingCodes: [] });

    facade.setArchiveShown(false);
    TestBed.tick();

    httpTesting.expectNone((request) => request.url.includes('publish-checklist'));
  });

  /**
   * On the way back it asks about the rows that are ARRIVING, not the ones on
   * screen.
   *
   * The workbench is where an issue gets published, and publishing one opens its
   * successor — so the rows still on screen when it closes name an issue that is
   * finished. Asking about that one costs the slowest read this screen makes,
   * for an answer thrown away the moment the archive comes back.
   */
  it('waits for the fresh rows before asking about the open issue', () => {
    const { facade, httpTesting } = setup();
    loadWithOpenIssue(httpTesting, facade);
    httpTesting.expectOne(CHECKLIST_URL).flush({ rows: [], canPublish: true, blockingCodes: [] });

    // The order the screen closes an issue in: the gate first, the refresh after.
    facade.setArchiveShown(false);
    TestBed.tick();
    facade.setArchiveShown(true);
    facade.refresh();
    TestBed.tick();

    // w36 is what the rows still on screen call open, and nothing is asked
    // about it while the archive that replaces them is in flight.
    httpTesting.expectNone((request) => request.url.includes('publish-checklist'));

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(
        issueList([
          issue({ publicId: 'w37', status: 'OPEN' }),
          issue({ publicId: 'w36', status: 'PUBLISHED' }),
        ]),
      );
    TestBed.tick();

    httpTesting.expectNone((request) => request.url.includes('/issue/w36/publish-checklist'));
    httpTesting
      .expectOne('/rest/publication-issues/issue/w37/publish-checklist?allowFuture=false')
      .flush({ rows: [], canPublish: true, blockingCodes: [] });
  });

  /**
   * Asked once on the way back, because the workbench is where a warning is
   * dealt with: a card still announcing the one just fixed is a card that lies.
   */
  it('asks for the checklist again on the way back to the archive', () => {
    const { facade, httpTesting } = setup();
    loadWithOpenIssue(httpTesting, facade);
    httpTesting.expectOne(CHECKLIST_URL).flush({ rows: [], canPublish: true, blockingCodes: [] });

    facade.setArchiveShown(false);
    TestBed.tick();
    // The order the screen closes an issue in: the gate first, so the refresh
    // that follows is not spent while it is still shut.
    facade.setArchiveShown(true);
    facade.refresh();
    TestBed.tick();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', descs: [] });
    httpTesting
      .expectOne(ISSUES_URL)
      .flush(issueList([issue({ publicId: 'w36', status: 'OPEN' })]));
    TestBed.tick();

    httpTesting.expectOne(CHECKLIST_URL).flush({ rows: [], canPublish: true, blockingCodes: [] });
  });

  /**
   * A hand-created issue is not RECOVERED.
   *
   * Nobody is reconstructing a period that was missed, and the marker is how a
   * later reader tells a bound that was stamped at the time from one worked out
   * afterwards. Sending it here would label every first issue as reconstructed.
   */
  it('creates a fresh issue with no interval and no recovery marker', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));

    facade.createIssue({ recovered: false });

    const request = httpTesting.expectOne('/rest/publication-issues/issue');
    expect(request.request.body).toEqual({
      seriesId: 'weekly-ntm',
      intervalFrom: null,
      recovered: false,
    });
    request.flush(issue({ status: 'OPEN' }));
    TestBed.tick();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'OPEN' })]));
    expect(facade.issues().length).toBe(1);
  });

  /** Gap recovery still marks its bound as reconstructed. */
  it('keeps the recovery marker on a gap-created issue', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));

    facade.createIssue({ intervalFrom: 1_700_000_000_000, recovered: true });

    const request = httpTesting.expectOne('/rest/publication-issues/issue');
    expect(request.request.body).toEqual({
      seriesId: 'weekly-ntm',
      intervalFrom: 1_700_000_000_000,
      recovered: true,
    });
    request.flush(issue({ intervalTo: 1_700_600_000_000 }));
    TestBed.tick();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
  });

  /**
   * A reviewed END bound travels with the create, in the ONE request.
   *
   * It used to be applied afterwards, through a second write: the issue stood
   * for a moment with a period nobody had approved, and stood there for good
   * whenever that second write was the one that failed.
   */
  it('creates the reviewed period in one request, both bounds included', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));

    facade.createIssue({
      intervalFrom: 1_700_000_000_000,
      intervalTo: 1_700_900_000_000,
      recovered: true,
    });

    const request = httpTesting.expectOne('/rest/publication-issues/issue');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      seriesId: 'weekly-ntm',
      intervalFrom: 1_700_000_000_000,
      intervalTo: 1_700_900_000_000,
      recovered: true,
    });
    request.flush(issue({ publicId: 'nm-1', intervalTo: 1_700_900_000_000 }));
    httpTesting.expectNone('/rest/publication-issues/issue/nm-1');
    TestBed.tick();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
  });

  /** A refused create names the rule instead of failing silently. */
  it('surfaces a refused create', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));

    facade.createIssue({ recovered: false });
    httpTesting
      .expectOne('/rest/publication-issues/issue')
      .flush({ code: 'SERIES_NOT_FOUND' }, { status: 409, statusText: 'Conflict' });

    expect(facade.createErrorKey()).toBe('admin.publications.error.seriesNotFound');
    expect(facade.working()).toBe(false);
  });

  it('refresh() refetches both', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));

    facade.refresh();
    TestBed.tick();

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));
    expect(facade.issues().length).toBe(1);
  });
});

/**
 * Who may write this series, from the desk the admin is currently at.
 *
 * The same question the server answers about the same write, asked here so the
 * screen can decline to offer an action rather than offer one that always
 * fails. Nothing about the archive or the timeline moves with it: reads are not
 * scoped.
 */
describe('SeriesDetailFacade — writability', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  /** `setup()` resets the desk, so the caller's domain is named after it, not before. */
  function load(domainId: string | undefined, currentDomainId?: string) {
    const { facade, httpTesting } = setup();
    sessionDomain.set(currentDomainId ? ({ domainId: currentDomainId } as Domain) : undefined);
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', status: 'ACTIVE', domainId, descs: [] });
    // Another desk's series is refused in place of the page, so its archive is
    // never asked for: the rows arrive only where the screen would draw them.
    if (domainId && domainId !== currentDomainId) {
      httpTesting.expectNone(ISSUES_URL);
    } else {
      httpTesting.expectOne(ISSUES_URL).flush(issueList([issue({ status: 'PUBLISHED' })]));
    }
    return facade;
  }

  it('is writable from the domain that owns it, and says nothing more', () => {
    const facade = load('niord-dk', 'niord-dk');

    expect(facade.writable()).toBe(true);
    expect(facade.canCreateIssue()).toBe(true);
  });

  /**
   * From another desk it is read-only, and the screen refuses it outright.
   *
   * Creating an issue is a write on the series, so it goes with the rest — the
   * status half of that gate is untouched, which is what keeps the two refusals
   * distinguishable on the screen.
   */
  it('is read-only from another domain, and names the one that owns it', () => {
    const facade = load('niord-dk', 'niord-de');

    expect(facade.writable()).toBe(false);
    expect(facade.canCreateIssue()).toBe(false);
    // Still ACTIVE: what changed is who may act, not what the series is.
    expect(facade.seriesAcceptsIssues()).toBe(true);
  });

  /** A series that names no domain is visible from everywhere, so it is writable there too. */
  it('lets any domain write a series that names none', () => {
    const facade = load(undefined, 'niord-de');

    expect(facade.writable()).toBe(true);
  });
});

/**
 * The series' own trail — the entries recorded against the series rather than
 * against one of its issues.
 *
 * Read behind a fold: this screen's request budget has no room for an answer
 * that stays folded away, and most visits never open it. It matters because it
 * outlives the issues: the line a removal leaves behind is the only record that
 * the issue existed.
 */
describe('SeriesDetailFacade — the history of the series', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  /** Matched on the path alone: the language travels as a parameter. */
  function expectHistory(httpTesting: HttpTestingController, seriesId = 'weekly-ntm') {
    return httpTesting.expectOne(
      (request) => request.url === `/rest/publication-series/${seriesId}/history`,
    );
  }

  function loaded() {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
    return { facade, httpTesting };
  }

  /** `verify()` in the afterEach is what actually asserts the absence. */
  it('asks for nothing until somebody opens the panel', () => {
    const { facade, httpTesting } = loaded();

    expect(facade.history()).toEqual([]);
    expect(facade.historyLoading()).toBe(false);
    httpTesting.expectNone((request) => request.url.endsWith('/history'));
  });

  it('reads the trail once the panel asks for it', () => {
    const { facade, httpTesting } = loaded();

    facade.loadHistory();
    TestBed.tick();
    expectHistory(httpTesting).flush([{ id: 1, action: 'DELETED', created: 1 }]);

    expect(facade.history().length).toBe(1);
    expect(facade.history()[0].action).toBe('DELETED');
    expect(facade.historyError()).toBe(false);
  });

  /** Asking twice is one read: the panel re-renders far more often than it opens. */
  it('reads it once however often it is asked for', () => {
    const { facade, httpTesting } = loaded();

    facade.loadHistory();
    TestBed.tick();
    expectHistory(httpTesting).flush([]);

    facade.loadHistory();
    TestBed.tick();
    httpTesting.expectNone((request) => request.url.endsWith('/history'));
  });

  /**
   * A failure here is not the page's failure: the series and its archive render
   * perfectly well without the trail, and only the panel says it could not be
   * read.
   */
  it('reports a failed read without failing the screen', () => {
    const { facade, httpTesting } = loaded();

    facade.loadHistory();
    TestBed.tick();
    expectHistory(httpTesting).flush('nope', { status: 500, statusText: 'Server Error' });

    expect(facade.historyError()).toBe(true);
    expect(facade.history()).toEqual([]);
    expect(facade.error()).toBe(false);
  });

  /**
   * The trail is written BY what this screen does, so a refresh re-reads it.
   *
   * The case is a removal: an admin opens the panel, opens an issue, deletes it,
   * and comes back to the archive — which refetches. Without the refresh in the
   * key the panel would still be showing the trail as it stood before the line
   * the removal just wrote, which is the one line the panel exists to show.
   */
  it('re-reads the trail after the page refreshes', () => {
    const { facade, httpTesting } = loaded();

    facade.loadHistory();
    TestBed.tick();
    expectHistory(httpTesting).flush([]);
    expect(facade.history()).toEqual([]);

    facade.refresh();
    TestBed.tick();

    expectHistory(httpTesting).flush([{ id: 7, action: 'DELETED', created: 2 }]);
    expect(facade.history().length).toBe(1);
    expect(facade.history()[0].action).toBe('DELETED');

    // The refresh re-reads the page as well; answered so `verify()` is about the
    // trail rather than about the two requests that always accompany it.
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
  });

  /** A refresh with the panel never opened buys nothing: there is no trail to re-read. */
  it('does not read the trail on a refresh nobody has asked for it', () => {
    const { facade, httpTesting } = loaded();

    facade.refresh();
    TestBed.tick();

    httpTesting.expectNone((request) => request.url.endsWith('/history'));
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
  });

  /**
   * Moving to another series forgets that the panel was open: the answer belongs
   * to the series it was read for.
   */
  it('forgets the request when the screen moves to another series', () => {
    const { facade, httpTesting } = loaded();
    facade.loadHistory();
    TestBed.tick();
    expectHistory(httpTesting).flush([]);

    facade.load('firing-areas');
    TestBed.tick();

    httpTesting.expectNone((request) => request.url.endsWith('/history'));
    httpTesting
      .expectOne('/rest/publication-series/editable-series/firing-areas')
      .flush({ seriesId: 'firing-areas', descs: [] });
    TestBed.tick();
    httpTesting
      .expectOne(`/rest/publication-issues/series/firing-areas?maxSize=${ISSUE_LIST_MAX_SIZE}`)
      .flush(issueList([]));
  });
});

/**
 * The first pair of answers, told apart from every later one.
 *
 * The screen's panels are drawn from the archive, and an archive nobody has
 * fetched is indistinguishable from an empty one — so before the first answer
 * they state things that are not true yet. Afterwards the rows deliberately
 * survive a re-read, because this screen refetches itself after every action
 * taken on it.
 */
describe('SeriesDetailFacade — waiting for the first read', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('waits until BOTH the series and the archive have answered', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    expect(facade.awaitingFirstRead()).toBe(true);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    // The series is in, the archive is not: still nothing to draw the panels from.
    expect(facade.awaitingFirstRead()).toBe(true);

    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));
    expect(facade.awaitingFirstRead()).toBe(false);
  });

  /** A re-read is not a first read: what is on screen stays on screen. */
  it('stops waiting for good once the pair has answered once', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));

    facade.refresh();
    TestBed.tick();

    expect(facade.awaitingFirstRead()).toBe(false);
    expect(facade.loading()).toBe(true);

    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });
    httpTesting.expectOne(ISSUES_URL).flush(issueList([issue()]));
  });

  /**
   * A failed series read is not something to wait for.
   *
   * The banner takes the screen, and a gate still reporting "waiting" would hold
   * back panels the error branch is not rendering anyway.
   */
  it('stops waiting when the series read failed', () => {
    const { facade, httpTesting } = setup();
    loadWeekly(facade);
    httpTesting.expectOne(SERIES_URL).flush(null, { status: 500, statusText: 'Server Error' });
    TestBed.tick();

    expect(facade.awaitingFirstRead()).toBe(false);
  });

  /** An issue opened by deep link reads no archive, so there is none to wait for. */
  it('does not wait for an archive the workbench never asks for', () => {
    const { facade, httpTesting } = setup();
    facade.setArchiveShown(false);
    loadWeekly(facade);
    answerSeries(httpTesting, { seriesId: 'weekly-ntm', descs: [] });

    expect(facade.awaitingFirstRead()).toBe(false);
    facade.setArchiveShown(true);
    TestBed.tick();
    httpTesting.expectOne(ISSUES_URL).flush(issueList([]));
  });
});
