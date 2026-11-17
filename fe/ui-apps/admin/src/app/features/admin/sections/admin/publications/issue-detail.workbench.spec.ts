import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { CopyLinkButton } from '../../../../../shared/components/copy-link-button/copy-link-button';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { MESSAGE_STATUSES } from '../../../../../shared/models/message-status.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  IssueAuditEntry,
  IssueMember,
  IssueOverride,
  IssuePreview,
  MessageSinceRelease,
  PreviewMiss,
  PublicationIssue,
  PublicationIssueDesc,
  PublicationSeries,
  PublishCheck,
  PublishResult,
  SourceIssue,
  SourceIssueRef,
} from '../../../services/api/publication-series-api.service';
import type { CitingMessage } from './publication-errors';
import { CHECK_CODES, CHECK_DETAIL_CODES, IssueDetail } from './issue-detail';
import {
  IssueDetailFacade,
  type IssueViewInstant,
  type PeriodProblem,
} from './issue-detail.facade';

const HOUR = 60 * 60 * 1000;
const PAST = Date.now() - 48 * HOUR;
const FUTURE = Date.now() + 48 * HOUR;

@Component({
  imports: [IssueDetail],
  template: `<app-issue-detail
    [publicId]="publicId()"
    [series]="series()"
    [previewDebounceMs]="debounceMs()"
    (openIssue)="opened.set($event)"
    (closed)="left.set(true)"
  />`,
})
class Host {
  /** Bound rather than fixed: the panel is REUSED when one issue hands over to another. */
  readonly publicId = signal('i-1');
  readonly series = signal<PublicationSeries | undefined>(undefined);
  /**
   * No wait by default, so a test asserting what a typed period asks for is not
   * asserting a timer. The one test that IS about the wait names its own.
   */
  readonly debounceMs = signal(0);
  readonly opened = signal<string | undefined>(undefined);
  readonly left = signal(false);
}

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '/img/flags/da.png' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '/img/flags/en.png' },
];

/**
 * The two member lists the screen splits between its sections.
 *
 * Hoisted out of the mock so the third — what the main table lists — is derived
 * here the way the facade derives it, rather than being a second list every
 * test has to remember to keep in step with the first.
 */
const members = signal<readonly IssueMember[]>([]);
const lateMembers = signal<readonly IssueMember[]>([]);
const viewInstant = signal<IssueViewInstant>('now');

/**
 * Whether the screen is looking at a compilation, and what it compiled.
 *
 * Hoisted beside the member lists for the same reason they are: the grouped
 * list is DERIVED from the flat one here exactly as the facade derives it, so a
 * case that sets the members gets the groups that go with them rather than a
 * second list it has to remember to keep in step.
 */
const compiled = signal(false);
const sources = signal<readonly SourceIssue[] | undefined>(undefined);

/**
 * The facade's own grouping, reproduced: the survey's published issues in survey
 * order, each holding the rows that name it, then any source the survey does not
 * hold, then the hand-added run.
 */
function groupsOf(rows: readonly IssueMember[], survey: readonly SourceIssue[] | undefined) {
  const groups = new Map<string, { source?: SourceIssueRef; members: IssueMember[] }>();
  for (const source of survey ?? []) {
    if (source.status !== 'PUBLISHED' || source.publicId === undefined) {
      continue;
    }
    groups.set(`source:${source.publicId}`, {
      source: { ...source, publicId: source.publicId },
      members: [],
    });
  }
  for (const member of rows) {
    const key = member.sourceIssue ? `source:${member.sourceIssue.publicId}` : 'manual';
    const group = groups.get(key);
    if (group) {
      group.members.push(member);
    } else {
      groups.set(key, { source: member.sourceIssue, members: [member] });
    }
  }
  const manual = groups.get('manual');
  groups.delete('manual');
  const ordered = [...groups].map(([key, group]) => ({ ...group, key }));
  return manual ? [...ordered, { ...manual, key: 'manual' }] : ordered;
}

/**
 * ONE facade mock, reused.
 *
 * `overrideComponent` caches the compiled definition, so a fresh mock per test
 * would leave the component bound to the first one.
 */
const facade = {
  issue: signal<PublicationIssue | undefined>(undefined),
  members,
  lateMembers,
  // The instant those rows are late against, as the answer named it. The
  // screen falls back to the issue’s own planned cut-off where none is named.
  lateAfter: signal<number | undefined>(undefined),
  tableMembers: computed(() => {
    const late = new Set(lateMembers().map((member) => member.messageUid));
    return members().filter((member) => !late.has(member.messageUid));
  }),
  switchAvailable: computed(() => lateMembers().length > 0),
  viewInstant,
  viewedAt: signal<number | undefined>(undefined),
  viewedFrom: signal<number | undefined>(undefined),
  // The period the screen is answering for while one is being typed into the
  // edit panel. The panel pushes it on every change, closed panel included --
  // an undefined push is what puts the screen back on the period on file.
  setPreviewPeriod: vi.fn(),
  // Why the typed period cannot be read, when it cannot: nothing is asked in
  // either state, so the panel is what has to say so.
  periodProblem: signal<PeriodProblem | undefined>(undefined),
  setViewInstant: vi.fn((view: IssueViewInstant) => viewInstant.set(view)),
  attachSeries: vi.fn(),
  compiled,
  sources,
  groupedMembers: computed(() => (compiled() ? groupsOf(members(), sources()) : [])),
  audit: signal<readonly IssueAuditEntry[]>([]),
  overrides: signal<readonly IssueOverride[]>([]),
  checklist: signal<{ rows: readonly PublishCheck[]; canPublish: boolean } | undefined>(undefined),
  loading: signal(false),
  error: signal(false),
  working: signal(false),
  // Which of the two slow writes is running. The screen points at the place the
  // work is happening — the release control, or the documents being replaced —
  // so a single busy flag is not enough for it to render.
  publishing: signal(false),
  amending: signal(false),
  lastActionError: signal<string | undefined>(undefined),
  lastActionMessage: signal<string | undefined>(undefined),
  curatable: signal(true),
  acknowledgeableWarnings: signal<readonly PublishCheck[]>([]),
  informationalWarnings: signal<readonly PublishCheck[]>([]),
  blockers: signal([]),
  unacknowledgedWarnings: signal<readonly string[]>([]),
  previewRows: signal<readonly IssuePreview[]>([]),
  previewWorking: signal(false),
  lastPreviewError: signal<unknown>(undefined),
  omissions: signal<readonly PreviewMiss[]>([]),
  omissionCount: signal(0),
  omissionsBounded: signal(false),
  // What a released issue went without: messages published into its period
  // after the release.
  publishedSinceRelease: signal<readonly MessageSinceRelease[]>([]),
  successorDescs: signal<readonly PublicationIssueDesc[]>([]),
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
  archiveFileUrl: vi.fn(() => '/rest/archive'),
  excludeMembers: vi.fn(),
  includeByMessageId: vi.fn(),
  clearOverride: vi.fn(),
  amend: vi.fn(),
  newEdition: vi.fn(),
  deleteIssue: vi.fn(),
  // What a refused removal named. Held apart from the sentence for the same
  // reason the unacknowledged warnings are: the ids ARE the answer.
  citingMessages: signal<readonly CitingMessage[]>([]),
  citingCount: signal(0),
  citationsBounded: signal(false),
  probeSuccessor: vi.fn(),
};

const sessionDomain = signal<Domain | undefined>(undefined);

/**
 * The clock every instant on the screen is printed through.
 *
 * A spy rather than a plain stub: each date renders as the same string here, so
 * the DOM alone cannot say WHICH instant a label was handed — and the whole
 * point of the late heading is that it is not always the one on file.
 */
const formatDateTime = vi.fn<(value: number) => string>(() => '02.09.2026 12.21');

const WEEKLY = {
  seriesId: 's-1',
  reportId: 'ntm',
  cadence: 'WEEKLY',
  descs: [{ lang: 'da', name: 'Ugentlig EfS' }],
} as unknown as PublicationSeries;

function setup(issue: Partial<PublicationIssue>, series: PublicationSeries | undefined = WEEKLY) {
  vi.clearAllMocks();
  sessionDomain.set(undefined);
  facade.issue.set({ publicId: 'i-1', status: 'OPEN', descs: [], ...issue } as PublicationIssue);
  facade.checklist.set(issue.status === 'OPEN' ? { rows: [], canPublish: true } : undefined);
  facade.curatable.set(issue.status !== 'PUBLISHED');
  facade.members.set([]);
  facade.overrides.set([]);
  // Every screen starts as of now with nothing late: the switch and the section
  // above the table are the exception, and a test that wants them says so.
  lateMembers.set([]);
  facade.lateAfter.set(undefined);
  viewInstant.set('now');
  // Every screen is an ordinary member list unless the case says otherwise: a
  // compilation changes the shape of the whole panel, so it is never the
  // default any other case is asserted against.
  compiled.set(series?.timeRelation === 'COMPILED_FROM_SOURCE');
  sources.set(undefined);
  facade.audit.set([]);
  facade.working.set(false);
  facade.publishing.set(false);
  facade.amending.set(false);
  facade.successorDescs.set([]);
  facade.publishedSinceRelease.set([]);
  facade.previewRows.set([]);
  facade.citingMessages.set([]);
  facade.citingCount.set(0);
  facade.citationsBounded.set(false);

  TestBed.configureTestingModule({
    providers: [
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('da'), modelLanguages: LANGUAGES },
      },
      {
        provide: LocaleFormatService,
        useValue: {
          formatDate: () => '02.09.2026',
          formatDateTime,
          // Read by the shared time picker, which renders as soon as a test
          // opens the edit panel.
          formatTime: () => '12.21',
          formatWeekdayDateTime: () => 'ons. 2. sep 12.00',
          uses12HourClock: () => false,
          sessionTimeZone: () => 'UTC',
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
        // Enough of the catalogue for the refusal a foreign series would draw,
        // and a zone on the desk that owns the annual: a source issue's cut-off
        // is read on the owner's clock, and a yearly roll-up closing at 31
        // December 23:59 lands in the wrong year on another desk's.
        useValue: {
          sessionDomain,
          resolveById: (domainId: string) =>
            domainId === 'niord-nm'
              ? { domainId, name: 'NtM', timeZone: 'Europe/Copenhagen' }
              : { domainId, name: domainId },
          editableDomains: signal<readonly Domain[]>([]),
          selectSessionDomain: vi.fn(),
        } as unknown as DomainFacade,
      },
      {
        provide: TicketedDownloadService,
        useValue: {
          openTicketed: vi.fn(),
          openPublic: vi.fn(),
        } as unknown as TicketedDownloadService,
      },
    ],
  }).overrideComponent(IssueDetail, {
    set: { providers: [{ provide: IssueDetailFacade, useValue: facade }, ConfirmService] },
  });

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.series.set(series);
  fixture.autoDetectChanges();
  return { fixture };
}

