import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ISSUE_IN_FLIGHT_POLL_MS, IssueDetailFacade } from './issue-detail.facade';
import type { PublicationSeries } from '../../../services/api/publication-series-api.service';
import { publicationErrorKey } from './publication-errors';

const ID = 'i-1';
// ONE address for the whole screen. The active language travels with it because
// it titles the member rows; nothing else in the payload depends on it.
const WORKBENCH_URL = `/rest/publication-issues/issue/${ID}/workbench?lang=en`;

/**
 * @param pollMs how often the facade re-asks while the SERVER says a release or
 *   an amend is running. Named by a test that drives it under fake timers; left
 *   alone it is the shipped five seconds.
 */
function setup(pollMs?: number) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      ...(pollMs === undefined ? [] : [{ provide: ISSUE_IN_FLIGHT_POLL_MS, useValue: pollMs }]),
      IssueDetailFacade,
    ],
  });
  const facade = TestBed.inject(IssueDetailFacade);
  TestBed.tick();
  return { facade, httpTesting: TestBed.inject(HttpTestingController) };
}

/**
 * Hand the facade the series, the way the screen that provides it does: as a
 * signal it reads from, rather than as values pushed into it one at a time.
 */
function attachSeries(facade: IssueDetailFacade, series: Partial<PublicationSeries>): void {
  facade.attachSeries(signal(series as PublicationSeries));
}

function load(facade: IssueDetailFacade) {
  facade.load(ID);
  TestBed.tick();
}

/** Flush the one fetch; `shape` lets a test decide what any part of it answers. */
function flushAll(
  httpTesting: HttpTestingController,
  shape: {
    issue?: Record<string, unknown>;
    members?: unknown[];
    audit?: unknown[];
    overrides?: unknown[];
    checklist?: Record<string, unknown>;
    omissions?: Record<string, unknown>;
    sources?: unknown[];
    previews?: unknown[];
    /** The server's own record of a release or amend still running on the issue. */
    inFlight?: { action: 'PUBLISH' | 'AMEND'; since: number };
    /** What was published into a released issue's period after its release. */
    publishedSinceRelease?: unknown[] | null;
  } = {},
) {
  const issue = shape.issue ?? {
    publicId: ID,
    status: 'OPEN',
    descs: [{ lang: 'en', name: 'Week 1' }],
  };

  // The release gate is computed only while the issue can still be released: it
  // re-resolves the criteria as they stand NOW, so on a frozen issue it answers a
  // question nobody asked -- and contradicts the record. The payload leaves it
  // out in exactly that case, and this stands in for the server doing so.
  const checklist =
    issue['status'] === 'OPEN'
      ? (shape.checklist ?? { rows: [], canPublish: true, blockingCodes: [] })
      : undefined;

  httpTesting.expectOne(WORKBENCH_URL).flush({
    issue,
    members: shape.members ?? [],
    audit: shape.audit ?? [],
    overrides: shape.overrides ?? [],
    checklist,
    omissions: shape.omissions,
    sources: shape.sources,
    previews: shape.previews,
    inFlight: shape.inFlight,
    publishedSinceRelease: shape.publishedSinceRelease,
  });
}

