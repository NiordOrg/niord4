import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { fakeRowLinkRouter } from '../../../../../shared/testing/fake-row-link-router';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  IssueAuditEntry,
  PublicationIssueRow,
  PublishChecklist,
} from '../../../services/api/publication-series-api.service';
import { SeriesDetail } from './series-detail';
import { SeriesDetailFacade } from './series-detail.facade';
import { PublicationTransferTargets } from './transfer-owner';

@Component({
  imports: [SeriesDetail],
  template: `<app-series-detail seriesId="weekly-ntm" />`,
})
class Host {}

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '' },
];

const GAP_ROW: PublicationIssueRow = {
  pseudo: 'MISSING',
  computedStatus: 'MISSING',
  publicationSeriesId: 'weekly-ntm',
  descs: [],
  suggestedDescs: [{ lang: 'en', name: 'Week 27' }],
  intervalFrom: 1_766_000_000_000,
  intervalTo: 1_766_604_800_000,
  sortKey: 1_766_604_800_000,
} as unknown as PublicationIssueRow;

/** The series the mock answers with, ACTIVE unless a test says otherwise. */
function weekly(over: Record<string, unknown> = {}) {
  return {
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    descs: [],
    cadence: 'WEEKLY',
    ...over,
  };
}

/**
 * ONE facade mock, reused.
 *
 * `overrideComponent` caches the compiled definition, so a fresh mock per test
 * would leave the component bound to the first one and every panel would keep
 * answering with the previous test's state.
 */
