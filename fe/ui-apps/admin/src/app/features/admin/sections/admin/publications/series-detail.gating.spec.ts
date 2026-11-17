import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
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

const LANGUAGES = [{ value: 'en', label: 'English', locale: 'en-GB', flagPath: '' }];

const NM_DESK = { domainId: 'niord-nm', name: 'NM' } as Domain;
const FA_DESK = { domainId: 'niord-fa', name: 'Firing areas' } as Domain;

/** The desk being worked at, as the load came up with it. */
const sessionDomain = signal<Domain | undefined>(NM_DESK);

/**
 * The header's picker applies a desk by reloading the whole application, so a
 * switch reaches this screen as a fresh mount at the same address — never as a
 * value changing under a screen that stayed up. Either way the owner answer is
 * the same one, which is the point of there being only one.
 */
const domains = {
  sessionDomain,
  availableDomains: signal<readonly Domain[]>([NM_DESK, FA_DESK]),
  editableDomains: signal<readonly Domain[]>([NM_DESK, FA_DESK]),
  resolveById: (domainId: string) =>
    [NM_DESK, FA_DESK].find((domain) => domain.domainId === domainId),
  selectSessionDomain: vi.fn(),
};

/** The series this screen was built on, as the archive read answers it. */
function seriesRow(domainId: string | undefined) {
  return {
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    domainId,
    descs: [{ lang: 'en', name: 'Weekly NtM' }],
    cadence: 'WEEKLY',
  };
}

/**
 * ONE facade mock, reused.
 *
 * `overrideComponent` caches the compiled definition, so a fresh mock per test
 * would leave the component bound to the first one.
 */
const facade = {
  series: signal<unknown>({
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    domainId: 'niord-nm',
    descs: [{ lang: 'en', name: 'Weekly NtM' }],
    cadence: 'WEEKLY',
  }),
  issues: signal<readonly PublicationIssueRow[]>([]),
  issueCount: signal(0),
  loading: signal(false),
  error: signal(false),
  notFound: signal(false),
  awaitingFirstRead: signal(false),
  gapDetection: signal({ enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' }),
  gapCount: signal(0),
  publicNowIssue: signal(undefined),
  currentIssue: signal<PublicationIssueRow | undefined>(undefined),
  currentChecklist: signal<PublishChecklist | undefined>(undefined),
  seriesAcceptsIssues: signal(true),
  canCreateIssue: signal(false),
  writable: signal(true),
  isEmpty: signal(false),
  working: signal(false),
  createErrorKey: signal(undefined),
  transferWorking: signal(false),
  transferErrorKey: signal(undefined),
  transferErrorDetail: signal(undefined),
  transferOwner: vi.fn(),
  createIssue: vi.fn(),
  setArchiveShown: vi.fn(),
  load: vi.fn(),
  refresh: vi.fn(),
};

/** One published issue, so the archive has something to draw. */
const ROW: PublicationIssueRow = {
  publicId: 'w27',
  status: 'PUBLISHED',
  descs: [{ lang: 'en', name: 'Week 27' }],
  sortKey: 1,
  effectiveCutoff: 1,
};

function setup(
  options: {
    desk?: Domain;
    awaiting?: boolean;
    /** Which desk owns the series; `null` for one that names none. */
    owner?: string | null;
    /** Mount before the series read has answered, as a slow read does. */
    seriesPending?: boolean;
    /** Open at one issue, as `?issue=` on the address does. */
    issue?: string;
    /** Mount with the series read already failed, as a stale or mistyped id does. */
    failed?: boolean;
    /** The failed read was the server's coded 404: the series is not there. */
    notFound?: boolean;
    /** Desks the reader holds a role in — what decides whether a switch is offered. */
    editable?: readonly Domain[];
  } = {},
) {
  vi.clearAllMocks();
  sessionDomain.set(options.desk ?? NM_DESK);
  domains.editableDomains.set(options.editable ?? [NM_DESK, FA_DESK]);
  const owner = options.owner === undefined ? 'niord-nm' : (options.owner ?? undefined);
  facade.series.set(options.seriesPending ? undefined : seriesRow(owner));
  facade.issues.set([ROW]);
  facade.issueCount.set(1);
  facade.isEmpty.set(false);
  facade.currentIssue.set(undefined);
  facade.awaitingFirstRead.set(options.awaiting ?? false);
  facade.error.set(options.failed ?? false);
  facade.notFound.set(options.notFound ?? false);

  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      // The real Router, so the relative navigation this screen makes is the one
      // Angular would actually run rather than a shape a hand-written stub let
      // through.
      provideRouter([]),
      {
        provide: PublicationTransferTargets,
        useValue: { targetsFor: () => [] } as unknown as PublicationTransferTargets,
      },
      { provide: DomainFacade, useValue: domains as unknown as DomainFacade },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), modelLanguages: LANGUAGES },
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
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  }).overrideComponent(SeriesDetail, {
    set: {
      providers: [{ provide: SeriesDetailFacade, useValue: facade }, ConfirmService],
    },
  });

  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const screen = fixture.debugElement.query(By.directive(SeriesDetail))
    .componentInstance as unknown as { openIssue(publicId: string): void };
  if (options.issue) {
    screen.openIssue(options.issue);
    fixture.detectChanges();
  }
  return { fixture, element: fixture.nativeElement as HTMLElement, navigate, screen };
}