type Fixture = ReturnType<typeof setup>['fixture'];

const root = (fixture: Fixture) => fixture.nativeElement as HTMLElement;
const text = (fixture: Fixture) => root(fixture).textContent ?? '';

function button(fixture: Fixture, label: string): HTMLButtonElement | undefined {
  return [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
    b.textContent?.includes(label),
  );
}

/**
 * One header per level.
 *
 * The screen above renders the series; this one renders the issue, and the way
 * back names the parent rather than a direction — so a reader always knows which
 * level they are on and which one is above.
 */
describe('IssueDetail — the header', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('names the series on the way back, and the issue in the title', () => {
    const { fixture } = setup({ status: 'OPEN', descs: [{ lang: 'da', name: 'EfS uge 36' }] });

    const back = root(fixture).querySelector('button')!;
    expect(back.textContent).toContain('Ugentlig EfS');
    expect(root(fixture).querySelector('h2')!.textContent).toContain('EfS uge 36');
  });

  it('leaves the issue when the way back is taken', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    root(fixture).querySelector('button')!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.left()).toBe(true);
  });

  /** The lifecycle badge wears the issue tint, never the accent. */
  it('tints the badge blue while the issue is open', () => {
    const { fixture } = setup({ status: 'OPEN' });

    expect(root(fixture).querySelector('.status-badge')!.classList).toContain('is-issue-open');
  });

  it('tints the badge green once the issue is published', () => {
    const { fixture } = setup({ status: 'PUBLISHED' });

    expect(root(fixture).querySelector('.status-badge')!.classList).toContain('is-issue-published');
  });

  /** One muted line: which series, what this is, and how it goes out. */
  it('says the series, the kind and the release mode while the issue is open', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const shown = text(fixture);
    expect(shown).toContain('admin.publications.issue.kind');
    expect(shown).toContain('admin.publications.issue.releaseMode.MANUAL_GATE');
  });

  /** Once it has gone out, WHEN it did replaces HOW it was going to. */
  it('replaces the release mode with the release itself once published', () => {
    const { fixture } = setup({ status: 'PUBLISHED', publishedAt: PAST, publishedBy: 'R. Jensen' });

    const shown = text(fixture);
    expect(shown).toContain('admin.publications.issue.publishedLine');
    expect(shown).not.toContain('admin.publications.issue.publishedLineNoUser');
    expect(shown).not.toContain('admin.publications.issue.releaseMode.');
  });

  /**
   * A release nobody signed is not a release by a dash.
   *
   * An issue released by a job, and one carried in from the previous system,
   * have no person to name. Said through the sentence that has a slot for one,
   * they read "published by —" — and the dash stands exactly where a name would,
   * so a reader takes it for a name that went missing.
   */
  it('leaves the person out of the release line where none was recorded', () => {
    const { fixture } = setup({ status: 'PUBLISHED', publishedAt: PAST });

    expect(text(fixture)).toContain('admin.publications.issue.publishedLineNoUser');
  });

  it('offers the edit pencil on an open issue and no withdrawal', () => {
    const { fixture } = setup({ status: 'OPEN' });

    expect(button(fixture, 'admin.publications.issue.edit')).toBeDefined();
    expect(button(fixture, 'admin.publications.issue.retire')).toBeUndefined();
  });

  it('offers the withdrawal on a published issue and no edit', () => {
    const { fixture } = setup({ status: 'PUBLISHED' });

    expect(button(fixture, 'admin.publications.issue.retire')).toBeDefined();
    expect(button(fixture, 'admin.publications.issue.edit')).toBeUndefined();
  });

  /**
   * The issue's own identifier is the hover text, not a line of its own.
   *
   * Nobody reads a public id off a screen; the one person who needs it is
   * quoting it into a support mail, and it travels with the address they are
   * copying.
   */
  it('carries the public id on the copy-link control rather than under the title', () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      seriesId: 's-1',
      descs: [{ lang: 'da', name: 'EfS uge 36' }],
    });

    expect(root(fixture).querySelector('[title="i-1"]')).not.toBeNull();
    expect(root(fixture).querySelector('header')!.textContent).not.toContain('i-1');
  });

  /**
   * The address is offered once the issue HAS one.
   *
   * Nothing cites an issue that has not gone out, and a copy control on an open
   * one hands over a link to a page the recipient cannot reach. Labelled, not a
   * glyph: an unnamed icon among named buttons reads as a different kind of
   * control rather than as one more thing a reader can do here.
   */
  it('offers a labelled copy-link on a published issue', () => {
    const { fixture } = setup({ status: 'PUBLISHED', seriesId: 's-1' });

    const copy = fixture.debugElement.query(By.directive(CopyLinkButton));
    expect((copy.componentInstance as CopyLinkButton).variant()).toBe('button');
    expect(copy.nativeElement.textContent).toContain('admin.publications.issue.copyLinkLabel');
    expect(copy.nativeElement.querySelector('button')!.className).toContain('btn-secondary');
  });

  it('offers no copy-link while the issue has not gone out', () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' });

    expect(fixture.debugElement.queryAll(By.directive(CopyLinkButton))).toEqual([]);
  });

  /**
   * A series that opens its own next issue is never offered "supersede".
   *
   * The menu is still there — an open issue can be removed, since nothing has
   * gone out under it — but neither of the two release actions is in it.
   */
  it('offers no amend and no successor on an open issue of a cadenced series', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    root(fixture)
      .querySelector<HTMLButtonElement>('[aria-label="admin.publications.actionsAriaLabel"]')!
      .click();
    await fixture.whenStable();

    const labels = [...document.querySelectorAll('.cdk-overlay-container .menu-item')].map(
      (el) => el.textContent ?? '',
    );
    expect(labels.some((l) => l.includes('admin.publications.amend.action'))).toBe(false);
    expect(labels.some((l) => l.includes('admin.publications.newEdition.action'))).toBe(false);
  });
});

/**
 * The workbench: the members take the width, the rail holds 340px beside them.
 *
 * Below the wide breakpoint the rail comes FIRST — the verdict is what a reader
 * opens this screen for, and a member list they must scroll past to reach it
 * buries the answer under the evidence.
 */
describe('IssueDetail — the layout', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('lays the members out beside a 340px rail that stacks first when narrow', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const grid = root(fixture).querySelector('.grid')!;
    expect(grid.className).toContain('xl:grid-cols-[minmax(0,1fr)_340px]');
    expect(grid.className).toContain('items-start');

    const rail = root(fixture).querySelector('aside')!;
    expect(rail.className).toContain('order-1');
    expect(rail.className).toContain('xl:order-2');
    expect(rail.className).toContain('xl:w-[340px]');
    expect(rail.className).toContain('xl:sticky');

    const left = grid.firstElementChild as HTMLElement;
    expect(left.className).toContain('order-2');
    expect(left.className).toContain('xl:order-1');
  });
});

/**
 * A date after now that nobody has stamped is a projection.
 *
 * Rendered plainly it is indistinguishable from one that was recorded, and the
 * cut-off in particular is stamped by the act of publishing rather than by a
 * clock.
 */