describe('IssueDetailFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  // The in-flight poll is the one thing here that runs on a clock, and a test
  // that fakes it must not leave the clock faked for the next one.
  afterEach(() => vi.useRealTimers());

  /**
   * ONE request for the whole screen.
   *
   * Asking for the parts in turn paid a round trip each, and asking for them side
   * by side resolved the same issue three times over. Asking once also makes them
   * one answer: the rail, the member list and the omissions panel are decided from
   * a single resolution instead of from several taken milliseconds apart.
   */
  it('takes the issue, its members, its history, its overrides and the rail from one request', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      members: [{ messageUid: 'm1' }],
      audit: [{ action: 'CREATED' }],
      overrides: [{ messageUid: 'uid-z', kind: 'EXCLUDE' }],
    });

    expect(facade.loading()).toBe(false);
    expect(facade.issue()?.publicId).toBe(ID);
    expect(facade.members().length).toBe(1);
    expect(facade.audit().length).toBe(1);
    expect(facade.overrides()).toEqual([{ messageUid: 'uid-z', kind: 'EXCLUDE' }]);
    expect(facade.checklist()?.canPublish).toBe(true);
  });

  it('reloads localized member titles when the active language changes', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { members: [{ messageUid: 'm1', title: 'English title' }] });
    expect(facade.members()[0]?.title).toBe('English title');

    TestBed.inject(TranslocoService).setActiveLang('da');
    TestBed.tick();
    httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/workbench?lang=da`).flush({
      issue: { publicId: ID, status: 'OPEN', descs: [] },
      members: [{ messageUid: 'm1', title: 'Danish response title' }],
      audit: [],
      overrides: [],
    });

    expect(facade.issue()?.publicId).toBe(ID);
    expect(facade.members()[0]?.title).toBe('Danish response title');
  });

  /**
   * The release gate is an administrator's; the screen is not.
   *
   * A curator decides what goes INTO an issue, and whether it may be released is
   * not their decision — so the payload reaches them without a checklist and with
   * everything else exactly as an admin sees it. Refusing them the screen over
   * the half they may not act on would hide an issue from somebody allowed to
   * curate it.
   */
  it('renders the whole screen for a curator who gets no release rail', () => {
    const { facade, httpTesting } = setup();
    load(facade);

    httpTesting.expectOne(WORKBENCH_URL).flush({
      issue: { publicId: ID, status: 'OPEN', descs: [] },
      members: [{ messageUid: 'm1' }],
      audit: [{ action: 'CREATED' }],
      overrides: [{ messageUid: 'uid-z', kind: 'EXCLUDE' }],
    });

    expect(facade.error()).toBe(false);
    expect(facade.checklist()).toBeUndefined();
    expect(facade.members().length).toBe(1);
    expect(facade.overrides()).toEqual([{ messageUid: 'uid-z', kind: 'EXCLUDE' }]);
    expect(facade.issue()?.publicId).toBe(ID);
  });

  /**
   * The failure is all-or-nothing, because the answer is.
   *
   * The release rail must never render beside a member list that failed to load:
   * an admin would be deciding to publish a document while looking at an empty
   * table, and the empty table looks exactly like an issue with nothing in it.
   */
  it('puts the page in the error state when the request fails', () => {
    const { facade, httpTesting } = setup();
    load(facade);

    httpTesting.expectOne(WORKBENCH_URL).flush('no', { status: 500, statusText: 'Server Error' });

    expect(facade.error()).toBe(true);
    expect(facade.issue()).toBeUndefined();
    expect(facade.members()).toEqual([]);
    expect(facade.checklist()).toBeUndefined();
  });

  /** Only an OPEN issue can be curated; a published one is history. */
  it('marks a published issue as not curatable', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'PUBLISHED', descs: [] },
    });

    expect(facade.curatable()).toBe(false);
  });

  /**
   * A FROZEN ISSUE CARRIES NO RELEASE GATE.
   *
   * The checklist re-resolves the criteria as they stand now. Run against a
   * published issue it reports what the query would select TODAY, which is not
   * what was published: a weekly issue that released 20 messages had its gate
   * count 26, because six more had been published since. It also rendered two
   * red failures on a successfully published issue -- one of them failing
   * precisely BECAUSE the issue is PUBLISHED. The rule is held on this side as
   * well, so a gate arriving anyway is still never drawn beside a record.
   */
  it('holds no release rail for a frozen issue, even when one arrives', () => {
    const { facade, httpTesting } = setup();
    load(facade);

    httpTesting.expectOne(WORKBENCH_URL).flush({
      issue: { publicId: ID, status: 'PUBLISHED', descs: [] },
      members: [],
      audit: [],
      overrides: [],
      checklist: { rows: [], canPublish: true, blockingCodes: [] },
    });

    expect(facade.checklist()).toBeUndefined();
  });

  it('marks an open issue as curatable', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    expect(facade.curatable()).toBe(true);
  });

  /**
   * A blocker and an acknowledgeable warning are different things.
   *
   * A warning is a decision an admin may take responsibility for; a blocker is
   * not theirs to take. Collapsing the two would either offer a tickbox that
   * cannot work, or hide a warning that only needed accepting.
   */
  it('separates blockers from warnings an admin may accept', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      checklist: {
        rows: [
          { code: 'NO_MEMBERS', severity: 'BLOCK', passed: false, acknowledgeable: false },
          { code: 'FEW_MEMBERS', severity: 'WARN', passed: false, acknowledgeable: true },
          { code: 'HAS_FILE', severity: 'OK', passed: true, acknowledgeable: false },
        ],
        canPublish: false,
        blockingCodes: ['NO_MEMBERS'],
      },
    });

    expect(facade.blockers().map((r) => r.code)).toEqual(['NO_MEMBERS']);
    expect(facade.acknowledgeableWarnings().map((r) => r.code)).toEqual(['FEW_MEMBERS']);
  });

  /**
   * A warning nobody may tick is still a warning about this release.
   *
   * It carries no decision, so it is not offered as one — but the release is
   * made from a dialog, and a row said only on the screen behind it is a fact
   * the admin has to remember rather than one they can read.
   */
  it('lists the failing warnings that carry no tickbox, and nothing else', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      checklist: {
        rows: [
          { code: 'NO_MEMBERS', severity: 'BLOCK', passed: false, acknowledgeable: false },
          { code: 'FEW_MEMBERS', severity: 'WARN', passed: false, acknowledgeable: true },
          {
            code: 'NO_INEFFECTIVE_OVERRIDES',
            severity: 'WARN',
            passed: false,
            acknowledgeable: false,
          },
          { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: true, acknowledgeable: false },
        ],
        canPublish: false,
        blockingCodes: ['NO_MEMBERS'],
      },
    });

    expect(facade.informationalWarnings().map((r) => r.code)).toEqual(['NO_INEFFECTIVE_OVERRIDES']);
  });

  /**
   * A failing warning nobody may tick is still not a blocker.
   *
   * Exactly ONE row is acknowledgeable, so "failing and not acknowledgeable"
   * described almost every warning the rail can raise — an interval that does
   * not chain, an override that no longer bites — and filed each of them beside
   * the reasons the server would actually refuse. The severity is the server's
   * own answer.
   */
  it('files a failing unacknowledgeable warning as a warning, not a blocker', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      checklist: {
        rows: [
          { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false },
          { code: 'MEMBER_LIMIT', severity: 'BLOCK', passed: false, acknowledgeable: false },
        ],
        canPublish: false,
        blockingCodes: ['MEMBER_LIMIT'],
      },
    });

    expect(facade.blockers().map((r) => r.code)).toEqual(['MEMBER_LIMIT']);
  });

  /**
   * A preview is FETCHED, not linked to.
   *
   * The render response reports a root-relative address on an endpoint that
   * answers only to a request carrying the bearer, so following it as a link
   * asks the wrong host for a path it does not serve — and would get a 401 page
   * if it did serve it.
   */
  it('fetches a rendered preview as bytes and hands them to the caller', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    const seen: Blob[] = [];
    facade.fetchPreview('da', (file) => seen.push(file));

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/preview/da`);
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));
    TestBed.tick();

    expect(seen.length).toBe(1);
    expect(facade.previewWorking()).toBe(false);
  });

  /**
   * The archive is composed as an address, and only once an issue is loaded.
   *
   * The opposite half of the preview above, deliberately: this endpoint checks
   * the admin role in code so a one-time ticket satisfies it, which is what lets
   * a top-level navigation carrying no bearer reach the document at all. So the
   * facade hands back a URL and issues no request — nothing is flushed here, and
   * the suite's own `httpTesting.verify()` is what proves it.
   */
  it('composes the address of a superseded document, and nothing before an issue is loaded', () => {
    const { facade, httpTesting } = setup();
    expect(facade.archiveFileUrl(40122, 'da')).toBeUndefined();

    load(facade);
    flushAll(httpTesting);

    expect(facade.archiveFileUrl(40122, 'da')).toBe(
      `/rest/publication-issues/issue/${ID}/archive/40122/da`,
    );
  });

  /**
   * The refusal is decoded out of the bytes it arrived as.
   *
   * A request that asks for a document gets its error body as a document too, so
   * the coded refusal reaches the client as an opaque blob — and every refusal on
   * this path would otherwise read as the generic "could not be generated".
   */
  it('reports a refused preview fetch through the coded-error mapping', async () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.fetchPreview('da', () => undefined);
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/preview/da`)
      .flush(new Blob([JSON.stringify({ code: 'NO_PREVIEW' })], { type: 'application/json' }), {
        status: 404,
        statusText: 'Not Found',
      });
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
    TestBed.tick();

    expect(publicationErrorKey(facade.lastPreviewError(), 'fallback')).toBe(
      'admin.publications.error.noPreview',
    );
  });

  /**
   * A mutation refetches rather than patching local state.
   *
   * A publish stamps the cut-off, freezes the members, writes a history entry
   * and may open a successor — four things this screen shows. Reconstructing
   * that client-side would be re-implementing the transaction.
   */
  it('refetches everything after a publish', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/publish`)
      .flush({ publicId: ID, stampedAt: 1, memberCount: 3 });
    TestBed.tick();

    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'PUBLISHED', descs: [] },
    });
    expect(facade.issue()?.status).toBe('PUBLISHED');
  });

  /**
   * A failed mutation names the backend's code rather than swallowing it.
   *
   * The catalogue distinguishes a state conflict an admin can resolve from a
   * permission they simply lack, and "something went wrong" hides which.
   */
  it('surfaces the error code when an action is refused', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.retire('Wrong document');
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/retire?reason=Wrong%20document`)
      .flush({ code: 'ISSUE_NOT_PUBLISHED' }, { status: 409, statusText: 'Conflict' });

    expect(facade.lastActionError()).toBe('ISSUE_NOT_PUBLISHED');
    expect(facade.working()).toBe(false);
  });

  /** The completion hook runs on the transaction, never on the click. */
  it('runs the retire hook only when the withdrawal happened', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    const refused = vi.fn();
    facade.retire('Wrong document', refused);
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/retire?reason=Wrong%20document`)
      .flush({ code: 'ISSUE_NOT_PUBLISHED' }, { status: 409, statusText: 'Conflict' });
    expect(refused).not.toHaveBeenCalled();

    const done = vi.fn();
    facade.retire('Wrong document', done);
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/retire?reason=Wrong%20document`)
      .flush({});
    expect(done).toHaveBeenCalledTimes(1);
  });

  /** A 403 is named as such: curation needs a permission the rest of the screen does not. */
  it('names a refused permission rather than reporting it as unknown', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.excludeMembers(['m1'], 'Withdrawn by the author');
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/overrides/exclude`)
      .flush(null, { status: 403, statusText: 'Forbidden' });

    expect(facade.lastActionError()).toBe('FORBIDDEN');
  });

  // ------------------------------------------------------------------- edit

  /**
   * An edit sends only what changed, and refetches.
   *
   * Moving the interval re-derives week/year and re-renders every name the
   * series suggested, so the response is the only thing that knows what the
   * issue is now called.
   */
  it('sends an edit and reloads the issue', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.edit({ names: { da: 'Nyt navn' } });

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ names: { da: 'Nyt navn' } });
    request.flush({ publicId: ID, status: 'OPEN', descs: [] });
    TestBed.tick();

    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'OPEN', descs: [{ lang: 'da', name: 'Nyt navn' }] },
    });
    expect(facade.issue()?.descs[0].name).toBe('Nyt navn');
  });

  /** A refused edit names the code. */
  it('surfaces a refused edit', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.edit({ intervalFrom: 2, intervalTo: 1 });
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}`)
      .flush({ code: 'INTERVAL_INVERTED' }, { status: 409, statusText: 'Conflict' });

    expect(facade.lastActionError()).toBe('INTERVAL_INVERTED');
  });

  // --------------------------------------------------------------- document

  /**
   * An upload posts the file to the language it was chosen for, and refetches.
   *
   * Refetching is not caution. An upload archives what it replaces, writes an
   * audit entry and flips the sticky flag that decides whether the next publish
   * regenerates over it — three things this screen shows, none reconstructable
   * from the request.
   */
  it('uploads a document for one language and reloads the issue', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.uploadFile('da', new File(['pdf'], 'EfS.pdf', { type: 'application/pdf' }));

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/file/da`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeInstanceOf(FormData);
    request.flush({ publicId: ID, status: 'OPEN', descs: [] });
    TestBed.tick();

    flushAll(httpTesting, {
      issue: {
        publicId: ID,
        status: 'OPEN',
        descs: [{ lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', fileSource: 'UPLOADED' }],
      },
    });
    expect(facade.issue()?.descs[0].fileSource).toBe('UPLOADED');
  });

  /** Clearing a file targets the same per-language address. */
  it('clears a document for one language', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.clearFile('en');

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/file/en`);
    expect(request.request.method).toBe('DELETE');
    request.flush({ publicId: ID, status: 'OPEN', descs: [] });
    TestBed.tick();

    flushAll(httpTesting);
  });

  /** A blank link is sent as such: clearing is a value, not a missing request. */
  it('sends a link change, including an empty one', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.setLink('da', '');

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/link/da`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ link: '' });
    request.flush({ publicId: ID, status: 'OPEN', descs: [] });
    TestBed.tick();

    flushAll(httpTesting);
  });

  /**
   * A refused upload names the code, exactly as every other mutation does.
   *
   * FILE_NAME_NOT_DISTINCT is the one an admin will actually hit: both languages
   * write into the same folder, so sharing a name means one silently overwrites
   * the other, and the server refuses rather than letting it happen.
   */
  it('surfaces a refused upload', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.uploadFile('da', new File(['pdf'], 'EfS.pdf'));
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/file/da`)
      .flush({ code: 'FILE_NAME_NOT_DISTINCT' }, { status: 409, statusText: 'Conflict' });

    expect(facade.lastActionError()).toBe('FILE_NAME_NOT_DISTINCT');
    expect(facade.working()).toBe(false);
  });

  /** Nothing is fetched before an issue is chosen. */
  it('issues no request until an id is set', () => {
    const { facade } = setup();
    expect(facade.loading()).toBe(false);
    expect(facade.issue()).toBeUndefined();
  });

  // -------------------------------------------------------------- publishing

  const PUBLISH_URL = `/rest/publication-issues/issue/${ID}/publish`;

  /**
   * "Now" is the ABSENCE of a chosen instant.
   *
   * Sent as a null it would be a chosen cut-off with no value, and the server
   * would have to guess which of the two the caller meant.
   */
  it('omits the cut-off entirely when the release moment is the cut-off', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });

    const request = httpTesting.expectOne(PUBLISH_URL);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ acknowledgedWarnings: [] });
    expect('cutoff' in (request.request.body as object)).toBe(false);
    request.flush({ publicId: ID, stampedAt: 1, memberCount: 0 });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  /** A chosen past instant travels as epoch ms, which is what the wire speaks. */
  it('sends a chosen cut-off and the accepted codes', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({
      cutoff: 1_700_000_000_000,
      acknowledgedWarnings: ['FEW_MEMBERS'],
    });

    const request = httpTesting.expectOne(PUBLISH_URL);
    expect(request.request.body).toEqual({
      acknowledgedWarnings: ['FEW_MEMBERS'],
      cutoff: 1_700_000_000_000,
    });
    request.flush({ publicId: ID, stampedAt: 1, memberCount: 0 });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  /**
   * A 409 over warnings is not an error to record and forget.
   *
   * The server names exactly what it is still waiting for, and it can be a code
   * the checklist did not carry — publish re-resolves, so a warning can appear
   * between the rail being fetched and the release being attempted.
   */
  it('keeps the warning codes a refused publish is still waiting on', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });
    httpTesting.expectOne(PUBLISH_URL).flush(
      {
        code: 'WARNING_NOT_ACKNOWLEDGED',
        message: 'not acknowledged',
        unacknowledgedWarnings: ['FEW_MEMBERS', 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF'],
      },
      { status: 409, statusText: 'Conflict' },
    );

    expect(facade.lastActionError()).toBe('WARNING_NOT_ACKNOWLEDGED');
    expect(facade.unacknowledgedWarnings()).toEqual([
      'FEW_MEMBERS',
      'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
    ]);
    // Nothing was refetched: the issue is exactly as it was.
    expect(facade.issue()?.status).toBe('OPEN');
  });

  /** The success callback runs on the transaction, never on the click. */
  it('reports a publish only once the release has actually happened', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    let published = 0;
    facade.publish({ acknowledgedWarnings: [] }, () => published++);
    expect(published).toBe(0);

    httpTesting.expectOne(PUBLISH_URL).flush({ publicId: ID, stampedAt: 1, memberCount: 2 });
    expect(published).toBe(1);
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  it('does not report a publish that was refused', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    let published = 0;
    facade.publish({ acknowledgedWarnings: [] }, () => published++);
    httpTesting
      .expectOne(PUBLISH_URL)
      .flush({ code: 'CUTOFF_IN_FUTURE' }, { status: 400, statusText: 'Bad Request' });

    expect(published).toBe(0);
    expect(facade.lastActionError()).toBe('CUTOFF_IN_FUTURE');
  });

  /**
   * The release is NAMED while it runs, not merely counted as work.
   *
   * A publish renders the whole document per language and takes tens of seconds,
   * and the dialog it was pressed from can be closed over it — the request lives
   * here. The screen behind therefore has to know that it is a publish that is
   * running, or it has nothing to point at.
   */
  it('names the publish as the work in flight until the release lands', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });
    expect(facade.publishing()).toBe(true);
    expect(facade.amending()).toBe(false);
    expect(facade.working()).toBe(true);

    httpTesting.expectOne(PUBLISH_URL).flush({ publicId: ID, stampedAt: 1, memberCount: 0 });

    expect(facade.publishing()).toBe(false);
    expect(facade.working()).toBe(false);
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  /** A refusal ends the work as surely as a release does; the indicator stops. */
  it('stops the publish indicator when the release is refused', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });
    httpTesting
      .expectOne(PUBLISH_URL)
      .flush({ code: 'CUTOFF_IN_FUTURE' }, { status: 400, statusText: 'Bad Request' });

    expect(facade.publishing()).toBe(false);
    expect(facade.working()).toBe(false);
  });

  /**
   * One release at a time, and the second press is not queued either.
   *
   * The two write the same documents. A second action started over the first
   * would have the server rendering one issue twice at once, and the loser
   * would overwrite the winner's files.
   */
  it('refuses an amend while a publish is still running', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });
    facade.amend({ reason: 'Wrong chart number', acknowledgedWarnings: [] });

    httpTesting.expectNone(`/rest/publication-issues/issue/${ID}/amend`);
    expect(facade.amending()).toBe(false);
    expect(facade.publishing()).toBe(true);

    httpTesting.expectOne(PUBLISH_URL).flush({ publicId: ID, stampedAt: 1, memberCount: 0 });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  // --------------------------------------------- work the server is doing

  /**
   * The work is the ISSUE's fact, not this browser's.
   *
   * A release renders the document per language and takes tens of seconds, and
   * the request outlives the dialog it was pressed from. Held only in the tab
   * that pressed the button, a reloaded page, a second tab and another admin
   * all saw an idle issue while the document was being written — and were
   * offered the button that would write it a second time.
   */
  it('names a release the server is running that this client never started', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { inFlight: { action: 'PUBLISH', since: 1_700_000_000_000 } });

    expect(facade.serverInFlight()).toEqual({
      action: 'PUBLISH',
      since: 1_700_000_000_000,
    });
    expect(facade.publishing()).toBe(true);
    expect(facade.amending()).toBe(false);
    expect(facade.working()).toBe(true);
  });

  /** The same for an amend, which is shown somewhere else entirely. */
  it('names an amend the server is running that this client never started', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { inFlight: { action: 'AMEND', since: 1_700_000_000_000 } });

    expect(facade.amending()).toBe(true);
    expect(facade.publishing()).toBe(false);
    expect(facade.working()).toBe(true);
  });

  /**
   * The guard that refuses a second action reads the server's fact too.
   *
   * Otherwise the whole point is lost: two admins pressing publish a second
   * apart would have the server rendering one issue twice at once, and the
   * loser's files would overwrite the winner's.
   */
  it('refuses a release while the server says one is already running', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { inFlight: { action: 'PUBLISH', since: 1 } });

    facade.publish({ acknowledgedWarnings: [] });

    httpTesting.expectNone(PUBLISH_URL);
  });

  /**
   * A client that is only WATCHING has to ask; nothing pushes the answer.
   *
   * The fact lives on the issue, so the only way to learn it has cleared is to
   * read the issue again — and the read stops the moment the answer comes back
   * without it, so an idle issue is never polled at all.
   */
  it('re-reads while the work is somebody else’s and stops when it ends', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup(5_000);
    load(facade);
    flushAll(httpTesting, { inFlight: { action: 'AMEND', since: 1 } });
    TestBed.tick();

    expect(facade.amending()).toBe(true);

    // Still running: the fact stands and the next question is already scheduled.
    vi.advanceTimersByTime(5_000);
    TestBed.tick();
    flushAll(httpTesting, { inFlight: { action: 'AMEND', since: 1 } });
    TestBed.tick();
    expect(facade.amending()).toBe(true);

    // Finished. The answer that carries no record is what clears the screen.
    vi.advanceTimersByTime(5_000);
    TestBed.tick();
    flushAll(httpTesting);
    TestBed.tick();

    expect(facade.serverInFlight()).toBeUndefined();
    expect(facade.amending()).toBe(false);
    expect(facade.working()).toBe(false);

    // And nothing is asked again: the issue is idle.
    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    httpTesting.expectNone(WORKBENCH_URL);
  });

  /**
   * The client that STARTED the work does not poll beside its own request.
   *
   * Its response is what tells it the work is over, and a re-read landing in
   * the middle would redraw the screen from a transaction that has not finished.
   */
  it('does not poll while this client is the one doing the work', () => {
    vi.useFakeTimers();
    const { facade, httpTesting } = setup(5_000);
    load(facade);
    flushAll(httpTesting);
    TestBed.tick();

    facade.publish({ acknowledgedWarnings: [] });
    const publish = httpTesting.expectOne(PUBLISH_URL);
    TestBed.tick();

    vi.advanceTimersByTime(30_000);
    TestBed.tick();
    httpTesting.expectNone(WORKBENCH_URL);

    publish.flush({ publicId: ID, stampedAt: 1, memberCount: 0 });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  /**
   * `ISSUE_BUSY` is answered with a sentence AND a re-read.
   *
   * The screen that met it did not know work was running — that is why it asked
   * — so the sentence alone would leave the page looking idle beside a message
   * saying it is not. The re-read is what fetches the fact behind the refusal.
   */
  it('names the busy refusal and re-reads the issue that produced it', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.publish({ acknowledgedWarnings: [] });
    httpTesting
      .expectOne(PUBLISH_URL)
      .flush(
        { code: 'ISSUE_BUSY', message: 'a release is already running' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(facade.lastActionError()).toBe('ISSUE_BUSY');
    expect(publicationErrorKey({ error: { code: 'ISSUE_BUSY' } }, 'fallback')).toBe(
      'admin.publications.error.issueBusy',
    );

    // The re-read the refusal triggered, answering with the fact it was about.
    TestBed.tick();
    flushAll(httpTesting, { inFlight: { action: 'PUBLISH', since: 1 } });
    expect(facade.publishing()).toBe(true);
  });

  // ---------------------------------------------------------------- preview

  const PREVIEW_URL = `/rest/publication-issues/issue/${ID}/preview`;

  /**
   * A preview refetches the issue.
   *
   * Rendering touches the issue itself, so the copy this facade holds — its
   * stamp, and the version every write is submitted under — would otherwise be
   * one step behind the server's the moment a preview is made.
   */
  it('renders a preview per language and refetches the rail afterwards', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.generatePreview();

    const request = httpTesting.expectOne(PREVIEW_URL);
    expect(request.request.method).toBe('POST');
    request.flush([
      { lang: 'da', renderedAt: 1_700_000_000_000, href: '/rest/preview/da.pdf' },
      { lang: 'en', renderedAt: 1_700_000_000_001, href: '/rest/preview/en.pdf' },
    ]);
    TestBed.tick();

    expect(facade.previewRows().map((row) => row.lang)).toEqual(['da', 'en']);
    expect(facade.previewWorking()).toBe(false);

    flushAll(httpTesting, {
      checklist: {
        rows: [{ code: 'MEMBERS_RESOLVED', severity: 'OK', passed: true, acknowledgeable: false }],
        canPublish: true,
        blockingCodes: [],
      },
    });
    expect(facade.checklist()?.rows[0].passed).toBe(true);
  });

  /** A series with no report has nothing to render, and the refusal says which. */
  it('keeps a refused preview so the screen can name the reason', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.generatePreview();
    httpTesting
      .expectOne(PREVIEW_URL)
      .flush({ code: 'REPORT_NOT_CONFIGURED' }, { status: 409, statusText: 'Conflict' });

    expect(facade.previewRows()).toEqual([]);
    expect(facade.previewWorking()).toBe(false);
    expect(
      publicationErrorKey(facade.lastPreviewError(), 'admin.publications.preview.failed'),
    ).toBe('admin.publications.preview.noReport');
  });

  /**
   * A render outlives the page it was made on.
   *
   * The rows used to be whatever this sitting had produced, so the documents the
   * server was still holding had no route to them at all after a reload. They
   * arrive with the rest of the screen instead.
   */
  it('lists the previews the workbench answer carries', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      previews: [
        { lang: 'da', renderedAt: 1_700_000_000_000, href: '/rest/preview/da.pdf' },
        { lang: 'en', renderedAt: 1_700_000_000_001, href: '/rest/preview/en.pdf' },
      ],
    });

    expect(facade.previewRows().map((row) => row.lang)).toEqual(['da', 'en']);
    expect(facade.previewRows().map((row) => row.renderedAt)).toEqual([
      1_700_000_000_000, 1_700_000_000_001,
    ]);
  });

  /** A server that does not send the field, and one holding nothing, read alike. */
  it('lists no previews where the workbench answer carries none', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    expect(facade.previewRows()).toEqual([]);
  });

  // ------------------------------------------- what a release went without

  /**
   * A message published into a released period is in no issue.
   *
   * The released issue's answer names those messages, and the screen holds
   * them for the warning that offers the amend.
   */
  it('holds the messages published into a released issue since its release', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    const rows = [
      { messageUid: 'uid-1', messageId: 'NM-820-26', title: 'Buoy moved' },
      { messageUid: 'uid-2', title: null },
    ];
    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'PUBLISHED', descs: [{ lang: 'en', name: 'Week 1' }] },
      publishedSinceRelease: rows,
    });

    expect(facade.publishedSinceRelease()).toEqual(rows);
    expect(facade.publishedSinceRelease().length).toBe(2);
  });

  /** An empty list, a null and a server older than the field all read as nothing. */
  it('holds none where a released issue missed nothing or the answer names none', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'PUBLISHED', descs: [] },
      publishedSinceRelease: null,
    });

    expect(facade.publishedSinceRelease()).toEqual([]);
  });

  /** Only a published issue can have gone out without anything. */
  it('holds none on an issue that is not published, whatever the answer says', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'RETIRED', descs: [] },
      publishedSinceRelease: [{ messageUid: 'uid-1' }],
    });

    expect(facade.publishedSinceRelease()).toEqual([]);
  });

  /**
   * Both authors write the same rows, and the server has the last word.
   *
   * A render shows its own result at once rather than a round trip later, and
   * the refetch it triggers then re-seeds from the server — so the screen after
   * the refresh says what is actually stored, including on a frozen issue, which
   * holds no preview and must stop offering the ones from before.
   */
  it('shows a fresh render at once and re-seeds from the refetch it triggers', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      previews: [{ lang: 'da', renderedAt: 1_600_000_000_000, href: '/old.pdf' }],
    });

    facade.generatePreview();
    httpTesting
      .expectOne(PREVIEW_URL)
      .flush([{ lang: 'da', renderedAt: 1_700_000_000_000, href: '/new.pdf' }]);
    TestBed.tick();

    expect(facade.previewRows()).toEqual([
      { lang: 'da', renderedAt: 1_700_000_000_000, href: '/new.pdf' },
    ]);

    flushAll(httpTesting, {
      previews: [
        { lang: 'da', renderedAt: 1_700_000_000_000, href: '/new.pdf' },
        { lang: 'en', renderedAt: 1_700_000_000_002, href: '/new-en.pdf' },
      ],
    });

    expect(facade.previewRows().map((row) => row.lang)).toEqual(['da', 'en']);
  });

  // -------------------------------------------------------------- curation

  /**
   * One decision over a list, and the wire says so.
   *
   * `messageUids` rather than a request per row: the server applies all of them or
   * none, and a client that fanned out would produce exactly the half-applied
   * state the all-or-nothing rule exists to prevent.
   */
  it('sends a curation decision as one all-or-nothing request', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    const done = vi.fn();
    facade.excludeMembers(['m1', 'm2'], 'Superseded before release', done);

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/overrides/exclude`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      messageUids: ['m1', 'm2'],
      reason: 'Superseded before release',
    });
    request.flush(null);
    TestBed.tick();
    flushAll(httpTesting);

    expect(done).toHaveBeenCalledTimes(1);
  });

  /**
   * The admin types what is printed on the message; the override needs the uid.
   *
   * Two calls, sequenced, because only the first knows what `NM-123-24` is.
   */
  it('resolves the typed message id before writing the override', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.includeByMessageId('NM-123-24', 'Requested by the author');

    httpTesting.expectOne('/rest/messages/message/NM-123-24').flush({
      id: 'uid-123',
      shortId: 'NM-123-24',
    });

    const include = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/overrides/include`);
    expect(include.request.body).toEqual({
      messageUids: ['uid-123'],
      reason: 'Requested by the author',
    });
    include.flush(null);
    TestBed.tick();
    flushAll(httpTesting);
  });

  /**
   * An unknown id never reaches the curation endpoint, and is reported with the
   * code the server would have used — so the dialog says the same thing either way.
   */
  it('reports an unresolvable message id without writing anything', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.includeByMessageId('NM-999-24', 'Requested by the author');
    // 200 with an empty body is how the messages endpoint says "no such message".
    httpTesting.expectOne('/rest/messages/message/NM-999-24').flush(null);

    expect(facade.lastActionError()).toBe('OVERRIDE_MESSAGE_NOT_FOUND');
    expect(facade.working()).toBe(false);
  });

  /** The reason travels as a query parameter, the same shape as retire. */
  it('withdraws an override by the message it was about', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.clearOverride('m1', 'Excluded in error');

    const request = httpTesting.expectOne(
      `/rest/publication-issues/issue/${ID}/overrides/m1?reason=Excluded%20in%20error`,
    );
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    TestBed.tick();
    flushAll(httpTesting);
  });

  /** A refusal keeps the server's own sentence: it names every uid it could not resolve. */
  it('keeps the refusal text beside the code', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.excludeMembers(['m1'], 'Superseded before release');
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/overrides/exclude`)
      .flush(
        { code: 'OVERRIDE_MESSAGE_NOT_FOUND', message: 'no message has uid m1' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(facade.lastActionError()).toBe('OVERRIDE_MESSAGE_NOT_FOUND');
    expect(facade.lastActionMessage()).toBe('no message has uid m1');
  });

  // ------------------------------------------------- amend and new edition

  /** The reason goes in the body; there is no cut-off and nothing to decide about the file. */
  it('amends a published issue and reports what the replacement carries', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    const amended = vi.fn();
    facade.amend({ reason: 'Wrong chart number', acknowledgedWarnings: [] }, amended);

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/amend`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      reason: 'Wrong chart number',
      acknowledgedWarnings: [],
    });
    request.flush({
      publicId: ID,
      stampedAt: 1_700_000_000_000,
      memberCount: 21,
      archivePaths: [],
    });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    expect(amended).toHaveBeenCalledWith(expect.objectContaining({ memberCount: 21 }));
  });

  /**
   * A 409 over warnings is not a failure to record and forget: it is the server
   * naming what it is still waiting for, and the dialog re-lists it.
   */
  it('re-lists the warnings a refused amend is still waiting on', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    const amended = vi.fn();
    facade.amend({ reason: 'Wrong chart number', acknowledgedWarnings: [] }, amended);
    httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/amend`).flush(
      {
        code: 'WARNING_NOT_ACKNOWLEDGED',
        message: 'not acknowledged',
        unacknowledgedWarnings: ['FEW_MEMBERS'],
      },
      { status: 409, statusText: 'Conflict' },
    );

    expect(amended).not.toHaveBeenCalled();
    expect(facade.unacknowledgedWarnings()).toEqual(['FEW_MEMBERS']);
    // The refusal ends the work: the documents are not being written after all.
    expect(facade.amending()).toBe(false);
    expect(facade.working()).toBe(false);
  });

  /**
   * The amend is named while it runs, for the card whose documents it replaces.
   *
   * The screen withholds those rows over it — they are the files about to be
   * superseded — so "something is busy" is not an answer it can render.
   */
  it('names the amend as the work in flight until the documents are written', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    facade.amend({ reason: 'Wrong chart number', acknowledgedWarnings: [] });
    expect(facade.amending()).toBe(true);
    expect(facade.publishing()).toBe(false);
    expect(facade.working()).toBe(true);

    // A second press changes nothing while the first is still rendering.
    facade.amend({ reason: 'Wrong chart number', acknowledgedWarnings: [] });

    httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/amend`).flush({
      publicId: ID,
      stampedAt: 1_700_000_000_000,
      memberCount: 21,
      archivePaths: [],
    });

    expect(facade.amending()).toBe(false);
    expect(facade.working()).toBe(false);
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });
  });

  /** An imported issue is a historical record; the refusal is named as itself. */
  it('names a refused amend on an imported issue', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    facade.amend({ reason: 'Wrong chart number', acknowledgedWarnings: [] });
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/amend`)
      .flush({ code: 'ISSUE_IMPORTED' }, { status: 409, statusText: 'Conflict' });

    expect(publicationErrorKey({ error: { code: facade.lastActionError() } }, 'x')).toBe(
      'admin.publications.amend.imported',
    );
  });

  /** The successor's id comes back so the screen can follow it. */
  it('opens a new edition and hands back its public id', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    const created = vi.fn();
    facade.newEdition(created);

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/new-edition`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({});
    request.flush({ publicId: 'i-2', status: 'OPEN', descs: [] });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    expect(created).toHaveBeenCalledWith('i-2');
  });

  // ------------------------------------------------------------ the revision

  const OPEN_AT_4 = { publicId: ID, status: 'OPEN', descs: [], version: 4 };

  /**
   * A release is composed against a member list, and the list can move.
   *
   * The revision the screen read goes out with the publish, so an admin pressing
   * it against a rail drawn before somebody else curated the issue is refused
   * rather than freezing a different publication from the one they reviewed.
   */
  it('publishes against the revision the screen read', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: OPEN_AT_4 });

    facade.publish({ acknowledgedWarnings: [] });

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/publish`);
    expect(request.request.body).toEqual({
      acknowledgedWarnings: [],
      version: 4,
    });
    request.flush({ publicId: ID, stampedAt: 1, memberCount: 3 });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [], version: 5 } });
  });

  /**
   * A stale refusal is answered by re-reading, not by pressing again.
   *
   * The refused write changed nothing, so the screen has to show what moved
   * underneath it — and that re-read is also what makes a second attempt carry a
   * revision the server will accept. Without it the admin is left pressing a
   * button that can only ever be refused for the same reason.
   */
  it('re-reads the issue when a publish is refused as composed against an older revision', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: OPEN_AT_4 });

    facade.publish({ acknowledgedWarnings: [] });
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}/publish`)
      .flush(
        { code: 'STALE_VERSION', storedVersion: 5, submittedVersion: 4 },
        { status: 409, statusText: 'Conflict' },
      );
    TestBed.tick();

    expect(facade.lastActionError()).toBe('STALE_VERSION');
    // The refusal is a refusal like any other: the screen names it, and the busy
    // flag is released so the admin can act on what the re-read brings back.
    expect(facade.working()).toBe(false);

    flushAll(httpTesting, { issue: { publicId: ID, status: 'OPEN', descs: [], version: 5 } });
    expect(facade.issue()?.version).toBe(5);

    // And the next attempt is composed against what is now on screen.
    facade.publish({ acknowledgedWarnings: [] });
    const retry = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/publish`);
    expect(retry.request.body).toEqual({
      acknowledgedWarnings: [],
      version: 5,
    });
    retry.flush({ publicId: ID, stampedAt: 1, memberCount: 3 });
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [], version: 6 } });
  });

  /**
   * A curation carries THE ISSUE's revision, and the server forces it on.
   *
   * Two curators who both loaded the same open issue collide here rather than
   * one of them silently replacing the other's decision in a member list neither
   * will re-read — which is the case a member list cannot show, because an
   * excluded message has no row left to show it in.
   */
  it('curates against the issue’s revision, and re-reads when it has moved', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: OPEN_AT_4 });

    facade.includeByMessageId('NM-123-24', 'Requested by the author');
    httpTesting.expectOne('/rest/messages/message/NM-123-24').flush({ id: 'uid-123' });

    const include = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/overrides/include`);
    expect(include.request.body).toEqual({
      messageUids: ['uid-123'],
      reason: 'Requested by the author',
      version: 4,
    });
    include.flush(
      { code: 'STALE_VERSION', storedVersion: 5, submittedVersion: 4 },
      { status: 409, statusText: 'Conflict' },
    );
    TestBed.tick();

    expect(facade.lastActionError()).toBe('STALE_VERSION');
    flushAll(httpTesting, { issue: { publicId: ID, status: 'OPEN', descs: [], version: 5 } });
    expect(facade.issue()?.version).toBe(5);
  });

  /**
   * Every other refusal leaves the screen where it was.
   *
   * Re-reading after a rule refusal would replace what the admin is looking at
   * with the same thing they were already looking at, and would fire a burst of
   * requests behind every mistyped interval.
   */
  it('does not re-read on a refusal that is not about the revision', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: OPEN_AT_4 });

    facade.edit({ intervalFrom: 2, intervalTo: 1 });
    httpTesting
      .expectOne(`/rest/publication-issues/issue/${ID}`)
      .flush({ code: 'INTERVAL_INVERTED' }, { status: 409, statusText: 'Conflict' });
    TestBed.tick();

    expect(facade.lastActionError()).toBe('INTERVAL_INVERTED');
    httpTesting.expectNone(WORKBENCH_URL);
  });

  /**
   * The document and withdrawal calls put it in the query, where the body cannot
   * carry it: a multipart envelope, a DELETE with no body, a bare status token.
   */
  it('sends the revision as a query parameter where there is no body to put it in', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: OPEN_AT_4 });

    facade.clearFile('da');
    const clear = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/file/da?version=4`);
    expect(clear.request.method).toBe('DELETE');
    clear.flush({});
    TestBed.tick();
    flushAll(httpTesting, { issue: OPEN_AT_4 });

    facade.clearOverride('m1', 'Excluded in error');
    httpTesting
      .expectOne(
        `/rest/publication-issues/issue/${ID}/overrides/m1?reason=Excluded%20in%20error&version=4`,
      )
      .flush(null);
    TestBed.tick();
    flushAll(httpTesting, { issue: OPEN_AT_4 });
  });
});