/**
 * A series another desk owns is never shown here.
 *
 * However the reader arrived — a link from a colleague's mail, a bookmark, the
 * back button, or the desk picker reloading the application onto this address —
 * the answer is the same one: the page is refused, the desk that owns it is
 * named, and the switch that would make it theirs is the one thing offered.
 * Showing it with every action quietly withdrawn read as a screen that broke.
 */
describe('SeriesDetail — a series another desk owns', () => {
  /** What the refusal panel is, to a query that does not care how it is built. */
  function refusal(element: HTMLElement): HTMLElement | null {
    return element.querySelector('[data-testid="foreign-domain-refusal"]');
  }

  it('refuses the page and names the desk that owns it', async () => {
    const { fixture, element } = setup({ desk: FA_DESK });
    await fixture.whenStable();

    expect(refusal(element)).not.toBeNull();
    expect(element.textContent).toContain('admin.publications.foreignDomain.title');
    // Neither the header nor the archive: nothing about the series is drawn.
    expect(element.querySelector('table')).toBeNull();
    expect(element.querySelector('header')).toBeNull();
  });

  /**
   * The issue panel is not mounted either.
   *
   * An issue inherits its series' owner, and the workbench behind the panel
   * answers members, history, curation and the release rail — a request for an
   * issue this desk may not open.
   */
  it('does not mount the issue panel for an issue on a foreign series', async () => {
    const { fixture, element } = setup({ desk: FA_DESK, issue: 'w27' });
    await fixture.whenStable();

    expect(element.querySelector('app-issue-detail')).toBeNull();
    expect(refusal(element)).not.toBeNull();
  });

  /**
   * Nor before the series has answered.
   *
   * The owner arrives with the series, so a panel mounted first would have made
   * its own read for an issue nobody had established the reader may open.
   */
  it('waits for the series before mounting the issue panel', async () => {
    const { fixture, element } = setup({ seriesPending: true, issue: 'w27' });
    await fixture.whenStable();
    expect(element.querySelector('app-issue-detail')).toBeNull();

    facade.series.set(seriesRow('niord-nm'));
    await fixture.whenStable();

    expect(element.querySelector('app-issue-detail')).not.toBeNull();
  });

  /** The one action: the desk it belongs to, applied the way the picker applies one. */
  it('switches to the owning desk when the refusal is acted on', async () => {
    const { fixture, element } = setup({ desk: FA_DESK });
    await fixture.whenStable();

    element
      .querySelector<HTMLButtonElement>('[data-testid="foreign-domain-refusal"] button')!
      .click();

    // Keeping the address is the point of this one switch: the desk being chosen
    // is the one that can render this series.
    expect(domains.selectSessionDomain).toHaveBeenCalledWith('niord-nm', { keepAddress: true });
  });

  /**
   * A desk the reader holds no role in is named, and nothing is offered.
   *
   * The switch resolves the desk out of the catalogue the picker uses, so one
   * they are not a member of cannot be switched to — and a button that fails
   * explains less than a sentence that says why.
   */
  it('offers no switch to a desk the reader is not a member of', async () => {
    const { fixture, element } = setup({ desk: FA_DESK, editable: [FA_DESK] });
    await fixture.whenStable();

    expect(element.textContent).toContain('admin.publications.foreignDomain.noAccess');
    expect(element.querySelector('[data-testid="foreign-domain-refusal"] button')).toBeNull();
  });

  /** Nothing is refused while the series is still on its way. */
  it('draws no refusal before the series has answered', async () => {
    const { fixture, element } = setup({ desk: FA_DESK, seriesPending: true });
    await fixture.whenStable();

    expect(refusal(element)).toBeNull();
  });

  /**
   * A publication that names no desk belongs to every desk.
   *
   * There is nowhere else to look at it, so refusing it here would make it
   * unreachable from everywhere.
   */
  it('renders a series that names no owning desk, from any desk', async () => {
    const { fixture, element } = setup({ desk: FA_DESK, owner: null });
    await fixture.whenStable();

    expect(refusal(element)).toBeNull();
    expect(element.querySelector('header h2')?.textContent).toContain('Weekly NtM');
  });

  /** From the desk that owns it, nothing about the screen has changed. */
  it('renders the series from the desk that owns it', async () => {
    const { fixture, element } = setup({ desk: NM_DESK });
    await fixture.whenStable();

    expect(refusal(element)).toBeNull();
    expect(element.querySelector('table')).not.toBeNull();
  });
});