describe('IssueDetail — the facts strip', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const publicWindowMarkers = (fixture: Fixture) =>
    root(fixture).querySelector('[data-testid="issue-facts"]')!.querySelectorAll('.expected-date');

  /** Label/value pairs are a description list, so each label names its value. */
  it('states the facts as a description list', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const facts = root(fixture).querySelector('[data-testid="issue-facts"]')!;
    expect(facts.tagName).toBe('DL');
    expect(facts.querySelector('div')!.firstElementChild!.tagName).toBe('DT');
  });

  it('marks the period end, the cut-off and the public window while the issue is open', () => {
    const { fixture } = setup({
      status: 'OPEN',
      intervalFrom: PAST,
      intervalTo: FUTURE,
      publicFrom: FUTURE,
      publicTo: FUTURE + 7 * 24 * HOUR,
    });

    const facts = root(fixture).querySelector('[data-testid="issue-facts"]')!;
    // The period end, the cut-off, and the public window as ONE range.
    expect(facts.querySelectorAll('.expected-date').length).toBe(3);
    expect(facts.textContent).toContain('admin.publications.issue.cutoffStampedOnPublish');
  });

  /**
   * A window nobody has opened yet is expected whole.
   *
   * One clock over the range rather than one per end: two markers side by side
   * read as two projections that happen to sit next to each other rather than as
   * the single period they are.
   */
  it('marks an unopened public window as one range', () => {
    const { fixture } = setup({
      status: 'OPEN',
      publicFrom: FUTURE,
      publicTo: FUTURE + 7 * 24 * HOUR,
    });

    // One marker for the pair, and it carries both ends.
    const markers = publicWindowMarkers(fixture);
    expect(markers.length).toBe(1);
    expect(markers[0].textContent).toContain('–');
  });

  /** Once the window has opened its start is a fact, and only the end is a projection. */
  it('marks an open public window at its end alone', () => {
    const { fixture } = setup({ status: 'PUBLISHED', publicFrom: PAST, publicTo: FUTURE });

    const markers = publicWindowMarkers(fixture);
    expect(markers.length).toBe(1);
    expect(markers[0].textContent).not.toContain('–');
  });

  /**
   * The provenance is a sentence ABOUT the period, not part of it.
   *
   * Standing between the two dates it broke a two-date range across three lines
   * and read as a value of its own; below the period it is the same sentence the
   * cut-off beside it already carries. An end that has not happened contributes
   * none, because a date that does not exist has no provenance.
   */
  it('says where a reconstructed period came from under the period, not inside it', () => {
    const { fixture } = setup({
      status: 'OPEN',
      intervalFrom: PAST,
      intervalTo: FUTURE,
      intervalFromSource: 'RECOVERED',
      intervalToSource: 'NOMINAL',
    });

    const facts = root(fixture).querySelector('[data-testid="issue-facts"]')!;
    const [, value, source] = facts.querySelector('div')!.querySelectorAll('dt, dd');
    // The value is the period and nothing else.
    expect(value.textContent).not.toContain('admin.publications.boundSource.');
    expect(source.textContent).toContain('admin.publications.boundSource.RECOVERED');
    // Said once — the expected end has not happened, so it has no provenance.
    expect(facts.textContent).not.toContain('admin.publications.boundSource.NOMINAL');
  });

  /** Both ends witnessed: nothing to caveat, and a caveat on every date says nothing. */
  it('says nothing about a period whose bounds were both stamped', () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      intervalFrom: PAST,
      intervalTo: PAST + HOUR,
      intervalFromSource: 'STAMPED',
      intervalToSource: 'STAMPED',
    });

    expect(text(fixture)).not.toContain('admin.publications.boundSource.');
  });

  /** A stamped date never carries the marker, and says where it came from instead. */
  it('renders a published issue’s cut-off plainly, with its provenance in words', () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      intervalFrom: PAST,
      intervalTo: PAST + HOUR,
      cutoffStampedAt: PAST + HOUR,
      cutoffSource: 'RECOVERED_FROM_NEXT_TAG',
    });

    const facts = root(fixture).querySelector('[data-testid="issue-facts"]')!;
    expect(facts.textContent).toContain('admin.publications.boundSource.RECOVERED_FROM_NEXT_TAG');
    expect(facts.textContent).not.toContain('admin.publications.issue.cutoffStampedOnPublish');
  });

  /** An ordinary release stamp says nothing extra: it is the expected case. */
  it('says nothing about the provenance of a stamp taken at the release', () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      cutoffStampedAt: PAST,
      cutoffSource: 'STAMPED_AT_PUBLISH',
    });

    expect(text(fixture)).not.toContain('admin.publications.boundSource.');
  });

  /** What the issue selects, one criterion at a time. */
  it('renders one chip per criterion', async () => {
    const { fixture } = setup({
      status: 'OPEN',
      seriesCriteria: {
        criteria: [{ kind: 'messageSeries', operator: 'IN', values: ['dma-nm', 'dma-nw'] }],
      },
    });
    await fixture.whenStable();

    const chips = [...root(fixture).querySelectorAll('[data-testid="issue-criteria"] .chip')];
    expect(chips).toHaveLength(1);
    // One string rather than three interpolations: written across lines they are
    // joined with the whitespace between them, which put a space in front of the
    // colon and read as a label whose value had gone missing.
    expect(chips[0].textContent).toBe(
      'admin.publications.criteria.kind.messageSeries: dma-nm, dma-nw',
    );
  });

  /** An excluding criterion says so between the kind and the colon. */
  it('marks a negated criterion without breaking the label apart', async () => {
    const { fixture } = setup({
      status: 'OPEN',
      seriesCriteria: {
        criteria: [{ kind: 'area', operator: 'NOT_IN', values: ['dk-baltic'] }],
      },
    });
    await fixture.whenStable();

    const chip = root(fixture).querySelector('[data-testid="issue-criteria"] .chip')!;
    expect(chip.textContent).toBe('admin.publications.criteria.kind.area ≠: dk-baltic');
  });

  /** The pencil opens the existing editor straight onto the criteria. */
  it('opens the criteria editor from the facts strip', async () => {
    const { fixture } = setup({
      status: 'OPEN',
      seriesCriteria: { criteria: [{ kind: 'domain', operator: 'IN', values: ['niord-nm'] }] },
    });

    expect(root(fixture).querySelector('app-series-criteria-editor')).toBeNull();

    root(fixture)
      .querySelector<HTMLButtonElement>('[aria-label="admin.publications.issue.editCriteria"]')!
      .click();
    await fixture.whenStable();

    expect(root(fixture).querySelector('app-series-criteria-editor')).not.toBeNull();
  });
});

/**
 * The member list, with a why-line on every row.
 *
 * The reason column never disappears — quietly for the rows the query chose and
 * at full weight for the ones somebody decided.
 */
describe('IssueDetail — the member list', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const MEMBERS: readonly IssueMember[] = [
    {
      messageUid: 'uid-a',
      frozenShortId: 'NM-810-26',
      frozenType: 'PRELIMINARY_NOTICE',
      frozenStatus: 'PUBLISHED',
      source: 'CRITERIA',
      reasonCode: 'IN_INTERVAL',
    },
    {
      messageUid: 'uid-b',
      frozenShortId: 'NM-811-26',
      frozenType: 'TEMPORARY_NOTICE',
      frozenStatus: 'PUBLISHED',
      source: 'OVERRIDE_INCLUDE',
      reasonCode: 'MANUAL_INCLUDE',
      curation: { author: 'rasmus', at: PAST, reason: 'Requested by the author' },
    },
  ];

  /** The message cell, past the selection box every curatable row carries. */
  const messageCell = (row: Element) => row.querySelectorAll('td')[1];

  it('links the short id to the editor and prints the type suffix beside it', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set(MEMBERS);
    await fixture.whenStable();

    const first = root(fixture).querySelector('tbody tr')!;
    const link = first.querySelector('a')!;
    expect(link.textContent).toContain('NM-810-26');
    expect(link.getAttribute('href')).toBe('/editor/edit/uid-a');
    expect(first.textContent).toContain('(P)');
    // Angular drops the whitespace-only node between the link and the suffix, so
    // the space that keeps "NM-810-26 (P)" from reading as one token is a margin.
    const suffix = [...first.querySelectorAll('span')].find((s) => s.textContent === '(P)')!;
    expect(suffix.className).toContain('ml-1');
  });

  /** No suffix for the types the message lists print without one. */
  it('prints no suffix for a notice type that carries none', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([{ ...MEMBERS[0], frozenType: 'PERMANENT_NOTICE' }]);
    await fixture.whenStable();

    const first = root(fixture).querySelector('tbody tr')!;
    expect(first.textContent).toContain('NM-810-26');
    expect(first.textContent).not.toContain('(');
  });

  /**
   * The row says what the message is CALLED, under the id that addresses it.
   *
   * An id names a row only to somebody who already knows which message it is;
   * everybody else reads the list by its titles. Its own line rather than an
   * addition to the first, so the id and the type stay scannable down the column.
   */
  it('names each message under its short id', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([{ ...MEMBERS[0], title: 'Fyrslukning, Nordsøen' }]);
    await fixture.whenStable();

    const cell = messageCell(root(fixture).querySelector('tbody tr')!);
    const title = [...cell.querySelectorAll('span')].find(
      (s) => s.textContent?.trim() === 'Fyrslukning, Nordsøen',
    );
    expect(title).toBeTruthy();
    expect(title!.className).toContain('block');
    expect(title!.className).toContain('text-fg-muted');
  });

  /**
   * A member whose message is gone carries no title, and the row says no more.
   *
   * The id and the drift marks are the record; an empty line under them would
   * read as a message with no name rather than as one that is no longer there.
   */
  it('adds nothing under the id when the message has no title', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([MEMBERS[0]]);
    await fixture.whenStable();

    const cell = messageCell(root(fixture).querySelector('tbody tr')!);
    // The title line specifically — the cell still carries the narrow-screen
    // publication date, which is a different fact about the row.
    expect(cell.querySelectorAll('.truncate.text-fg-muted').length).toBe(0);
  });

  /**
   * A member nobody has numbered yet is named by its TITLE, never by its uid.
   *
   * A uuid in the message column names nothing to the person reading the list,
   * and a dash names even less. The title takes the id's place, the note says
   * why there is no number, and the uid stays where it belongs — in the link.
   */
  it('names an unnumbered member by its title and marks it as not numbered', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([
      { ...MEMBERS[0], frozenShortId: undefined, title: 'Fyrslukning, Nordsøen' },
    ]);
    await fixture.whenStable();

    const cell = messageCell(root(fixture).querySelector('tbody tr')!);
    expect(cell.querySelector('a')!.textContent).toContain('Fyrslukning, Nordsøen');
    expect(cell.textContent).toContain('admin.publications.messageRef.notNumbered');
    expect(cell.textContent).not.toContain('uid-a');
    expect(cell.querySelector('a')!.getAttribute('href')).toBe('/editor/edit/uid-a');
    // And the title is not ALSO repeated as the second line under itself.
    expect(cell.querySelectorAll('.truncate.text-fg-muted').length).toBe(0);
  });

  /**
   * Once published, the band says the list stopped moving.
   *
   * The reasons still belong to the rows — they say why each message is in the
   * document — but the fact that answers "is this still being resolved" is the
   * freeze, and it is a fact about the list rather than about any row.
   */
  it('says the list was frozen at the release on a published issue', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' });
    facade.members.set([MEMBERS[0]]);
    await fixture.whenStable();

    const band = root(fixture).querySelector('.admin-panel-header')!.textContent ?? '';
    expect(band).toContain('admin.publications.issue.frozenAtPublish');
    expect(band).not.toContain('admin.publications.reason.');

    // The column stays, and says the reason quietly on every row. A published
    // table has no selection box, so the reason is the fourth cell: message,
    // status, published, reason.
    const cells = [...root(fixture).querySelectorAll('tbody tr')].map(
      (row) => row.querySelectorAll('td')[3],
    );
    expect(cells[0].textContent).toContain('admin.publications.reason.IN_INTERVAL');
    expect(cells[0].className).toContain('text-fg-muted');
  });

  /** One reason for every row is said once, over the table, not on each of them. */
  it('names the one reason every row shares in the band', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([MEMBERS[0]]);
    await fixture.whenStable();

    expect(root(fixture).querySelector('.admin-panel-header')!.textContent).toContain(
      'admin.publications.reason.IN_INTERVAL',
    );
  });

  it('says nothing uniform when the rows disagree', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set(MEMBERS);
    await fixture.whenStable();

    const band = root(fixture).querySelector('.admin-panel-header')!.textContent ?? '';
    expect(band).not.toContain('admin.publications.reason.');
  });

  /** The status badge: the only one a member row carries, whatever the issue's state. */
  const statusBadge = (row: Element) => row.querySelector('.status-badge')!;

  /**
   * One table shape, always.
   *
   * The column used to appear only where the rows disagreed, which made the
   * table change shape from one issue to the next and read as a fault. It is
   * there on every list now, open or published, and shows the status the
   * message has today.
   */
  it('shows the status column on every list, rows agreeing or not', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set(MEMBERS);
    await fixture.whenStable();
    expect(text(fixture)).toContain('admin.publications.column.status');
    expect(statusBadge(root(fixture).querySelector('tbody tr')!).textContent).toContain(
      'admin.publications.memberStatus.PUBLISHED',
    );
  });

  /**
   * On a published issue the badge is the LIVE status, and a row that changed
   * says so underneath — and says that the message is still in the issue.
   *
   * The frozen status is "published" for every member by construction, so a
   * badge showing it would answer a question nobody asks. What the reader wants
   * is what the message is now and, where that differs, that it happened after
   * the cut-off and so does not take the message out of the issue.
   */
  it('shows the live status on a published issue, with a note on the rows that changed', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' });
    facade.members.set([
      {
        ...MEMBERS[0],
        current: { exists: true, publiclyVisible: true, status: 'PUBLISHED' },
      },
      {
        ...MEMBERS[1],
        drift: ['status'],
        current: { exists: true, publiclyVisible: true, status: 'EXPIRED' },
      },
    ]);
    await fixture.whenStable();

    const rows = [...root(fixture).querySelectorAll('tbody tr')];
    expect(statusBadge(rows[0]).textContent).toContain('admin.publications.memberStatus.PUBLISHED');
    expect(rows[0].querySelector('[data-testid="member-change-note"]')).toBeNull();

    expect(statusBadge(rows[1]).textContent).toContain('admin.publications.memberStatus.EXPIRED');
    expect(statusBadge(rows[1]).classList).toContain('is-expired');
    // An icon whose tooltip carries the finding and the reason; no text in the cell.
    const note = rows[1].querySelector('[data-testid="member-change-note"]')!;
    expect(note.getAttribute('title')).toContain('admin.publications.change.status');
    expect(note.getAttribute('title')).toContain('admin.publications.change.why');
    expect(note.textContent?.trim()).toBe('');
  });

  /** A message that is gone reads as deleted, not as an empty badge. */
  it('reads a vanished message as deleted', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' });
    facade.members.set([
      { ...MEMBERS[0], drift: ['exists'], current: { exists: false, publiclyVisible: false } },
    ]);
    await fixture.whenStable();

    const badge = statusBadge(root(fixture).querySelector('tbody tr')!);
    expect(badge.textContent).toContain('admin.publications.memberStatus.DELETED');
    expect(badge.classList).toContain('is-deleted');
  });

  /**
   * A member's frozen status is a MESSAGE status, so it wears the message
   * palette every other list colours a message with. Every one of the six is
   * covered: a status left in the badge's own grey says nothing about which
   * status it is.
   */
  it('tints the status badge with the message palette, whichever status it is', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    for (const status of MESSAGE_STATUSES) {
      facade.members.set([{ ...MEMBERS[0], frozenStatus: status }]);
      await fixture.whenStable();

      const row = root(fixture).querySelector('tbody tr')!;
      expect(statusBadge(row).classList).toContain(`is-${status.toLowerCase()}`);
    }
  });

  /** A value outside the vocabulary gets no tint rather than a guessed one. */
  it('leaves an unrecognised status in the badge’s own neutral', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([{ ...MEMBERS[0], frozenStatus: 'SOMETHING_ELSE' }]);
    await fixture.whenStable();

    const badge = statusBadge(root(fixture).querySelector('tbody tr')!);
    expect(badge.className).toBe('status-badge');
    expect(badge.textContent).toContain('admin.publications.memberStatus.UNKNOWN');
  });

  /** Quiet for the query's own rows, full weight for the ones somebody decided. */
  it('says an automatic reason quietly and a manual one at full weight', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set(MEMBERS);
    await fixture.whenStable();

    const rows = [...root(fixture).querySelectorAll('tbody tr')];
    // Selection box, message, status, published, reason.
    const reasonCell = (row: Element) => row.querySelectorAll('td')[4];
    expect(reasonCell(rows[0]).className).toContain('text-fg-muted');
    expect(reasonCell(rows[1]).className).toContain('font-medium');
    expect(reasonCell(rows[1]).textContent).toContain('admin.publications.curation.addedBy');
  });

  /** A standing exclusion sits in the same table, struck through, with a way back. */
  it('renders a standing exclusion in the member table with a way to include it again', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set(MEMBERS);
    facade.overrides.set([
      {
        messageUid: 'uid-z',
        messageId: 'NM-815-26',
        title: 'Buoy off station',
        kind: 'EXCLUDE',
        author: 'rasmus',
        reason: 'Withdrawn',
      },
    ]);
    await fixture.whenStable();

    const row = root(fixture).querySelector('[data-testid="issue-exclusion"]')!;
    expect(row.querySelector('.line-through')!.textContent).toContain('NM-815-26');
    expect(row.textContent).not.toContain('uid-z');
    expect(row.textContent).toContain('admin.publications.curation.excludedBy');
    expect(row.textContent).toContain('admin.publications.curation.reinclude');
  });

  /**
   * An exclusion whose message has no number yet is named by its title.
   *
   * The row a curator most needs to recognise, and the one the old shape said
   * least about: a uuid struck through tells nobody which notice was taken out.
   */
  it('names an unnumbered exclusion by its title, never by the uid', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set(MEMBERS);
    facade.overrides.set([
      { messageUid: 'uid-z', title: 'Buoy off station', kind: 'EXCLUDE', author: 'rasmus' },
    ]);
    await fixture.whenStable();

    const row = root(fixture).querySelector('[data-testid="issue-exclusion"]')!;
    expect(row.querySelector('.line-through')!.textContent).toContain('Buoy off station');
    expect(row.textContent).toContain('admin.publications.messageRef.notNumbered');
    expect(row.textContent).not.toContain('uid-z');
    expect(row.querySelector('a')!.getAttribute('href')).toBe('/editor/edit/uid-z');
  });
});

