import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, computed, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { IssueDetail } from './issue-detail';
import { IssueDetailFacade } from './issue-detail.facade';

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '/img/flags/da.png' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '/img/flags/en.png' },
];

const SERIES = {
  seriesId: 'weekly-ntm',
  status: 'ACTIVE',
  kind: 'SCHEDULED',
  cadence: 'WEEKLY',
  contentMode: 'GENERATED_FROM_QUERY',
  domainId: 'niord-nm',
  languages: ['da', 'en'],
  reportId: 'nm-tp-report',
  reportParams: { overskrift: 'Aktive P&T' },
  descs: [
    {
      lang: 'da',
      name: 'Aktive P&T',
      nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year}',
      fileNamePattern: 'EfS-PT-Uge-${week}-${year}.pdf',
    },
    {
      lang: 'en',
      name: 'Active P&T',
      nameSuggestionPattern: 'Active P&T Week ${week} - ${year}',
      fileNamePattern: 'NtM-PT-Week-${week}-${year}.pdf',
    },
  ],
} as unknown as PublicationSeries;

const FOLLOWING = {
  publicId: 'i-38',
  seriesId: 'weekly-ntm',
  status: 'OPEN',
  week: 38,
  year: 2026,
  descs: [
    { lang: 'da', name: 'Aktive P&T uge 38 - 2026' },
    { lang: 'en', name: 'Active P&T Week 38 - 2026' },
  ],
} as PublicationIssue;

/** ONE facade mock, reset per test — overrideComponent caches the first one it sees. */
const facade = {
  issue: signal<PublicationIssue | undefined>(undefined),
  members: signal([]),
  lateMembers: signal([]),
  lateAfter: signal(undefined),
  tableMembers: computed(() => []),
  switchAvailable: computed(() => false),
  viewInstant: signal('now'),
  viewedAt: signal(undefined),
  viewedFrom: signal(undefined),
  setPreviewPeriod: vi.fn(),
  periodProblem: signal(undefined),
  setViewInstant: vi.fn(),
  attachSeries: vi.fn(),
  // A compilation draws its member list grouped by the source issue each row
  // came from. Nothing here is one, so the flat list is what every case below
  // asserts against -- the grouped rendering has its own file.
  compiled: signal(false),
  sources: signal(undefined),
  groupedMembers: computed(() => []),
  audit: signal([]),
  overrides: signal([]),
  checklist: signal(undefined),
  loading: signal(false),
  error: signal(false),
  working: signal(false),
  // Which of the two slow writes is running. The screen points at the place the
  // work is happening — the release control, or the documents being replaced —
  // so a single busy flag is not enough for it to render.
  publishing: signal(false),
  amending: signal(false),
  lastActionError: signal(undefined),
  lastActionMessage: signal(undefined),
  curatable: signal(true),
  acknowledgeableWarnings: signal([]),
  informationalWarnings: signal([]),
  blockers: signal([]),
  unacknowledgedWarnings: signal([]),
  previewRows: signal([]),
  previewWorking: signal(false),
  lastPreviewError: signal(undefined),
  load: vi.fn(),
  refresh: vi.fn(),
  clearError: vi.fn(),
  uploadFile: vi.fn(),
  clearFile: vi.fn(),
  setLink: vi.fn(),
  edit: vi.fn(),
  publish: vi.fn(),
  retire: vi.fn(),
  reactivate: vi.fn(),
  generatePreview: vi.fn(),
  fetchPreview: vi.fn(),
  excludeMembers: vi.fn(),
  includeByMessageId: vi.fn(),
  clearOverride: vi.fn(),
  amend: vi.fn(),
  newEdition: vi.fn(),
  deleteIssue: vi.fn(),
  citingMessages: signal([]),
  citingCount: signal(0),
  citationsBounded: signal(false),
  omissions: signal([]),
  omissionCount: signal(0),
  omissionsBounded: signal(false),
  publishedSinceRelease: signal([]),
  successorDescs: signal([]),
  probeSuccessor: vi.fn(),
};

@Component({
  imports: [IssueDetail],
  template: `<app-issue-detail publicId="i-38" [previewDebounceMs]="0" [series]="series()" />`,
})
class Host {
  readonly series = input<PublicationSeries | undefined>(undefined);
}

/** The desk the admin is sitting at — the fixture series' own, unless a test says otherwise. */
const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-nm' } as Domain);

/** The protected surface these assertions reach for. */
interface Internals {
  canTailor(): boolean;
  tailored(): boolean;
  tailoredFactCount(): number;
  overridesOpen: { (): boolean; set: (v: boolean) => void };
  overridesMounted(): boolean;
  openOverrides(): void;
  saveOverrides(patch: Record<string, unknown>): void;
  documentFacts(): readonly { key: string; labelKey: string; param: string; value: string }[];
}