const facade = {
  series: signal<unknown>(weekly()),
  issues: signal<readonly PublicationIssueRow[]>([]),
  issueCount: signal(1),
  loading: signal(false),
  error: signal(false),
  // The failed read was the server's coded 404: the series is not there.
  notFound: signal(false),
  awaitingFirstRead: signal(false),
  gapDetection: signal({ enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' }),
  gapCount: signal(0),
  publicNowIssue: signal<PublicationIssueRow | undefined>(undefined),
  currentIssue: signal<PublicationIssueRow | undefined>(undefined),
  currentChecklist: signal<PublishChecklist | undefined>(undefined),
  seriesAcceptsIssues: signal(true),
  canCreateIssue: signal(true),
  // Whether this admin's current domain may change anything here. The default is
  // the ordinary case: a series that names no domain, writable from everywhere.
  writable: signal(true),
  isEmpty: signal(false),
  working: signal(false),
  createErrorKey: signal(undefined),
  // The series' own trail, behind the same kind of fold: read when the panel is
  // opened, and never on the way in.
  history: signal<readonly IssueAuditEntry[]>([]),
  historyLoading: signal(false),
  historyError: signal(false),
  loadHistory: vi.fn(),
  transferWorking: signal(false),
  transferErrorKey: signal<string | undefined>(undefined),
  transferErrorDetail: signal<string | undefined>(undefined),
  transferOwner: vi.fn(),
  createIssue: vi.fn(),
  // Which of the two views is on screen; the archive read is gated on it.
  setArchiveShown: vi.fn(),
  load: vi.fn(),
  refresh: vi.fn(),
};

/**
 * What the transfer picker would offer. Empty unless a test says otherwise.
 *
 * A SIGNAL, not a plain variable: the gate that reads it is a `computed()`, and a
 * value it cannot track leaves the first (empty) answer cached for the life of
 * the fixture.
 */
const transferTargets = signal<readonly Domain[]>([]);

/** The desk the admin is sitting at, and the catalogue the owner is named from. */
const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-nm', name: 'NM' } as Domain);
const availableDomains = signal<readonly Domain[]>([
  { domainId: 'niord-nm', name: 'NM' } as Domain,
  { domainId: 'niord-annex', name: 'NM Annex' } as Domain,
]);

/**
 * The address, as a stream the test can move.
 *
 * The screen follows it rather than reading it once: a link from a compiled
 * issue to one of its source weeks changes the address without rebuilding the
 * page, and Back does the same.
 */
let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

function setup() {
  vi.clearAllMocks();
  queryParams = new BehaviorSubject(convertToParamMap({}));

  TestBed.configureTestingModule({
    providers: [
      // The draft dialog is mounted with the screen and reads its own preview.
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      // The transfer action asks which domains this admin administers, which
      // reaches AuthFacade -> Keycloak through the live service. Stubbed empty:
      // the action is not what these assert.
      {
        provide: PublicationTransferTargets,
        useValue: { targetsFor: () => transferTargets() } as unknown as PublicationTransferTargets,
      },
      {
        provide: DomainFacade,
        // Enough of the catalogue for the refusal a foreign series would draw.
        useValue: {
          sessionDomain,
          availableDomains,
          editableDomains: availableDomains,
          resolveById: (domainId: string) =>
            availableDomains().find((domain) => domain.domainId === domainId),
          selectSessionDomain: vi.fn(),
        } as unknown as DomainFacade,
      },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: signal('en'),
          activeLocale: signal('en'),
          modelLanguages: LANGUAGES,
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
      { provide: Router, useValue: { ...fakeRowLinkRouter(), navigate: vi.fn() } },
      {
        provide: ActivatedRoute,
        useValue: { queryParamMap: queryParams.asObservable() },
      },
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  }).overrideComponent(SeriesDetail, {
    set: {
      // The override REPLACES the component's own providers, so the confirm
      // service the screen scopes for its activate/reinstate prompts has to be
      // named again here or the tree has none.
      providers: [{ provide: SeriesDetailFacade, useValue: facade }, ConfirmService],
    },
  });

  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  return fixture;
}

type Fixture = ReturnType<typeof setup>;

const element = (fixture: Fixture) => fixture.nativeElement as HTMLElement;

function button(fixture: Fixture, key: string): HTMLButtonElement | undefined {
  return [...element(fixture).querySelectorAll('button')].find((b) => b.textContent?.includes(key));
}

const panel = () => document.querySelector('.dialog-panel');

/**
 * Open the `⋯` menu and hand back its rows — command leaves are `<div>`s.
 *
 * Closes an already-open menu through its own trigger first: CDK holds a menu
 * stack, so emptying the overlay container by hand leaves the trigger believing
 * the panel is still up and the next click closes it rather than opening it.
 */
async function menuItems(fixture: Fixture): Promise<HTMLElement[]> {
  await fixture.whenStable();
  const trigger = element(fixture).querySelector('app-menu-trigger button') as HTMLButtonElement;
  if (document.querySelector('.menu-panel')) {
    trigger.click();
    await fixture.whenStable();
  }
  trigger.click();
  await fixture.whenStable();
  return [...document.querySelectorAll<HTMLElement>('.menu-panel .menu-item')];
}

async function menuItem(fixture: Fixture, key: string): Promise<HTMLElement | undefined> {
  return (await menuItems(fixture)).find((item) => item.textContent?.includes(key));
}

async function typeReason(fixture: Fixture, text: string) {
  const box = document.querySelector('#publication-reason') as HTMLTextAreaElement;
  box.value = text;
  box.dispatchEvent(new Event('input', { bubbles: true }));
  await fixture.whenStable();
}

function resetFacade(): void {
  facade.writable.set(true);
  facade.canCreateIssue.set(true);
  facade.currentIssue.set(undefined);
  facade.publicNowIssue.set(undefined);
  facade.issues.set([]);
  facade.series.set(weekly());
  facade.error.set(false);
  facade.notFound.set(false);
  sessionDomain.set({ domainId: 'niord-nm', name: 'NM' } as Domain);
}

/**
 * A series that cannot be read, on the archive view.
 *
 * A link to a series that does not exist used to read as a failed load with a
 * Retry that fetched the same 404, and this branch had no way back but the
 * rail: the page header is hidden while a series is selected.
 */
describe('SeriesDetail — a series that cannot be read', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    resetFacade();
  });

  it('says a missing series does not exist, without Retry, and offers the way back', async () => {
    facade.series.set(undefined);
    facade.error.set(true);
    facade.notFound.set(true);
    const fixture = setup();
    await fixture.whenStable();

    const banner = element(fixture).querySelector('app-error-banner');
    expect(banner?.textContent).toContain('admin.publications.error.seriesNotFound');
    expect(banner?.textContent).not.toContain('admin.publications.detail.loadError');
    expect(banner?.querySelector('button')).toBeNull();
    expect(button(fixture, 'admin.publications.detail.backToList')).toBeDefined();
  });

  it('keeps the load failure and Retry for any other failure, with the way back', async () => {
    facade.series.set(undefined);
    facade.error.set(true);
    const fixture = setup();
    await fixture.whenStable();

    const banner = element(fixture).querySelector('app-error-banner');
    expect(banner?.textContent).toContain('admin.publications.detail.loadError');
    expect(button(fixture, 'admin.publications.retry')).toBeDefined();
    expect(button(fixture, 'admin.publications.detail.backToList')).toBeDefined();
  });
});