/**
 * A COMPILATION: a member list that is other issues' member lists, end to end.
 *
 * The screen's whole shape turns on it. There are no criteria to show, no
 * candidates a query left out and no second instant to read at — every one of
 * those panels answers a question that was never asked of a compilation — and
 * the list itself is a list of lists, headed by the issue each run was printed
 * by. What replaces the left-out panel is the sources card: on a roll-up of a
 * whole year, "which of the weeks are actually in here" is the question, and a
 * member list can only show what it contains.
 */
describe('IssueDetail — a compiled issue', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const ANNUAL = {
    seriesId: 'accumulated-yearly-ntm',
    reportId: 'nm-accumulated',
    cadence: 'YEARLY',
    contentMode: 'GENERATED_FROM_QUERY',
    timeRelation: 'COMPILED_FROM_SOURCE',
    sourceSeriesId: 'weekly-ntm',
    domainId: 'niord-nm',
    descs: [{ lang: 'da', name: 'Akkumuleret Årlig EfS' }],
  } as unknown as PublicationSeries;

  const WEEK_12: SourceIssueRef = {
    publicId: 'w-12',
    seriesId: 'weekly-ntm',
    week: 12,
    year: 2026,
    // A content period: the week its notices were published in.
    intervalFrom: PAST - 7 * 24 * HOUR,
    cutoff: PAST,
    name: 'EfS uge 12',
  };

  const WEEK_13: SourceIssueRef = {
    publicId: 'w-13',
    seriesId: 'weekly-ntm',
    week: 13,
    year: 2026,
    cutoff: PAST,
    name: 'EfS uge 13',
  };

  /** Two weeks and one message somebody added by hand — the three kinds of run. */
  const COMPILED: readonly IssueMember[] = [
    {
      messageUid: 'uid-a',
      frozenShortId: 'NM-810-26',
      frozenStatus: 'PUBLISHED',
      source: 'COMPILED',
      reasonCode: 'FROM_SOURCE_ISSUE',
      sourceIssue: WEEK_12,
    },
    {
      messageUid: 'uid-b',
      frozenShortId: 'NM-811-26',
      frozenStatus: 'PUBLISHED',
      source: 'COMPILED',
      reasonCode: 'FROM_SOURCE_ISSUE',
      sourceIssue: WEEK_12,
    },
    {
      messageUid: 'uid-c',
      frozenShortId: 'NM-812-26',
      frozenStatus: 'PUBLISHED',
      source: 'COMPILED',
      reasonCode: 'FROM_SOURCE_ISSUE',
      sourceIssue: WEEK_13,
    },
    {
      messageUid: 'uid-h',
      frozenShortId: 'NM-900-26',
      frozenStatus: 'PUBLISHED',
      source: 'OVERRIDE_INCLUDE',
      reasonCode: 'MANUAL_INCLUDE',
    },
  ];

  /**
   * The one read this screen takes for itself: what the source series is called.
   *
   * Answered rather than ignored, because an unanswered request also leaves the
   * fixture unstable — and because the band is a sentence about the list under
   * it, so the name is part of what these cases are about.
   */
  function nameSourceSeries(fixture: Fixture, name = 'Ugentlig EfS'): void {
    fixture.detectChanges();
    for (const req of TestBed.inject(HttpTestingController).match((r) =>
      r.url.includes('/publication-series/series/'),
    )) {
      req.flush({ seriesId: 'weekly-ntm', descs: [{ lang: 'da', name }] });
    }
  }

  async function compilation(issue: Partial<PublicationIssue> = {}) {
    const { fixture } = setup({ status: 'OPEN', intervalTo: PAST, ...issue }, ANNUAL);
    // Read from the desk that owns the annual, so the screen is writable and the
    // source cut-offs are read on that desk's clock — which is the whole point
    // of a yearly roll-up closing at the turn of the year.
    sessionDomain.set({ domainId: 'niord-nm', name: 'NtM' } as Domain);
    facade.members.set(COMPILED);
    nameSourceSeries(fixture);
    await fixture.whenStable();
    return fixture;
  }

  const body = (fixture: Fixture) =>
    root(fixture).querySelector('[data-testid="issue-members"] tbody')!;

  const headings = (fixture: Fixture) => [
    ...root(fixture).querySelectorAll('[data-testid="issue-source-group"]'),
  ];

  /** The chevron on each heading, in the order the runs are drawn. */
  const toggles = (fixture: Fixture) => [
    ...root(fixture).querySelectorAll<HTMLButtonElement>(
      '[data-testid="issue-source-group-toggle"]',
    ),
  ];

  /** Every run open, for the cases that are about the rows rather than the folding. */
  async function expandAll(fixture: Fixture): Promise<void> {
    for (const toggle of toggles(fixture)) {
      toggle.click();
    }
    await fixture.whenStable();
  }

  /**
   * One heading per source issue, its rows beneath it in frozen order, and the
   * hand-added rows last — which is what "stapled together" means on screen.
   */
  it('draws the list as one run per source issue, with the hand-added rows last', async () => {
    const fixture = await compilation();
    await expandAll(fixture);

    const shape = [...body(fixture).children].map((row) =>
      row.getAttribute('data-testid') === 'issue-source-group' ? 'heading' : 'member',
    );
    expect(shape).toEqual([
      'heading',
      'member',
      'member',
      'heading',
      'member',
      'heading',
      'member',
    ]);

    // The rows themselves, in the order the union took them. The heading links
    // live in a `th`, so the message links are the ones in the cells.
    const named = [...body(fixture).querySelectorAll('td a')].map((a) => a.textContent?.trim());
    expect(named).toEqual(['NM-810-26', 'NM-811-26', 'NM-812-26', 'NM-900-26']);
  });

  /**
   * A heading names the issue that printed the run, and opens it.
   *
   * The whole point of the grouping is that the run can be checked against what
   * actually went out that week, which is one click away rather than a search.
   */
  it('heads each run with the source issue, its week, its content period and its count', async () => {
    const fixture = await compilation();

    const first = headings(fixture)[0];
    expect(first.textContent).toContain('EfS uge 12');
    expect(first.querySelector('a')!.getAttribute('href')).toBe(
      '/admin/publications/weekly-ntm?issue=w-12',
    );
    expect(first.textContent).toContain('admin.publications.issue.sourceWeek');
    // Dated by the stretch its notices were published in, not by its cut-off.
    expect(first.textContent).toContain('admin.publications.issue.sourcePeriod');
    expect(first.textContent).not.toContain('admin.publications.issue.sourceCutoff');
    expect(first.textContent).toContain('admin.publications.issue.memberCount');

    // A source with no period start — an in-force edition — keeps its cut-off.
    const second = headings(fixture)[1];
    expect(second.textContent).toContain('admin.publications.issue.sourceCutoff');
    expect(second.textContent).not.toContain('admin.publications.issue.sourcePeriod');

    // The hand-added run names no issue, because no issue printed it.
    const last = headings(fixture)[2];
    expect(last.textContent).toContain('admin.publications.issue.addedByHand');
    expect(last.querySelector('a')).toBeNull();
  });

  /**
   * The list opens as its headings and nothing else.
   *
   * A year compiles fifty-one weeks, and every one of their rows laid out at
   * once is a list whose own headings are off the screen — which is the list
   * this grouping exists to make readable. The count on each heading is what
   * makes folding safe: a reader sees which weeks are in the issue, and how much
   * each one brought, without opening anything.
   */
  it('opens with every run folded, each heading still saying how many rows it holds', async () => {
    const fixture = await compilation();

    expect(headings(fixture).length).toBe(3);
    // Headings only: no member row is drawn under any of them.
    expect([...body(fixture).children].every((row) => row.querySelector('th') !== null)).toBe(true);
    expect(body(fixture).querySelectorAll('td').length).toBe(0);

    for (const toggle of toggles(fixture)) {
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(toggle.getAttribute('aria-label')).toContain(
        'admin.publications.issue.sourceGroupToggle',
      );
    }
    // What is folded away is still counted, run by run.
    expect(headings(fixture)[0].textContent).toContain('admin.publications.issue.memberCount');
  });

  /**
   * One run opens at a time, and folds again — the rest stay as they were.
   *
   * The rows arrive in the order the union took them, because opening a run is a
   * way of drawing the list and never a second list built for the occasion.
   */
  it('unfolds one run onto its own rows in frozen order, and folds it again', async () => {
    const fixture = await compilation();

    toggles(fixture)[0].click();
    await fixture.whenStable();

    expect(toggles(fixture)[0].getAttribute('aria-expanded')).toBe('true');
    // The other two runs are untouched by their neighbour opening.
    expect(toggles(fixture)[1].getAttribute('aria-expanded')).toBe('false');
    expect(toggles(fixture)[2].getAttribute('aria-expanded')).toBe('false');

    const shape = [...body(fixture).children].map((row) =>
      row.getAttribute('data-testid') === 'issue-source-group' ? 'heading' : 'member',
    );
    expect(shape).toEqual(['heading', 'member', 'member', 'heading', 'heading']);
    expect([...body(fixture).querySelectorAll('td a')].map((a) => a.textContent?.trim())).toEqual([
      'NM-810-26',
      'NM-811-26',
    ]);

    toggles(fixture)[0].click();
    await fixture.whenStable();

    expect(toggles(fixture)[0].getAttribute('aria-expanded')).toBe('false');
    expect(body(fixture).querySelectorAll('td').length).toBe(0);
    expect(headings(fixture).length).toBe(3);
  });

  /**
   * Folding hides rows; it does not take them out of the issue.
   *
   * Select-all is taken from the flat member list and always was, so a curator
   * who ticks the box with everything folded has every member — including the
   * ones no run is showing — and the rows say so as soon as a run is opened.
   */
  it('selects every member with the runs folded, and the opened rows show it', async () => {
    const fixture = await compilation();

    const selectAll = root(fixture).querySelector<HTMLInputElement>(
      '[data-testid="issue-members"] thead input.checkbox',
    )!;
    selectAll.click();
    await fixture.whenStable();

    expect(selectAll.checked).toBe(true);
    // The bulk control counts the whole flat list, folded or not.
    expect(button(fixture, 'admin.publications.curation.excludeSelected')).toBeDefined();

    await expandAll(fixture);
    const boxes = [...body(fixture).querySelectorAll<HTMLInputElement>('td input.checkbox')];
    expect(boxes.length).toBe(4);
    expect(boxes.every((box) => box.checked)).toBe(true);
  });

  /** The hand-added run folds like every other one: it is a run, not a footer. */
  it('folds the hand-added run too', async () => {
    const fixture = await compilation();

    const manual = toggles(fixture)[2];
    expect(manual.getAttribute('aria-expanded')).toBe('false');

    manual.click();
    await fixture.whenStable();

    expect(manual.getAttribute('aria-expanded')).toBe('true');
    expect([...body(fixture).querySelectorAll('td a')].map((a) => a.textContent?.trim())).toEqual([
      'NM-900-26',
    ]);
  });

  /**
   * The band says where the list came from, not what every row shares.
   *
   * "Its source issue printed it" is true of every row and is already said by
   * the heading above each run; what a reader wants over the whole table is how
   * many issues went into it, and of what.
   */
  it('bands the list with the number of source issues and the series they came from', async () => {
    const fixture = await compilation();
    sources.set([
      { publicId: 'w-12', status: 'PUBLISHED', memberCount: 2 },
      { publicId: 'w-13', status: 'PUBLISHED', memberCount: 1 },
    ]);
    await fixture.whenStable();

    const band = root(fixture).querySelector('[data-testid="issue-compiled-band"]')!;
    expect(band.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      '· admin.publications.issue.compiledFrom',
    );
    expect(root(fixture).querySelector('.admin-panel-header')!.textContent).not.toContain(
      'admin.publications.reason.',
    );
  });

  /**
   * And it names the series the way a reader knows it, not the way the record
   * does.
   *
   * `weekly-ntm` is an id: it is what a link is built from and what the frozen
   * header holds, and it is not what the publication is called. Nothing already
   * on the screen carries the name — the source ISSUES name themselves, their
   * series does not — so it is read, once, and the id stands in until it lands.
   */
  it('names the source series by its name, falling back to its id until the name lands', async () => {
    const { fixture } = setup({ status: 'OPEN', intervalTo: PAST }, ANNUAL);
    TestBed.inject(TranslocoService).setTranslation(
      { 'admin.publications.issue.compiledFrom': 'compiled from {{count}} issues of {{series}}' },
      'en',
      { merge: true },
    );
    sessionDomain.set({ domainId: 'niord-nm', name: 'NtM' } as Domain);
    facade.members.set(COMPILED);
    fixture.detectChanges();

    const band = () => root(fixture).querySelector('[data-testid="issue-compiled-band"]')!;
    expect(band().textContent).toContain('issues of weekly-ntm');

    const req = TestBed.inject(HttpTestingController).expectOne(
      '/rest/publication-series/series/weekly-ntm',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ seriesId: 'weekly-ntm', descs: [{ lang: 'da', name: 'Ugentlig EfS' }] });
    await fixture.whenStable();

    expect(band().textContent).toContain('issues of Ugentlig EfS');
  });

  /**
   * Four panels that ask a compilation a question it was never asked.
   *
   * It selects by nothing, so it has no criteria strip and no candidates it left
   * out; and a member's own publication date says nothing about which side of
   * this period's cut-off its source issue fell on, so the two instants select
   * the same rows and there is nothing to switch between.
   */
  it('hides the criteria strip, the left-out panel, the view switch and the late section', async () => {
    const fixture = await compilation();
    facade.lateMembers.set([COMPILED[0]]);
    await fixture.whenStable();

    const shown = root(fixture);
    expect(shown.querySelector('[data-testid="issue-criteria"]')).toBeNull();
    expect(shown.querySelector('[data-testid="issue-omissions-toggle"]')).toBeNull();
    expect(shown.querySelector('[data-testid="issue-view-switch"]')).toBeNull();
    expect(shown.querySelector('[data-testid="issue-late-section"]')).toBeNull();

    // And the edit panel offers no criteria to override either. The server
    // refuses one on a compilation and has no predicate to preview, so the
    // disclosure is withheld rather than opened onto a probe that fails.
    button(fixture, 'admin.publications.issue.edit')!.click();
    await fixture.whenStable();
    expect(root(fixture).querySelector('[data-testid="issue-edit-panel"]')).not.toBeNull();
    expect(text(fixture)).not.toContain('admin.publications.issue.criteria');
  });

  /**
   * Every source period in the rail, including the ones that contributed nothing.
   *
   * A week still open and a stretch nobody published at all are exactly what the
   * fifteenth checklist row warns about, and this is where a reader finds out
   * which weeks they are. An uncovered stretch names no issue, so the period it
   * spans stands in its place and opens nothing.
   */
  it('lists every source period in the rail, marking the open and the uncovered ones', async () => {
    const fixture = await compilation();
    sources.set([
      {
        publicId: 'w-12',
        seriesId: 'weekly-ntm',
        week: 12,
        year: 2026,
        cutoff: PAST,
        status: 'PUBLISHED',
        memberCount: 2,
        name: 'EfS uge 12',
      },
      { status: 'MISSING', intervalFrom: PAST, cutoff: PAST },
      {
        publicId: 'w-14',
        seriesId: 'weekly-ntm',
        week: 14,
        year: 2026,
        cutoff: FUTURE,
        status: 'OPEN',
        name: 'EfS uge 14',
      },
    ]);
    await fixture.whenStable();

    const rows = [...root(fixture).querySelectorAll('[data-testid="issue-sources"] li')];
    expect(rows.length).toBe(3);

    expect(rows[0].textContent).toContain('EfS uge 12');
    expect(rows[0].textContent).toContain('admin.publications.issueStatus.PUBLISHED');
    expect(rows[0].querySelector('a')!.getAttribute('href')).toBe(
      '/admin/publications/weekly-ntm?issue=w-12',
    );

    expect(rows[1].textContent).toContain('admin.publications.row.MISSING');
    expect(rows[1].querySelector('a')).toBeNull();

    expect(rows[2].textContent).toContain('admin.publications.issueStatus.OPEN');
    expect(rows[2].querySelector('a')!.getAttribute('href')).toBe(
      '/admin/publications/weekly-ntm?issue=w-14',
    );
  });

  /**
   * A week that printed nothing is a heading all the same, and says so when it
   * is opened.
   *
   * The survey is what knows about it — it contributed no row — and dropping it
   * would leave a year quietly listing fifty-one of its fifty-two weeks. Opened
   * onto nothing at all it would read as a run that failed to draw, so it
   * carries the sentence the whole table carries when it is empty.
   */
  it('heads a source issue that printed nothing and opens it onto the empty sentence', async () => {
    const fixture = await compilation();
    sources.set([
      { ...WEEK_12, status: 'PUBLISHED', memberCount: 2 },
      { ...WEEK_13, status: 'PUBLISHED', memberCount: 1 },
      {
        publicId: 'w-14',
        seriesId: 'weekly-ntm',
        week: 14,
        year: 2026,
        cutoff: PAST,
        status: 'PUBLISHED',
        memberCount: 0,
        name: 'EfS uge 14',
      },
    ]);
    await fixture.whenStable();

    // Four runs: the three weeks the year covered, and the hand-added one.
    expect(headings(fixture).length).toBe(4);
    expect(headings(fixture)[2].textContent).toContain('EfS uge 14');
    // Nought reads correctly through the plural key, so the heading needs no
    // wording of its own for it.
    expect(headings(fixture)[2].textContent).toContain('admin.publications.issue.memberCount');
    expect(root(fixture).querySelector('[data-testid="issue-source-group-empty"]')).toBeNull();

    toggles(fixture)[2].click();
    await fixture.whenStable();

    const empty = root(fixture).querySelector('[data-testid="issue-source-group-empty"]')!;
    expect(empty.textContent).toContain('admin.publications.issue.noMembers');
    // And it is a sentence about the run, not a row of the issue: nothing is
    // selectable under the heading and the list below is untouched.
    expect(empty.querySelector('input.checkbox')).toBeNull();
    expect(body(fixture).querySelectorAll('td a').length).toBe(0);
  });

  /**
   * The sources card belongs to the OPEN issue, and goes once the year is out.
   *
   * What it answers is which weeks are still open or uncovered, which is a
   * question about a release that has not happened. The survey is carried on a
   * released compilation too — that is what makes the week that printed nothing
   * a heading above — so drawing the card from its presence alone would repeat
   * fifty-two weeks beside the very table that already heads them.
   */
  it('withholds the sources card on a released compilation, keeping the runs it covered', async () => {
    const fixture = await compilation({ status: 'PUBLISHED', membershipProvenance: 'COMPILED' });
    sources.set([
      { ...WEEK_12, status: 'PUBLISHED', memberCount: 2 },
      { ...WEEK_13, status: 'PUBLISHED', memberCount: 1 },
    ]);
    await fixture.whenStable();

    const shown = root(fixture);
    expect(shown.querySelector('[data-testid="issue-sources"]')).toBeNull();
    expect(text(fixture)).not.toContain('admin.publications.issue.noSources');
    // What it compiled is still on screen, run by run.
    expect(shown.querySelectorAll('[data-testid="issue-source-group"]').length).toBe(3);
  });

  /** And the open issue still has it, which is the half the card was built for. */
  it('draws the sources card while the compilation is open', async () => {
    const fixture = await compilation();
    sources.set([{ ...WEEK_12, status: 'PUBLISHED', memberCount: 2 }]);
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-sources"]')).not.toBeNull();
  });

  /**
   * An OPEN compilation whose period holds no source issue says so.
   *
   * That is a real and different answer from the one above — the survey was
   * taken and found nothing — and it is the state a year created before its
   * first weekly is published sits in.
   */
  it('says a compilation covers no source issue when the survey found none', async () => {
    const fixture = await compilation();
    sources.set([]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('admin.publications.issue.noSources');
  });

  /** An ordinary weekly is drawn exactly as it was: flat, with its criteria strip. */
  it('leaves every other regime’s list flat, with no headings and no sources card', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.members.set([COMPILED[0]]);
    await fixture.whenStable();

    const shown = root(fixture);
    expect(shown.querySelectorAll('[data-testid="issue-source-group"]').length).toBe(0);
    expect(shown.querySelector('[data-testid="issue-sources"]')).toBeNull();
    expect(shown.querySelector('[data-testid="issue-compiled-band"]')).toBeNull();
    expect(shown.querySelector('[data-testid="issue-criteria"]')).not.toBeNull();
    expect(shown.querySelectorAll('[data-testid="issue-members"] tbody tr').length).toBe(1);
  });
});