/**
 * What the criteria left out, from the same answer as the members.
 *
 * Not its own question any more: the rows and the count are decided by the
 * resolution the member list beside them came from, so the two panels cannot
 * describe a message as both taken and dropped.
 */
describe('IssueDetailFacade — what the criteria left out', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  const MISSES = { missCount: 5, misses: [{ messageUid: 'uid-x', code: 'AFTER_CUTOFF' }] };

  it('reports nothing before an issue is loaded', () => {
    const { facade } = setup();

    expect(facade.omissionCount()).toBe(0);
    expect(facade.omissions()).toEqual([]);
  });

  /**
   * The rows are a sample; the count is the answer.
   *
   * A resolve over a whole published history drops more than a panel can show,
   * so the server caps the rows and counts them all — and a panel that said
   * "1 left out" while the server meant 5 would understate the difference
   * between the document and the query behind it.
   */
  it('reports the count the payload carries beside the sample of rows', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { omissions: MISSES });

    expect(facade.omissions()).toEqual([{ messageUid: 'uid-x', code: 'AFTER_CUTOFF' }]);
    expect(facade.omissionCount()).toBe(5);
    expect(facade.omissionsBounded()).toBe(true);
  });

  /** A sample that IS the whole set is not announced as one. */
  it('reports an unbounded set when the rows account for the count', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      omissions: { missCount: 1, misses: [{ messageUid: 'uid-x', code: 'AFTER_CUTOFF' }] },
    });

    expect(facade.omissionsBounded()).toBe(false);
  });

  /**
   * Nothing resolved means nothing to report, and the panel is hidden.
   *
   * The payload leaves the half out entirely on an issue whose membership is
   * frozen and on a series that has no query to answer for — which reads exactly
   * like a query that dropped nothing, deliberately, because in both cases there
   * is nothing to show.
   */
  it('reports nothing when the payload carries no omissions', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    expect(facade.omissionCount()).toBe(0);
    expect(facade.omissions()).toEqual([]);
    expect(facade.error()).toBe(false);
  });

  /**
   * NOTHING IS ASKED A SECOND TIME.
   *
   * The omissions used to be resolved on their own, which asked the corpus the
   * same question the member list had just asked and could answer it for a
   * different instant. The suite's own `verify()` is what proves there is no
   * second request: a stray one fails this test in `afterEach`.
   */
  it('asks nothing beyond the one request for what was left out', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { omissions: MISSES });

    expect(facade.omissionCount()).toBe(5);
    expect(facade.members()).toEqual([]);
  });

  /** A refetch answers again, including with nothing. */
  it('forgets what was left out when the issue comes back without it', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { omissions: MISSES });
    expect(facade.omissionCount()).toBe(5);

    facade.refresh();
    TestBed.tick();
    flushAll(httpTesting);

    expect(facade.omissionCount()).toBe(0);
    expect(facade.omissions()).toEqual([]);
  });
});