/**
 * The header cluster: which series, and what the public site is serving from it.
 *
 * One header per level. The list above renders its own title; this one names the
 * series, the way back to the list, and the two facts the screen is opened with.
 */
describe('SeriesDetail — the header cluster', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    resetFacade();
  });

  it('offers one way back, and asks the list to take it', async () => {
    const fixture = setup();
    await fixture.whenStable();

    const back = button(fixture, 'admin.publications.detail.backToList');
    expect(back).toBeDefined();
    expect(element(fixture).querySelectorAll('h2')).toHaveLength(1);
  });

  /** ACTIVE is the ordinary case and carries no badge; DRAFT and RETIRED do. */
  it('badges only the states that change what may be done', async () => {
    const fixture = setup();
    await fixture.whenStable();
    expect(element(fixture).querySelector('header .status-badge')).toBeNull();

    facade.series.set(weekly({ status: 'DRAFT' }));
    await fixture.whenStable();
    const draft = element(fixture).querySelector('header .status-badge');
    expect(draft?.className).toContain('is-issue-open');
    expect(draft?.textContent).toContain('admin.publications.seriesStatus.DRAFT');

    facade.series.set(weekly({ status: 'RETIRED' }));
    await fixture.whenStable();
    expect(element(fixture).querySelector('header .status-badge')?.className).toContain(
      'is-cancelled',
    );
  });

  it('states the cadence, and says so plainly when nothing is public', async () => {
    const fixture = setup();
    await fixture.whenStable();

    const header = element(fixture).querySelector('header')?.textContent ?? '';
    expect(header).toContain('admin.publications.cadence.WEEKLY');
    expect(header).toContain('admin.publications.detail.publicNowNone');
  });

  /**
   * The live issue is a link, and the end of its window is a projection.
   *
   * The window closes when the successor goes out, and nobody has done that yet.
   */
  it('links the issue that is public now and marks the end of its window', async () => {
    const fixture = setup();
    facade.publicNowIssue.set({
      publicId: 'w35',
      status: 'PUBLISHED',
      descs: [{ lang: 'en', name: 'Week 35' }],
      publicFrom: 1_766_000_000_000,
      publicTo: 1_766_604_800_000,
    } as unknown as PublicationIssueRow);
    await fixture.whenStable();

    const header = element(fixture).querySelector('header');
    expect(header?.textContent).toContain('admin.publications.detail.publicNowLabel');
    expect(header?.querySelector('.link-button')?.textContent).toContain('Week 35');
    expect(header?.querySelector('.expected-date')).not.toBeNull();
  });

  /**
   * A window nothing has closed is said as one phrase, not as half a range.
   *
   * The line used to read "(9 Sep – —)": a dash, and then the em dash the date
   * formatter prints for nothing at all. An open end is the correct and complete
   * answer for the current edition of a series, and it is said with the same
   * words every one-off screen says it with.
   */
  it('says since when the live window has no end', async () => {
    const fixture = setup();
    facade.publicNowIssue.set({
      publicId: 'w37',
      status: 'PUBLISHED',
      descs: [{ lang: 'en', name: 'Week 37' }],
      publicFrom: 1_766_000_000_000,
    } as unknown as PublicationIssueRow);
    await fixture.whenStable();

    const header = element(fixture).querySelector('header')?.textContent ?? '';
    expect(header).toContain('admin.publications.visibility.compact.since');
    expect(header).not.toContain('–');
  });

  /**
   * A series that has never published anything has no public history to point
   * at, so it gets the sentence that claims none.
   */
  it('says so plainly when the series has never published anything', async () => {
    const fixture = setup();
    await fixture.whenStable();

    const header = element(fixture).querySelector('header')?.textContent ?? '';
    expect(header).toContain('admin.publications.detail.publicNowNone');
    expect(header).not.toContain('admin.publications.detail.publicNowNoneHistoric');
  });

  /**
   * …and one whose windows have all closed says exactly that instead.
   *
   * "Only historic publications" asserts an archive, and asserting one about a
   * brand-new series with a single open issue would invent it.
   */
  it('says only historic publications remain once the series has published', async () => {
    const fixture = setup();
    facade.issues.set([
      { publicId: 'w34', status: 'PUBLISHED', descs: [] } as unknown as PublicationIssueRow,
      { publicId: 'w35', status: 'OPEN', descs: [] } as unknown as PublicationIssueRow,
    ]);
    await fixture.whenStable();

    expect(element(fixture).querySelector('header')?.textContent).toContain(
      'admin.publications.detail.publicNowNoneHistoric',
    );
  });

  /**
   * The owner is never named in the header, because it is never in doubt.
   *
   * Every series this screen draws is owned by the desk reading it: one that is
   * not is refused in place of the page, naming the desk there instead.
   */
  it('says nothing about the owner in the header', async () => {
    const fixture = setup();
    facade.series.set(weekly({ domainId: 'niord-nm' }));
    await fixture.whenStable();

    expect(element(fixture).querySelector('header')?.textContent).not.toContain(
      'admin.publications.detail.ownedBy',
    );
  });
});

