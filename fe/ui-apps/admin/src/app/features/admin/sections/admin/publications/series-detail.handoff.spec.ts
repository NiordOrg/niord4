import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

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
  PublicationIssueRow,
  PublicationSeries,
  PublishChecklist,
} from '../../../services/api/publication-series-api.service';
import { SeriesDetail } from './series-detail';
import { SeriesDetailFacade } from './series-detail.facade';
import { PublicationTransferTargets } from './transfer-owner';

/** The row the list clicked, which is everything the header needs. */
const CLICKED = {
  seriesId: 'weekly-ntm',
  status: 'DRAFT',
  descs: [{ lang: 'en', name: 'Weekly NtM' }],
  cadence: 'WEEKLY',
} as unknown as PublicationSeries;

const taken = { back: 0, settings: 0, copy: 0 };

@Component({
  imports: [SeriesDetail],
  template: `<app-series-detail
    seriesId="weekly-ntm"
    [initial]="initial()"
    (back)="taken.back = taken.back + 1"
    (settings)="taken.settings = taken.settings + 1"
    (copySeries)="taken.copy = taken.copy + 1"
  />`,
})
class Host {
  readonly initial = signal<PublicationSeries | undefined>(CLICKED);
  protected readonly taken = taken;
}

const LANGUAGES = [{ value: 'en', label: 'English', locale: 'en-GB', flagPath: '' }];

/**
 * ONE facade mock, reused — and answering NOTHING by default.
 *
 * This spec is about the moment before the two requests behind the archive have
 * come back, which is exactly when a header drawn only from the fetch is blank.
 */
const facade = {
  series: signal<unknown>(undefined),
  issues: signal<readonly PublicationIssueRow[]>([]),
  issueCount: signal(0),
  loading: signal(true),
  error: signal(false),
  awaitingFirstRead: signal(false),
  gapDetection: signal(undefined),
  gapCount: signal(undefined),
  publicNowIssue: signal<PublicationIssueRow | undefined>(undefined),
  currentIssue: signal<PublicationIssueRow | undefined>(undefined),
  currentChecklist: signal<PublishChecklist | undefined>(undefined),
  seriesAcceptsIssues: signal(false),
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
  // Which of the two views is on screen; the archive read is gated on it.
  setArchiveShown: vi.fn(),
  load: vi.fn(),
  refresh: vi.fn(),
};

function setup() {
  vi.clearAllMocks();
  taken.back = 0;
  taken.settings = 0;
  taken.copy = 0;

  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: PublicationTransferTargets,
        useValue: { targetsFor: () => [] } as unknown as PublicationTransferTargets,
      },
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain: signal<Domain | undefined>({
            domainId: 'niord-nm',
            name: 'NM',
          } as Domain),
          availableDomains: signal<readonly Domain[]>([]),
        } as unknown as DomainFacade,
      },
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
      { provide: Router, useValue: { ...fakeRowLinkRouter(), navigate: vi.fn() } },
      { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  }).overrideComponent(SeriesDetail, {
    set: {
      providers: [{ provide: SeriesDetailFacade, useValue: facade }, ConfirmService],
    },
  });

  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, element: fixture.nativeElement as HTMLElement };
}

function click(element: HTMLElement, key: string): void {
  const found = [...element.querySelectorAll('button')].find((b) => b.textContent?.includes(key));
  expect(found, `no button for ${key}`).toBeDefined();
  found!.click();
}

/**
 * What the list already knew, drawn before the fetch answers.
 *
 * The two requests behind the archive take long enough on the busiest series
 * that a header waiting for them reads as a page which failed to load — and the
 * name and the status are the two things the list was already showing.
 */
describe('SeriesDetail — the row handed down from the list', () => {
  it('draws the header from the clicked row while the fetch is in flight', () => {
    const { element } = setup();

    const header = element.querySelector('header');
    expect(header?.querySelector('h2')?.textContent).toContain('Weekly NtM');
    expect(header?.querySelector('.status-badge')?.textContent).toContain(
      'admin.publications.seriesStatus.DRAFT',
    );
    expect(header?.textContent).toContain('admin.publications.cadence.WEEKLY');
  });

  /** Nothing BELOW the header is guessed: the archive waits for the real answer. */
  it('shows no archive and no current-issue card until the series arrives', () => {
    const { element } = setup();

    expect(element.querySelector('table')).toBeNull();
    expect(element.textContent).not.toContain('admin.publications.detail.currentIssue');
    expect(element.textContent).not.toContain('admin.publications.detail.history.title');
  });

  /** The fetch wins the moment it answers — the handoff is a placeholder only. */
  it('replaces the clicked row with the series itself', async () => {
    const { fixture, element } = setup();

    facade.series.set({ ...CLICKED, descs: [{ lang: 'en', name: 'Weekly Notices' }] });
    facade.loading.set(false);
    await fixture.whenStable();

    expect(element.querySelector('h2')?.textContent).toContain('Weekly Notices');

    facade.series.set(undefined);
    facade.loading.set(true);
  });

  /**
   * The header's actions menu looks like the one on every other admin page: the
   * cog with the dropdown chevron, and no hairline of its own.
   */
  it('draws the actions trigger as the standard cog with a chevron', () => {
    const { element } = setup();
    const trigger = element.querySelector('app-menu-trigger button') as HTMLButtonElement;

    expect(trigger.className).not.toContain('border-surface-200');
    expect(trigger.querySelectorAll('app-icon').length).toBe(2);
  });
});

/**
 * The list owns the selection and the editor drawer, so it decides.
 *
 * Navigating up from here and opening a drawer that lives on the list would be
 * this screen reaching past its own boundary; it asks instead.
 */
describe('SeriesDetail — what it asks the list to do', () => {
  it('asks the list to go back, open the settings, and start a copy', async () => {
    const { fixture, element } = setup();

    click(element, 'admin.publications.detail.backToList');
    click(element, 'admin.publications.series.editor.settings');
    await fixture.whenStable();

    expect(taken.back).toBe(1);
    expect(taken.settings).toBe(1);

    (element.querySelector('app-menu-trigger button') as HTMLButtonElement).click();
    await fixture.whenStable();
    const copy = [...document.querySelectorAll<HTMLElement>('.menu-panel .menu-item')].find(
      (item) => item.textContent?.includes('admin.publications.menu.copySeries'),
    );
    expect(copy).toBeDefined();
    copy!.click();
    await fixture.whenStable();

    expect(taken.copy).toBe(1);
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });
});