/**
 * Removing an issue, which is the one mutation here that does not refetch.
 *
 * There is nothing left to read: the issue, its members, its standing decisions
 * and its own trail are gone, and a refresh would answer with the 404 that
 * produces — a failed screen where the caller is about to leave for the series.
 */
describe('IssueDetailFacade — removing the issue', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  /** The path is the issue's own, and the revision travels beside it. */
  it('DELETEs the issue at the revision the screen is holding', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      issue: { publicId: ID, status: 'OPEN', version: 4, descs: [] },
    });

    const done = vi.fn();
    facade.deleteIssue(done);
    const req = httpTesting.expectOne(
      (request) =>
        request.method === 'DELETE' && request.url === `/rest/publication-issues/issue/${ID}`,
    );
    expect(req.request.params.get('version')).toBe('4');
    // The language rides along for the refusal: a removal blocked by citations
    // names the messages citing the issue, and those titles are read here.
    expect(req.request.params.get('lang')).toBe('en');
    req.flush(null);

    expect(done).toHaveBeenCalledTimes(1);
    expect(facade.working()).toBe(false);
  });

  /** No re-read: `verify()` in the afterEach is what asserts nothing followed. */
  it('does not read the issue back after removing it', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting);

    facade.deleteIssue();
    httpTesting.expectOne((request) => request.method === 'DELETE').flush(null);
    TestBed.tick();

    httpTesting.expectNone(WORKBENCH_URL);
  });

  /**
   * A refusal over citations keeps the ids, because the sentence cannot carry
   * them: the count is the real answer and the rows are a capped sample of it.
   */
  it('keeps the messages a citation refusal named', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'RETIRED', descs: [] } });

    const refused = vi.fn();
    facade.deleteIssue(refused);
    httpTesting
      .expectOne((request) => request.method === 'DELETE')
      .flush(
        {
          code: 'ISSUE_CITED',
          message: 'still cited',
          citingMessages: [
            { messageId: 'NM-814-26', uid: 'uid-a' },
            { messageId: 'NM-2-26', uid: 'uid-b' },
          ],
          citingCount: 31,
        },
        { status: 409, statusText: 'Conflict' },
      );

    expect(refused).not.toHaveBeenCalled();
    expect(facade.lastActionError()).toBe('ISSUE_CITED');
    expect(facade.citingMessages().map((m) => m.messageId)).toEqual(['NM-814-26', 'NM-2-26']);
    expect(facade.citingCount()).toBe(31);
    expect(facade.citationsBounded()).toBe(true);
  });

  /**
   * The list belongs to the refusal that produced it.
   *
   * A citation list left standing under an unrelated refusal reads as though
   * messages were citing an issue the second attempt never asked about.
   */
  it('drops the citations once another action is attempted', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'RETIRED', descs: [] } });

    facade.deleteIssue();
    httpTesting
      .expectOne((request) => request.method === 'DELETE')
      .flush(
        { code: 'ISSUE_CITED', citingMessages: [{ messageId: 'NM-814-26' }], citingCount: 1 },
        { status: 409, statusText: 'Conflict' },
      );
    expect(facade.citingMessages().length).toBe(1);

    facade.reactivate();
    httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/reactivate`).flush({});
    // Every other mutation refetches, so the re-read has to be answered before
    // `verify()` looks at what is still outstanding.
    TestBed.tick();
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    expect(facade.citingMessages()).toEqual([]);
    expect(facade.citingCount()).toBe(0);
  });

  /** A refusal that is not about citations carries none of them. */
  it('names an ordinary refusal without a citation list', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { issue: { publicId: ID, status: 'PUBLISHED', descs: [] } });

    facade.deleteIssue();
    httpTesting
      .expectOne((request) => request.method === 'DELETE')
      .flush({ code: 'ISSUE_PUBLISHED_NOT_DELETABLE' }, { status: 409, statusText: 'Conflict' });

    expect(facade.lastActionError()).toBe('ISSUE_PUBLISHED_NOT_DELETABLE');
    expect(facade.citingMessages()).toEqual([]);
    expect(
      publicationErrorKey({ error: { code: 'ISSUE_PUBLISHED_NOT_DELETABLE' } }, 'fallback'),
    ).toBe('admin.publications.error.issuePublishedNotDeletable');
  });
});

/**
 * ONE INSTANT DECIDES THE WHOLE SCREEN.
 *
 * An open issue whose planned cut-off has passed has two honest answers to
 * "what is in it": the messages a release right now would freeze, and the ones
 * that were there when the period was planned to close. They are different
 * lists, they imply different documents, and the difference is exactly the
 * messages published in between — so the screen reads at ONE of the two and
 * re-reads to change, rather than filtering one answer into the shape of the
 * other.
 */
describe('IssueDetailFacade — which instant the issue is read at', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  const HOUR = 60 * 60 * 1000;
  const PLANNED = Date.now() - 6 * HOUR;

  const AT_URL = `${WORKBENCH_URL}&at=${PLANNED}`;

  const OPEN_ISSUE = {
    publicId: ID,
    status: 'OPEN',
    descs: [{ lang: 'en', name: 'Week 36' }],
    intervalFrom: PLANNED - 7 * 24 * HOUR,
    intervalTo: PLANNED,
  };

  const EARLY = { messageUid: 'uid-early', frozenShortId: 'NM-100-26' };
  const LATE = { messageUid: 'uid-late', frozenShortId: 'NM-101-26' };

  /** Answer one read, with whatever the test wants it to carry. */
  function answer(
    httpTesting: HttpTestingController,
    url: string,
    shape: {
      issue?: Record<string, unknown>;
      members?: unknown[];
      afterPlannedCutoff?: unknown[];
      lateAfter?: number | null;
      viewedAt?: number;
    } = {},
  ) {
    httpTesting.expectOne(url).flush({
      issue: shape.issue ?? OPEN_ISSUE,
      members: shape.members ?? [],
      audit: [],
      overrides: [],
      checklist: { rows: [], canPublish: true, blockingCodes: [] },
      afterPlannedCutoff: shape.afterPlannedCutoff,
      lateAfter: shape.lateAfter,
      viewedAt: shape.viewedAt ?? Date.now(),
    });
    TestBed.tick();
  }

  /**
   * A weekly notice closes when it goes out, so "now" is what its release
   * freezes and the first read is the only one.
   */
  it('reads as of now, asking for no instant, where the series releases at the click', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { cutoffDefault: 'RELEASE_MOMENT' });
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    expect(facade.viewInstant()).toBe('now');
    expect(facade.members().length).toBe(2);
  });

  /**
   * A publication that closes at the end of its content period is read there.
   *
   * The release its admin is about to make closes at that boundary however late
   * the document is produced, so a screen showing today's resolution would show
   * them a member list their own release will not freeze.
   */
  it('reads a period-end series at its planned cut-off once that has passed', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { cutoffDefault: 'PERIOD_END' });
    load(facade);
    // The first read cannot know the planned cut-off — it comes back IN the
    // answer — so it is taken as of now and the instant it names is read next.
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    expect(facade.viewInstant()).toBe('planned');
    answer(httpTesting, AT_URL, {
      members: [EARLY],
      afterPlannedCutoff: [LATE],
      viewedAt: PLANNED,
    });

    expect(facade.members()).toEqual([EARLY]);
    expect(facade.viewedAt()).toBe(PLANNED);
  });

  /** A cut-off still ahead has nothing to be read back to. */
  it('reads as of now while the planned cut-off has not passed', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { cutoffDefault: 'PERIOD_END' });
    load(facade);
    answer(httpTesting, WORKBENCH_URL, {
      issue: { ...OPEN_ISSUE, intervalTo: Date.now() + 6 * HOUR },
    });

    expect(facade.viewInstant()).toBe('now');
  });

  /**
   * The switch is a RE-READ, not a filter.
   *
   * Members, the rail and the omissions are one resolution, so asking the
   * server again is the only way they can all answer for the instant on screen.
   */
  it('re-reads at the planned cut-off when the reader switches, and back again', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    facade.setViewInstant('planned');
    TestBed.tick();
    answer(httpTesting, AT_URL, { members: [EARLY], afterPlannedCutoff: [LATE] });
    expect(facade.viewInstant()).toBe('planned');
    expect(facade.members()).toEqual([EARLY]);

    facade.setViewInstant('now');
    TestBed.tick();
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });
    expect(facade.viewInstant()).toBe('now');
    expect(facade.members().length).toBe(2);
  });

  /**
   * The late rows are lifted out of the table, not dropped.
   *
   * They are members at this instant — a release right now would carry them —
   * so the count above the list keeps them and only the table below does not
   * repeat them.
   */
  it('splits the late rows out of the table and keeps them in the count', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    expect(facade.members().length).toBe(2);
    expect(facade.lateMembers()).toEqual([LATE]);
    expect(facade.tableMembers()).toEqual([EARLY]);
    expect(facade.switchAvailable()).toBe(true);
  });

  /**
   * Nothing published late means the two readings select the same messages, and
   * a switch between two identical answers is a control that does nothing.
   */
  it('offers no switch where nothing was published after the planned cut-off', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY] });

    expect(facade.switchAvailable()).toBe(false);
    expect(facade.lateMembers()).toEqual([]);
    expect(facade.tableMembers()).toEqual([EARLY]);
  });

  /**
   * WHICH instant those rows are late for comes back with them.
   *
   * The answer measures lateness against the instant the request named where it
   * named one — a period being typed is asked about at the cut-off in its box —
   * so the screen reads it off the answer rather than assuming the value on
   * file, which is a different moment exactly when it matters.
   */
  it('hands back the instant the answer measured lateness against', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    const typed = PLANNED - 2 * HOUR;
    answer(httpTesting, WORKBENCH_URL, {
      members: [EARLY, LATE],
      afterPlannedCutoff: [LATE],
      lateAfter: typed,
    });

    expect(facade.lateAfter()).toBe(typed);
  });

  /**
   * `null` is the server saying there is no such instant, and it arrives with an
   * empty list. It reaches the screen the same way a server that says nothing at
   * all does, where the issue's own planned cut-off is what was measured against.
   */
  it('reports no instant where the answer names none', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY], lateAfter: null });

    expect(facade.lateAfter()).toBeUndefined();
    expect(facade.lateMembers()).toEqual([]);
  });

  /**
   * The preview renders at the instant on screen.
   *
   * In the planned view that is the planned cut-off, which the publish dialog
   * also offers first. Left to the server, a period-end series rendered there
   * and a release-moment one as of now, whichever view was on screen.
   */
  it('previews at the planned cut-off in the planned view', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { cutoffDefault: 'PERIOD_END' });
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });
    expect(facade.viewInstant()).toBe('planned');
    answer(httpTesting, AT_URL, { members: [EARLY], afterPlannedCutoff: [LATE] });

    facade.generatePreview();

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/preview`);
    expect(request.request.body).toEqual({ at: PLANNED });
    request.flush([]);
    TestBed.tick();
    // The refetch every render triggers, still at the instant on screen.
    answer(httpTesting, AT_URL, { members: [EARLY], afterPlannedCutoff: [LATE] });
  });

  /** In the as-of-now view the render is taken now, not at the planned cut-off. */
  it('previews as of now in the now view, whatever the series prefers', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { cutoffDefault: 'PERIOD_END' });
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });
    answer(httpTesting, AT_URL, { members: [EARLY], afterPlannedCutoff: [LATE] });
    facade.setViewInstant('now');
    TestBed.tick();
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    const before = Date.now();
    facade.generatePreview();
    const after = Date.now();

    const request = httpTesting.expectOne(`/rest/publication-issues/issue/${ID}/preview`);
    const at = (request.request.body as { at: number }).at;
    expect(at).toBeGreaterThanOrEqual(before);
    expect(at).toBeLessThanOrEqual(after);
    request.flush([]);
    TestBed.tick();
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });
  });

  /**
   * A frozen issue is the record of what went out.
   *
   * There is no second instant to read it at, so the preference that would have
   * chosen one is not consulted and no second request is made.
   */
  it('reads a published issue as of now whatever the series prefers', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { cutoffDefault: 'PERIOD_END' });
    load(facade);
    answer(httpTesting, WORKBENCH_URL, {
      issue: { ...OPEN_ISSUE, status: 'PUBLISHED' },
      members: [EARLY],
    });

    expect(facade.viewInstant()).toBe('now');
  });

  /**
   * The instant belongs to ONE issue.
   *
   * A planned cut-off carried into the next issue would key its first read to a
   * moment outside its own period, which the server refuses outright.
   */
  it('forgets the instant when another issue is loaded', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });
    facade.setViewInstant('planned');
    TestBed.tick();
    answer(httpTesting, AT_URL, { members: [EARLY], afterPlannedCutoff: [LATE] });

    facade.load('i-2');
    TestBed.tick();

    expect(facade.viewInstant()).toBe('now');
    httpTesting.expectOne('/rest/publication-issues/issue/i-2/workbench?lang=en').flush({
      issue: { publicId: 'i-2', status: 'OPEN', descs: [] },
      members: [],
      audit: [],
      overrides: [],
      viewedAt: Date.now(),
    });
    TestBed.tick();
  });
});