/**
 * The actions cluster: settings beside the name, everything else behind `⋯`.
 *
 * The lifecycle rows are offered one at a time — a series is in exactly one
 * state, and listing the other two greyed out asks the reader to work out which
 * one they are looking at.
 */
describe('SeriesDetail — the actions cluster', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    transferTargets.set([]);
    resetFacade();
  });

  it('asks the list to open the settings drawer', async () => {
    const fixture = setup();
    await fixture.whenStable();

    expect(button(fixture, 'admin.publications.series.editor.settings')).toBeDefined();
  });

  it('offers the copy and, where there is a target, the move', async () => {
    transferTargets.set([{ domainId: 'niord-annex', name: 'NM Annex' } as Domain]);
    const fixture = setup();

    const labels = (await menuItems(fixture)).map((item) => item.textContent?.trim());
    expect(labels).toContain('admin.publications.menu.copySeries');
    expect(labels).toContain('admin.publications.transfer.action');
  });

  /** An admin who administers one domain has no target, so the row is not there. */
  it('offers no move when there is nowhere to move the series to', async () => {
    const fixture = setup();

    const labels = (await menuItems(fixture)).map((item) => item.textContent?.trim());
    expect(labels).not.toContain('admin.publications.transfer.action');
  });

  it('offers exactly the transition the series is in a state to make', async () => {
    const fixture = setup();
    let labels = (await menuItems(fixture)).map((item) => item.textContent?.trim());
    expect(labels).toContain('admin.publications.series.editor.retire');
    expect(labels).not.toContain('admin.publications.series.editor.activate');

    facade.series.set(weekly({ status: 'DRAFT' }));
    labels = (await menuItems(fixture)).map((item) => item.textContent?.trim());
    expect(labels).toContain('admin.publications.series.editor.activate');
    expect(labels).not.toContain('admin.publications.series.editor.retire');

    facade.series.set(weekly({ status: 'RETIRED' }));
    labels = (await menuItems(fixture)).map((item) => item.textContent?.trim());
    expect(labels).toContain('admin.publications.series.editor.reinstate');
  });

  /** Every write is refused from another desk, so none of them is offered. */
  it('offers no transition on a series another domain owns', async () => {
    const fixture = setup();
    facade.writable.set(false);

    const labels = (await menuItems(fixture)).map((item) => item.textContent?.trim());
    expect(labels).not.toContain('admin.publications.series.editor.retire');
  });

  /**
   * Retiring asks WHY, because the reason is recorded against the transition.
   *
   * Activation and reinstatement ask "whether" instead: nothing new is being
   * recorded, and the rules are re-run server-side either way.
   */
  it('asks for a reason before retiring, and sends it with the revision', async () => {
    const fixture = setup();
    facade.series.set(weekly({ version: 7 }));

    (await menuItem(fixture, 'admin.publications.series.editor.retire'))!.click();
    await fixture.whenStable();
    expect(panel()?.textContent).toContain('admin.publications.series.editor.retireTitle');

    await typeReason(fixture, 'superseded by the 2027 edition');
    (panel()!.querySelector('.btn-danger') as HTMLButtonElement).click();
    await fixture.whenStable();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (r) => r.url === '/rest/publication-series/series/weekly-ntm/status',
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toBe(JSON.stringify('RETIRED'));
    expect(request.request.params.get('reason')).toBe('superseded by the 2027 edition');
    expect(request.request.params.get('version')).toBe('7');
    request.flush(weekly({ status: 'RETIRED' }));
    await fixture.whenStable();

    expect(facade.refresh).toHaveBeenCalled();
  });

  it('activates behind a plain confirm, with no reason', async () => {
    const fixture = setup();
    facade.series.set(weekly({ status: 'DRAFT' }));

    (await menuItem(fixture, 'admin.publications.series.editor.activate'))!.click();
    await fixture.whenStable();

    const confirm = document.querySelector('.confirm-panel, .dialog-panel');
    expect(confirm?.textContent).toContain('admin.publications.series.editor.activateTitle');
    (confirm!.querySelector('.btn-primary') as HTMLButtonElement).click();
    await fixture.whenStable();

    const request = TestBed.inject(HttpTestingController).expectOne(
      (r) => r.url === '/rest/publication-series/series/weekly-ntm/status',
    );
    expect(request.request.body).toBe(JSON.stringify('ACTIVE'));
    expect(request.request.params.get('reason')).toBeNull();
    request.flush(weekly());
  });

  /**
   * The one step out of DRAFT is offered inside the sentence that says why the
   * screen is refusing everything else.
   */
  it('offers the activation inline in the draft note', async () => {
    const fixture = setup();
    facade.series.set(weekly({ status: 'DRAFT' }));
    await fixture.whenStable();

    const inline = button(fixture, 'admin.publications.detail.activateInline');
    expect(inline).toBeDefined();
    inline!.click();
    await fixture.whenStable();

    expect(document.querySelector('.confirm-panel, .dialog-panel')?.textContent).toContain(
      'admin.publications.series.editor.activateTitle',
    );
  });
});