/**
 * The panels below the header wait for the first pair of answers.
 *
 * They are drawn from the archive, and an archive nobody has fetched yet is
 * indistinguishable from an empty one — so the first paint announced "no issue
 * matches the filter" and an empty current-issue card, about a list still on its
 * way.
 */
describe('SeriesDetail — the first read', () => {
  it('draws neither the current-issue card nor the archive while the first read is out', () => {
    const { element } = setup({ awaiting: true });

    expect(element.textContent).not.toContain('admin.publications.detail.currentIssue');
    expect(element.textContent).not.toContain('admin.publications.filter.noMatchingIssues');
    expect(element.querySelector('table')).toBeNull();
    expect(element.querySelector('app-publication-list-filter')).toBeNull();
    // What IS on screen: the header, and the strip that says a read is running.
    expect(element.querySelector('header h2')?.textContent).toContain('Weekly NtM');
    expect(element.querySelector('app-progress-bar')).not.toBeNull();
  });

  it('draws both once the series and the archive have answered', async () => {
    const { fixture, element } = setup({ awaiting: true });

    facade.awaitingFirstRead.set(false);
    await fixture.whenStable();

    expect(element.textContent).toContain('admin.publications.detail.currentIssue');
    expect(element.querySelector('table')).not.toBeNull();
  });

  /**
   * A re-read keeps what is on screen.
   *
   * This screen refetches itself after every action taken on it, and a table
   * that blanked each time would read as a page that keeps breaking.
   */
  it('keeps the rows through a refresh', async () => {
    const { fixture, element } = setup();
    expect(element.querySelector('table')).not.toBeNull();

    facade.loading.set(true);
    await fixture.whenStable();

    expect(element.querySelector('table')).not.toBeNull();
    expect(element.textContent).toContain('admin.publications.detail.currentIssue');

    facade.loading.set(false);
  });
});

/**
 * A link straight into an issue of a series that cannot be read.
 *
 * The owner lives on the series, so the workbench is never mounted ahead of it —
 * and the workbench is where this branch's own back button and error banner
 * live. Without them a stale or mistyped id left the reader on a spinner that
 * never resolves, with nothing said and nowhere to go.
 */
describe('SeriesDetail — a deep link whose series fails', () => {
  it('says the series could not be read, and offers the retry and the way back', async () => {
    const { fixture, element } = setup({ seriesPending: true, failed: true, issue: 'w27' });
    await fixture.whenStable();

    expect(element.querySelector('app-issue-detail')).toBeNull();
    const banner = element.querySelector('app-error-banner');
    expect(banner?.textContent).toContain('admin.publications.detail.loadError');
    expect(banner?.querySelector('button')?.textContent).toContain('admin.publications.retry');
    expect(element.textContent).toContain('admin.publications.detail.backToList');
  });

  /** A series that is not there says so, and offers no Retry that would meet the same 404. */
  it('says a missing series does not exist, without Retry, with the way back', async () => {
    const { fixture, element } = setup({
      seriesPending: true,
      failed: true,
      notFound: true,
      issue: 'w27',
    });
    await fixture.whenStable();

    expect(element.querySelector('app-issue-detail')).toBeNull();
    const banner = element.querySelector('app-error-banner');
    expect(banner?.textContent).toContain('admin.publications.error.seriesNotFound');
    expect(banner?.querySelector('button')).toBeNull();
    expect(element.textContent).toContain('admin.publications.detail.backToList');
  });

  /** The bare strip is kept for the series that is genuinely still on its way. */
  it('waits on the progress strip while the series is still in flight', async () => {
    const { fixture, element } = setup({ seriesPending: true, issue: 'w27' });
    await fixture.whenStable();

    expect(element.querySelector('app-error-banner')).toBeNull();
    expect(element.querySelector('app-progress-bar')).not.toBeNull();
  });
});

/**
 * What the facade is told about the archive: which view is on screen, and only that.
 *
 * The archive is the heaviest read in the section and the workbench ignores it,
 * so opening an issue takes it off the gate. Whose publication it is settles
 * nothing here — the owner arrives on the series, which the facade holds before
 * this screen could answer at all, so that refusal is decided inside the read
 * chain rather than by a flag set one flush later.
 */
