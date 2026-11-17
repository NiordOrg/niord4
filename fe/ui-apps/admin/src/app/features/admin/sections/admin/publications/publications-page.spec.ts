import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ExpectedDate } from '../../../../../shared/components/expected-date/expected-date';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { fakeRowLinkRouter } from '../../../../../shared/testing/fake-row-link-router';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { OneOffPublication } from '../../../services/api/one-off-api.service';
import type {
  IssueTimeline,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { OneOffEditor } from './one-off-editor';
import { PublicationsPage } from './publications-page';
import { PublicationsFacade } from './publications.facade';
import { PublicationSeriesEditor } from './series-editor';
import { SeriesDetail } from './series-detail';
import { PublicationTransferTargets } from './transfer-owner';

@Component({
  imports: [PublicationsPage],
  template: `<app-publications-page />`,
})
class Host {}

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '' },
];

function series(over: Partial<PublicationSeries> = {}): PublicationSeries {
  return {
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    descs: [{ lang: 'en', name: 'Weekly NtM' }],
    ...over,
  } as PublicationSeries;
}

/** Only the parts of a one-off a test ever varies. */
interface OneOffOverrides {
  readonly series?: Partial<PublicationSeries>;
  readonly links?: readonly { lang: string; value: string | null }[];
  readonly fileNames?: readonly { lang: string; value: string | null }[];
  /** The first of the three facts: `OPEN`, `PUBLISHED`, `RETIRED`. */
  readonly issueStatus?: string;
  /** The issue the status transitions address. Explicitly `undefined` for one with none. */
  readonly issuePublicId?: string;
  readonly issueVersion?: number;
  readonly publicFrom?: number | null;
  readonly publicTo?: number | null;
  readonly categoryPublish?: boolean;
}

/**
 * A one-off row, said by whether it is out.
 *
 * The boolean seeds the STATUS rather than a fold of three facts: a published
 * publication is out, and one that is not is withdrawn unless a test says
 * otherwise. `active` travels too, because the server still sends it — nothing
 * on the screen reads it, and a fixture that dropped it would stop proving that.
 */
function oneOff(published: boolean, over: OneOffOverrides = {}): OneOffPublication {
  const { series: seriesOver, ...rest } = over;
  return {
    active: published,
    issueStatus: published ? 'PUBLISHED' : 'RETIRED',
    // The issue the two status transitions are addressed at, and the revision
    // they are checked against — both off this payload, which is the only place
    // a one-off screen learns either.
    issuePublicId: 'fyrliste-2026-1',
    issueVersion: 4,
    links: [],
    fileNames: [{ lang: 'en', value: 'fyrliste-2026.pdf' }],
    ...rest,
    series: {
      seriesId: 'fyrliste-2026',
      domainId: 'niord-annex',
      contentMode: 'UPLOADED_FILE',
      availability: 'ALL_DOMAINS',
      descs: [{ lang: 'en', name: 'List of lights 2026' }],
      ...(seriesOver ?? {}),
    },
  } as unknown as OneOffPublication;
}

/**
 * ONE facade mock, reused.
 *
 * `overrideComponent` caches the compiled definition, so a fresh mock per test
 * would leave the component bound to the first one and every list would keep
 * answering with the previous test's rows.
 */
const timelines = signal<ReadonlyMap<string, IssueTimeline>>(new Map());

const facade = {
  series: signal<readonly PublicationSeries[]>([]),
  loading: signal(false),
  error: signal(false),
  cadenced: signal<readonly PublicationSeries[]>([]),
  retired: signal<readonly PublicationSeries[]>([]),
  isEmpty: signal(false),
  timelineError: signal(false),
  timeline: (seriesId: string) => timelines().get(seriesId),
  refresh: vi.fn(),
  // The page tells the facade which of its two screens is on show; everything
  // the dashboard fetches is gated on it.
  setDashboardShown: vi.fn(),
  // The card's "new issue" flow.
  issueCreating: signal(false),
  createIssueErrorKey: signal(undefined),
  createIssue: vi.fn(),
};

/**
 * The desk the page is being read from.
 *
 * The list, the one-off table and both editor drawers are all scoped to it, so
 * it is set per test rather than left undefined.
 */
const sessionDomain = signal<Domain | undefined>(undefined);

/**
 * The query the page is reading, live.
 *
 * `?issue=` is written by the series screen rather than by this page, so it is a
 * subject a test can push to — which is the only way to reach the state where an
 * issue is open inside a series.
 */
let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

/**
 * The route param, live for the same reason.
 *
 * Moving from one series to another keeps this component and changes only the
 * param, so a test that wants the page to turn over pushes the new id here.
 */
let routeParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

/** The desks a transfer could move a one-off to. Empty hides the menu row. */
const transferTargets = signal<readonly Domain[]>([]);

/** The `domain` each one-off request carried, in order. Reset per setup. */
const requestedOneOffDomains: (string | null)[] = [];

/**
 * Every URL a visit asked for, in order. Reset per setup.
 *
 * `setup` answers whatever the page and its screen fetch, so by the time a test
 * runs there is nothing outstanding to look at — an assertion about what was
 * NOT requested has to read the record instead of the queue.
 */
const requestedUrls: string[] = [];

let router: {
  navigate: ReturnType<typeof vi.fn>;
  currentNavigation: () => { extras: { state?: Record<string, unknown> } } | null;
} & Record<string, unknown>;
let confirmService: ConfirmService;

interface MenuRow {
  readonly label?: string;
  readonly separator?: boolean;
  readonly disabled?: boolean;
  readonly command?: () => void;
}

interface PageInternals {
  confirmImport: () => void;
  copySeries: (seriesId: string) => void;
  editorModel: () => { mode: string; series: PublicationSeries } | undefined;
  seriesRefreshTick: () => number;
  onSeriesSaved: (series: PublicationSeries) => void;
  oneOffModel: () => { mode: string } | undefined;
  cogItems: () => readonly MenuRow[];
  newItems: () => readonly MenuRow[];
  oneOffMenus: () => ReadonlyMap<string, readonly MenuRow[]>;
  seriesFilter: () => string;
  setSeriesFilter: (value: string) => void;
  selectedSeries: PublicationSeries | undefined;
  selectedSeriesId: () => string | undefined;
  pageDescriptionKey: () => string | undefined;
  draftDialogOpen: () => boolean;
  draftSeries: () => PublicationSeries | undefined;
  submitDraft: (decision: { recovered: boolean }) => void;
  transferDialogOpen: () => boolean;
  submitTransfer: (decision: { domainId: string; reason: string }) => void;
  withdrawDialogOpen: () => boolean;
  submitWithdraw: (reason: string) => void;
}

interface SetupOptions {
  readonly selected?: string;
  readonly oneOffs?: readonly OneOffPublication[];
  readonly series?: readonly PublicationSeries[];
  readonly cadenced?: readonly PublicationSeries[];
  readonly timelines?: readonly IssueTimeline[];
  readonly timelineError?: boolean;
  readonly loading?: boolean;
  readonly error?: boolean;
  readonly retired?: readonly PublicationSeries[];
  /** What `?seriesFilter=` carried into the screen, as a deep link would. */
  readonly seriesFilter?: string;
  /** The desk the page is read from; the one-off list is scoped to it. */
  readonly sessionDomain?: string;
  /** What `?issue=` carried in, as a deep link into an open issue would. */
  readonly openIssue?: string;
  /** The desks a one-off could be moved to. */
  readonly transferTargets?: readonly string[];
  /** The row the click carried in navigation state, as opening a series does. */
  readonly navigatedSeries?: PublicationSeries;
}