/**
 * The document card, read first.
 *
 * The ordinary visit is somebody checking which file is attached; an upload box
 * under every language turns reading into a form.
 */
describe('IssueDetail — the document card', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('shows the attached file and hides the replace controls until they are asked for', async () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      descs: [
        { lang: 'da', name: 'Uge 36', fileName: 'EfS-36.pdf', href: '/rest/repo/EfS-36.pdf' },
      ],
    });
    await fixture.whenStable();

    const card = root(fixture).querySelector('[data-testid="issue-document"]')!;
    expect(card.textContent).toContain('EfS-36.pdf');
    expect(card.textContent).toContain('admin.publications.issue.fileGenerated');
    expect(root(fixture).querySelector('[data-testid="issue-document-controls"]')).toBeNull();

    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-document-toggle"]',
    )!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-document-controls"]')).not.toBeNull();
  });

  /** A query-backed series renders its own file; every other mode waits for a person. */
  it('says a missing file will be generated at the release', () => {
    const { fixture } = setup({ status: 'OPEN', descs: [{ lang: 'da', name: 'Uge 36' }] });

    expect(root(fixture).querySelector('[data-testid="issue-document"]')!.textContent).toContain(
      'admin.publications.issue.fileOnPublish',
    );
  });

  it('says there is no file where nothing will generate one', () => {
    const { fixture } = setup({ status: 'OPEN', descs: [{ lang: 'da', name: 'Uge 36' }] }, {
      ...WEEKLY,
      contentMode: 'UPLOADED_FILE',
    } as PublicationSeries);

    expect(root(fixture).querySelector('[data-testid="issue-document"]')!.textContent).toContain(
      'admin.publications.issue.noDocument',
    );
  });

  /**
   * Where the upload IS the work, the card comes first with its controls open.
   *
   * A card that has to be opened before the only action on it appears is a card
   * that hides its own purpose.
   */
  it('puts the document card first and open for a series whose file is uploaded', async () => {
    const { fixture } = setup({ status: 'OPEN' }, {
      ...WEEKLY,
      contentMode: 'UPLOADED_FILE',
    } as PublicationSeries);
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-document-controls"]')).not.toBeNull();

    const rail = root(fixture).querySelector('aside')!;
    const order = [...rail.querySelectorAll('[data-testid]')].map((el) =>
      el.getAttribute('data-testid'),
    );
    expect(order.indexOf('issue-document')).toBeLessThan(order.indexOf('issue-release'));
  });

  /**
   * The link box sets an EXTERNAL address.
   *
   * A repository path typed back into it would store the app's own file location
   * as if somebody else were hosting the document.
   */
  it('never seeds the link box from a repository address', async () => {
    const { fixture } = setup({
      status: 'OPEN',
      descs: [{ lang: 'da', name: 'Uge 36', link: '/rest/repo/file/x/EfS-36.pdf' }],
    });
    root(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="issue-document-toggle"]')!
      .click();
    await fixture.whenStable();

    const box = root(fixture).querySelector<HTMLInputElement>('input[type="url"]')!;
    expect(box.value).toBe('');
  });
});