function setup(issue: Partial<PublicationIssue>, series: PublicationSeries | undefined = SERIES) {
  vi.clearAllMocks();
  sessionDomain.set({ domainId: 'niord-nm' } as Domain);
  facade.error.set(false);
  facade.working.set(false);
  facade.publishing.set(false);
  facade.amending.set(false);
  facade.issue.set({ ...FOLLOWING, ...issue } as PublicationIssue);

  TestBed.configureTestingModule({
    providers: [
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('da'), modelLanguages: LANGUAGES },
      },
      {
        provide: LocaleFormatService,
        useValue: {
          formatDate: () => '',
          formatDateTime: () => '',
          formatTime: () => '',
          formatWeekdayDateTime: () => '',
          uses12HourClock: () => false,
          sessionTimeZone: () => 'Europe/Copenhagen',
        },
      },
      { provide: DiscardConfirmService, useValue: { confirmDiscard: () => Promise.resolve(true) } },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: DomainFacade,
        // `resolveById` names the owning desk in the "another domain" note the
        // header draws; the fixture series names a desk, so the lookup runs.
        useValue: {
          sessionDomain,
          resolveById: (domainId: string) => ({ domainId, name: domainId }),
          // Enough of the catalogue for the refusal a foreign series would draw.
          editableDomains: signal<readonly Domain[]>([]),
          selectSessionDomain: vi.fn(),
        } as unknown as DomainFacade,
      },
    ],
  }).overrideComponent(IssueDetail, {
    set: { providers: [{ provide: IssueDetailFacade, useValue: facade }, ConfirmService] },
  });

  const fixture = TestBed.createComponent(Host);
  fixture.componentRef.setInput('series', series);
  fixture.detectChanges();
  const detail = fixture.debugElement.children[0].componentInstance as unknown as Internals;
  return { fixture, detail };
}

function tailorButton(fixture: { nativeElement: HTMLElement }): HTMLButtonElement | null {
  return fixture.nativeElement.querySelector<HTMLButtonElement>('[data-testid="issue-tailor"]');
}

afterEach(() => {
  document.body.replaceChildren();
  TestBed.resetTestingModule();
});

describe('the entry point onto the tailoring drawer', () => {
  it('is offered on an OPEN issue the reader may write', () => {
    const { fixture, detail } = setup({ status: 'OPEN' });
    expect(detail.canTailor()).toBe(true);
    expect(tailorButton(fixture)).not.toBeNull();
  });

  /**
   * A published issue's file names are the addresses its citations resolve to
   * and its numbering is what went out on the cover. Changing either is not a
   * correction but a rewrite of the record — the action is withheld rather than
   * offered and refused.
   */
  it('is withheld once the issue has gone out', () => {
    const { fixture, detail } = setup({ status: 'PUBLISHED' });
    expect(detail.canTailor()).toBe(false);
    expect(tailorButton(fixture)).toBeNull();
  });

  /** With the whole workbench refused there, the drawer goes with it. */
  it('is withheld on a series another desk is accountable for', () => {
    const { fixture, detail } = setup({ status: 'OPEN' });
    sessionDomain.set({ domainId: 'niord-firing-area' } as Domain);
    fixture.detectChanges();
    expect(detail.canTailor()).toBe(false);
    expect(tailorButton(fixture)).toBeNull();
  });

  /** Nothing is held while the drawer is shut — the mount goes up on the open. */
  it('mounts the drawer only while it is open', () => {
    const { fixture, detail } = setup({ status: 'OPEN' });
    expect(detail.overridesMounted()).toBe(false);
    expect(document.querySelector('.niord-drawer-panel')).toBeNull();

    detail.openOverrides();
    fixture.detectChanges();
    expect(detail.overridesMounted()).toBe(true);
    expect(document.querySelector('.niord-drawer-panel')).not.toBeNull();
    // A refusal from a previous attempt would otherwise be sitting inside it.
    expect(facade.clearError).toHaveBeenCalled();
  });

  it('opens nothing where the action is withheld', () => {
    const { fixture, detail } = setup({ status: 'PUBLISHED' });
    detail.openOverrides();
    fixture.detectChanges();
    expect(detail.overridesMounted()).toBe(false);
  });

  /** The save closes on the transaction, not on the click. */
  it('sends the patch through the one issue write, and closes when it lands', () => {
    const { detail } = setup({ status: 'OPEN' });
    detail.overridesOpen.set(true);
    detail.saveOverrides({ weekLabel: '36+37' });
    expect(facade.edit).toHaveBeenCalledWith({ weekLabel: '36+37' }, expect.any(Function));
    expect(detail.overridesOpen()).toBe(true);

    (facade.edit.mock.calls[0][1] as () => void)();
    expect(detail.overridesOpen()).toBe(false);
  });
});