async function setup(options: SetupOptions = {}) {
  vi.clearAllMocks();
  requestedOneOffDomains.length = 0;
  requestedUrls.length = 0;
  queryParams = new BehaviorSubject(
    convertToParamMap({
      ...(options.seriesFilter ? { seriesFilter: options.seriesFilter } : {}),
      ...(options.openIssue ? { issue: options.openIssue } : {}),
    }),
  );
  routeParams = new BehaviorSubject(
    convertToParamMap(options.selected ? { seriesId: options.selected } : {}),
  );
  transferTargets.set((options.transferTargets ?? []).map((domainId) => ({ domainId }) as Domain));
  sessionDomain.set(
    options.sessionDomain ? ({ domainId: options.sessionDomain } as Domain) : undefined,
  );
  facade.series.set(options.series ?? []);
  facade.cadenced.set(options.cadenced ?? []);
  facade.retired.set(options.retired ?? []);
  facade.loading.set(options.loading ?? false);
  facade.error.set(options.error ?? false);
  facade.isEmpty.set(false);
  facade.timelineError.set(options.timelineError ?? false);
  timelines.set(new Map((options.timelines ?? []).map((t) => [t.publicationSeriesId, t])));

  router = {
    ...fakeRowLinkRouter(),
    navigate: vi.fn(),
    // The clicked row rides on the navigation that opened the series screen, so
    // a deep link — which is what an absent state stands for here — has none.
    currentNavigation: () =>
      options.navigatedSeries ? { extras: { state: { series: options.navigatedSeries } } } : null,
  };
  const notifications = { success: vi.fn(), error: vi.fn(), notify: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      // The detail panel and the settings drawer both read the current domain to
      // decide what they may offer. Stubbed so the live facade's AuthFacade →
      // Keycloak chain is not pulled into a spec about a list screen.
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain,
          availableDomains: signal<readonly Domain[]>([]),
        } as unknown as DomainFacade,
      },
      // The transfer action asks which domains this admin administers, which
      // reaches AuthFacade -> Keycloak through the live service.
      {
        provide: PublicationTransferTargets,
        useValue: {
          targetsFor: () => transferTargets(),
        } as unknown as PublicationTransferTargets,
      },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), modelLanguages: LANGUAGES },
      },
      { provide: NotificationService, useValue: notifications },
      { provide: Router, useValue: router },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            // Kept beside the stream for anything that still reads the
            // snapshot; the list's filter follows the stream (L-175).
            queryParamMap: {
              get: (name: string) =>
                name === 'seriesFilter' ? (options.seriesFilter ?? null) : null,
            },
          },
          // Both subscribed rather than read once: the series follows the param
          // when a link moves the page from one series to another, and the open
          // issue is written by the series screen, so this page only hears about
          // it through the stream.
          paramMap: routeParams.asObservable(),
          queryParamMap: queryParams.asObservable(),
        },
      },
      {
        provide: LocaleFormatService,
        useValue: {
          formatDate: (v: number) => String(v),
          formatDateTime: (v: number) => String(v),
          formatWeekdayDateTime: (v: number) => String(v),
          uses12HourClock: () => false,
          sessionTimeZone: () => 'UTC',
        },
      },
    ],
  }).overrideComponent(PublicationsPage, {
    set: { providers: [{ provide: PublicationsFacade, useValue: facade }, ConfirmService] },
  });

  const fixture = TestBed.createComponent(Host);
  // What the page told the facade from its constructor alone: the host renders
  // nothing but the page, so at this point no effect has run yet.
  const gateBeforeFirstRender = facade.setDashboardShown.mock.calls.map((call) => call[0]);
  fixture.detectChanges();

  const httpTesting = TestBed.inject(HttpTestingController);
  // What the page and the screen it is showing fetch for themselves — the
  // one-off table here, the series screen's own reads on the other branch, and
  // the detail panel's, which start from a microtask. Neither editor exists
  // yet, so none of the four picker lists is among them: those are read when a
  // drawer is opened, by the test that opens it. Answering everything keeps this
  // spec about the page; the one-off list is the only response it reads.
  for (let round = 0; round < 6; round++) {
    await Promise.resolve();
    fixture.detectChanges();
    for (const request of httpTesting.match(() => true)) {
      // Recorded on the way past: by the time a test runs every request has
      // already been answered here, so the record is the only trace left of
      // what a plain visit fetched.
      requestedUrls.push(request.request.url);
      if (request.request.url.endsWith('/one-off-publications/')) {
        // The table is scoped to the desk, and the scope travels as a param.
        requestedOneOffDomains.push(request.request.params.get('domain'));
      }
      request.flush(answerFor(request.request.url, options));
    }
  }
  fixture.detectChanges();

  const page = fixture.debugElement.children[0];
  confirmService = page.injector.get(ConfirmService);
  return {
    fixture,
    httpTesting,
    notifications,
    gateBeforeFirstRender,
    element: fixture.nativeElement as HTMLElement,
    page: page.componentInstance as unknown as PageInternals,
  };
}

/**
 * A shaped answer for every request the page and its children make.
 *
 * Only the one-off list is read by any assertion here; the rest are answered
 * with the smallest well-formed payload their consumer can render, because an
 * empty array where an object is expected fails inside a child rather than
 * where the test is looking.
 */
function answerFor(url: string, options: SetupOptions): object | unknown[] {
  if (url.endsWith('/one-off-publications/')) {
    return options.oneOffs ?? [];
  }
  if (url.includes('/publication-series/editable-series/')) {
    return series({ seriesId: options.selected ?? 'weekly-ntm' });
  }
  if (url.includes('/publication-issues/series/')) {
    return {
      publicationSeriesId: options.selected ?? 'weekly-ntm',
      data: [],
      total: 0,
      gapDetection: { enabled: false, reasonCode: 'SERIES_NOT_ACTIVE', reason: '' },
    };
  }
  if (url.includes('/issue-draft')) {
    return {
      descs: [{ lang: 'en', name: 'The next issue' }],
      intervalFrom: 1_756_000_000_000,
      intervalTo: 1_756_814_400_000,
      warnings: [],
    };
  }
  return [];
}

/**
 * Answer whatever the newly-mounted detail panel asks for.
 *
 * Selecting a series mounts `<app-series-detail>`, whose facade loads from its
 * own input — so a test that only asserts on the navigation still has to settle
 * the fetches that navigation starts, or `verify()` fails on them.
 */
async function drainDetail(
  fixture: { detectChanges: () => void },
  httpTesting: HttpTestingController,
  options: SetupOptions,
): Promise<string[]> {
  const answered: string[] = [];
  for (let round = 0; round < 4; round++) {
    await Promise.resolve();
    fixture.detectChanges();
    for (const request of httpTesting.match(() => true)) {
      answered.push(request.request.url);
      request.flush(answerFor(request.request.url, options));
    }
  }
  fixture.detectChanges();
  return answered;
}

/**
 * The series screen mounted on the detail branch.
 *
 * Reached through the component rather than through its markup: what this page
 * owes it is four bindings, and asserting on the rendered header cluster would
 * be a test of the series screen rather than of the seam between them.
 */
function seriesDetail(fixture: ComponentFixture<Host>): SeriesDetail {
  return fixture.debugElement.query(By.directive(SeriesDetail)).componentInstance as SeriesDetail;
}

/**
 * The expectation marker on the first card's cut-off line.
 *
 * Read off the component rather than off its output: what the card decides is
 * the RESOLUTION, and the rendered string comes from a locale service the spec
 * has stubbed to echo the epoch back.
 */
function cutoffMarker(fixture: ComponentFixture<Host>): ExpectedDate {
  const card = fixture.debugElement.query(By.css('.grid .admin-card'));
  return card.query(By.directive(ExpectedDate)).componentInstance as ExpectedDate;
}

/** Every button label on screen, trimmed — the way a duplicated control is counted. */
function buttonLabels(element: HTMLElement): string[] {
  return [...element.querySelectorAll('button')].map((b) => (b.textContent ?? '').trim());
}

function disclosure(element: HTMLElement, key: string): HTMLButtonElement | undefined {
  return [...element.querySelectorAll('button')].find((b) => b.textContent?.includes(key));
}

describe('PublicationsPage — the fetch chain', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * A refetch reports itself in a strip; it does not take the screen away.
   *
   * Every mutation on this page refetches the list, so a loading branch that
   * owned the whole screen tore down the toolbar, both create buttons and the
   * cog after each save.
   */
  it('keeps the toolbar mounted while the list is in flight', async () => {
    const { element } = await setup({ loading: true });

    expect(element.querySelector('.admin-toolbar')).not.toBeNull();
    expect(
      [...element.querySelectorAll('button')].some((b) =>
        b.textContent?.includes('admin.publications.new.label'),
      ),
    ).toBe(true);
    expect(element.querySelector('app-progress-bar')).not.toBeNull();
  });

  /** A failed list still offers the way back in, rather than an empty screen. */
  it('keeps the toolbar mounted when the list fails, and offers a retry', async () => {
    const { element } = await setup({ error: true });

    expect(element.querySelector('.admin-toolbar')).not.toBeNull();
    const banner = element.querySelector('app-error-banner');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('admin.publications.retry');
  });

  /**
   * The dashboard is one screen now.
   *
   * The categories moved to a section of their own, so a tab strip here would be
   * a strip with one tab in it — and the page's one route param is spent on the
   * series id, which is why the tab was never deep-linkable in the first place.
   */
  it('has no tab strip and does not mount the categories screen', async () => {
    const { element } = await setup();

    expect(element.querySelector('app-tabs')).toBeNull();
    expect(element.querySelector('app-publication-categories-tab')).toBeNull();
  });
});