describe('SeriesDetail — the archive gate', () => {
  it('asks for the archive while it is the view on screen', async () => {
    const { fixture } = setup();
    await fixture.whenStable();

    expect(facade.setArchiveShown).toHaveBeenLastCalledWith(true);
  });

  it('takes it off the gate while an issue is open', async () => {
    const { fixture } = setup({ issue: 'w27' });
    await fixture.whenStable();

    expect(facade.setArchiveShown).toHaveBeenLastCalledWith(false);
  });
});

/**
 * L-044. An activation or a reinstatement the series rules refuse says which
 * step failed and lists the rules, in the words the series editor uses.
 *
 * It used to say "The publication could not be saved as it stands", the save
 * sentence, and name no rule: the admin had saved nothing, and had to open the
 * editor and check the rules to learn why.
 */
describe('SeriesDetail — a transition the series rules refuse', () => {
  /** The refusal as the status endpoint sends it: a code and the failed rules. */
  const RULES_REFUSED = {
    code: 'SERIES_INVALID',
    message: '2 rule(s) fail',
    fieldErrors: [
      { rule: 'S-17', field: 'reportId', message: 'an active series names its report' },
      { rule: 'S-99', field: 'cadence', message: 'a rule with no sentence yet' },
    ],
  };

  function transitionSetup(status: 'DRAFT' | 'RETIRED') {
    const { fixture } = setup();
    facade.series.set({ ...seriesRow('niord-nm'), status, version: 3 });
    fixture.detectChanges();
    TestBed.inject(TranslocoService).setTranslation(
      { 'admin.publications.rule.S-17': 'An active series must name its report.' },
      'en',
    );
    const notifications = TestBed.inject(NotificationService);
    const notify = vi.spyOn(notifications, 'notify');
    const error = vi.spyOn(notifications, 'error');
    const debug = fixture.debugElement.query(By.directive(SeriesDetail));
    const screen = debug.componentInstance as unknown as { activate(): void; reinstate(): void };
    const confirm = debug.injector.get(ConfirmService);
    return { fixture, notify, error, screen, confirm };
  }

  function refuse(body: object, status = 400): void {
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url === '/rest/publication-series/series/weekly-ntm/status')
      .flush(body, { status, statusText: 'Refused' });
  }

  it('says the activation failed and lists the rules it failed', () => {
    const { notify, error, screen, confirm } = transitionSetup('DRAFT');

    screen.activate();
    confirm.active()!.accept();
    refuse(RULES_REFUSED);

    expect(error).not.toHaveBeenCalled();
    expect(notify).toHaveBeenCalledTimes(1);
    const toast = notify.mock.calls[0][0];
    expect(toast.severity).toBe('error');
    expect(toast.summaryKey).toBe('admin.publications.series.toast.activateError');
    expect(toast.summaryKey).not.toBe('admin.publications.error.seriesInvalid');
    expect(toast.detailKey).toBe('admin.publications.series.toast.failedRules');
    expect(toast.detailParams?.['rules']).toBe(
      'An active series must name its report. (S-17) admin.publications.series.ruleFailed',
    );
    // A list of rules is not read in five seconds: it stays until it is closed.
    expect(toast.life).toBe(0);
  });

  it('says the reinstatement failed, not the activation', () => {
    const { notify, screen, confirm } = transitionSetup('RETIRED');

    screen.reinstate();
    confirm.active()!.accept();
    refuse(RULES_REFUSED);

    const toast = notify.mock.calls[0][0];
    expect(toast.summaryKey).toBe('admin.publications.series.toast.reinstateError');
    expect(toast.detailKey).toBe('admin.publications.series.toast.failedRules');
  });

  /** A refusal that named no rule says only which step failed. */
  it('says the activation failed with no list when the refusal named no rule', () => {
    const { notify, error, screen, confirm } = transitionSetup('DRAFT');

    screen.activate();
    confirm.active()!.accept();
    refuse({ code: 'SERIES_INVALID', message: 'refused' });

    expect(notify).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('admin.publications.series.toast.activateError');
  });

  /** Every other refusal keeps its own sentence. */
  it('keeps the mapped sentence for a refusal that is not about the rules', () => {
    const { notify, error, screen, confirm } = transitionSetup('DRAFT');

    screen.activate();
    confirm.active()!.accept();
    refuse({ code: 'NOT_IN_DOMAIN', message: 'another desk' }, 403);

    expect(notify).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalledWith('admin.publications.error.notInDomain');
  });
});