/**
 * What a release went without.
 *
 * A message whose publish date lies in a period released before it was
 * published is in no issue of the series. The released issue's page says so,
 * names the messages and offers the amend that takes them in.
 */
describe('IssueDetail — messages published into a released period', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const missedBanner = (fixture: Fixture) =>
    root(fixture).querySelector('[data-testid="issue-missed-banner"]');

  const ROWS: readonly MessageSinceRelease[] = [
    { messageUid: 'uid-1', messageId: 'NM-820-26', title: 'Buoy moved' },
    { messageUid: 'uid-2', messageId: 'NM-821-26', title: 'Light unlit' },
  ];

  it('warns on a released issue, naming the messages and offering the amend', async () => {
    const { fixture } = setup({ status: 'PUBLISHED', publishedAt: PAST });
    facade.publishedSinceRelease.set(ROWS);
    await fixture.whenStable();

    const warning = missedBanner(fixture)!;
    expect(warning).not.toBeNull();
    expect(warning.className).toContain('message-warn');
    expect(warning.textContent).toContain('admin.publications.issue.publishedSinceRelease');
    expect(warning.textContent).not.toContain('admin.publications.issue.publishedSinceReleaseOne');
    expect(warning.textContent).toContain('NM-820-26');
    expect(warning.textContent).toContain('NM-821-26');

    const amend = [...warning.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('admin.publications.amend.action'),
    )!;
    amend.click();
    await fixture.whenStable();
    expect(document.querySelector('#publication-amend-reason')).not.toBeNull();
  });

  /** One message is said in the singular. */
  it('says one message in the singular', async () => {
    const { fixture } = setup({ status: 'PUBLISHED', publishedAt: PAST });
    facade.publishedSinceRelease.set([ROWS[0]]);
    await fixture.whenStable();

    expect(missedBanner(fixture)!.textContent).toContain(
      'admin.publications.issue.publishedSinceReleaseOne',
    );
  });

  /** Nothing missed, nothing said. */
  it('shows no warning on a released issue that missed nothing', () => {
    const { fixture } = setup({ status: 'PUBLISHED', publishedAt: PAST });

    expect(missedBanner(fixture)).toBeNull();
  });

  /** Only a published issue can have gone out without anything. */
  it('shows no warning on an open issue', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.publishedSinceRelease.set(ROWS);
    await fixture.whenStable();

    expect(missedBanner(fixture)).toBeNull();
  });

  /** The list is still said where this desk cannot amend; the action is not offered. */
  it('offers no amend on an imported issue, and still names the messages', async () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      publishedAt: PAST,
      legacyPublicationId: 'legacy-1',
    } as Partial<PublicationIssue>);
    facade.publishedSinceRelease.set(ROWS);
    await fixture.whenStable();

    const warning = missedBanner(fixture)!;
    expect(warning.textContent).toContain('NM-820-26');
    expect(warning.querySelector('button')).toBeNull();
  });
});