describe('PublicationsPage — the detail branch', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** A series open, reached by a click that carried the row with it. */
  const OPEN: SetupOptions = { selected: 'weekly-ntm', navigatedSeries: series() };

  /**
   * ONE header per level, and one control each.
   *
   * The back link, the settings button and the copy action are the series
   * screen's own header cluster. The row this page used to draw above it
   * repeated all three — pointing at the parent while describing the child.
   */
  it('draws no back, copy or settings control of its own above the series', async () => {
    const { element } = await setup(OPEN);

    const labels = buttonLabels(element);
    // The page's own key was `detail.back`, which is a PREFIX of the series
    // screen's `detail.backToList` -- so the absent one is matched exactly.
    expect(labels.filter((label) => label === 'admin.publications.detail.back')).toEqual([]);
    expect(
      labels.filter((label) => label.includes('admin.publications.detail.backToList')).length,
    ).toBe(1);
    expect(
      labels.filter((label) => label.includes('admin.publications.series.editor.settings')).length,
    ).toBe(1);
    // Copy lives in the screen's `⋯` menu now, under a key of its own.
    expect(labels.some((label) => label.includes('admin.publications.series.editor.copy'))).toBe(
      false,
    );
  });

  /**
   * The clicked row travels down, so the header is drawn before the request
   * behind the series answers.
   *
   * It travels in the NAVIGATION rather than being looked up in the list: the
   * series branch fetches no list, and the two branches are separate routes, so
   * arriving here rebuilds this component with nothing but the URL.
   */
  it('hands the series screen the row the click carried', async () => {
    const { fixture, page } = await setup(OPEN);

    expect(page.selectedSeries?.seriesId).toBe('weekly-ntm');
    expect(seriesDetail(fixture).initial()?.seriesId).toBe('weekly-ntm');
  });

  /** A deep link carries no row — nobody clicked one — and it says so rather than guessing. */
  it('hands down nothing when the screen was reached by a link', async () => {
    const { fixture, page } = await setup({ selected: 'weekly-ntm' });

    expect(page.selectedSeries).toBeUndefined();
    expect(seriesDetail(fixture).initial()).toBeUndefined();
  });

  /**
   * Moving from one series to another keeps this component: only the param
   * changes. A link from a compiled issue to one of the weeks it was compiled
   * from is exactly that move, and the page has to turn over to the series the
   * address now names rather than wait for a reload.
   *
   * The row the first click carried belongs to the series the reader LEFT, so
   * the header under the new address is not drawn from it.
   */
  it('turns over to the series the address moves to', async () => {
    const { fixture, page, httpTesting, element } = await setup(OPEN);
    await drainDetail(fixture, httpTesting, OPEN);
    expect(element.textContent).toContain('Weekly NtM');

    routeParams.next(convertToParamMap({ seriesId: 'accumulated-yearly-ntm' }));
    fixture.detectChanges();

    expect(page.selectedSeriesId()).toBe('accumulated-yearly-ntm');
    expect(seriesDetail(fixture).seriesId()).toBe('accumulated-yearly-ntm');
    expect(element.textContent).not.toContain('Weekly NtM');
    const answered = await drainDetail(fixture, httpTesting, {
      ...OPEN,
      selected: 'accumulated-yearly-ntm',
    });
    expect(answered.some((url) => url.endsWith('/editable-series/accumulated-yearly-ntm'))).toBe(
      true,
    );
  });

  /**
   * The dashboard's data is the dashboard's, and this page holds both screens.
   *
   * Nothing tears the facade or the one-off table down on the way into a series,
   * so without the gate the list, both strip reads and the one-off table were
   * paid for again on every visit to a screen that reads none of them.
   */
  it('fetches nothing of the dashboard’s while a series is open', async () => {
    const { gateBeforeFirstRender } = await setup(OPEN);

    // Closed from the constructor, before any effect has had a chance to run:
    // the facade's reads emit on the first flush, and a gate that arrived on
    // that same flush would arrive after them.
    expect(gateBeforeFirstRender).toEqual([false]);
    expect(facade.setDashboardShown).toHaveBeenCalledWith(false);
    expect(requestedOneOffDomains).toEqual([]);
  });

  it('tells the facade the dashboard is on show when no series is selected', async () => {
    await setup();

    expect(facade.setDashboardShown).toHaveBeenCalledWith(true);
  });

  /**
   * A save in the settings drawer reaches the screen showing the series.
   *
   * The drawer is mounted here and the series screen fetches the series itself,
   * so without being told it goes on stating what the series was before the
   * save — name, cadence and public authority included.
   */
  it('tells the series screen to re-read itself after a save in the drawer', async () => {
    const { fixture, httpTesting, page } = await setup(OPEN);
    expect(page.seriesRefreshTick()).toBe(0);

    page.onSeriesSaved(series({ seriesId: 'weekly-ntm' }));
    fixture.detectChanges();

    expect(page.seriesRefreshTick()).toBe(1);
    expect(seriesDetail(fixture).refreshTick()).toBe(1);
    // And it stays on the series rather than navigating to it again.
    expect(router.navigate).not.toHaveBeenCalled();
    // The screen re-reads the series it is showing, which is the whole point.
    await drainDetail(fixture, httpTesting, OPEN);
  });

  /** Which series is selected is the LIST's state, so going back is its decision. */
  it('clears the selection when the series screen goes back', async () => {
    const { fixture, httpTesting } = await setup(OPEN);

    seriesDetail(fixture).back.emit();
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledWith(['..'], expect.anything());
    // The dashboard is on show again, so its own reads start.
    await drainDetail(fixture, httpTesting, OPEN);
  });

  /**
   * The settings drawer is mounted here, on the series the screen handed up — a
   * second request would ask for what is already in hand, and the list this page
   * used to find the row in is not fetched on this branch at all.
   */
  it('opens the settings drawer on the series the screen hands up', async () => {
    const { fixture, httpTesting, page } = await setup(OPEN);

    seriesDetail(fixture).settings.emit(series({ seriesId: 'weekly-ntm' }));
    fixture.detectChanges();

    expect(page.editorModel()?.mode).toBe('edit');
    expect(page.editorModel()?.series.seriesId).toBe('weekly-ntm');

    // The drawer loads its own option lists on open.
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /** Copy opens the same drawer on the SERVER's template for a new series. */
  it('copies the series when the series screen asks for it', async () => {
    const { fixture, httpTesting, page } = await setup(OPEN);

    seriesDetail(fixture).copySeries.emit();
    httpTesting
      .expectOne('/rest/publication-series/copy-series-template/weekly-ntm')
      .flush(series({ seriesId: '', status: 'DRAFT' }));
    fixture.detectChanges();

    expect(page.editorModel()?.mode).toBe('add');
    expect(page.editorModel()?.series.seriesId).toBe('');

    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /**
   * The list is not read on this branch, so it has no failure to report here.
   *
   * Two tests stood here: one for a banner repeating the list's failure, and one
   * for the refusal raised when the list did not hold the series the settings
   * drawer was asked for. Both described a list this branch no longer fetches —
   * the series screen reports its own read failing, and the settings drawer is
   * opened on the series that screen hands up.
   */
  it('draws no banner about a list it does not fetch', async () => {
    const { element } = await setup({ ...OPEN, error: true });

    expect(element.querySelector('app-error-banner')).toBeNull();
  });
});

describe('PublicationsPage — the archives', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** A retired series is reachable by click, exactly as a live card is. */
  it('opens a retired series from its row, and gives the row a control', async () => {
    const { fixture, element } = await setup({
      retired: [series({ seriesId: 'legacy-efs', status: 'RETIRED' })],
    });

    // The group is collapsed by default; the disclosure is what opens it.
    disclosure(element, 'group.retired')?.click();
    fixture.detectChanges();

    const row = element.querySelector<HTMLTableRowElement>('tbody tr.row-interactive');
    expect(row).not.toBeNull();
    // Reachable without a mouse, and openable in a second tab: the name is a
    // real link inside the row, addressing the series it opens.
    const link = row?.querySelector<HTMLAnchorElement>('a.row-link');
    expect(link).not.toBeNull();
    expect(link?.getAttribute('href')).toBe('/legacy-efs');

    row?.click();
    expect(router.navigate).toHaveBeenCalledWith(['legacy-efs'], expect.anything());
  });
});

describe('PublicationsPage — the one-off list', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The table lists the one-offs this desk OWNS, like the series list beside it.
   *
   * A one-off another domain has shared with this one is something to cite from
   * the message editor, not a row with an edit pencil on it here.
   */
  it('asks for the session domain’s own one-offs', async () => {
    await setup({ sessionDomain: 'niord-annex' });
    expect(requestedOneOffDomains).toEqual(['niord-annex']);
  });

  /** No desk, no scoping: the parameter is omitted rather than sent blank. */
  it('omits the filter when there is no session domain', async () => {
    await setup();
    expect(requestedOneOffDomains).toEqual([null]);
  });

  /**
   * WHERE IT STANDS, and separately WHERE THE PUBLIC CAN READ IT.
   *
   * One dot used to fold a series status, an issue status and a public period
   * into the single word "active", and every one of them was invisible from the
   * other two: a publication being prepared, one that had been withdrawn and one
   * due to go public next January all painted the same grey.
   */
  it('names the status in its own column, with no dot anywhere', async () => {
    const { element } = await setup({
      oneOffs: [oneOff(true, { publicFrom: Date.UTC(2017, 1, 22) })],
    });

    expect(element.querySelector('.status-dot')).toBeNull();
    expect(element.querySelector('[data-testid="one-off-status"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.status.PUBLISHED',
    );
    expect(element.querySelector('[data-testid="one-off-public"]')?.textContent?.trim()).toBe(
      'admin.publications.visibility.compact.since',
    );
  });

  it('names a withdrawn one-off as withdrawn in both columns', async () => {
    const { element } = await setup({ oneOffs: [oneOff(false)] });

    expect(element.querySelector('[data-testid="one-off-status"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.status.WITHDRAWN',
    );
    expect(element.querySelector('[data-testid="one-off-public"]')?.textContent?.trim()).toBe(
      'admin.publications.visibility.compact.withdrawn',
    );
  });

  /** A publication nobody has released, and one prepared for a day still to come. */
  it('tells a draft from one that is due to go public', async () => {
    const later = Date.now() + 90 * 24 * 60 * 60 * 1000;
    const { element } = await setup({
      oneOffs: [
        oneOff(true, { issueStatus: 'OPEN' }),
        oneOff(true, {
          series: { seriesId: 'scheduled-2027' },
          publicFrom: later,
        }),
      ],
    });

    const cells = [...element.querySelectorAll('[data-testid="one-off-public"]')].map((cell) =>
      (cell.textContent ?? '').trim(),
    );
    expect(cells).toEqual([
      'admin.publications.visibility.compact.draft',
      'admin.publications.visibility.compact.scheduled',
    ]);
  });

  /** A period that has run out reads as history, not as a publication that failed. */
  it('names a period that has run out as historic', async () => {
    const { element } = await setup({
      oneOffs: [
        oneOff(true, { publicFrom: Date.UTC(2017, 1, 22), publicTo: Date.UTC(2017, 11, 31) }),
      ],
    });

    expect(element.querySelector('[data-testid="one-off-public"]')?.textContent?.trim()).toBe(
      'admin.publications.visibility.compact.historic',
    );
  });

  /**
   * A category the public site does not list is the FIRST question: no status
   * and no period can put a publication onto a site that never lists it.
   */
  it('names a publication in a non-public category as not public', async () => {
    const { element } = await setup({
      oneOffs: [oneOff(true, { categoryPublish: false, publicFrom: Date.UTC(2017, 1, 22) })],
    });

    expect(element.querySelector('[data-testid="one-off-public"]')?.textContent?.trim()).toBe(
      'admin.publications.visibility.compact.notPublicCategoryUnnamed',
    );
  });

  /**
   * Ordered by the START of the public period, in three states.
   *
   * The third is the order the endpoint answered in — that is the order the
   * estate is administered in, and a column that could only be sorted by date
   * would have no way back to it. A publication with no start sorts LAST in both
   * directions: it is not early or late, it has no period.
   */
  it('sorts by the start of the public period, and back again', async () => {
    const { element, fixture } = await setup({
      oneOffs: [
        oneOff(true, {
          series: { seriesId: 'mid', descs: [{ lang: 'en', name: 'Mid' }] },
          publicFrom: Date.UTC(2020, 0, 1),
        }),
        oneOff(true, {
          series: { seriesId: 'none', descs: [{ lang: 'en', name: 'No period' }] },
          issueStatus: 'OPEN',
        }),
        oneOff(true, {
          series: { seriesId: 'old', descs: [{ lang: 'en', name: 'Old' }] },
          publicFrom: Date.UTC(2015, 0, 1),
        }),
      ],
    });

    const order = (): string[] =>
      [...element.querySelectorAll('tbody tr td:first-child button')].map((button) =>
        (button.textContent ?? '').trim(),
      );
    const header = element.querySelector<HTMLElement>('[data-testid="one-off-public-sort"]')!;
    const ariaSort = (): string | null => header.closest('th')?.getAttribute('aria-sort') ?? null;

    // Served: the order the endpoint answered in.
    expect(ariaSort()).toBe('none');
    expect(order()).toEqual(['Mid', 'No period', 'Old']);

    // The most recent period is what an admin pressing this is after, so it is
    // the first press rather than the second.
    header.click();
    await fixture.whenStable();
    expect(ariaSort()).toBe('descending');
    expect(order()).toEqual(['Mid', 'Old', 'No period']);

    header.click();
    await fixture.whenStable();
    expect(ariaSort()).toBe('ascending');
    expect(order()).toEqual(['Old', 'Mid', 'No period']);

    header.click();
    await fixture.whenStable();
    expect(ariaSort()).toBe('none');
    expect(order()).toEqual(['Mid', 'No period', 'Old']);
  });

  /** The action column carries a header for a screen reader, even with nothing visible in it. */
  it('names the action column for a screen reader', async () => {
    const { element } = await setup({ oneOffs: [oneOff(true)] });

    const hidden = [...element.querySelectorAll('thead th .sr-only')].map((el) =>
      (el.textContent ?? '').trim(),
    );
    expect(hidden).toContain('admin.publications.column.actions');
  });

  /**
   * An empty table names the desk, and says why it can still be empty.
   *
   * The reference publications every domain cites are owned by one desk and
   * administered only there — so "none here" is not "none at all", and an admin
   * who reads it as the second creates a duplicate.
   *
   * Said compactly: this is one band inside a card with cards above and below
   * it, and the full-height placeholder — glyph included — made the section
   * holding nothing the tallest thing on the screen.
   */
  it('states the empty case, naming the desk and the publications owned elsewhere', async () => {
    const { element } = await setup({ sessionDomain: 'niord-nm' });

    expect(element.querySelector('app-empty-state')).not.toBeNull();
    expect(element.querySelector('app-empty-state app-icon')).toBeNull();
    expect(element.textContent).toContain('admin.publications.oneOff.noneInDomain');
    expect(element.textContent).toContain('admin.publications.oneOff.elsewhereNote');
  });
});

describe('PublicationsPage — the series cards', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  function cardOptions(): SetupOptions {
    return {
      // The cadence is what tells the strip beneath the card that its cells are
      // weeks: a series numbered by nothing falls every cell back to the
      // period's name.
      cadenced: [series({ seriesId: 'weekly-ntm', cadence: 'WEEKLY' })],
      timelines: [
        {
          publicationSeriesId: 'weekly-ntm',
          rows: [
            { week: 33, computedStatus: 'MISSING' },
            { week: 32, computedStatus: 'PUBLISHED', publicId: 'p-32', memberCount: 12 },
          ],
          gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
        },
      ],
    };
  }

  /**
   * The card is a container with a button inside it, not a button itself.
   *
   * The strip beneath carries one control per period, and a button inside a
   * button is neither valid HTML nor reachable from a keyboard — the cells would
   * be swallowed by the card.
   */
  it('never nests the period controls inside the card’s own button', async () => {
    const { element } = await setup(cardOptions());

    expect(element.querySelector('app-issue-timeline')).not.toBeNull();
    expect(element.querySelector('button button')).toBeNull();
  });

  /**
   * The card hands its series to the strip, which is the only way a cell can
   * know that its number is a week rather than a year the reader is being shown
   * for no reason.
   */
  it('tells the strip what the series is numbered by', async () => {
    const { element } = await setup(cardOptions());

    const cells = [...element.querySelectorAll<HTMLElement>('app-issue-timeline .pub-cell')];
    expect(cells.map((cell) => cell.textContent?.trim())).toEqual(['33', '32']);
  });

  /**
   * A cell links to exactly the URL an admin would reach by opening the series
   * and clicking the row — the series as a sibling segment, the issue as the
   * query parameter that screen already carries.
   */
  it('opens the issue behind a cell on the series’ own screen', async () => {
    const { element, fixture, httpTesting } = await setup(cardOptions());

    const cells = element.querySelectorAll<HTMLElement>('app-issue-timeline .pub-cell');
    // Newest first: the missing period, then the issue that did come out.
    cells[1].click();
    fixture.detectChanges();
    await drainDetail(fixture, httpTesting, cardOptions());

    expect(router.navigate).toHaveBeenCalledWith(['weekly-ntm'], {
      relativeTo: expect.anything(),
      queryParams: { issue: 'p-32' },
      // The clicked row rides along, so the header behind the issue is drawn.
      state: { series: expect.objectContaining({ seriesId: 'weekly-ntm' }) },
    });
  });

  /** A missing period has no issue to open, so its cell opens the series itself. */
  it('sends a missing period to the series, where it can be recovered', async () => {
    const { element, fixture, httpTesting } = await setup(cardOptions());

    element.querySelector<HTMLElement>('app-issue-timeline .pub-cell')?.click();
    fixture.detectChanges();
    await drainDetail(fixture, httpTesting, cardOptions());

    expect(router.navigate).toHaveBeenCalledWith(['weekly-ntm'], {
      relativeTo: expect.anything(),
      // The clicked row rides along, so the series screen draws its header
      // before its own request answers.
      state: { series: expect.objectContaining({ seriesId: 'weekly-ntm' }) },
    });
  });

  /**
   * The strips decorate a page that works without them, so their failure is said
   * once and quietly rather than as a banner over a section that still works.
   */
  it('says the strips could not be read without failing the list', async () => {
    const { element } = await setup({ ...cardOptions(), timelineError: true });

    expect(element.textContent).toContain('admin.publications.timeline.error');
    expect(element.querySelectorAll('app-error-banner').length).toBe(0);
  });
});

describe('PublicationsPage — importing', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The import asks first, and names what it overwrites.
   *
   * The upload is not additive: it rewrites every series the file names, field
   * for field, and drops a series whose category is missing here with nothing
   * but a line in the batch log. None of that is visible from a file picker.
   */
  it('confirms before opening the import picker', async () => {
    const { fixture, page } = await setup();

    page.confirmImport();
    fixture.detectChanges();

    const pending = confirmService.active();
    expect(pending).toBeDefined();
    expect(pending?.intent).toBe('destructive');
    expect(pending?.message).toContain('admin.publications.import.confirm.message');
    confirmService.dismiss();
  });
});

describe('PublicationsPage — copying a series', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The copy comes from the SERVER, like the blank template does.
   *
   * Deciding here which fields to drop would put a second answer beside the one
   * the save is validated against — and the id comes back blank, which is the
   * one thing the admin has to supply.
   */
  it('opens the create drawer on the server’s copy of the series', async () => {
    const { fixture, httpTesting, page } = await setup({ selected: 'weekly-ntm' });

    page.copySeries('weekly-ntm');
    const request = httpTesting.expectOne(
      '/rest/publication-series/copy-series-template/weekly-ntm',
    );
    expect(request.request.method).toBe('GET');
    request.flush(series({ seriesId: '', status: 'DRAFT' }));
    fixture.detectChanges();

    expect(page.editorModel()?.mode).toBe('add');
    expect(page.editorModel()?.series.seriesId).toBe('');

    // The drawer loads its own option lists on open; answering them keeps this
    // test about the copy rather than about the editor.
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  it('names the failure rather than opening an empty drawer', async () => {
    const { fixture, httpTesting, page, notifications } = await setup({ selected: 'weekly-ntm' });

    page.copySeries('weekly-ntm');
    httpTesting
      .expectOne('/rest/publication-series/copy-series-template/weekly-ntm')
      .flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(page.editorModel()).toBeUndefined();
    expect(notifications.error).toHaveBeenCalledWith('admin.publications.toast.copyError');
  });
});

/**
 * One box over every group.
 *
 * A reader looking for a publication does not know which section it is filed
 * under, so one filter narrows the cards, the one-off table and the retired
 * group together.
 */
describe('PublicationsPage - the title filter', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const WEEKLY = series({ seriesId: 'weekly-ntm', descs: [{ lang: 'en', name: 'Weekly NtM' }] });
  const ANNUAL = series({
    seriesId: 'annual-list',
    descs: [{ lang: 'da', name: 'Årlig oversigt' }],
  });

  it('narrows the cadenced cards, and says so when nothing is left', async () => {
    const { element, fixture, page } = await setup({
      cadenced: [WEEKLY, ANNUAL],
      series: [WEEKLY, ANNUAL],
    });
    expect(element.textContent).toContain('Weekly NtM');
    expect(element.textContent).toContain('Årlig oversigt');

    page.setSeriesFilter('weekly');
    fixture.detectChanges();
    expect(element.textContent).toContain('Weekly NtM');
    expect(element.textContent).not.toContain('Årlig oversigt');

    page.setSeriesFilter('nothing at all');
    fixture.detectChanges();
    expect(element.textContent).toContain('admin.publications.filter.noMatches');
  });

  /** Accents are folded, so "arlig" finds "Årlig" and neither spelling hides a row. */
  it('matches a name whose accents the reader did not type', async () => {
    const { element, fixture, page } = await setup({ cadenced: [WEEKLY, ANNUAL] });

    page.setSeriesFilter('arlig');
    fixture.detectChanges();

    expect(element.textContent).toContain('Årlig oversigt');
    expect(element.textContent).not.toContain('Weekly NtM');
  });

  it('narrows the one-off table and the retired group from the same box', async () => {
    const retired = series({
      seriesId: 'legacy-efs',
      status: 'RETIRED',
      descs: [{ lang: 'da', name: 'Efterretninger' }],
    });
    const { element, fixture, page } = await setup({
      cadenced: [WEEKLY],
      retired: [retired],
      oneOffs: [oneOff(true)],
    });
    expect(element.textContent).toContain('List of lights 2026');
    expect(disclosure(element, 'group.retired')).toBeDefined();

    page.setSeriesFilter('lights');
    fixture.detectChanges();

    // The one-off survives; the retired disclosure and the series card do not.
    expect(element.textContent).toContain('List of lights 2026');
    expect(disclosure(element, 'group.retired')).toBeUndefined();
    expect(element.textContent).not.toContain('Weekly NtM');
  });

  /** An empty one-off table under a filter says why it is empty. */
  it('tells an emptied one-off table apart from one with nothing in it', async () => {
    const { element, fixture, page } = await setup({ oneOffs: [oneOff(true)] });

    page.setSeriesFilter('weekly');
    fixture.detectChanges();

    expect(element.textContent).toContain('admin.publications.filter.noMatches');
    expect(element.textContent).not.toContain('admin.publications.oneOff.noneInDomain');
  });

  /**
   * The filter lives in the address bar, so a reload and a shared link both keep
   * it. Merged rather than replacing the query, and removed rather than left
   * blank when the box is emptied.
   */
  it('mirrors the settled filter into the query, and drops it when cleared', async () => {
    const { fixture, page } = await setup({ cadenced: [WEEKLY] });

    page.setSeriesFilter('weekly');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        queryParams: { seriesFilter: 'weekly' },
        queryParamsHandling: 'merge',
      }),
    );

    page.setSeriesFilter('');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: { seriesFilter: null } }),
    );
  });

  it('opens filtered when the address bar carries one', async () => {
    const { element, page } = await setup({
      cadenced: [WEEKLY, ANNUAL],
      seriesFilter: 'weekly',
    });

    expect(page.seriesFilter()).toBe('weekly');
    expect(element.textContent).toContain('Weekly NtM');
    expect(element.textContent).not.toContain('Årlig oversigt');
    // The box itself shows what the link filtered by.
    const input = element.querySelector('app-publication-list-filter input') as HTMLInputElement;
    expect(input.value).toBe('weekly');
  });

  /**
   * Back moves the address and the router keeps the page, so the filter follows
   * the address rather than being read from it once (L-175).
   */
  it('follows the address when Back drops the filter', async () => {
    const { element, fixture, page } = await setup({
      cadenced: [WEEKLY, ANNUAL],
      seriesFilter: 'weekly',
    });
    expect(element.textContent).not.toContain('Årlig oversigt');

    queryParams.next(convertToParamMap({}));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.seriesFilter()).toBe('');
    expect(element.textContent).toContain('Weekly NtM');
    expect(element.textContent).toContain('Årlig oversigt');
    const input = element.querySelector('app-publication-list-filter input') as HTMLInputElement;
    expect(input.value).toBe('');
  });

  it('follows the address when Forward brings a filter back', async () => {
    const { element, fixture, page } = await setup({ cadenced: [WEEKLY, ANNUAL] });

    queryParams.next(convertToParamMap({ seriesFilter: 'arlig' }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.seriesFilter()).toBe('arlig');
    expect(element.textContent).toContain('Årlig oversigt');
    expect(element.textContent).not.toContain('Weekly NtM');
  });

  /**
   * One filter is one history entry: the first settled value adds an entry,
   * and refining a filter the address already carries replaces it, so Back
   * does not step through every pause in the typing. Clearing adds an entry,
   * so Back returns to the filter that was cleared.
   */
  it('adds a history entry for a new filter and replaces it while the filter is refined', async () => {
    const { fixture, page } = await setup({ cadenced: [WEEKLY] });

    page.setSeriesFilter('week');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: { seriesFilter: 'week' }, replaceUrl: false }),
    );

    // The address has echoed the first value back.
    queryParams.next(convertToParamMap({ seriesFilter: 'week' }));
    fixture.detectChanges();
    page.setSeriesFilter('weekly');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: { seriesFilter: 'weekly' }, replaceUrl: true }),
    );

    queryParams.next(convertToParamMap({ seriesFilter: 'weekly' }));
    fixture.detectChanges();
    page.setSeriesFilter('');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: { seriesFilter: null }, replaceUrl: false }),
    );
  });
});