describe('SeriesDetail — reviewing an issue before it is created', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    facade.issues.set([]);
    resetFacade();
  });

  /**
   * No one-click create remains.
   *
   * The bound a gap row carries is the preceding issue's close where there is
   * one and a nominal step where there is not, so the old click could commit a
   * reconstructed date as though somebody had chosen it.
   */
  it('opens the draft dialog from a missing period instead of creating', async () => {
    const fixture = setup();
    facade.issues.set([GAP_ROW]);
    await fixture.whenStable();

    button(fixture, 'admin.publications.detail.gaps.create')!.click();
    await fixture.whenStable();

    expect(facade.createIssue).not.toHaveBeenCalled();
    expect(panel()?.textContent).toContain('admin.publications.issueDraft.title');
  });

  it('opens the same dialog from the new-issue control', async () => {
    const fixture = setup();
    await fixture.whenStable();

    button(fixture, 'admin.publications.detail.newIssue')!.click();
    await fixture.whenStable();

    expect(facade.createIssue).not.toHaveBeenCalled();
    expect(panel()?.textContent).toContain('admin.publications.issueDraft.title');
  });
});

/**
 * A series another domain is accountable for is READ here, never written.
 *
 * The whole screen still renders — the archive, the header, the comparison
 * evidence — because reads are not scoped and an admin who cannot see a series
 * cannot be told why their write was refused. What goes away is every control
 * that would have produced a refusal, and one line says why.
 */
describe('SeriesDetail — a series another domain owns', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    facade.issues.set([]);
    resetFacade();
  });

  /**
   * The writes withdrawn, without the series itself changing hands.
   *
   * A series whose OWNER is another desk is refused in place of the page — which
   * is the test below — so what these exercise is the other half: the controls
   * that are gated on the write rule, on a screen that does render.
   */
  function fromAnotherDomain(): void {
    facade.writable.set(false);
    facade.canCreateIssue.set(false);
  }

  /** The page itself is refused, and the desk that owns it is what is named. */
  it('refuses the page and names the desk that owns it', async () => {
    const fixture = setup();
    facade.series.set(weekly({ domainId: 'niord-annex' }));
    await fixture.whenStable();

    expect(element(fixture).querySelector('[data-testid="foreign-domain-refusal"]')).not.toBeNull();
    expect(element(fixture).textContent).toContain('admin.publications.foreignDomain.title');
  });

  it('offers neither the new issue nor the gap-row create', async () => {
    const fixture = setup();
    fromAnotherDomain();
    facade.issues.set([GAP_ROW]);
    await fixture.whenStable();

    expect(button(fixture, 'admin.publications.detail.newIssue')).toBeUndefined();
    expect(button(fixture, 'admin.publications.detail.gaps.create')).toBeUndefined();
  });
});