/**
 * The release, and the successor it opens.
 *
 * The successor is a different issue at a different address, so the screen
 * behind the release cannot show it — it is offered as a way to follow rather
 * than left to be found.
 */
describe('IssueDetail — releasing', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('says what a release will do beneath the button', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const rail = root(fixture).querySelector('[data-testid="issue-release"]')!;
    expect(rail.textContent).toContain('admin.publications.issue.publishFoot');
  });

  it('names the period a release would open where the series opens one', async () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' });
    facade.successorDescs.set([{ lang: 'da', name: 'EfS uge 37' }]);
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-release"]')!.textContent).toContain(
      'admin.publications.issue.publishFootNext',
    );
  });

  /**
   * Release, then press the primary button of the dialog that opens.
   *
   * Stops at the change detection that renders the banner rather than at a
   * stable fixture: the successor read is in flight at that point, and
   * `whenStable()` waits for it.
   */
  async function release(fixture: Fixture, result: Partial<PublishResult> = {}): Promise<void> {
    facade.publish.mockImplementation(
      (_request: unknown, onPublished: (r: PublishResult) => void) =>
        onPublished({ publicId: 'i-1', stampedAt: PAST, memberCount: 1, ...result }),
    );
    button(fixture, 'admin.publications.issue.publish')!.click();
    await fixture.whenStable();
    document.querySelector<HTMLButtonElement>('.dialog-panel .btn-primary')!.click();
    fixture.detectChanges();
  }

  const banner = (fixture: Fixture) =>
    root(fixture).querySelector('[data-testid="issue-published-banner"]')!;

  /**
   * The name in the banner is the SERVER's, or there is no name in it.
   *
   * The rail's prediction is made from the issue's plan before the release, and
   * an issue spanning several periods is published at a cut-off that prediction
   * never saw: week 36 cut off in week 38 opens week 39, and the probe says
   * week 37. So the banner names what the publish actually created, read by the
   * address the publish reported, and says nothing about a period until it has
   * that answer.
   */
  it('names the successor the release opened rather than the one the probe predicted', async () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' });
    facade.successorDescs.set([{ lang: 'da', name: 'EfS uge 37' }]);
    TestBed.inject(TranslocoService).setTranslation(
      { 'admin.publications.issue.successorOpened': '{{next}} has been opened' },
      'en',
      { merge: true },
    );
    await release(fixture, { successorId: 'i-2' });

    expect(banner(fixture).textContent).toContain('admin.publications.issue.publishedDone');
    // Nothing named while the read is in flight -- the prediction least of all.
    expect(banner(fixture).textContent).toContain(
      'admin.publications.issue.successorOpenedUnnamed',
    );
    expect(banner(fixture).textContent).not.toContain('EfS uge 37');

    const req = TestBed.inject(HttpTestingController).expectOne(
      '/rest/publication-issues/issue/i-2',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ publicId: 'i-2', descs: [{ lang: 'da', name: 'EfS uge 39' }] });
    await fixture.whenStable();

    expect(banner(fixture).textContent).toContain('EfS uge 39');
    expect(banner(fixture).textContent).not.toContain(
      'admin.publications.issue.successorOpenedUnnamed',
    );

    banner(fixture).querySelector<HTMLButtonElement>('button')!.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.opened()).toBe('i-2');
  });

  /**
   * A read that failed knows no name, and says so.
   *
   * The successor exists either way — the publish reported its address — so the
   * way to follow it stays; what goes is the claim about what it is called.
   */
  it('leaves the successor unnamed where the read fails, and still offers it', async () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' });
    facade.successorDescs.set([{ lang: 'da', name: 'EfS uge 37' }]);
    await release(fixture, { successorId: 'i-2' });

    TestBed.inject(HttpTestingController)
      .expectOne('/rest/publication-issues/issue/i-2')
      .flush('no', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(banner(fixture).textContent).toContain(
      'admin.publications.issue.successorOpenedUnnamed',
    );
    expect(banner(fixture).textContent).not.toContain('EfS uge 37');

    banner(fixture).querySelector<HTMLButtonElement>('button')!.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.opened()).toBe('i-2');
  });

  /** A release that opened nothing promises nothing, and asks nothing about one. */
  it('banners a release with no successor without offering one', async () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' });
    await release(fixture);
    await fixture.whenStable();

    expect(banner(fixture).textContent).toContain('admin.publications.issue.publishedDone');
    expect(banner(fixture).textContent).not.toContain('admin.publications.issue.successorOpened');
    expect(banner(fixture).querySelector('button')).toBeNull();
    TestBed.inject(HttpTestingController).expectNone((r) =>
      r.url.includes('/publication-issues/issue/'),
    );
  });

  /**
   * The banner names no time.
   *
   * The time a release answers with is its CUT-OFF, and a release at a chosen
   * past cut-off printed "Published" with that past moment as if it were when
   * the issue went out. The header line prints the issue's own publication
   * time once the refetch lands, so the banner says only that it happened.
   */
  it('carries no cut-off time in the banner after a release at a past cut-off', async () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' });
    TestBed.inject(TranslocoService).setTranslation(
      {
        'admin.publications.issue.publishedNow': 'Published {{at}}.',
        'admin.publications.issue.publishedDone': 'Published.',
      },
      'en',
      { merge: true },
    );
    formatDateTime.mockImplementation((value: number) =>
      value === PAST ? 'THE-PAST-CUT-OFF' : '02.09.2026 12.21',
    );
    try {
      await release(fixture, { stampedAt: PAST });
      await fixture.whenStable();

      expect(banner(fixture).textContent).toContain('Published.');
      expect(banner(fixture).textContent).not.toContain('THE-PAST-CUT-OFF');
    } finally {
      formatDateTime.mockImplementation(() => '02.09.2026 12.21');
    }
  });

  /**
   * A published issue is not re-checked.
   *
   * The rail re-resolves the criteria as they stand NOW, and on a frozen issue
   * that answers a question nobody asked. What stands instead is the record of
   * the release.
   */
  it('replaces the checklist with the record of the release once published', () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      cutoffStampedAt: PAST,
      publicFrom: PAST,
      publicTo: FUTURE,
      memberCount: 12,
    });

    const rail = root(fixture).querySelector('[data-testid="issue-release"]')!;
    expect(rail.querySelector('[data-testid="issue-verdict"]')!.textContent).toContain(
      'admin.publications.issueStatus.PUBLISHED',
    );
    expect(rail.textContent).toContain('admin.publications.issue.memberCount');
    expect(rail.textContent).not.toContain('admin.publications.issue.memberCountOne');
    // The end of the currently public window has not happened yet.
    expect(rail.querySelectorAll('.expected-date').length).toBe(1);
    expect(button(fixture, 'admin.publications.issue.publish')).toBeUndefined();
  });

  /** "1 message(s)" is the shape of a sentence nobody wrote for the number it got. */
  it('counts one published message in the singular', () => {
    const { fixture } = setup({ status: 'PUBLISHED', cutoffStampedAt: PAST, memberCount: 1 });

    expect(root(fixture).querySelector('[data-testid="issue-release"]')!.textContent).toContain(
      'admin.publications.issue.memberCountOne',
    );
  });
});

/**
 * Every checklist row this build renders has a label, a sentence AND a clause.
 *
 * The label names the question the rail asked; the sentence says what a failure
 * means and is the only half a reader can act on; the clause is that same answer
 * cut to the width a series card's closing phrase has room for. A code added to
 * the set without all three is a key path on one of the three surfaces, which
 * reads as a broken build rather than as a check nobody has worded yet.
 */
describe('the checklist vocabulary', () => {
  type Node = string | { [key: string]: Node };

  function lookup(bundle: Node, key: string): Node | undefined {
    let node: Node | undefined = bundle;
    for (const part of key.split('.')) {
      if (typeof node !== 'object' || node === null) {
        return undefined;
      }
      node = node[part];
    }
    return node;
  }

  const keys = [
    ...CHECK_CODES.map((code) => `admin.publications.check.${code}`),
    ...CHECK_CODES.map((code) => `admin.publications.checkDetail.${code}`),
    ...CHECK_CODES.map((code) => `admin.publications.checkSummary.${code}`),
  ];

  it('has an English label and sentence for every check code', () => {
    const missing = keys.filter((key) => typeof lookup(en as Node, key) !== 'string');
    expect(missing, `keys missing in en.json:\n${missing.join('\n')}`).toEqual([]);
  });

  /**
   * And words for every measured answer, in the English bundle.
   *
   * The answers sit beside the sentences under names carrying dots of their own,
   * so each is ONE literal key under `checkDetail` rather than a path — walked as
   * a path, the lookup would stop at the row's own sentence. A code without
   * wording falls back to the server's English beside a translated row, which is what
   * this vocabulary exists to stop.
   */
  function detailSentence(bundle: Node, code: string): Node | undefined {
    const group = lookup(bundle, 'admin.publications.checkDetail');
    return typeof group === 'object' ? group[code] : undefined;
  }

  it('has an English sentence for every detail code', () => {
    const missing = CHECK_DETAIL_CODES.filter(
      (code) => typeof detailSentence(en as Node, code) !== 'string',
    );
    expect(missing, `detail codes missing in en.json:\n${missing.join('\n')}`).toEqual([]);
  });
});

/**
 * THE TWO INSTANTS AN OPEN ISSUE CAN BE READ AT.
 *
 * Once the planned cut-off has passed and something has been published since,
 * "what is in this issue" has two honest answers — and the difference between
 * them is exactly the messages that arrived late. The screen names both, shows
 * the late ones first and says what they mean at the instant being read; it
 * never mixes them into the table, where they would read as ordinary members of
 * a list that is one instant old.
 */