/** The toolbar is the screen's own header row: the filter and what this section makes. */
describe('PublicationsPage — the toolbar', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * ONE create control, not two primary buttons.
   *
   * A series and a one-off are the same decision taken two ways, and a toolbar
   * that spends two primary buttons on it leaves nothing to distinguish the
   * action a reader actually came for.
   */
  it('offers both things this section makes behind one create menu', async () => {
    const { page } = await setup();

    expect(page.newItems().map((item) => item.label)).toEqual([
      'admin.publications.series.editor.newSeries',
      'admin.publications.series.editor.newOneOff',
    ]);
  });

  it('opens the one-off drawer from the create menu', async () => {
    const { fixture, page, httpTesting } = await setup();

    page.newItems()[1].command?.();
    fixture.detectChanges();

    expect(page.oneOffModel()?.mode).toBe('add');

    // The drawer reads the lists its pickers offer as it opens.
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /**
   * The cog is maintenance and nothing else: the definitions out, and back in.
   *
   * Two rows, in one ungrouped list. A control that moves what the public site
   * serves does not belong on a menu somebody opens to take an export.
   */
  it('offers the export and the import, and nothing beside them', async () => {
    const { page } = await setup();

    const labels = page.cogItems().map((item) => item.label);
    expect(labels).toEqual(['admin.publications.menu.export', 'admin.publications.menu.import']);
    expect(page.cogItems().some((item) => item.separator)).toBe(false);
  });

  /**
   * Every menu on the page opens from the same trigger as the rest of the app:
   * a glyph plus the dropdown chevron. The per-row actions menu is the cog the
   * message list uses for its rows, not a shape of its own.
   */
  it('gives the create menu, the cog and the row actions the standard chevron', async () => {
    const { element } = await setup({ oneOffs: [oneOff(true)] });

    const glyphs = (selector: string) => element.querySelectorAll(`${selector} app-icon`).length;

    // The leading plus AND the chevron.
    expect(glyphs('app-menu-trigger button[aria-label="admin.publications.new.aria"]')).toBe(2);
    // The cog AND the chevron, as on every other admin page.
    expect(
      glyphs('app-menu-trigger button[aria-label="admin.publications.actionsAriaLabel"]'),
    ).toBe(2);
    expect(
      glyphs('app-menu-trigger button[aria-label="admin.publications.oneOff.rowActions"]'),
    ).toBe(2);
  });
});

/**
 * One header per level.
 *
 * The series screen carries its own header cluster and an issue opened inside it
 * carries a third — so this page draws the section title and, on the dashboard
 * alone, the one-line description.
 */
describe('PublicationsPage — one header per level', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('describes the section on the dashboard only', async () => {
    const { page } = await setup();
    expect(page.pageDescriptionKey()).toBe('admin.publications.intro');
  });

  it('drops the description as soon as a series is open', async () => {
    const { page, element } = await setup({ selected: 'weekly-ntm', series: [series()] });

    expect(page.pageDescriptionKey()).toBeUndefined();
    expect(element.textContent).not.toContain('admin.publications.intro');
  });

  /**
   * And deeper still. An issue opened inside a series renders a third header
   * cluster of its own, so the section's blurb above it would describe a screen
   * the reader left two levels ago.
   */
  it('keeps the description suppressed while an issue is open inside the series', async () => {
    const { page, element } = await setup({
      selected: 'weekly-ntm',
      series: [series()],
      openIssue: 'p-36',
    });

    expect(page.pageDescriptionKey()).toBeUndefined();
    expect(element.textContent).not.toContain('admin.publications.intro');
  });

  /**
   * The section TITLE goes with the description.
   *
   * Dropping the blurb alone still left "Publications" and its rule above the
   * series' own header cluster — two headers describing different levels.
   */
  it('draws the section title on the dashboard', async () => {
    const { element } = await setup();

    expect(element.querySelector('app-admin-section-page > header h2')?.textContent).toContain(
      'admin.sections.publications.label',
    );
  });

  it('drops the section title as soon as a series is open', async () => {
    const { element } = await setup({ selected: 'weekly-ntm', series: [series()] });

    expect(element.querySelector('app-admin-section-page > header')).toBeNull();
  });

  /** The blurb names the desk the dashboard is scoped to, so the desk travels with it. */
  it('hands the description the desk it is scoped to', async () => {
    const { fixture } = await setup({ sessionDomain: 'niord-annex' });
    const sectionPage = fixture.debugElement.query(By.directive(AdminSectionPage))
      .componentInstance as AdminSectionPage;

    expect(sectionPage.descriptionParams()).toEqual({ domain: 'niord-annex' });
  });
});