/**
 * Moving a series to another desk.
 *
 * The action is a WRITE, so it is gated on the series being this desk's — and
 * it is gated a second time on there being anywhere to move the series to, which
 * is a fact about the caller's own grants rather than about the series.
 */
describe('SeriesDetail — moving the series to another domain', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    transferTargets.set([]);
    facade.transferErrorKey.set(undefined);
    facade.transferErrorDetail.set(undefined);
    resetFacade();
  });

  function withTargets(): void {
    transferTargets.set([{ domainId: 'niord-annex', name: 'NM Annex' } as Domain]);
  }

  it('submits the target and the reason together', async () => {
    withTargets();
    const fixture = setup();
    facade.series.set(weekly({ domainId: 'niord-nm' }));

    (await menuItem(fixture, 'admin.publications.transfer.action'))!.click();
    await fixture.whenStable();
    expect(panel()).not.toBeNull();

    const select = document.querySelector('#publication-transfer-domain') as HTMLSelectElement;
    select.value = 'niord-annex';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    const reason = document.querySelector('#publication-transfer-reason') as HTMLTextAreaElement;
    reason.value = 'the annex desk maintains it now';
    reason.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();

    const confirm = [...document.querySelectorAll('.dialog-panel button')].find((b) =>
      b.textContent?.includes('admin.publications.transfer.confirm'),
    ) as HTMLButtonElement;
    expect(confirm.disabled).toBe(false);
    confirm.click();
    await fixture.whenStable();

    expect(facade.transferOwner).toHaveBeenCalledWith(
      'niord-annex',
      'the annex desk maintains it now',
      expect.any(Function),
    );
  });

  /**
   * A refusal names WHICH domain is missing, in the server's own words.
   *
   * The mapped sentence says the rule; only the server's sentence says which of
   * the two sides the caller is not an admin in, and no key can carry that.
   */
  it('shows the server’s own sentence beneath a domain refusal', async () => {
    withTargets();
    const fixture = setup();

    (await menuItem(fixture, 'admin.publications.transfer.action'))!.click();
    facade.transferErrorKey.set('admin.publications.transfer.notInDomain');
    facade.transferErrorDetail.set('NOT_IN_DOMAIN: not an admin in niord-annex');
    await fixture.whenStable();

    const text = document.querySelector('.dialog-panel')?.textContent ?? '';
    expect(text).toContain('admin.publications.transfer.notInDomain');
    expect(text).toContain('not an admin in niord-annex');
  });
});

/**
 * The series' own trail, folded away below the archive.
 *
 * A record rather than a decision: nothing about a series makes reading it the
 * reason somebody came here, so it opens closed and the read behind it is paid
 * for by the reader opening it. It matters because it is where the line left by
 * a removed issue lives — the issue's own entries went with it.
 */