/**
 * The period the screen is answering for, while somebody is typing one.
 *
 * The edit panel holds two date boxes, and the numbers beside them have to
 * describe the period IN THEM rather than the one on file — otherwise the only
 * way to find out what an edit would select is to save it. Asked of the server
 * rather than filtered from the answer already in hand, for the reason the view
 * switch is: members, the rail and the omissions are one resolution, and a
 * client that re-cut one of them would put a count of one period beside a list
 * of another.
 */
describe('IssueDetailFacade — a period being proposed', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  const HOUR = 60 * 60 * 1000;
  const STORED_FROM = Date.now() - 30 * 24 * HOUR;
  const STORED_TO = Date.now() - 6 * HOUR;
  const TYPED_FROM = Date.now() - 60 * 24 * HOUR;
  const TYPED_TO = Date.now() - 2 * HOUR;

  const OPEN_ISSUE = {
    publicId: ID,
    status: 'OPEN',
    descs: [{ lang: 'en', name: 'Week 36' }],
    intervalFrom: STORED_FROM,
    intervalTo: STORED_TO,
  };

  const EARLY = { messageUid: 'uid-early', frozenShortId: 'NM-100-26' };
  const LATE = { messageUid: 'uid-late', frozenShortId: 'NM-101-26' };

  function answer(
    httpTesting: HttpTestingController,
    url: string,
    shape: {
      issue?: Record<string, unknown>;
      members?: unknown[];
      afterPlannedCutoff?: unknown[];
      lateAfter?: number | null;
      viewedAt?: number;
      // Null is a real answer: an in-force resolution has no lower bound at all.
      viewedFrom?: number | null;
    } = {},
  ) {
    httpTesting.expectOne(url).flush({
      issue: shape.issue ?? OPEN_ISSUE,
      members: shape.members ?? [],
      audit: [],
      overrides: [],
      checklist: { rows: [], canPublish: true, blockingCodes: [] },
      afterPlannedCutoff: shape.afterPlannedCutoff,
      lateAfter: shape.lateAfter,
      viewedAt: shape.viewedAt ?? Date.now(),
      viewedFrom: shape.viewedFrom,
    });
    TestBed.tick();
  }

  /** The screen as it stands, so each test starts from the stored period. */
  function loaded() {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });
    return { facade, httpTesting };
  }

  /**
   * Both ends of the typed period go on the wire, and the read is taken again.
   *
   * The instant AND the start, because they are one question: the resolution
   * runs over the window they bound, and either alone describes a period nobody
   * typed.
   */
  it('re-reads over the typed period, start and instant together', () => {
    const { facade, httpTesting } = loaded();

    facade.setPreviewPeriod({ from: TYPED_FROM, to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}&from=${TYPED_FROM}`, {
      members: [EARLY],
      viewedAt: TYPED_TO,
      viewedFrom: TYPED_FROM,
    });

    expect(facade.members()).toEqual([EARLY]);
    expect(facade.viewedAt()).toBe(TYPED_TO);
    expect(facade.viewedFrom()).toBe(TYPED_FROM);
  });

  /**
   * A period that ends in the future ends now.
   *
   * Nothing exists beyond this moment, so the two questions have one answer —
   * and only one of them is an instant the server accepts. Said by carrying no
   * instant at all, which is what "now" is on this endpoint, so that every
   * future end keys the same read rather than one per keystroke.
   */
  it('asks as of now for a period whose end has not happened yet', () => {
    const { facade, httpTesting } = loaded();

    facade.setPreviewPeriod({ from: TYPED_FROM, to: Date.now() + 48 * HOUR });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&from=${TYPED_FROM}`, { members: [EARLY, LATE] });

    expect(facade.members().length).toBe(2);
  });

  /**
   * NOTHING IS LATE AGAINST A CUT-OFF THAT HAS NOT ARRIVED.
   *
   * The read for such a period is the as-of-now one — there being no future
   * instant to resolve at — and the answer to that question names the rows that
   * are late against the cut-off ON FILE. Left as they came, the screen would
   * head them "published after the planned cut-off, <the saved instant>" while a
   * different instant sits in the box beside them; the count that goes with the
   * period being typed is nought, and the instant it is counted from is the one
   * in the box.
   */
  it('counts nothing as late while the cut-off being typed has not arrived', () => {
    const { facade, httpTesting } = loaded();
    expect(facade.lateMembers()).toEqual([LATE]);

    const ahead = Date.now() + 48 * HOUR;
    facade.setPreviewPeriod({ from: TYPED_FROM, to: ahead });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&from=${TYPED_FROM}`, {
      members: [EARLY, LATE],
      // The server answered about the STORED cut-off, which is the only one the
      // as-of-now question could be measured against.
      afterPlannedCutoff: [LATE],
      lateAfter: STORED_TO,
    });

    expect(facade.lateMembers()).toEqual([]);
    expect(facade.lateAfter()).toBe(ahead);
    // And the row is back in the table it was lifted out of: relative to the
    // period being typed it is an ordinary member.
    expect(facade.tableMembers().length).toBe(2);
  });

  /** An emptied end box is an open period, which closes now. */
  it('asks as of now for a period with no end typed into it', () => {
    const { facade, httpTesting } = loaded();

    facade.setPreviewPeriod({ from: TYPED_FROM });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&from=${TYPED_FROM}`, {
      members: [EARLY, LATE],
      afterPlannedCutoff: [LATE],
      lateAfter: STORED_TO,
    });

    expect(facade.members().length).toBe(2);
    // An open end closes now, and nothing is published after now.
    expect(facade.lateMembers()).toEqual([]);
  });

  /**
   * A cut-off that HAS arrived is answered by the server, not clamped here.
   *
   * The read for it carries that instant, so the rows come back counted from the
   * very cut-off in the box — and the client has nothing to correct.
   */
  it('keeps the answer’s own late rows for a typed cut-off that has passed', () => {
    const { facade, httpTesting } = loaded();

    facade.setPreviewPeriod({ from: TYPED_FROM, to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}&from=${TYPED_FROM}`, {
      members: [EARLY, LATE],
      afterPlannedCutoff: [LATE],
      lateAfter: TYPED_TO,
    });

    expect(facade.lateMembers()).toEqual([LATE]);
    expect(facade.lateAfter()).toBe(TYPED_TO);
  });

  /** An emptied start box leaves the issue's own start to answer for it. */
  it('sends no start when none is typed, and still asks about the typed end', () => {
    const { facade, httpTesting } = loaded();

    facade.setPreviewPeriod({ to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}`, { members: [EARLY] });

    expect(facade.members()).toEqual([EARLY]);
  });

  /**
   * The proposal ends, and the screen is the saved issue again.
   *
   * Nothing was written, so there is nothing to keep: the read goes back to the
   * period on file at the instant the reader had chosen.
   */
  it('reads the stored period again once the proposal ends', () => {
    const { facade, httpTesting } = loaded();
    facade.setPreviewPeriod({ from: TYPED_FROM, to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}&from=${TYPED_FROM}`, { members: [EARLY] });

    facade.setPreviewPeriod(undefined);
    TestBed.tick();
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    expect(facade.members().length).toBe(2);
  });

  /**
   * The reader's own choice of instant waits while a period is being typed.
   *
   * Both readings are about the period ON FILE. Offered beside two boxes naming
   * another one, the switch asks the reader to choose between two answers to a
   * question they have already replaced.
   */
  it('withholds the second reading while a period is being proposed', () => {
    const { facade, httpTesting } = loaded();
    expect(facade.switchAvailable()).toBe(true);

    facade.setPreviewPeriod({ from: TYPED_FROM, to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}&from=${TYPED_FROM}`, {
      members: [EARLY],
      afterPlannedCutoff: [LATE],
    });

    expect(facade.switchAvailable()).toBe(false);

    facade.setPreviewPeriod(undefined);
    TestBed.tick();
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY, LATE], afterPlannedCutoff: [LATE] });

    expect(facade.switchAvailable()).toBe(true);
  });

  /** A server that does not echo the start resolved over the issue's own. */
  it('falls back to the issue’s stored start where the answer names none', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY] });

    expect(facade.viewedFrom()).toBe(STORED_FROM);
  });

  /**
   * An in-force answer names no start, and says so with a null rather than by
   * leaving the key out. The reader takes it the same way: the issue's own.
   */
  it('takes an answer that resolved over no start at all', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    answer(httpTesting, WORKBENCH_URL, { members: [EARLY], viewedFrom: null });

    expect(facade.viewedFrom()).toBe(STORED_FROM);
  });

  /**
   * A PERIOD THAT STARTS AFTER IT ENDS IS NOT ASKED ABOUT.
   *
   * The server refuses it — `from ≤ at` — and the refusal used to arrive as a
   * failure of the whole screen: the answer was dropped, the edit panel that
   * caused it unmounted with everything else, and Retry re-sent the same
   * impossible question. Two clicks away on a real issue, because moving the
   * start date forward is how a period gets carried forward and the calendar
   * offers no bound. So the question is not sent, the numbers stand as they
   * were, and the screen is told which state the boxes are in.
   */
  it('asks nothing over a period that starts after it ends', () => {
    const { facade } = loaded();

    facade.setPreviewPeriod({ from: TYPED_TO, to: TYPED_FROM });
    TestBed.tick();

    // No request: the afterEach verify would report one.
    expect(facade.periodProblem()).toBe('START_AFTER_CUTOFF');
    expect(facade.members().length).toBe(2);
    expect(facade.issue()?.publicId).toBe(ID);
    expect(facade.error()).toBe(false);
    expect(facade.loading()).toBe(false);
  });

  /**
   * Nor one that has not begun — a different sentence for the reader, and the
   * same refusal on the wire, because nothing exists past the server's own now.
   */
  it('asks nothing over a period that has not started yet', () => {
    const { facade } = loaded();

    facade.setPreviewPeriod({ from: Date.now() + 24 * HOUR, to: Date.now() + 48 * HOUR });
    TestBed.tick();

    expect(facade.periodProblem()).toBe('START_IN_FUTURE');
    expect(facade.members().length).toBe(2);
  });

  /** And it reads again the moment the boxes describe a period that can be read. */
  it('reads again as soon as the typed period can be answered', () => {
    const { facade, httpTesting } = loaded();

    facade.setPreviewPeriod({ from: TYPED_TO, to: TYPED_FROM });
    TestBed.tick();
    expect(facade.periodProblem()).toBe('START_AFTER_CUTOFF');

    facade.setPreviewPeriod({ from: TYPED_FROM, to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}&from=${TYPED_FROM}`, { members: [EARLY] });

    expect(facade.periodProblem()).toBeUndefined();
    expect(facade.members()).toEqual([EARLY]);
  });

  /**
   * A REFUSED RE-READ KEEPS THE ANSWER IT HAD.
   *
   * The screen is emptied only while there is nothing on it to keep. Once an
   * issue has been read, dropping it on a failed refresh took the edit panel —
   * date boxes, Cancel and all — down with it, so a refusal in the middle of an
   * edit could only be left through Back, which discards the edit without
   * asking. The banner says the numbers are not current; they are still a whole
   * answer taken a moment ago.
   */
  it('keeps the issue on screen when a re-read is refused', () => {
    const { facade, httpTesting } = loaded();

    facade.refresh();
    TestBed.tick();
    httpTesting.expectOne(WORKBENCH_URL).flush('no', { status: 500, statusText: 'Server Error' });
    TestBed.tick();

    expect(facade.error()).toBe(true);
    expect(facade.issue()?.publicId).toBe(ID);
    expect(facade.members().length).toBe(2);
    expect(facade.loading()).toBe(false);
  });

  /**
   * A read of ANOTHER issue carries nothing over.
   *
   * The panel is reused when one issue hands over to another, and the
   * predecessor's members standing under the successor's address is a screen
   * that looks loaded and is wrong.
   */
  it('shows nothing of the previous issue while the next one is loading', () => {
    const { facade, httpTesting } = loaded();

    facade.load('i-2');
    TestBed.tick();

    expect(facade.issue()).toBeUndefined();
    expect(facade.members()).toEqual([]);
    expect(facade.loading()).toBe(true);

    httpTesting.expectOne('/rest/publication-issues/issue/i-2/workbench?lang=en').flush({
      issue: { publicId: 'i-2', status: 'OPEN', descs: [] },
      members: [],
      audit: [],
      overrides: [],
      viewedAt: Date.now(),
    });
    TestBed.tick();
  });

  /**
   * A proposal belongs to the issue it was typed against.
   *
   * Carried into the next one it would read that issue over a window out of the
   * previous issue's period — which the server refuses outright.
   */
  it('forgets the proposed period when another issue is loaded', () => {
    const { facade, httpTesting } = loaded();
    facade.setPreviewPeriod({ from: TYPED_FROM, to: TYPED_TO });
    TestBed.tick();
    answer(httpTesting, `${WORKBENCH_URL}&at=${TYPED_TO}&from=${TYPED_FROM}`, { members: [EARLY] });

    facade.load('i-2');
    TestBed.tick();

    httpTesting.expectOne('/rest/publication-issues/issue/i-2/workbench?lang=en').flush({
      issue: { publicId: 'i-2', status: 'OPEN', descs: [] },
      members: [],
      audit: [],
      overrides: [],
      viewedAt: Date.now(),
    });
    TestBed.tick();
  });
});

/**
 * A COMPILATION, on the side that assembles it.
 *
 * Its members are the source issues' own frozen lists laid end to end, and that
 * is the only shape that makes a year of them readable. The cut into runs is a
 * DERIVATION over the same list every count and every selection is taken from,
 * so a grouped table can never disagree with the flat one underneath it.
 */
describe('IssueDetailFacade — a compilation', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  const WEEK_12 = { publicId: 'w-12', seriesId: 'weekly-ntm', week: 12, name: 'EfS uge 12' };
  const WEEK_13 = { publicId: 'w-13', seriesId: 'weekly-ntm', week: 13, name: 'EfS uge 13' };

  /** Two weeks and one message somebody added by hand — the three kinds of run. */
  const COMPILED = [
    { messageUid: 'uid-a', source: 'COMPILED', sourceIssue: WEEK_12 },
    { messageUid: 'uid-b', source: 'COMPILED', sourceIssue: WEEK_12 },
    { messageUid: 'uid-c', source: 'COMPILED', sourceIssue: WEEK_13 },
    { messageUid: 'uid-h', source: 'OVERRIDE_INCLUDE' },
  ];

  /**
   * The survey travels exactly as it arrived, absence included.
   *
   * It is the workbench's own statement of which regime answered: a `[]`
   * substituted for a missing field would turn "this issue compiles nothing"
   * into "this compilation found nothing", which is the difference between no
   * card at all and a card saying the year is empty.
   */
  it('carries the source survey through as it came', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      sources: [{ publicId: 'w-12', status: 'PUBLISHED', memberCount: 2 }],
    });

    expect(facade.sources()).toEqual([{ publicId: 'w-12', status: 'PUBLISHED', memberCount: 2 }]);
  });

  it('leaves the survey absent where the answer carried none', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { members: [{ messageUid: 'm1' }] });

    expect(facade.sources()).toBeUndefined();
  });

  /**
   * The survey is the record of what the year covered; the rows are what it printed.
   *
   * One heading per published source issue, in the order the survey lists them —
   * which IS the frozen cut-off order — and each row filed under the issue it
   * names. Taken from the survey rather than from the rows because only the
   * survey knows about a week that printed nothing.
   */
  it('takes the runs from the survey, in survey order, with the rows filed under them', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { timeRelation: 'COMPILED_FROM_SOURCE' });
    load(facade);
    flushAll(httpTesting, {
      members: COMPILED,
      sources: [
        { ...WEEK_13, status: 'PUBLISHED', memberCount: 1 },
        { ...WEEK_12, status: 'PUBLISHED', memberCount: 2 },
      ],
    });

    const groups = facade.groupedMembers();
    // The survey's order, not the rows': the rows arrived week 12 first.
    expect(groups.map((group) => group.key)).toEqual(['source:w-13', 'source:w-12', 'manual']);
    expect(groups[0].source?.name).toBe('EfS uge 13');
    expect(groups[0].members.map((member) => member.messageUid)).toEqual(['uid-c']);
    // And the rows keep the order they arrived in inside their own run.
    expect(groups[1].members.map((member) => member.messageUid)).toEqual(['uid-a', 'uid-b']);
    expect(groups[2].members.map((member) => member.messageUid)).toEqual(['uid-h']);
  });

  /**
   * A week that printed nothing is still a week the year covered.
   *
   * The whole reason the groups are taken from the survey: it contributes no
   * row, so a grouping cut from the rows drops it, and a year that lists
   * fifty-one of its fifty-two weeks says one of them was never covered.
   */
  it('keeps a published source that contributed no row as a run of its own', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { timeRelation: 'COMPILED_FROM_SOURCE' });
    load(facade);
    flushAll(httpTesting, {
      members: [COMPILED[0], COMPILED[1]],
      sources: [
        { ...WEEK_12, status: 'PUBLISHED', memberCount: 2 },
        { ...WEEK_13, status: 'PUBLISHED', memberCount: 0 },
      ],
    });

    const groups = facade.groupedMembers();
    expect(groups.map((group) => group.key)).toEqual(['source:w-12', 'source:w-13']);
    expect(groups[1].source?.name).toBe('EfS uge 13');
    expect(groups[1].members).toEqual([]);
    // The empty run adds no rows to the list every count is taken from.
    expect(facade.members().length).toBe(2);
  });

  /**
   * What contributed nothing at all is not a run.
   *
   * An open week and an uncovered stretch are not part of what the issue holds —
   * they are what the release rail warns about and what the sources card lists —
   * so heading the member table with them would claim the issue covers a period
   * no published issue ever answered for.
   */
  it('heads nothing with the open and the uncovered periods', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { timeRelation: 'COMPILED_FROM_SOURCE' });
    load(facade);
    flushAll(httpTesting, {
      members: [COMPILED[0], COMPILED[1]],
      sources: [
        { ...WEEK_12, status: 'PUBLISHED', memberCount: 2 },
        { status: 'MISSING', intervalFrom: 1 },
        { publicId: 'w-14', seriesId: 'weekly-ntm', week: 14, status: 'OPEN' },
      ],
    });

    expect(facade.groupedMembers().map((group) => group.key)).toEqual(['source:w-12']);
  });

  /**
   * A row whose source the survey does not name still has somewhere to go.
   *
   * The two answers come from different reads and should agree, and a member
   * with no run would vanish from the very table its count is taken from — so it
   * gets a run of its own after the surveyed ones, ahead of the hand-added rows.
   */
  it('appends a run for a source the survey does not hold, the hand-added rows still last', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { timeRelation: 'COMPILED_FROM_SOURCE' });
    load(facade);
    flushAll(httpTesting, {
      members: COMPILED,
      sources: [{ ...WEEK_12, status: 'PUBLISHED', memberCount: 2 }],
    });

    const groups = facade.groupedMembers();
    expect(groups.map((group) => group.key)).toEqual(['source:w-12', 'source:w-13', 'manual']);
    expect(groups[1].members.map((member) => member.messageUid)).toEqual(['uid-c']);
    expect(groups[2].source).toBeUndefined();
  });

  /**
   * Without a survey, the rows are every fact there is to cut the runs from.
   *
   * They arrive in the order they were compiled in, so the runs come out in that
   * order too — what such an answer cannot say is which weeks printed nothing.
   */
  it('cuts the members into one run per source issue where no survey came', () => {
    const { facade, httpTesting } = setup();
    attachSeries(facade, { timeRelation: 'COMPILED_FROM_SOURCE' });
    load(facade);
    flushAll(httpTesting, { members: COMPILED });

    const groups = facade.groupedMembers();
    expect(groups.map((group) => group.key)).toEqual(['source:w-12', 'source:w-13', 'manual']);
    expect(groups[0].source?.name).toBe('EfS uge 12');
    expect(groups[0].members.map((member) => member.messageUid)).toEqual(['uid-a', 'uid-b']);
    expect(groups[1].members.map((member) => member.messageUid)).toEqual(['uid-c']);
    // The run nobody printed names no source, which is what puts it last.
    expect(groups[2].source).toBeUndefined();
    expect(groups[2].members.map((member) => member.messageUid)).toEqual(['uid-h']);
    // And the flat list is untouched, because it is what the groups were cut from.
    expect(facade.members().length).toBe(4);
  });

  /**
   * The frozen provenance is the second witness, and on a released issue the
   * authoritative one: the series can be re-pointed after a release, and the
   * record of what this issue holds must not be redrawn when it is.
   */
  it('reads a released compilation as one from its own provenance', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, {
      issue: {
        publicId: ID,
        status: 'PUBLISHED',
        descs: [],
        membershipProvenance: 'COMPILED',
        snapshotSourceSeriesId: 'weekly-ntm',
      },
      members: COMPILED,
    });

    expect(facade.compiled()).toBe(true);
    expect(facade.groupedMembers().length).toBe(3);
  });

  /**
   * Nothing is grouped on any other regime.
   *
   * A row that names no source issue would land in one group holding everything,
   * which is a heading over the whole table saying nothing.
   */
  it('groups nothing on an issue that compiles nothing', () => {
    const { facade, httpTesting } = setup();
    load(facade);
    flushAll(httpTesting, { members: COMPILED });

    expect(facade.compiled()).toBe(false);
    expect(facade.groupedMembers()).toEqual([]);
  });
});