/** The card says what the series is doing, and offers the one act that follows. */
describe('PublicationsPage — what a card says', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  function withOpenIssue(): SetupOptions {
    return {
      cadenced: [series({ seriesId: 'weekly-ntm', cadence: 'WEEKLY' })],
      timelines: [
        {
          publicationSeriesId: 'weekly-ntm',
          rows: [
            {
              week: 36,
              label: 'EfS week 36 – 2026',
              computedStatus: 'OPEN',
              publicId: 'p-36',
              memberCount: 26,
              intervalTo: 1_756_814_400_000,
            },
            { week: 35, computedStatus: 'PUBLISHED', publicId: 'p-35', memberCount: 31 },
          ],
          gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
        },
      ],
    };
  }

  /**
   * The cut-off on an open issue has not been taken yet: it is stamped by the
   * act of publishing, so the card shows what WOULD be written and marks it.
   */
  it('names the open issue, its expected cut-off and how big it is', async () => {
    const { element } = await setup(withOpenIssue());

    expect(element.textContent).toContain('admin.publications.card.openIssue');
    expect(element.textContent).toContain('admin.publications.card.cutoff');
    expect(element.querySelector('.grid .admin-card app-expected-date')).not.toBeNull();
    expect(element.textContent).toContain('admin.publications.card.members');
  });

  /** One message is "1 message", never "1 messages". */
  it('says the member count in the singular when it is one', async () => {
    const many = withOpenIssue();
    const [timeline] = many.timelines!;
    const [open, ...rest] = timeline.rows;
    const { element } = await setup({
      ...many,
      timelines: [{ ...timeline, rows: [{ ...open, memberCount: 1 }, ...rest] }],
    });

    expect(element.textContent).toContain('admin.publications.card.membersOne');
  });

  /**
   * A cut-off days away is planned around by weekday: "Thursday" is what an
   * editor works to, and the date says less.
   */
  it('names the weekday of a weekly cut-off', async () => {
    const { fixture } = await setup(withOpenIssue());

    expect(cutoffMarker(fixture).format()).toBe('weekday');
  });

  /**
   * A year out that stops being true — the weekday of a date twelve months
   * ahead is noise, and the date is the fact.
   */
  it('names the date of an annual cut-off', async () => {
    const { fixture } = await setup({
      cadenced: [series({ seriesId: 'annual', cadence: 'YEARLY' })],
      timelines: [
        {
          publicationSeriesId: 'annual',
          rows: [
            {
              year: 2026,
              label: 'Annual 2026',
              computedStatus: 'OPEN',
              publicId: 'p-26',
              intervalTo: 1_756_814_400_000,
            },
          ],
          gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
        },
      ],
    });

    expect(cutoffMarker(fixture).format()).toBe('date');
  });

  it('offers the open issue first, and the archive beside it', async () => {
    const { element } = await setup(withOpenIssue());

    const open = element.querySelector<HTMLButtonElement>('.grid .admin-card button.btn-primary');
    expect(open?.textContent).toContain('admin.publications.card.openAction');
    expect(
      [...element.querySelectorAll('.grid .admin-card button.btn-tertiary')].some((b) =>
        b.textContent?.includes('admin.publications.card.allIssues'),
      ),
    ).toBe(true);
    // Nothing to create while an issue is already open: the series opens its own
    // successor when this one publishes.
    expect(element.querySelector('.grid .admin-card button.btn-secondary')).toBeNull();
  });

  it('opens that issue on the series own screen', async () => {
    const { element, fixture, httpTesting } = await setup(withOpenIssue());

    element.querySelector<HTMLButtonElement>('.grid .admin-card button.btn-primary')?.click();
    fixture.detectChanges();
    await drainDetail(fixture, httpTesting, withOpenIssue());

    expect(router.navigate).toHaveBeenCalledWith(['weekly-ntm'], {
      relativeTo: expect.anything(),
      queryParams: { issue: 'p-36' },
      state: { series: expect.objectContaining({ seriesId: 'weekly-ntm' }) },
    });
  });

  /** Nothing open: the card says so, and offers the issue that would follow. */
  it('offers a new issue when nothing is open on an active series', async () => {
    const { element } = await setup({
      cadenced: [series({ seriesId: 'annual', cadence: 'YEARLY' })],
      timelines: [
        {
          publicationSeriesId: 'annual',
          rows: [{ year: 2026, computedStatus: 'MISSING' }],
          gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
        },
      ],
    });

    expect(element.textContent).toContain('admin.publications.card.noOpenIssue');
    const create = element.querySelector<HTMLButtonElement>(
      '.grid .admin-card button.btn-secondary',
    );
    expect(create?.textContent).toContain('admin.publications.detail.newIssue');
  });

  /** A draft series has no issues to open, which the endpoint enforces anyway. */
  it('offers no new issue on a series that has not been activated', async () => {
    const { element } = await setup({
      cadenced: [series({ seriesId: 'mine', status: 'DRAFT', cadence: 'WEEKLY' })],
    });

    expect(element.querySelector('.grid .admin-card button.btn-secondary')).toBeNull();
  });

  /** The draft is reviewed before it is created, and lands on what was made. */
  it('reviews the period, then goes to the issue it created', async () => {
    const { element, fixture, page, httpTesting } = await setup({
      cadenced: [series({ seriesId: 'annual', cadence: 'YEARLY' })],
    });

    element.querySelector<HTMLButtonElement>('.grid .admin-card button.btn-secondary')?.click();
    fixture.detectChanges();
    expect(page.draftDialogOpen()).toBe(true);
    expect(page.draftSeries()?.seriesId).toBe('annual');

    facade.createIssue.mockImplementation(
      (_seriesId: string, _decision: unknown, onDone?: (issue: { publicId: string }) => void) =>
        onDone?.({ publicId: 'p-new' }),
    );
    page.submitDraft({ recovered: false });
    fixture.detectChanges();

    expect(facade.createIssue).toHaveBeenCalledWith(
      'annual',
      { recovered: false },
      expect.anything(),
    );
    expect(router.navigate).toHaveBeenCalledWith(['annual'], {
      relativeTo: expect.anything(),
      queryParams: { issue: 'p-new' },
      state: { series: expect.objectContaining({ seriesId: 'annual' }) },
    });

    // The dialog asks the server what the period would be as it opens, and the
    // detail panel the navigation mounted starts its own reads.
    await drainDetail(fixture, httpTesting, {});
  });

  /**
   * A series with no calendar has no periods to draw, so the card summarises the
   * archive instead: how many issues, and what came out of it last.
   */
  it('summarises an unscheduled series instead of drawing a strip', async () => {
    const { element } = await setup({
      cadenced: [series({ seriesId: 'ncags', cadence: 'NONE', kind: 'UNSCHEDULED' })],
      timelines: [
        {
          publicationSeriesId: 'ncags',
          rows: [
            { label: 'NCAGS 2026', computedStatus: 'PUBLISHED', publicId: 'p-11', intervalTo: 1 },
            { label: 'NCAGS 2025', computedStatus: 'PUBLISHED', publicId: 'p-10', intervalTo: 0 },
          ],
          gapDetection: { enabled: false, reasonCode: 'NO_CADENCE', reason: '' },
        },
      ],
    });

    expect(element.querySelector('app-issue-timeline')).toBeNull();
    expect(element.textContent).toContain('admin.publications.card.issueCount');
    expect(element.textContent).toContain('NCAGS 2026');
  });

  /**
   * "1 issue(s)" is the shape of a string nobody wrote for the number it ended
   * up with, so an archive holding exactly one takes the singular key.
   */
  it('counts a single released issue in the singular', async () => {
    const { element } = await setup({
      cadenced: [series({ seriesId: 'ncags', cadence: 'NONE', kind: 'UNSCHEDULED' })],
      timelines: [
        {
          publicationSeriesId: 'ncags',
          rows: [
            { label: 'NCAGS 2026', computedStatus: 'PUBLISHED', publicId: 'p-11', intervalTo: 1 },
          ],
          gapDetection: { enabled: false, reasonCode: 'NO_CADENCE', reason: '' },
        },
      ],
    });

    expect(element.textContent).toContain('admin.publications.card.issueCountOne');
  });

  /**
   * "Nothing open" alone does not say whether that is a week's pause or seven
   * years, so the card names the last thing that DID come out and the stretch
   * since that produced nothing.
   */
  it('names the latest issue and the stretch of missing periods when nothing is open', async () => {
    const { element } = await setup({
      cadenced: [
        series({ seriesId: 'annual', cadence: 'YEARLY', numberingScheme: 'YEAR_EDITION' }),
      ],
      timelines: [
        {
          publicationSeriesId: 'annual',
          rows: [
            { year: 2025, computedStatus: 'MISSING' },
            { year: 2024, computedStatus: 'MISSING' },
            { year: 2023, label: 'Annual 2023', computedStatus: 'PUBLISHED', publicId: 'p-23' },
          ],
          gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
        },
      ],
    });

    expect(element.textContent).toContain('admin.publications.card.noOpenIssue');
    expect(element.textContent).toContain('admin.publications.card.latestLabel');
    expect(element.textContent).toContain('Annual 2023');
    expect(element.textContent).toContain('admin.publications.card.gapSpan');
  });

  /** The named issue opens where every other cell on the card opens it. */
  it('opens the latest issue from the card’s own link', async () => {
    const options: SetupOptions = {
      cadenced: [
        series({ seriesId: 'annual', cadence: 'YEARLY', numberingScheme: 'YEAR_EDITION' }),
      ],
      timelines: [
        {
          publicationSeriesId: 'annual',
          rows: [
            { year: 2023, label: 'Annual 2023', computedStatus: 'PUBLISHED', publicId: 'p-23' },
          ],
          gapDetection: { enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' },
        },
      ],
    };
    const { element, fixture, httpTesting } = await setup(options);

    const link = [...element.querySelectorAll<HTMLButtonElement>('.grid .admin-card .link-button')];
    link[0].click();
    fixture.detectChanges();
    await drainDetail(fixture, httpTesting, options);

    expect(router.navigate).toHaveBeenCalledWith(['annual'], {
      relativeTo: expect.anything(),
      queryParams: { issue: 'p-23' },
      state: { series: expect.objectContaining({ seriesId: 'annual' }) },
    });
  });

  /**
   * A series with no calendar is not waiting for a period, so what decides
   * whether "nothing open" is normal is what the next edition would BE.
   */
  it('says what a cadence-less series’ issues are when nothing is open', async () => {
    const { element } = await setup({
      cadenced: [
        series({
          seriesId: 'ncags',
          cadence: 'NONE',
          kind: 'UNSCHEDULED',
          contentMode: 'UPLOADED_FILE',
        }),
      ],
    });

    expect(element.textContent).toContain('admin.publications.card.noOpenIssue');
    expect(element.textContent).toContain('admin.publications.card.contentMode.UPLOADED_FILE');
  });

  /** A content mode this build does not know says nothing rather than the wrong thing. */
  it('leaves the content line out for a mode the vocabulary does not hold', async () => {
    const { element } = await setup({
      cadenced: [
        series({
          seriesId: 'ncags',
          cadence: 'NONE',
          kind: 'UNSCHEDULED',
          // A mode this build's vocabulary does not hold, as an older or newer
          // server could send.
          contentMode: 'FILMSTRIP' as PublicationSeries['contentMode'],
        }),
      ],
    });

    expect(element.textContent).toContain('admin.publications.card.noOpenIssue');
    expect(element.textContent).not.toContain('admin.publications.card.contentMode');
  });

  /**
   * Only the two states that change what the reader may do carry a badge.
   * Badging the ordinary case spends attention on the fact that never needs it.
   */
  it('badges a draft series, and leaves an active one unbadged', async () => {
    const { element } = await setup({
      cadenced: [
        series({ seriesId: 'live', cadence: 'WEEKLY' }),
        series({ seriesId: 'mine', status: 'DRAFT', cadence: 'WEEKLY' }),
      ],
    });

    const badges = [...element.querySelectorAll('.grid .admin-card .status-badge')];
    expect(badges.length).toBe(1);
    expect(badges[0].textContent).toContain('admin.publications.badge.draft');
    expect(badges[0].className).toContain('is-issue-open');
  });

  /** A series that has stopped producing says so where it is read. */
  it('warns on a dormant series', async () => {
    const { element } = await setup({
      cadenced: [series({ seriesId: 'stalled', cadence: 'WEEKLY', dormant: true })],
    });

    expect(element.querySelector('.grid .admin-card .text-warning')).not.toBeNull();
    expect(element.textContent).toContain('admin.publications.dormant');
  });

  /** The series id is not on the face: nobody looks a card up by its identifier. */
  it('keeps the series id off the card', async () => {
    const { element } = await setup({
      cadenced: [series({ seriesId: 'weekly-ntm', cadence: 'WEEKLY' })],
    });

    expect(element.querySelector('.grid .admin-card')?.textContent).not.toContain('weekly-ntm');
  });
});