describe('the marks on the issue screen', () => {
  it('says nothing about an issue that follows its series', () => {
    const { fixture, detail } = setup({});
    expect(detail.tailored()).toBe(false);
    expect(detail.tailoredFactCount()).toBe(0);
    expect(fixture.nativeElement.querySelector('[data-testid="issue-tailored-mark"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="issue-tailored-note"]')).toBeNull();
  });

  it('marks the header once something is tailored', () => {
    const { fixture, detail } = setup({
      week: 36,
      weekTo: 37,
      weekLabel: '36+37',
      descs: [
        {
          lang: 'da',
          name: 'Aktive P&T uge 36+37 - 2026',
          fileName: 'EfS-PT-Dobbelt.pdf',
        },
        { lang: 'en', name: 'Active P&T Week 36+37 - 2026' },
      ],
      reportParams: { overskrift: 'Aktive P&T uge 36+37' },
    } as unknown as PublicationIssue);

    expect(detail.tailored()).toBe(true);
    expect(
      fixture.nativeElement.querySelector('[data-testid="issue-tailored-mark"]'),
    ).not.toBeNull();
    const note = fixture.nativeElement.querySelector('[data-testid="issue-tailored-note"]');
    // The plain sentence carries the way back INTO the drawer.
    expect(note?.querySelector('[data-testid="issue-see-tailoring"]')).not.toBeNull();
  });

  /**
   * The header mark asks whether ANYTHING differs; the Document card's sentence
   * counts the rows above it. This issue differs in three ways — the Danish file
   * name, the numbering and one report parameter — and the card lists one of
   * them, so the sentence says one.
   */
  it('counts the facts the card states, not everything the issue tailors', () => {
    const { fixture, detail } = setup({
      week: 36,
      weekTo: 37,
      weekLabel: '36+37',
      descs: [
        {
          lang: 'da',
          name: 'Aktive P&T uge 36+37 - 2026',
          fileName: 'EfS-PT-Dobbelt.pdf',
        },
        { lang: 'en', name: 'Active P&T Week 36+37 - 2026' },
      ],
      reportParams: { overskrift: 'Aktive P&T uge 36+37' },
    } as unknown as PublicationIssue);

    expect(detail.documentFacts().map((fact) => fact.key)).toEqual(['param:overskrift']);
    expect(detail.tailoredFactCount()).toBe(1);
    const note = fixture.nativeElement.querySelector('[data-testid="issue-tailored-note"]');
    expect(note?.textContent).toContain('admin.publications.overrides.tailoredFactsOne');
  });

  /**
   * The Document card states the report parameters this edition answers
   * differently, and nothing else — an issue that answers them all the way its
   * series does has no facts to state.
   */
  it('states nothing where no report parameter was tailored', () => {
    const { fixture, detail } = setup({});
    expect(detail.documentFacts()).toEqual([]);
    expect(fixture.nativeElement.querySelector('[data-testid="issue-document-facts"]')).toBeNull();
  });

  /** One row per tailored parameter, named by the key it is stored under. */
  it('states one row per tailored report parameter, with the value this issue answers', () => {
    const { fixture, detail } = setup({
      reportParams: { overskrift: 'Aktive P&T uge 38', fodnote: 'Rettet' },
    } as unknown as PublicationIssue);

    expect(detail.documentFacts()).toEqual([
      {
        key: 'param:overskrift',
        labelKey: 'admin.publications.overrides.fact.param',
        param: 'overskrift',
        value: 'Aktive P&T uge 38',
      },
      {
        key: 'param:fodnote',
        labelKey: 'admin.publications.overrides.fact.param',
        param: 'fodnote',
        value: 'Rettet',
      },
    ]);
    expect(detail.tailoredFactCount()).toBe(2);

    const rows = fixture.nativeElement.querySelectorAll(
      '[data-testid="issue-document-facts"] > div',
    );
    expect(rows).toHaveLength(2);
    // Every row in the list is tailored, so every row carries the mark.
    expect(
      [...rows].every((row: Element) => row.querySelector('.status-badge.is-tailored') !== null),
    ).toBe(true);
  });

  /**
   * A backend that carries none of this round's fields answers "follows the
   * series" for every one of them, which is the only reading that does not mark
   * the whole estate as tailored on the day the frontend ships first.
   */
  it('marks nothing on a response that carries none of the new fields', () => {
    const { detail } = setup({
      descs: [
        { lang: 'da', name: 'Aktive P&T uge 38 - 2026' },
        { lang: 'en', name: 'Active P&T Week 38 - 2026' },
      ],
    });
    expect(detail.tailored()).toBe(false);
  });

  /** With no series there is nothing to compare against, and nothing is claimed. */
  it('claims nothing while the series is still loading', () => {
    const { fixture, detail } = setup({ weekLabel: '36+37' });
    expect(detail.tailored()).toBe(true);
    fixture.componentRef.setInput('series', undefined);
    fixture.detectChanges();
    expect(detail.tailored()).toBe(false);
  });
});