describe('IssueDetail — the two instants', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const EARLY: IssueMember = {
    messageUid: 'uid-a',
    frozenShortId: 'NM-810-26',
    frozenStatus: 'PUBLISHED',
    source: 'CRITERIA',
    reasonCode: 'IN_INTERVAL',
  };

  const LATE: IssueMember = {
    messageUid: 'uid-late',
    frozenShortId: 'NM-899-26',
    frozenStatus: 'PUBLISHED',
    source: 'CRITERIA',
    reasonCode: 'IN_INTERVAL',
  };

  const card = (fixture: Fixture) => root(fixture).querySelector('[data-testid="issue-members"]')!;

  /** The member table, which is the one in the card that is not the late list. */
  const mainTable = (fixture: Fixture) =>
    [...card(fixture).querySelectorAll('table')].find((table) => !table.dataset['testid'])!;

  async function withLate(members: readonly IssueMember[], late: readonly IssueMember[]) {
    const { fixture } = setup({ status: 'OPEN', intervalTo: PAST });
    facade.members.set(members);
    facade.lateMembers.set(late);
    await fixture.whenStable();
    return fixture;
  }

  /**
   * With nothing late the two readings select the same messages, so there is
   * nothing to switch between and nothing new to show.
   */
  it('shows no switch and no late section where nothing arrived late', async () => {
    const fixture = await withLate([EARLY], []);

    expect(root(fixture).querySelector('[data-testid="issue-view-switch"]')).toBeNull();
    expect(root(fixture).querySelector('[data-testid="issue-late-section"]')).toBeNull();
    expect(mainTable(fixture).querySelectorAll('tbody tr').length).toBe(1);
  });

  it('offers the two instants once something was published after the cut-off', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    const buttons = [
      ...root(fixture).querySelectorAll<HTMLButtonElement>(
        '[data-testid="issue-view-switch"] button',
      ),
    ];
    expect(buttons).toHaveLength(2);
    expect(buttons[0].textContent).toContain('admin.publications.issue.viewNow');
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true');
    expect(buttons[1].textContent).toContain('admin.publications.issue.viewPlanned');
    expect(buttons[1].getAttribute('aria-pressed')).toBe('false');
  });

  /** The screen's own answer to "late for what", which the stub bundle hides. */
  const detail = (fixture: Fixture) =>
    fixture.debugElement.query(By.directive(IssueDetail)).componentInstance as unknown as {
      lateAfter(): number | undefined;
    };

  /**
   * WHICH cut-off these rows are late for is the answer's to say, not the
   * screen's to assume.
   *
   * The two part company exactly where it matters: a period being typed is
   * asked about at the cut-off in the box, and the rows come back counted from
   * there — so a heading quoting the value on file over rows counted from the
   * typed one would be two answers under one sentence.
   */
  it('names the instant the answer measured lateness against', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);
    const typed = PAST + 6 * HOUR;
    formatDateTime.mockClear();

    facade.lateAfter.set(typed);
    await fixture.whenStable();

    expect(detail(fixture).lateAfter()).toBe(typed);
    // And it reaches the heading and the switch, not just the component.
    expect(formatDateTime).toHaveBeenCalledWith(typed);
  });

  /** A server that names no instant measured against the issue's own cut-off. */
  it('falls back to the issue’s own planned cut-off where the answer names none', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    expect(facade.lateAfter()).toBeUndefined();
    expect(detail(fixture).lateAfter()).toBe(PAST);
    expect(formatDateTime).toHaveBeenCalledWith(PAST);
  });

  /** Choosing the other instant is a re-read, which is the facade's to make. */
  it('asks the facade to read at the planned cut-off when that instant is chosen', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    root(fixture)
      .querySelectorAll<HTMLButtonElement>('[data-testid="issue-view-switch"] button')[1]
      .click();
    await fixture.whenStable();

    expect(facade.setViewInstant).toHaveBeenCalledWith('planned');
    const buttons = root(fixture).querySelectorAll('[data-testid="issue-view-switch"] button');
    expect(buttons[1].getAttribute('aria-pressed')).toBe('true');
  });

  /**
   * The late rows come FIRST and are not repeated below.
   *
   * They are the news on this screen: a reader deciding when to close the
   * period is deciding about exactly these messages, and a list that mixed them
   * into two hundred others would make them findable rather than visible.
   */
  it('lists the late messages above the table and leaves them out of it', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    const late = card(fixture).querySelector('[data-testid="issue-late-section"]')!;
    expect(late.compareDocumentPosition(mainTable(fixture))).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(late.textContent).toContain('admin.publications.issue.lateTitle');

    late.querySelector<HTMLButtonElement>('[data-testid="issue-late-toggle"]')!.click();
    await fixture.whenStable();
    expect(late.querySelector('[data-testid="issue-late-table"]')!.textContent).toContain(
      'NM-899-26',
    );

    const rows = mainTable(fixture).querySelectorAll('tbody tr');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('NM-810-26');
    expect(rows[0].textContent).not.toContain('NM-899-26');
  });

  /**
   * The heading counts what the release would carry, late rows included.
   *
   * Lifting them into their own section is a way of showing the list, not a
   * decision about what is in the issue.
   */
  it('counts every member in the heading, including the late ones', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    expect(card(fixture).querySelector('h4')!.textContent).toContain('2');
  });

  /**
   * Opening it is a judgement about ONE issue's late messages.
   *
   * The panel is reused when a new edition hands over to the next issue, so a
   * section left open carries into a list of the next issue's rows that nobody
   * on that screen asked to see.
   */
  it('re-folds the late section when the panel is pointed at another issue', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    root(fixture).querySelector<HTMLButtonElement>('[data-testid="issue-late-toggle"]')!.click();
    await fixture.whenStable();
    expect(root(fixture).querySelector('[data-testid="issue-late-table"]')).not.toBeNull();

    fixture.componentInstance.publicId.set('i-2');
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-late-table"]')).toBeNull();
  });

  /**
   * Folded by default: the heading is the answer, the rows are the follow-up.
   *
   * How many arrived late and what they mean at this instant is what a reader
   * needs; the rows themselves matter to the one who is about to act on them,
   * and a second table opening by itself pushed the member list this screen
   * exists for off the first screenful.
   */
  it('folds the late section away and opens it on request', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);
    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-late-toggle"]',
    )!;

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(root(fixture).querySelector('[data-testid="issue-late-table"]')).toBeNull();
    // The count and the meaning are said whether or not the rows are showing.
    expect(
      root(fixture).querySelector('[data-testid="issue-late-section"]')!.textContent,
    ).toContain('admin.publications.issue.lateTitle');

    toggle.click();
    await fixture.whenStable();

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(root(fixture).querySelector('[data-testid="issue-late-table"]')).not.toBeNull();
  });

  /**
   * The same rows, and the opposite thing to say about them.
   *
   * Read as of now they go out with this issue; read at the planned cut-off
   * they do not. The rows cannot say which, so the line above them does.
   */
  it('says the late rows go out when the issue is read as of now', async () => {
    const fixture = await withLate([EARLY, LATE], [LATE]);

    expect(
      root(fixture).querySelector('[data-testid="issue-late-meaning"]')!.textContent,
    ).toContain('admin.publications.issue.lateIncludedNow');
  });

  it('says they are left out when the issue is read at the planned cut-off', async () => {
    const fixture = await withLate([EARLY], [LATE]);
    facade.viewInstant.set('planned');
    await fixture.whenStable();

    expect(
      root(fixture).querySelector('[data-testid="issue-late-meaning"]')!.textContent,
    ).toContain('admin.publications.issue.lateNotIncludedPlanned');
    // The planned reading resolved without them, so the table below is the
    // whole of what that release would freeze.
    expect(mainTable(fixture).querySelectorAll('tbody tr')).toHaveLength(1);
  });

  /**
   * An issue whose every member arrived late shows the section and no table.
   *
   * A header row over nothing reads as a list that failed to load, and "no
   * messages are in this issue" would contradict the section above it.
   */
  it('draws no empty table where every member is late', async () => {
    const fixture = await withLate([LATE], [LATE]);

    expect(root(fixture).querySelector('[data-testid="issue-late-section"]')).not.toBeNull();
    expect(
      [...card(fixture).querySelectorAll('table')].filter((t) => !t.dataset['testid']),
    ).toEqual([]);
    expect(card(fixture).textContent).not.toContain('admin.publications.issue.noMembers');
  });
});

/**
 * While a period is being typed, the boxes are the question.
 *
 * The two readings offered above the table are both about the period ON FILE,
 * and the panel names another one: every number on the screen follows the boxes
 * until they are saved or abandoned.
 */
describe('IssueDetail — reading over a period being edited', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const EARLY: IssueMember = {
    messageUid: 'uid-a',
    frozenShortId: 'NM-810-26',
    frozenStatus: 'PUBLISHED',
    source: 'CRITERIA',
    reasonCode: 'IN_INTERVAL',
  };

  const LATE: IssueMember = {
    messageUid: 'uid-late',
    frozenShortId: 'NM-899-26',
    frozenStatus: 'PUBLISHED',
    source: 'CRITERIA',
    reasonCode: 'IN_INTERVAL',
  };

  async function openIssue() {
    const { fixture } = setup({ status: 'OPEN', intervalTo: PAST });
    facade.members.set([EARLY, LATE]);
    lateMembers.set([LATE]);
    await fixture.whenStable();
    return fixture;
  }

  /**
   * The switch goes while the panel is open.
   *
   * Offered there it asks the reader to choose between two answers to a question
   * they have already replaced — and neither of them is the one the boxes in
   * front of them describe.
   */
  it('withdraws the two instants while the period is being edited', async () => {
    const fixture = await openIssue();
    expect(root(fixture).querySelector('[data-testid="issue-view-switch"]')).not.toBeNull();

    button(fixture, 'admin.publications.issue.edit')!.click();
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-view-switch"]')).toBeNull();
  });

  /**
   * A typed period is left alone before it is asked about.
   *
   * The boxes are typed into, and every intermediate value would otherwise be a
   * request — including the half-written dates a date field emits on its way to
   * one somebody meant.
   */
  it('waits for the typing to settle before asking about the period', async () => {
    const fixture = await openIssue();
    fixture.componentInstance.debounceMs.set(20);
    await fixture.whenStable();
    facade.setPreviewPeriod.mockClear();

    button(fixture, 'admin.publications.issue.edit')!.click();
    await fixture.whenStable();
    expect(facade.setPreviewPeriod).not.toHaveBeenCalled();

    await new Promise((resolve) => setTimeout(resolve, 40));

    expect(facade.setPreviewPeriod).toHaveBeenCalledWith({ from: undefined, to: PAST });
  });
});