/** The one-off table: what a publication released once actually has. */
describe('PublicationsPage — the one-off row', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('lists the columns a one-off is read by', async () => {
    const { element } = await setup({ oneOffs: [oneOff(true)] });

    const headers = [...element.querySelectorAll('thead th')].map((th) =>
      (th.textContent ?? '').trim(),
    );
    expect(headers).toEqual([
      'admin.publications.column.name',
      'admin.publications.column.type',
      'admin.publications.column.status',
      'admin.publications.column.public',
      'admin.publications.column.availability',
      'admin.publications.column.fileOrLink',
      'admin.publications.column.actions',
    ]);
  });

  /** The band says what the section is for, above the rows rather than beside them. */
  it('titles the card with a band and its subtitle', async () => {
    const { element } = await setup({ oneOffs: [oneOff(true)] });

    const band = element.querySelector('.admin-panel-header');
    expect(band?.textContent).toContain('admin.publications.group.oneOffs');
    expect(band?.textContent).toContain('admin.publications.oneOff.subtitle');
  });

  /** Who else may cite it, which is the question the column answers. */
  it('says who the publication can be cited from', async () => {
    const { element } = await setup({
      oneOffs: [
        oneOff(true, {
          series: {
            availability: 'SELECTED_DOMAINS',
            availableDomainIds: ['a', 'b'],
          },
        }),
      ],
    });

    expect(element.textContent).toContain('admin.publications.oneOff.availabilityCount');
  });

  /**
   * A link can be opened and copied. A stored file is shown by name: the row
   * carries no address for it, and a link that resolves to nothing is worse.
   */
  it('offers a link with a copy button, and a file by name alone', async () => {
    const { element } = await setup({
      oneOffs: [
        oneOff(true, {
          series: { contentMode: 'EXTERNAL_LINK' },
          links: [{ lang: 'en', value: 'https://example.test/havnelods' }],
        }),
      ],
    });

    const link = element.querySelector<HTMLAnchorElement>('tbody a[href]');
    expect(link?.getAttribute('href')).toBe('https://example.test/havnelods');
    // The scheme is dropped — every address in the column has one — and there is
    // nothing in the middle to elide.
    expect(link?.textContent?.trim()).toBe('example.test/havnelods');
    expect(element.querySelector('tbody app-copy-link-button')).not.toBeNull();
  });

  /**
   * A published address runs past the column, and the part that identifies the
   * document is the END of it. The label keeps the host and the document and
   * elides what is between them; the address itself is untouched, so nothing is
   * lost to the reader who follows or copies it.
   */
  it('reads a long address by its host and its document, and links the whole one', async () => {
    const { element } = await setup({
      oneOffs: [
        oneOff(true, {
          series: { contentMode: 'EXTERNAL_LINK' },
          links: [{ lang: 'en', value: 'https://example.test/media/1234/efs/havnelods-2026.pdf' }],
        }),
      ],
    });

    const link = element.querySelector<HTMLAnchorElement>('tbody a[href]');
    expect(link?.getAttribute('href')).toBe(
      'https://example.test/media/1234/efs/havnelods-2026.pdf',
    );
    expect(link?.textContent?.trim()).toBe('example.test/…/havnelods-2026.pdf');
  });

  /**
   * An uploaded document is served over an address too, so the row offers it
   * the same way an external link is offered: the file name a reader knows it
   * by, pointing at where the bytes actually are.
   */
  it('links an uploaded file to the address the row carries, under its own name', async () => {
    const { element } = await setup({
      oneOffs: [
        oneOff(true, {
          links: [{ lang: 'en', value: '/rest/repo/file/publications/a/8e/fyrliste-2026.pdf' }],
        }),
      ],
    });

    const link = element.querySelector<HTMLAnchorElement>('tbody a[href]');
    expect(link?.getAttribute('href')).toBe(
      `${document.location.origin}/rest/repo/file/publications/a/8e/fyrliste-2026.pdf`,
    );
    // The NAME is what is read; the address is where it points.
    expect(link?.textContent?.trim()).toBe('fyrliste-2026.pdf');
    expect(element.querySelector('tbody app-copy-link-button')).not.toBeNull();
  });

  /**
   * A row that carries no address shows the name alone: a link that resolves to
   * nothing is worse than a name, and the publication's own screen has the
   * resolved one.
   */
  it('shows an uploaded file by name where the row carries no address', async () => {
    const { element } = await setup({ oneOffs: [oneOff(true)] });

    expect(element.querySelector('tbody a[href]')).toBeNull();
    expect(element.querySelector('tbody app-copy-link-button')).toBeNull();
    expect(element.textContent).toContain('fyrliste-2026.pdf');
  });

  /**
   * A row that plainly has something behind it is never drawn as the dash that
   * means there is nothing — a stored document with no file name of its own is
   * read by its address instead.
   */
  it('reads a nameless stored document by its address rather than as nothing', async () => {
    const { element } = await setup({
      oneOffs: [
        oneOff(true, {
          fileNames: [{ lang: 'en', value: null }],
          links: [{ lang: 'en', value: '/rest/repo/file/publications/a/8e/fyrliste.pdf' }],
        }),
      ],
    });

    const link = element.querySelector<HTMLAnchorElement>('tbody a[href]');
    expect(link?.textContent?.trim()).toBe(
      `${document.location.origin}/rest/repo/file/publications/a/8e/fyrliste.pdf`,
    );
  });

  /** The name opens the publication's own screen, a sibling of the series one. */
  it('opens the publication own screen from its name', async () => {
    const { element, fixture } = await setup({ oneOffs: [oneOff(true)] });

    element.querySelector<HTMLButtonElement>('tbody td button')?.click();
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledWith(['one-off', 'fyrliste-2026'], {
      relativeTo: expect.anything(),
    });
  });

  /**
   * The row menu offers only what can actually be done: a move needs a domain to
   * move to, and there is nothing to take off the public site on a publication
   * that has already been withdrawn.
   */
  it('offers edit alone when there is nowhere to move it and it is already withdrawn', async () => {
    const { page } = await setup({ oneOffs: [oneOff(false)] });

    const rows = page.oneOffMenus().get('fyrliste-2026') ?? [];
    expect(rows.map((row) => row.label)).toEqual(['admin.publications.oneOff.edit']);
  });

  /** Nor on one nobody has released: it has not been on the public site to leave it. */
  it('offers no withdrawal on a publication that is not out yet', async () => {
    const { page } = await setup({ oneOffs: [oneOff(true, { issueStatus: 'OPEN' })] });

    const rows = page.oneOffMenus().get('fyrliste-2026') ?? [];
    expect(rows.map((row) => row.label)).toEqual(['admin.publications.oneOff.edit']);
  });

  it('adds the move and the separated withdrawal where both apply', async () => {
    const { page } = await setup({
      oneOffs: [oneOff(true)],
      transferTargets: ['niord-nm'],
    });

    const rows = page.oneOffMenus().get('fyrliste-2026') ?? [];
    expect(rows.filter((row) => !row.separator).map((row) => row.label)).toEqual([
      'admin.publications.oneOff.edit',
      'admin.publications.transfer.action',
      'admin.publications.oneOff.withdraw',
    ]);
    expect(rows.some((row) => row.separator)).toBe(true);
  });

  it('opens the editor from the row menu', async () => {
    const { fixture, httpTesting, page } = await setup({ oneOffs: [oneOff(true)] });

    (page.oneOffMenus().get('fyrliste-2026') ?? [])[0].command?.();
    fixture.detectChanges();

    expect(page.oneOffModel()?.mode).toBe('edit');

    // The drawer reads its own option lists as it opens.
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /** Moving a publication is a change somebody has to account for, so it asks why. */
  it('moves the publication with the reason the dialog collected', async () => {
    const { fixture, page, httpTesting } = await setup({
      oneOffs: [oneOff(true)],
      transferTargets: ['niord-nm'],
    });

    (page.oneOffMenus().get('fyrliste-2026') ?? [])[1].command?.();
    fixture.detectChanges();
    expect(page.transferDialogOpen()).toBe(true);

    page.submitTransfer({ domainId: 'niord-nm', reason: 'ruled at the desk review' });
    const request = httpTesting.expectOne('/rest/publication-series/series/fyrliste-2026/owner');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toMatchObject({
      domainId: 'niord-nm',
      reason: 'ruled at the desk review',
    });
    request.flush({});
    fixture.detectChanges();

    expect(page.transferDialogOpen()).toBe(false);
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /**
   * Withdrawing a one-off retires the issue behind it, so the reason is
   * asked for — and everything else on the row travels unchanged, because an
   * omitted collection reads as "unchanged" and a half-built payload would clear
   * a link on the way past.
   */
  it('withdraws the publication through the issue, against the issue revision', async () => {
    const { fixture, page, httpTesting } = await setup({ oneOffs: [oneOff(true)] });

    const rows = page.oneOffMenus().get('fyrliste-2026') ?? [];
    rows.at(-1)?.command?.();
    fixture.detectChanges();
    expect(page.withdrawDialogOpen()).toBe(true);

    page.submitWithdraw('replaced by the 2027 edition');
    const request = httpTesting.expectOne(
      (r) => r.url === '/rest/publication-issues/issue/fyrliste-2026-1/retire',
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.params.get('reason')).toBe('replaced by the 2027 edition');
    expect(request.request.params.get('version')).toBe('4');
    // Not the one-off save: that endpoint decides the transition from whether
    // the publication is live at this instant, which folds the public period in.
    httpTesting.expectNone('/rest/one-off-publications/fyrliste-2026');
    request.flush({});
    fixture.detectChanges();

    expect(page.withdrawDialogOpen()).toBe(false);
    // The list is re-read: whether it is still live is the server's answer.
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /**
   * The publication the fold could not take off the site.
   *
   * A one-off whose period starts next January is PUBLISHED and not live, so the
   * save's own comparison — asked-for against live — matched, and the call
   * answered 200 having changed nothing while the dialog closed over a
   * publication that was still published.
   */
  it('withdraws a scheduled publication, whose period has not started', async () => {
    const from = Date.now() + 90 * 24 * 3600 * 1000;
    const { fixture, page, httpTesting } = await setup({
      oneOffs: [oneOff(true, { publicFrom: from })],
    });

    page.oneOffMenus().get('fyrliste-2026')?.at(-1)?.command?.();
    fixture.detectChanges();
    page.submitWithdraw('prepared in error');

    httpTesting
      .expectOne((r) => r.url === '/rest/publication-issues/issue/fyrliste-2026-1/retire')
      .flush({});
    fixture.detectChanges();

    expect(page.withdrawDialogOpen()).toBe(false);
    for (const pending of httpTesting.match(() => true)) {
      pending.flush([]);
    }
  });

  /** A publication whose issue this payload does not name has nothing to withdraw. */
  it('offers no withdrawal where the payload names no issue', async () => {
    const { page } = await setup({
      oneOffs: [oneOff(true, { issuePublicId: undefined })],
    });

    expect((page.oneOffMenus().get('fyrliste-2026') ?? []).map((item) => item.label)).not.toContain(
      'admin.publications.oneOff.withdraw',
    );
  });
});

/**
 * The lists an editor reads as it is built, one drawer's worth.
 *
 * The endpoints, not the count: an editor reads exactly these four, and the
 * point of every test below is when they are asked for rather than how many
 * requests it took.
 */
const PICKER_LISTS = [
  '/rest/message-reports/reports',
  '/rest/publication-categories/all',
  '/rest/publication-series/name-tokens',
  '/rest/domains/all',
];

/** Answer everything outstanding, and report what was asked for. */
function flushAll(httpTesting: HttpTestingController): string[] {
  return httpTesting
    .match(() => true)
    .map((request) => {
      request.flush([]);
      return request.request.url;
    });
}

function seriesEditor(fixture: ComponentFixture<Host>) {
  return fixture.debugElement.query(By.directive(PublicationSeriesEditor));
}

describe('PublicationsPage — the editors’ own lists', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /** A series open, reached by a click that carried the row with it. */
  const OPEN: SetupOptions = { selected: 'weekly-ntm', navigatedSeries: series() };

  /**
   * An editor exists only while its drawer does.
   *
   * Each reads the lists its pickers offer as it is built, so an editor standing
   * on the page permanently costs four requests on every visit to a screen
   * nobody opened a drawer on — the dashboard, the series screen and the issue
   * screen alike.
   */
  it('builds no editor, and reads no picker list, until a drawer opens', async () => {
    const { fixture } = await setup();

    expect(fixture.debugElement.query(By.directive(PublicationSeriesEditor))).toBeNull();
    expect(fixture.debugElement.query(By.directive(OneOffEditor))).toBeNull();
    // Against the record of what the visit fetched, not against what is left
    // outstanding: `setup` answers everything, so an unanswered-queue check
    // would pass whether or not the four were ever asked for. The record is
    // checked to be a record at all first — an empty one would satisfy the
    // filter for the wrong reason.
    expect(requestedUrls.length).toBeGreaterThan(0);
    expect(requestedUrls.filter((url) => PICKER_LISTS.includes(url))).toEqual([]);
  });

  it('builds the one-off editor, and its lists, on the create menu', async () => {
    const { fixture, page, httpTesting } = await setup();

    page.newItems()[1].command?.();
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.directive(OneOffEditor))).not.toBeNull();
    expect(flushAll(httpTesting)).toEqual(expect.arrayContaining(PICKER_LISTS));
  });

  it('builds the series editor, and its lists, when the settings drawer is opened', async () => {
    const { fixture, page, httpTesting } = await setup(OPEN);

    expect(seriesEditor(fixture)).toBeNull();

    seriesDetail(fixture).settings.emit(series({ seriesId: 'weekly-ntm' }));
    fixture.detectChanges();

    expect(seriesEditor(fixture)).not.toBeNull();
    expect(page.editorModel()?.mode).toBe('edit');
    expect(flushAll(httpTesting)).toEqual(expect.arrayContaining(PICKER_LISTS));
  });

  /**
   * Closing is two moments, and the editor outlives the first one.
   *
   * `closed` is the ask — the model goes, which starts the slide-out — and the
   * component that draws the pane has to still be there while it slides.
   * `hidden` is the pane being gone, and only then is there nothing left to
   * keep. Reopening after that is a fresh editor, so the lists are read again
   * and no answer from the previous opening is carried over.
   */
  it('keeps the editor alive through the slide-out and destroys it on hidden', async () => {
    const { fixture, page, httpTesting } = await setup(OPEN);

    seriesDetail(fixture).settings.emit(series({ seriesId: 'weekly-ntm' }));
    fixture.detectChanges();
    flushAll(httpTesting);

    const editor = seriesEditor(fixture)!.componentInstance as PublicationSeriesEditor;

    editor.closed.emit();
    fixture.detectChanges();
    expect(page.editorModel()).toBeUndefined();
    expect(seriesEditor(fixture)).not.toBeNull();

    editor.hidden.emit();
    fixture.detectChanges();
    expect(seriesEditor(fixture)).toBeNull();

    seriesDetail(fixture).settings.emit(series({ seriesId: 'weekly-ntm' }));
    fixture.detectChanges();

    expect(seriesEditor(fixture)).not.toBeNull();
    expect(flushAll(httpTesting)).toEqual(expect.arrayContaining(PICKER_LISTS));
  });
});