describe('SeriesDetail — the history of the series', () => {
  afterEach(() => {
    facade.history.set([]);
    facade.historyLoading.set(false);
    facade.historyError.set(false);
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  async function toggleHistory(fixture: Fixture) {
    await fixture.whenStable();
    element(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="series-history-toggle"]')!
      .click();
    await fixture.whenStable();
  }

  /** Closed, and nothing asked for: the panel is what pays for the request. */
  it('reads nothing while the panel is closed', async () => {
    const fixture = setup();
    await fixture.whenStable();

    expect(element(fixture).querySelector('[data-testid="series-history"]')).toBeNull();
    expect(facade.loadHistory).not.toHaveBeenCalled();
  });

  it('asks for the trail the first time the panel is opened', async () => {
    const fixture = setup();
    await toggleHistory(fixture);

    expect(facade.loadHistory).toHaveBeenCalled();
    expect(element(fixture).querySelector('[data-testid="series-history"]')).not.toBeNull();
  });

  /**
   * A series nothing has been recorded against says so, rather than showing an
   * empty box that reads as a panel that failed to load.
   */
  it('says so when nothing has been recorded against the series', async () => {
    const fixture = setup();
    await toggleHistory(fixture);

    expect(element(fixture).querySelector('[data-testid="series-history"]')!.textContent).toContain(
      'admin.publications.detail.history.empty',
    );
  });

  /**
   * The line a removed issue left behind carries what the issue was, because
   * nothing else does any more: its name, the period it covered, and the state
   * it was in when it went.
   */
  it('renders what a removal recorded — who, when, and what the issue was', async () => {
    facade.history.set([
      {
        id: 91,
        action: 'DELETED',
        actorLabel: 'R. Jensen',
        created: 1_766_000_000_000,
        reason: 'opened against the wrong period',
        detail: {
          publicId: 'i-9',
          statusAtDeletion: 'RETIRED',
          names: { en: 'Week 27', da: 'Uge 27' },
          intervalFrom: 1_765_000_000_000,
          intervalTo: 1_766_000_000_000,
        },
      } as IssueAuditEntry,
    ]);
    const fixture = setup();
    await toggleHistory(fixture);

    const shown = element(fixture).querySelector('[data-testid="series-history"]')!.textContent!;
    expect(shown).toContain('admin.publications.action.DELETED');
    expect(shown).toContain('R. Jensen');
    expect(shown).toContain('Week 27');
    expect(shown).toContain('admin.publications.issueStatus.RETIRED');
    expect(shown).toContain('opened against the wrong period');
  });

  /**
   * An action this build has no words for renders as its own code.
   *
   * The code is imperfect English but it is still the name of what happened,
   * where a key built blind renders as the key path and reads as a broken build.
   */
  it('renders an unrecognised action as the code rather than as a key path', async () => {
    facade.history.set([
      { action: 'SOMETHING_NEW', created: 1_766_000_000_000 } as IssueAuditEntry,
    ]);
    const fixture = setup();
    await toggleHistory(fixture);

    const shown = element(fixture).querySelector('[data-testid="series-history"]')!.textContent!;
    expect(shown).toContain('SOMETHING_NEW');
    expect(shown).not.toContain('admin.publications.action.SOMETHING_NEW');
  });

  /** A trail that could not be read says so, and the rest of the screen stands. */
  it('reports a failed read inside the panel alone', async () => {
    facade.historyError.set(true);
    const fixture = setup();
    await toggleHistory(fixture);

    expect(element(fixture).querySelector('[data-testid="series-history"]')!.textContent).toContain(
      'admin.publications.detail.history.error',
    );
  });
});

describe('SeriesDetail — the address', () => {
  afterEach(() => {
    resetFacade();
  });

  /**
   * The screen follows `?issue=` rather than reading it once on entry.
   *
   * A link from a compiled issue to one of the weeks it was compiled from lands
   * on another issue through this same panel, without rebuilding it — and Back
   * from there moves the address the same way. Read once, both left the issue
   * the reader had just left on screen until a reload.
   */
  it('opens the issue the address moves to, and returns to the archive when it is dropped', () => {
    const fixture = setup();
    const screen = fixture.debugElement.children[0].componentInstance as unknown as {
      selectedIssueId(): string | undefined;
    };
    expect(screen.selectedIssueId()).toBeUndefined();

    queryParams.next(convertToParamMap({ issue: 'w27' }));
    fixture.detectChanges();
    expect(screen.selectedIssueId()).toBe('w27');
    expect(facade.setArchiveShown).toHaveBeenLastCalledWith(false);

    queryParams.next(convertToParamMap({}));
    fixture.detectChanges();
    expect(screen.selectedIssueId()).toBeUndefined();
    expect(facade.setArchiveShown).toHaveBeenLastCalledWith(true);
  });

  /** The address echoing the screen's own write back leaves the screen where it put itself. */
  it('keeps an issue it opened itself when the address echoes it', () => {
    const fixture = setup();
    const screen = fixture.debugElement.children[0].componentInstance as unknown as {
      selectedIssueId(): string | undefined;
      openIssue(publicId: string): void;
    };

    screen.openIssue('w28');
    fixture.detectChanges();
    queryParams.next(convertToParamMap({ issue: 'w28' }));
    fixture.detectChanges();

    expect(screen.selectedIssueId()).toBe('w28');
  });
});
