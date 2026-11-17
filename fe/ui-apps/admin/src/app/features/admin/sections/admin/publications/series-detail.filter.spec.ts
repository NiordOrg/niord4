import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { TranslocoService } from '@jsverse/transloco';
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

const DAY = 86_400_000;

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '/img/flags/da.png' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '/img/flags/en.png' },
];

/**
 * ONE facade mock, reused.
 *
 * `overrideComponent` caches the compiled definition, so a fresh mock per test
 * would leave the component bound to the first one and every table would keep
 * answering with the previous test's rows.
 */
const facade = {
  series: signal<unknown>({
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    descs: [],
    cadence: 'WEEKLY',
  }),
  issues: signal<readonly PublicationIssueRow[]>([]),
  issueCount: signal(0),
  loading: signal(false),
  error: signal(false),
  awaitingFirstRead: signal(false),
  gapDetection: signal({ enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' }),
  gapCount: signal(0),
  publicNowIssue: signal(undefined),
  currentIssue: signal(undefined),
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
  // Which of the two views is on screen; the archive read is gated on it.
  setArchiveShown: vi.fn(),
  load: vi.fn(),
  refresh: vi.fn(),
};

/**
 * Three issues and one period nobody published.
 *
 * The synthesized row is here deliberately: it is named only by what the series
 * WOULD call the period, and dropping it from a filtered list would hide exactly
 * the row the archive screen exists to surface.
 */
const ROWS: readonly PublicationIssueRow[] = [
  {
    publicId: 'w27',
    status: 'PUBLISHED',
    descs: [{ lang: 'da', name: 'Uge 27' }],
    sortKey: 3 * DAY,
    effectiveCutoff: 3 * DAY,
  },
  {
    publicId: 'w28',
    status: 'PUBLISHED',
    descs: [
      { lang: 'da', name: 'Uge 28' },
      { lang: 'en', name: 'Week 28' },
    ],
    sortKey: 2 * DAY,
    effectiveCutoff: 2 * DAY,
  },
  {
    publicId: 'aarlig',
    status: 'PUBLISHED',
    descs: [{ lang: 'da', name: 'Årlig oversigt' }],
    sortKey: 1 * DAY,
    effectiveCutoff: 1 * DAY,
  },
  {
    pseudo: 'MISSING',
    // A period nobody published has no descs of its own — only what the series
    // would call it.
    descs: [],
    suggestedDescs: [{ lang: 'da', name: 'Uge 29' }],
    sortKey: 4 * DAY,
    computedStatus: 'MISSING',
  },
];

let router: { navigate: ReturnType<typeof vi.fn> } & Record<string, unknown>;

interface DetailInternals {
  issueFilter: () => string;
  setIssueFilter: (value: string) => void;
}

/**
 * What the transfer picker would offer. Empty unless a test says otherwise.
 *
 * A SIGNAL, not a plain variable: the gate that reads it is a `computed()`, and a
 * value it cannot track leaves the first (empty) answer cached for the life of
 * the fixture.
 */
const transferTargets = signal<readonly Domain[]>([]);

function setup(options: { rows?: readonly PublicationIssueRow[]; issueFilter?: string } = {}) {
  vi.clearAllMocks();
  const rows = options.rows ?? ROWS;
  facade.issues.set(rows);
  facade.issueCount.set(rows.filter((r) => !r.pseudo).length);
  facade.isEmpty.set(rows.length === 0);

  router = { ...fakeRowLinkRouter(), navigate: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      // The screen mounts the draft dialog, which reads its own preview from the
      // API — so the tree needs a client even where no test opens it.
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
      // The header names the owning desk from the catalogue, which reaches the
      // AuthFacade -> Keycloak chain through the live facade.
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
        useValue: { selectedLanguage: signal('da'), modelLanguages: LANGUAGES },
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
      { provide: Router, useValue: router },
      {
        provide: ActivatedRoute,
        useValue: {
          queryParamMap: of(
            convertToParamMap(options.issueFilter ? { issueFilter: options.issueFilter } : {}),
          ),
        },
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

  // The count beside the box is the one string here whose PARAMETER is the
  // assertion; the stub loader renders keys, so these keys get real messages.
  // Both forms of it: which one the screen reaches for is part of what the
  // count says.
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.publications.detail.issueCount': '{{count}} issues',
      'admin.publications.detail.issueCountOne': '{{count}} issue',
    },
    'en',
  );

  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const panel = fixture.debugElement.children[0];
  return {
    fixture,
    element: fixture.nativeElement as HTMLElement,
    detail: panel.componentInstance as unknown as DetailInternals,
  };
}

/** The public id under each row's name — the stable handle, not the rendered copy. */
function rowNames(element: HTMLElement): string[] {
  return [...element.querySelectorAll('tbody tr td:first-child')].map((cell) =>
    (cell.textContent ?? '').trim(),
  );
}

describe('SeriesDetail — the issue filter', () => {
  it('narrows the archive to the rows whose name matches', () => {
    const { element, fixture, detail } = setup();
    expect(rowNames(element)).toHaveLength(4);

    detail.setIssueFilter('uge 2');
    fixture.detectChanges();

    const names = rowNames(element).join(' | ');
    expect(names).toContain('Uge 27');
    expect(names).toContain('Uge 28');
    // The period nobody published is named by what the series would call it.
    expect(names).toContain('Uge 29');
    expect(names).not.toContain('Årlig');
  });

  /**
   * Every language a row carries, not the one being rendered.
   *
   * The screen is in Danish here; an issue named "Week 28" in English is still
   * findable by that name.
   */
  it('matches a name in a language the screen is not showing', () => {
    const { element, fixture, detail } = setup();

    detail.setIssueFilter('week 28');
    fixture.detectChanges();

    expect(rowNames(element)).toHaveLength(1);
    expect(rowNames(element)[0]).toContain('Uge 28');
  });

  it('folds the accents the reader did not type', () => {
    const { element, fixture, detail } = setup();

    detail.setIssueFilter('arlig');
    fixture.detectChanges();

    expect(rowNames(element)).toHaveLength(1);
    expect(rowNames(element)[0]).toContain('Årlig oversigt');
  });

  /**
   * A series with issues and a filter none of them match is its own state.
   *
   * Reusing "no issues yet" would be untrue and would send the reader looking
   * for an import that never failed.
   */
  it('says the filter matched nothing rather than that the series is empty', () => {
    const { element, fixture, detail } = setup();

    detail.setIssueFilter('nothing at all');
    fixture.detectChanges();

    expect(element.querySelector('tbody')).toBeNull();
    expect(element.textContent).toContain('admin.publications.filter.noMatchingIssues');
    expect(element.textContent).not.toContain('admin.publications.detail.noIssues');
  });

  it('mirrors the settled filter into the query, and drops it when cleared', () => {
    const { fixture, detail } = setup();

    detail.setIssueFilter('uge 27');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        queryParams: { issueFilter: 'uge 27' },
        queryParamsHandling: 'merge',
      }),
    );

    detail.setIssueFilter('');
    fixture.detectChanges();
    expect(router.navigate).toHaveBeenLastCalledWith(
      [],
      expect.objectContaining({ queryParams: { issueFilter: null } }),
    );
  });

  it('opens filtered when the address bar carries one', () => {
    const { element, detail } = setup({ issueFilter: 'årlig' });

    expect(detail.issueFilter()).toBe('årlig');
    expect(rowNames(element)).toHaveLength(1);
    const input = element.querySelector('app-publication-list-filter input') as HTMLInputElement;
    expect(input.value).toBe('årlig');
  });

  /**
   * The count beside the box is the count AFTER the filter.
   *
   * A total that ignores the filter and a list that obeys it are two answers to
   * one question, and the number is the one a reader checks first. It is also
   * said in the form that fits the number: "1 issue(s)" is the shape of a
   * sentence nobody wrote for the count it ended up carrying.
   */
  it('counts the rows the filter leaves, not the whole archive', () => {
    const { element, fixture, detail } = setup();
    const count = () =>
      element.querySelector('.admin-toolbar span.text-fg-muted')?.textContent?.trim();

    expect(count()).toBe('4 issues');

    detail.setIssueFilter('uge 28');
    fixture.detectChanges();

    expect(rowNames(element)).toHaveLength(1);
    expect(count()).toBe('1 issue');
  });

  /** No box over an archive with nothing in it — there is nothing to narrow. */
  it('offers no filter box on a series that has no issues at all', () => {
    const { element } = setup({ rows: [] });

    expect(element.querySelector('app-publication-list-filter')).toBeNull();
    expect(element.textContent).toContain('admin.publications.detail.noIssues');
  });
});
