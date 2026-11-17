import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { CopyLinkButton } from '../../../../../shared/components/copy-link-button/copy-link-button';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type {
  IssueAuditEntry,
  IssueMember,
  IssueOverride,
  IssuePreview,
  PreviewMiss,
  PublicationIssue,
  PublicationSeries,
  PublishCheck,
  PublishRequest,
} from '../../../services/api/publication-series-api.service';
import type { CitingMessage } from './publication-errors';
import { IssueDetail } from './issue-detail';
import {
  IssueDetailFacade,
  type IssueViewInstant,
  type PeriodProblem,
} from './issue-detail.facade';
import { PublishDialog } from './publish-dialog';

@Component({
  imports: [IssueDetail],
  template: `<app-issue-detail publicId="i-1" [series]="series()" [previewDebounceMs]="0" />`,
})
class Host {
  readonly series = signal<PublicationSeries | undefined>(undefined);
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
  // A compilation draws its member list grouped by the source issue each row
  // came from. Nothing here is one, so the flat list is what every case below
  // asserts against -- the grouped rendering has its own file.
  compiled: signal(false),
  sources: signal(undefined),
  groupedMembers: computed(() => []),
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
  archiveFileUrl: vi.fn(
    (auditEntryId: number, lang: string) =>
      `/rest/publication-issues/issue/i-1/archive/${auditEntryId}/${lang}`,
  ),
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
  // What the criteria left out, from the same resolution as the members: the
  // count is the whole answer and the rows are a capped sample of it.
  omissions: signal<readonly PreviewMiss[]>([]),
  omissionCount: signal(0),
  omissionsBounded: signal(false),
  publishedSinceRelease: signal([]),
  // The successor probe: what the period a release would open is called. Its own
  // fetch, outside the workbench chain, so an empty answer costs a sentence and
  // never the screen.
  successorDescs: signal([]),
  probeSuccessor: vi.fn(),
};

/** The new-tab opener, so a spec never reaches for a ticket or for window.open. */
const downloads = {
  openTicketed: vi.fn(() => Promise.resolve()),
  openPublic: vi.fn(),
};

const SERIES_WITH_REPORT = {
  seriesId: 's-1',
  reportId: 'ntm',
  cutoffDefault: 'RELEASE_MOMENT',
} as PublicationSeries;

/** What would go out, for the tests that care that the rest of the screen stands. */
const MEMBERS: readonly IssueMember[] = [
  {
    messageUid: 'uid-a',
    frozenShortId: 'NM-101-26',
    source: 'CRITERIA',
    reasonCode: 'IN_INTERVAL',
  },
  {
    messageUid: 'uid-b',
    frozenShortId: 'NM-102-26',
    source: 'CRITERIA',
    reasonCode: 'IN_INTERVAL',
  },
];

/**
 * The desk the admin is sitting at.
 *
 * Undefined by default, and every fixture here names no domain -- which is
 * writable from everywhere, so the panel behaves exactly as it did before the
 * rule existed unless a test says otherwise.
 */
const sessionDomain = signal<Domain | undefined>(undefined);

/** The desks the reader holds a role in — both of them, unless a test narrows it. */
const editableDomains = signal<readonly Domain[]>([
  { domainId: 'niord-dk', name: 'Danish waters' } as Domain,
  { domainId: 'niord-de', name: 'German bight' } as Domain,
]);

/** How the refusal applies the desk it names, which is the picker's own path. */
const selectSessionDomain = vi.fn();

function setup(
  issue: Partial<PublicationIssue>,
  series?: PublicationSeries,
  currentDomainId?: string,
  editable?: readonly Domain[],
) {
  vi.clearAllMocks();
  sessionDomain.set(currentDomainId ? ({ domainId: currentDomainId } as Domain) : undefined);
  editableDomains.set(
    editable ?? [
      { domainId: 'niord-dk', name: 'Danish waters' } as Domain,
      { domainId: 'niord-de', name: 'German bight' } as Domain,
    ],
  );
  facade.issue.set({ publicId: 'i-1', status: 'OPEN', descs: [], ...issue } as PublicationIssue);
  facade.members.set([]);
  facade.checklist.set({ rows: [], canPublish: true });
  // Every screen starts as of now with nothing late: the switch and the section
  // above the table are the exception, and a test that wants them says so.
  lateMembers.set([]);
  viewInstant.set('now');
  facade.working.set(false);
  facade.publishing.set(false);
  facade.amending.set(false);
  facade.lastActionError.set(undefined);
  facade.acknowledgeableWarnings.set([]);
  facade.informationalWarnings.set([]);
  facade.unacknowledgedWarnings.set([]);
  facade.citingMessages.set([]);
  facade.citingCount.set(0);
  facade.citationsBounded.set(false);
  facade.previewRows.set([]);
  facade.previewWorking.set(false);
  facade.lastPreviewError.set(undefined);
  facade.audit.set([]);

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
          formatDateTime: () => '14.05.2026 09.35',
          formatWeekdayDateTime: () => 'ons. 14.05.2026 09.35',
          uses12HourClock: () => false,
          sessionTimeZone: () => 'UTC',
        },
      },
      { provide: DiscardConfirmService, useValue: { confirmDiscard: () => Promise.resolve(true) } },
      // The publish dialog asks the server for the rail of a chosen cut-off, so
      // the API service it injects has to be constructible here.
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      provideTestTransloco(),
      provideNiordIcons(),
      // Stubbed rather than real: the panel reads the current domain to decide
      // what it may offer, and the live facade would pull the AuthFacade ->
      // Keycloak chain into a spec about an issue.
      {
        provide: DomainFacade,
        // `resolveById` is what names the two desks on a transfer line. Only the
        // one domain is in the catalogue, so the other falls back to its id --
        // which is the case a deleted domain leaves behind.
        useValue: {
          sessionDomain,
          resolveById: (domainId: string) =>
            domainId === 'niord-nm'
              ? ({ domainId, name: 'Notices to Mariners' } as Domain)
              : undefined,
          // What decides whether the refusal can offer a switch at all.
          editableDomains,
          selectSessionDomain,
        } as unknown as DomainFacade,
      },
      // The archive affordance opens a new tab behind a one-time ticket, which
      // means a real one would fetch a ticket over HTTP and call window.open.
      // Stubbed so the assertion is about WHAT the panel asks to open.
      {
        provide: TicketedDownloadService,
        useValue: downloads as unknown as TicketedDownloadService,
      },
    ],
  }).overrideComponent(IssueDetail, {
    // The component's OWN providers are REPLACED by this list, so the confirm
    // surface it provides has to be repeated here or it disappears.
    set: {
      providers: [{ provide: IssueDetailFacade, useValue: facade }, ConfirmService],
    },
  });

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.series.set(series);
  fixture.autoDetectChanges();
  return { fixture };
}

const root = (fixture: { nativeElement: unknown }) => fixture.nativeElement as HTMLElement;
const text = (fixture: { nativeElement: unknown }) => root(fixture).textContent ?? '';

/**
 * Open the history card.
 *
 * The trail is folded by default — it is the longest thing in the rail and the
 * one nobody opens the screen to read — so a test about what a line says has to
 * ask for it the way a reader does.
 */
async function expandHistory(fixture: {
  nativeElement: unknown;
  whenStable: () => Promise<unknown>;
}): Promise<void> {
  root(fixture).querySelector<HTMLButtonElement>('[data-testid="issue-history-toggle"]')!.click();
  await fixture.whenStable();
}

/**
 * Open the replace-document disclosure.
 *
 * The card is read-first: the ordinary visit is somebody checking which file is
 * attached, so everything that CHANGES a document sits behind this one line.
 */
async function openDocumentControls(fixture: {
  nativeElement: unknown;
  whenStable: () => Promise<unknown>;
}): Promise<void> {
  root(fixture).querySelector<HTMLButtonElement>('[data-testid="issue-document-toggle"]')!.click();
  await fixture.whenStable();
}

describe('IssueDetail — preview', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * One place asks for a render, and it asks whether or not one exists.
   *
   * A preview is a look at what the current members would produce, so wanting a
   * fresh one is the ordinary case rather than a remedy to something — and it
   * belongs on the card that holds the documents, once, rather than on whichever
   * surface happened to be complaining.
   */
  it('offers the render from the document card, with and without stored renders', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);

    const generate = () =>
      [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].filter((b) =>
        b.textContent?.includes('admin.publications.preview.generate'),
      );
    expect(generate().length).toBe(1);
    expect(generate()[0].closest('[data-testid="issue-document"]')).not.toBeNull();

    facade.previewRows.set([{ lang: 'da', renderedAt: 1_700_000_000_000 }]);
    await fixture.whenStable();
    expect(generate().length).toBe(1);

    // And the replace-document disclosure adds no second control doing the same.
    await openDocumentControls(fixture);
    expect(generate().length).toBe(1);
  });

  /** A preview is a rendered report; a series without one has nothing to render. */
  /** An uploaded series renders nothing, so it is neither offered a render nor told it lacks a report. */
  it('shows no preview section at all for a series that does not render its document', async () => {
    const { fixture } = setup({ status: 'OPEN' }, {
      seriesId: 's-1',
      contentMode: 'UPLOADED_FILE',
    } as PublicationSeries);
    await fixture.whenStable();

    expect(text(fixture)).not.toContain('admin.publications.preview.generate');
    expect(text(fixture)).not.toContain('admin.publications.preview.noReport');
  });

  it('offers no preview, and says why, when the series has no report', async () => {
    // A generated series with no report yet: the one case the note is for. An
    // uploaded or linked series renders nothing and gets no preview section.
    const { fixture } = setup({ status: 'OPEN' }, {
      seriesId: 's-1',
      contentMode: 'GENERATED_FROM_QUERY',
    } as PublicationSeries);
    await fixture.whenStable();

    const button = [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('admin.publications.preview.generate'),
    );
    expect(button).toBeUndefined();
    expect(text(fixture)).toContain('admin.publications.preview.noReport');
  });

  /**
   * A render is slow, and the button has to say so.
   *
   * Producing the document takes seconds on a large issue. A control that only
   * greys itself out says the click landed and nothing about the work; the
   * spinner says the work is running, `aria-busy` says the same to a reader who
   * cannot see it, and the label stays put so the control keeps its size and its
   * meaning while it turns.
   */
  it('spins inside the render offer while a render is in flight', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    await fixture.whenStable();

    const generate = () =>
      [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
        b.textContent?.includes('admin.publications.preview.generate'),
      )!;

    expect(generate().closest('[data-testid="issue-document"]')).not.toBeNull();
    expect(generate().querySelector('.animate-spin')).toBeNull();
    expect(generate().getAttribute('aria-busy')).toBeNull();

    facade.previewWorking.set(true);
    await fixture.whenStable();

    expect(generate().querySelector('.animate-spin')).not.toBeNull();
    expect(generate().getAttribute('aria-busy')).toBe('true');
    expect(generate().textContent).toContain('admin.publications.preview.generate');
  });

  it('asks the facade to render when a report is configured', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    await fixture.whenStable();

    const buttons = [...root(fixture).querySelectorAll<HTMLButtonElement>('button')];
    const generate = buttons.find((b) =>
      b.textContent?.includes('admin.publications.preview.generate'),
    );
    expect(generate?.disabled).toBe(false);
    generate!.click();
    await fixture.whenStable();

    expect(facade.generatePreview).toHaveBeenCalledTimes(1);
  });

  /**
   * A preview is FETCHED, never linked to.
   *
   * The address the render reports is gated on the reader's own admin bearer and
   * is reported relative to a host that is not the API's, so an anchor pointed at
   * it asks the web server for a path it does not serve. The row offers an action
   * instead, and the document arrives over the same credentials as the rest of
   * the screen.
   */
  it('renders one row per language, each fetching its own document', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.previewRows.set([
      { lang: 'da', renderedAt: 1_700_000_000_000, href: '/rest/preview/i-1/da' },
      { lang: 'en', renderedAt: 1_700_000_000_001, href: '/rest/preview/i-1/en' },
    ]);
    await fixture.whenStable();

    // Nothing anchors at the bearer-gated address any more.
    const links = [...root(fixture).querySelectorAll<HTMLAnchorElement>('a')].filter((a) =>
      a.getAttribute('href')?.startsWith('/rest/preview/'),
    );
    expect(links).toEqual([]);

    const openButtons = [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].filter(
      (b) => b.textContent?.includes('admin.publications.preview.download'),
    );
    expect(openButtons.length).toBe(2);
    expect(text(fixture)).toContain('DA');
    expect(text(fixture)).toContain('14.05.2026 09.35');
    // And the offer to render again stands beside them.
    expect(text(fixture)).toContain('admin.publications.preview.generate');

    openButtons[0].click();
    await fixture.whenStable();
    expect(facade.fetchPreview).toHaveBeenCalledTimes(1);
    expect(facade.fetchPreview.mock.calls[0][0]).toBe('da');
  });

  /**
   * The address handed to somebody else is the ISSUE's.
   *
   * A preview URL answers only to the reader's own admin bearer, so pasting it
   * into a message hands over an address that fails for everybody it reaches.
   */
  /** A preview row hands out no address at all. */
  it('offers no copy control beside a rendered preview', async () => {
    const { fixture } = setup({ status: 'OPEN', seriesId: 's-1' }, SERIES_WITH_REPORT);
    facade.previewRows.set([
      { lang: 'da', renderedAt: 1_700_000_000_000, href: '/rest/preview/i-1/da' },
    ]);
    await fixture.whenStable();

    expect(fixture.debugElement.queryAll(By.directive(CopyLinkButton))).toEqual([]);
  });

  it('copies the issue deep link rather than the preview address', async () => {
    const { fixture } = setup({ status: 'PUBLISHED', seriesId: 's-1' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    await fixture.whenStable();

    const copies = fixture.debugElement.queryAll(By.directive(CopyLinkButton));
    expect(copies.length).toBe(1);
    const url = (copies[0].componentInstance as CopyLinkButton).url();
    expect(url).toContain('/admin/publications/s-1?issue=i-1');
    expect(url).not.toContain('/rest/preview/');
  });

  /** A refusal is named as itself rather than as a generic failure. */
  it('renders REPORT_NOT_CONFIGURED through the coded-error mapping', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.lastPreviewError.set({ error: { code: 'REPORT_NOT_CONFIGURED' } });
    await fixture.whenStable();

    expect(text(fixture)).toContain('admin.publications.preview.noReport');
  });

  it('renders ISSUE_NOT_OPEN through the coded-error mapping', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.lastPreviewError.set({ error: { code: 'ISSUE_NOT_OPEN' } });
    await fixture.whenStable();

    expect(text(fixture)).toContain('admin.publications.preview.notOpen');
  });
});

describe('IssueDetail — the release rail and the history', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The tier is the SERVER'S severity, not "can this be ticked".
   *
   * Exactly one row is ever acknowledgeable, so reading the tier off that painted
   * every other failing warning — an unchained interval, an override that no
   * longer bites — as a hard stop, beside a Publish button the server was
   * perfectly willing to honour.
   */
  it('lists what stops the release first, and hides what passed behind one line', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set({
      rows: [
        { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false },
        { code: 'MEMBER_LIMIT', severity: 'BLOCK', passed: false, acknowledgeable: false },
        { code: 'ISSUE_OPEN', severity: 'OK', passed: true, acknowledgeable: false },
      ],
      canPublish: false,
    });
    await fixture.whenStable();

    const rail = root(fixture).querySelector('[data-testid="issue-release"]')!;
    // Only what needs doing, blockers first — the tier is the SERVER'S severity,
    // not "can this be ticked".
    const rows = [...rail.querySelectorAll('li')];
    expect(rows.length).toBe(2);
    // Each row wears a round mark in its severity's own palette. The triangle
    // belongs to the verdict alone: repeated down the list it made every row
    // look alike, and the one that stops the release is the one being looked for.
    const marks = rows.map((row) => row.querySelector('[data-testid="issue-check-mark"]')!);
    expect(marks[0].textContent?.trim()).toBe('✕');
    expect(marks[0].className).toContain('notice-error');
    expect(marks[1].textContent?.trim()).toBe('!');
    expect(marks[1].className).toContain('notice-warn');
    expect(root(fixture).querySelector('[data-testid="issue-passed"]')).toBeNull();

    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-passed-toggle"]',
    )!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.textContent).toContain('admin.publications.checklist.passedCount');

    toggle.click();
    await fixture.whenStable();

    const passed = [...root(fixture).querySelectorAll('[data-testid="issue-passed"] li')];
    expect(passed.length).toBe(1);
    expect(passed[0].textContent).toContain('admin.publications.check.ISSUE_OPEN');
  });

  /**
   * A row the server says was never asked is not an answer.
   *
   * A rail padded with rules that do not apply to this series — an uploaded-PDF
   * series asked whether its report is configured — is one nobody reads to the
   * end, and the verdict above it would be counting questions nobody put.
   */
  it('renders nothing for a row the server marked as not applicable', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set({
      rows: [
        {
          code: 'REPORT_CONFIGURED',
          severity: 'BLOCK',
          passed: false,
          acknowledgeable: false,
          applicable: false,
        },
        { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false },
      ],
      canPublish: true,
    });
    await fixture.whenStable();

    const rail = root(fixture).querySelector('[data-testid="issue-release"]')!;
    expect(rail.textContent).not.toContain('REPORT_CONFIGURED');
    // And the verdict counts what is left: one warning, not one warning and a stop.
    expect(rail.querySelector('[data-testid="issue-verdict"]')!.textContent).toContain(
      'admin.publications.checklist.verdict.warnings',
    );
  });

  /**
   * The verdict counts in the shape the number needs.
   *
   * "1 warning(s) — can be published" is the shape of a sentence nobody wrote
   * for the number it ended up carrying, on the one line the whole rail exists
   * to produce.
   */
  it('says one warning in the singular and several in the plural', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    const verdict = () => root(fixture).querySelector('[data-testid="issue-verdict"]')!;

    facade.checklist.set({
      rows: [{ code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false }],
      canPublish: true,
    });
    await fixture.whenStable();
    expect(verdict().textContent).toContain('admin.publications.checklist.verdict.warningsOne');

    facade.checklist.set({
      rows: [
        {
          code: 'NO_INEFFECTIVE_OVERRIDES',
          severity: 'WARN',
          passed: false,
          acknowledgeable: false,
        },
        { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false },
      ],
      canPublish: true,
    });
    await fixture.whenStable();
    expect(verdict().textContent).toContain('admin.publications.checklist.verdict.warningsMany');
  });

  /**
   * The verdict is a LINE, not a notice.
   *
   * A tinted, left-accented box at the top of the card reads as something that
   * just happened; the verdict is the standing state of the issue the card is
   * about, and it is the first thing on the card either way. Weight and colour
   * carry it, and the severity picks the colour.
   */
  it('states the verdict as a bare coloured line in each severity', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    const verdict = () => root(fixture).querySelector('[data-testid="issue-verdict"]')!;

    expect(verdict().className).toContain('checklist-verdict');
    expect(verdict().className).toContain('is-ok');
    expect(verdict().className).not.toContain('message');

    facade.checklist.set({
      rows: [{ code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false, acknowledgeable: false }],
      canPublish: true,
    });
    await fixture.whenStable();
    expect(verdict().className).toContain('is-warn');

    facade.checklist.set({
      rows: [{ code: 'MEMBER_LIMIT', severity: 'BLOCK', passed: false, acknowledgeable: false }],
      canPublish: false,
    });
    await fixture.whenStable();
    expect(verdict().className).toContain('is-blocked');
  });

  /** And the record of a release wears the same line, in the same green. */
  it('states the release of a published issue as the same line', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    await fixture.whenStable();

    const verdict = root(fixture).querySelector('[data-testid="issue-verdict"]')!;
    expect(verdict.className).toContain('checklist-verdict');
    expect(verdict.className).toContain('is-ok');
    expect(verdict.className).not.toContain('message');
  });

  /**
   * An open issue with no rail has not been released either.
   *
   * The checklist is the server's answer to a reader who may administer the
   * issue; one who may only curate it is sent none. The record of a release is
   * what stood in that gap — a green tick, a status line and a cut-off stamp on
   * an issue nobody has published — so the branch is on the status rather than
   * on the absence of a checklist. The rest of the screen is untouched: what
   * would go out is the reason this reader opened it.
   */
  it('draws neither the rail nor the release record on an open issue with no checklist', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.members.set(MEMBERS);
    await fixture.whenStable();

    const rail = root(fixture).querySelector('[data-testid="issue-release"]')!;
    expect(rail.querySelector('[data-testid="issue-verdict"]')).toBeNull();
    expect(rail.querySelectorAll('li').length).toBe(0);
    // Nothing at all, rather than a record of something that has not happened —
    // and no publish affordance riding along with it.
    expect(rail.textContent?.trim()).toBe('');
    expect(rail.querySelector('button')).toBeNull();

    const rows = [...root(fixture).querySelectorAll('[data-testid="issue-members"] tbody tr')];
    expect(rows.length).toBe(2);
    expect(text(fixture)).toContain('NM-101-26');
  });

  /**
   * A failing row says what it MEANS, not the question it answers.
   *
   * "Every language has a file", read as a failure, says only that something
   * about the documents is wrong; the sentence says what is missing, and the way
   * to it sits on the row rather than somewhere further down the screen.
   */
  it('says what a failing row means and offers its remedy on the row', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set({
      rows: [
        {
          code: 'FILE_PRESENT_PER_LANGUAGE',
          severity: 'BLOCK',
          passed: false,
          acknowledgeable: false,
          detail: 'no document for en',
        },
      ],
      canPublish: false,
    });
    await fixture.whenStable();

    const row = root(fixture).querySelector('[data-testid="issue-release"] li')!;
    expect(row.textContent).toContain('admin.publications.checkDetail.FILE_PRESENT_PER_LANGUAGE');
    // What the check found is said under the sentence, in the reader's own
    // language. There is no hover repeating it in the server's English: that is
    // a second answer to the same question in a language nobody chose.
    expect(row.getAttribute('title')).toBeNull();
    expect(row.querySelector('[data-testid="issue-check-detail"]')!.textContent).toContain(
      'no document for en',
    );

    const remedy = [...row.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('admin.publications.issue.goToDocument'),
    );
    remedy!.click();
    await fixture.whenStable();
    expect(root(fixture).querySelector('[data-testid="issue-document-controls"]')).not.toBeNull();
  });

  /** A check this build has no sentence for reads as itself, never as its key. */
  it('renders an unregistered check code as the code', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set({
      rows: [{ code: 'SOMETHING_NEW', severity: 'WARN', passed: false, acknowledgeable: false }],
      canPublish: false,
    });
    await fixture.whenStable();

    expect(text(fixture)).toContain('SOMETHING_NEW');
    expect(text(fixture)).not.toContain('admin.publications.check.SOMETHING_NEW');
  });

  /**
   * What the check FOUND, under the sentence saying what it means.
   *
   * That line used to be the server's English — "2 member(s) cancelled or
   * expired after the cut-off, still included" — sitting under a Danish heading,
   * because the numbers and the words came down welded together. The server now
   * names the answer and sends its values apart, so the line is read in the same
   * language as the row above it.
   */
  it('says what a failing row was measured to be, in the reader language', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set({
      rows: [
        {
          code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
          severity: 'WARN',
          passed: false,
          acknowledgeable: true,
          detail: '2 member(s) cancelled or expired after the cut-off, still included',
          detailCode: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count',
          detailParams: { count: 2 },
        },
      ],
      canPublish: false,
    });
    await fixture.whenStable();

    const detail = root(fixture).querySelector('[data-testid="issue-check-detail"]')!;
    expect(detail.textContent).toContain(
      'admin.publications.checkDetail.CANCELLED_MEMBERS_ALIVE_AT_CUTOFF.count',
    );
    expect(detail.textContent).not.toContain('member(s)');
  });

  /** An answer this build has no words for still reads — as the server wrote it. */
  it('falls back to the sentence the server sent for an unworded answer', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.checklist.set({
      rows: [
        {
          code: 'MEMBERS_RESOLVED',
          severity: 'BLOCK',
          passed: false,
          acknowledgeable: false,
          detail: 'something the server measured',
          detailCode: 'MEMBERS_RESOLVED.somethingNew',
        },
      ],
      canPublish: false,
    });
    await fixture.whenStable();

    const detail = root(fixture).querySelector('[data-testid="issue-check-detail"]')!;
    expect(detail.textContent).toContain('something the server measured');
    expect(detail.textContent).not.toContain('admin.publications.checkDetail.MEMBERS_RESOLVED.');
  });

  /**
   * The trail arrives folded, with its length on the line.
   *
   * Open by default it is the longest thing in the rail and pushes the two cards
   * that carry an answer past the fold — and it answers a question ("who did
   * this, and when") that is asked after something looks wrong, not before. The
   * count is what makes folding it safe: a reader sees there IS a trail, and how
   * much of one, without opening it.
   */
  it('starts folded, saying how many entries are behind the line', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      { action: 'PUBLISHED', created: 2 },
      { action: 'CREATED', created: 1 },
    ]);
    await fixture.whenStable();

    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-history-toggle"]',
    )!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.textContent).toContain('admin.publications.issue.historyCount');
    expect(root(fixture).querySelector('[data-testid="issue-history"]')).toBeNull();

    await expandHistory(fixture);

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const history = root(fixture).querySelector('[data-testid="issue-history"]')!;
    expect(history.querySelectorAll('li').length).toBe(2);
  });

  /** Nothing to fold: an issue with no trail says so, with no line to open. */
  it('offers no toggle where nothing has happened yet', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([]);
    await fixture.whenStable();

    expect(root(fixture).querySelector('[data-testid="issue-history-toggle"]')).toBeNull();
    expect(text(fixture)).toContain('admin.publications.issue.noHistory');
  });

  /**
   * The history labels the actions the backend actually writes.
   *
   * A public window closed by a neighbour's release is written on every routine
   * weekly publish, and this panel is exactly where somebody looks to ask why a
   * publication left the site — so that line must not be a translation key.
   */
  it('labels a capped public window, a moved interval and a rename', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      { action: 'VISIBILITY_CAPPED', created: 1 },
      { action: 'INTERVAL_CHANGED', created: 2 },
      { action: 'NAME_CHANGED', created: 3 },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const history = root(fixture).querySelector('[data-testid="issue-history"]')!;
    const shown = history.textContent ?? '';
    expect(shown).toContain('admin.publications.action.VISIBILITY_CAPPED');
    expect(shown).toContain('admin.publications.action.INTERVAL_CHANGED');
    expect(shown).toContain('admin.publications.action.NAME_CHANGED');
  });

  /**
   * The arrival of an imported issue is ONE fact, said once.
   *
   * The action and the actor are the same thing on that line — an imported issue
   * was imported BY the import — and printed as two they read as two things
   * having happened to it. The identifier it carried out of the previous system
   * is not copy either: nobody reads a uuid off a history panel, and the one
   * person who needs it is matching it against a record elsewhere.
   */
  it('says an import once, with the identifier it arrived with in the hover text', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      {
        id: 7,
        action: 'IMPORTED',
        actorKind: 'IMPORT',
        created: 1,
        reason: 'legacy publication 703f5150-2233-4782-8468-7fddd254779b',
      },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const entry = root(fixture).querySelector('[data-testid="issue-history"] li')!;
    expect(entry.textContent).toContain('admin.publications.actor.IMPORT');
    expect(entry.textContent).not.toContain('admin.publications.action.IMPORTED');
    expect(entry.textContent).not.toContain('703f5150');
    expect(entry.querySelector('[title]')!.getAttribute('title')).toContain('703f5150');
  });

  /** Every other line keeps both halves, and its recorded reason in the open. */
  it('keeps the action, the actor and the reason on a line somebody wrote', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      {
        id: 8,
        action: 'RETIRED',
        actorLabel: 'R. Jensen',
        created: 1,
        reason: 'Superseded by an amended edition',
      },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const entry = root(fixture).querySelector('[data-testid="issue-history"] li')!;
    expect(entry.textContent).toContain('admin.publications.action.RETIRED');
    expect(entry.textContent).toContain('R. Jensen');
    expect(entry.textContent).toContain('Superseded by an amended edition');
  });

  /**
   * A transfer says WHERE the publication went, and names the desks.
   *
   * The action alone says only that it moved, which is what the reader already
   * knew. The ids are resolved through the domain catalogue and fall back to
   * themselves -- a domain that has since been deleted still has to be named,
   * and its id names it to anybody reading this trail.
   */
  it('names both desks on a transfer, falling back to the id for one it cannot resolve', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      {
        action: 'OWNER_TRANSFERRED',
        created: 1,
        reason: 'the annex desk maintains it now',
        detail: { from: 'niord-nm', to: 'niord-annex' },
      },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const shown = root(fixture).querySelector('[data-testid="issue-history"]')!.textContent ?? '';
    expect(shown).toContain('admin.publications.action.OWNER_TRANSFERRED');
    expect(shown).toContain('admin.publications.issue.ownerMoved');
    // The reason keeps the rendering every other recorded decision has.
    expect(shown).toContain('the annex desk maintains it now');
  });

  /** A transfer whose detail lost an end keeps the action and says no more. */
  it('says nothing about the desks when the detail carries only one end', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([{ action: 'OWNER_TRANSFERRED', created: 1, detail: { from: 'niord-nm' } }]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const shown = root(fixture).querySelector('[data-testid="issue-history"]')!.textContent ?? '';
    expect(shown).toContain('admin.publications.action.OWNER_TRANSFERRED');
    expect(shown).not.toContain('admin.publications.issue.ownerMoved');
  });

  /** And an action it has never heard of reads as the action, not as a key. */
  it('renders an unknown history action as the action', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([{ action: 'SOMETHING_NEW', created: 1 }]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const history = root(fixture).querySelector('[data-testid="issue-history"]')!;
    expect(history.textContent).toContain('SOMETHING_NEW');
    expect(history.textContent).not.toContain('admin.publications.action.SOMETHING_NEW');
  });

  /**
   * The release line keeps the checklist the release was taken against.
   *
   * The rail is not drawn on a published issue — it would re-ask its questions of
   * an issue that has already gone out — so without this the answers that were
   * true in the release, and the warning somebody ticked to get past, survive
   * nowhere a reader can reach. Closed until asked for: the ordinary visit is
   * reading what happened, not auditing it.
   */
  it('keeps the checklist a release was published against, behind one line', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      {
        id: 12,
        action: 'PUBLISHED',
        created: 1,
        detail: {
          checklist: [
            { code: 'ISSUE_OPEN', severity: 'OK', passed: true, acknowledged: false },
            {
              code: 'CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
              severity: 'WARN',
              passed: false,
              acknowledged: true,
            },
            { code: 'MEMBER_LIMIT', severity: 'WARN', passed: false, acknowledged: false },
          ],
        },
      },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-release-checks-toggle"]',
    )!;
    expect(toggle.textContent).toContain('admin.publications.issue.checklistAtPublish');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(root(fixture).querySelector('[data-testid="issue-release-checks"]')).toBeNull();

    toggle.click();
    await fixture.whenStable();

    const rows = [
      ...root(fixture).querySelectorAll('[data-testid="issue-release-checks"] li'),
    ] as HTMLElement[];
    expect(rows.length).toBe(3);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    // Every row speaks the vocabulary the rail already names its checks in.
    expect(rows[0].textContent).toContain('admin.publications.check.ISSUE_OPEN');
    // A passed row was never waved through, so it says nothing about that.
    expect(rows[0].textContent).not.toContain('admin.publications.issue.acknowledged');

    expect(rows[1].textContent).toContain(
      'admin.publications.check.CANCELLED_MEMBERS_ALIVE_AT_CUTOFF',
    );
    expect(rows[1].textContent).toContain('admin.publications.issue.acknowledged');
    expect(rows[2].textContent).toContain('admin.publications.issue.notAcknowledged');
  });

  /** Every other line, and a release written before the record existed, is untouched. */
  it('adds nothing to a line that carries no checklist', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      { id: 13, action: 'PUBLISHED', created: 1 },
      { id: 14, action: 'AMENDED', created: 2, detail: 'a corrected chart number' },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const history = root(fixture).querySelector('[data-testid="issue-history"]')!;
    expect(history.querySelector('[data-testid="issue-release-checks-toggle"]')).toBeNull();
    expect(history.textContent).not.toContain('admin.publications.issue.checklistAtPublish');
    expect(history.textContent).toContain('admin.publications.action.PUBLISHED');
    expect(history.textContent).toContain('admin.publications.action.AMENDED');
  });

  /**
   * A line that superseded a document offers it; the other lines offer nothing.
   *
   * The trail already said an amend replaced what people had read, and there was
   * no way to look at what it replaced. The affordance belongs on the line
   * itself for the same reason the archive is addressed by an audit entry: an
   * issue amended twice has two Danish documents behind it and only the entry
   * tells them apart.
   */
  it('offers the superseded document of each archived language, and only on those lines', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    // NEWEST FIRST, which is the order the server sends and the panel renders:
    // the amend is the recent entry and the creation is the old one.
    facade.audit.set([
      {
        id: 2,
        action: 'AMENDED',
        created: 2,
        archive: {
          auditEntryId: 2,
          files: [
            { lang: 'da', fileName: 'EfS-uge-27-2026.pdf' },
            { lang: 'en', fileName: 'NtM-week-27-2026.pdf' },
          ],
        },
      },
      { id: 1, action: 'CREATED', created: 1 },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const history = root(fixture).querySelector('[data-testid="issue-history"]')!;
    const rows = [...history.querySelectorAll('li')];
    expect(rows.length).toBe(2);
    expect(rows[1].querySelectorAll('button').length).toBe(0);

    const buttons = [...rows[0].querySelectorAll('button')];
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toContain('EfS-uge-27-2026.pdf');
    expect(buttons[1].textContent).toContain('NtM-week-27-2026.pdf');
    expect(rows[0].textContent).toContain('admin.publications.issue.archived');
  });

  /**
   * The archive is OPENED behind a one-time admin ticket, not fetched.
   *
   * The endpoint checks the role in code rather than by annotation precisely so
   * a ticket satisfies it, because a top-level navigation carries no bearer
   * token. Pulling the bytes over HttpClient would work and would throw that
   * away — and with it the browser's own viewer.
   */
  it('opens the superseded document behind a one-time admin ticket', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.audit.set([
      {
        id: 40122,
        action: 'FILE_REPLACED_MANUALLY',
        created: 1,
        archive: { auditEntryId: 40122, files: [{ lang: 'da', fileName: 'efs.pdf' }] },
      },
    ]);
    await fixture.whenStable();
    await expandHistory(fixture);

    const history = root(fixture).querySelector('[data-testid="issue-history"]')!;
    history.querySelector('button')!.click();
    await fixture.whenStable();

    expect(facade.archiveFileUrl).toHaveBeenCalledWith(40122, 'da');
    expect(downloads.openTicketed).toHaveBeenCalledWith(
      '/rest/publication-issues/issue/i-1/archive/40122/da',
      'admin',
    );
  });
});

describe('IssueDetail — releasing and withdrawing', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const panel = () => document.querySelector('.dialog-panel');

  function click(fixture: { nativeElement: unknown }, label: string) {
    const button = [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes(label),
    );
    button!.click();
  }

  /**
   * Publishing is a decision taken in a dialog, not on a click.
   *
   * Three things have to be answered before an issue goes out, and none of them
   * has a default that is right for every series.
   */
  it('opens the publish dialog rather than releasing straight away', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    expect(panel()).toBeNull();

    click(fixture, 'admin.publications.issue.publish');
    await fixture.whenStable();

    expect(panel()).not.toBeNull();
    expect(facade.publish).not.toHaveBeenCalled();
  });

  /**
   * The release runs in the facade, so the PAGE has to show it.
   *
   * The dialog can be closed over a render that takes tens of seconds — the
   * request does not go with it — and the issue behind it said nothing at all,
   * which reads as a press that never landed and invites a second one.
   */
  it('spins the rail’s publish control while the release is running', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    await fixture.whenStable();

    const publishButton = () =>
      [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
        b.textContent?.includes('admin.publications.issue.publish'),
      )!;

    expect(publishButton().getAttribute('aria-busy')).toBeNull();

    facade.working.set(true);
    facade.publishing.set(true);
    await fixture.whenStable();

    const button = publishButton();
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.disabled).toBe(true);
    expect(button.querySelector('app-icon')).not.toBeNull();
    // The label stays put: the control says what it does while it is doing it.
    expect(button.textContent).toContain('admin.publications.issue.publish');
  });

  /**
   * And the dialog behind it does not open either.
   *
   * The indicator is what the reader sees; the guard is what stops the second
   * release. It reads the same flag whether the work was started in this tab or
   * is the server's own record of one somebody else started — an issue being
   * rendered may not be handed a second release to run alongside the first.
   */
  it('refuses to open the release dialog while a release is running', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    await fixture.whenStable();

    facade.working.set(true);
    facade.publishing.set(true);
    await fixture.whenStable();

    click(fixture, 'admin.publications.issue.publish');
    await fixture.whenStable();

    expect(panel()).toBeNull();
    expect(facade.publish).not.toHaveBeenCalled();
  });

  /**
   * The count in the dialog is the list on the screen behind it.
   *
   * `memberCount` is a stored column, written when membership is frozen and
   * standing at `0` — not null — until then. Read off the issue, the dialog that
   * releases a native open issue opened on "0 messages" beside the 193 it had
   * resolved. While the issue is open the live list IS what would be frozen; the
   * column keeps answering for an issue that already was.
   */
  it('counts the live members in the release dialog of an open issue', async () => {
    const { fixture } = setup({ status: 'OPEN', memberCount: 0 }, SERIES_WITH_REPORT);
    facade.members.set(
      Array.from({ length: 193 }, (_, i) => ({ messageUid: `uid-${i}` }) as IssueMember),
    );
    await fixture.whenStable();

    click(fixture, 'admin.publications.issue.publish');
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(PublishDialog))
      .componentInstance as PublishDialog;
    expect(dialog.memberCount()).toBe(193);
  });

  /** What went out is the record; today's resolution answers a different question. */
  it('keeps the frozen count for an issue that has already been released', async () => {
    const { fixture } = setup({ status: 'PUBLISHED', memberCount: 20 }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    facade.members.set(MEMBERS);
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(PublishDialog))
      .componentInstance as PublishDialog;
    expect(dialog.memberCount()).toBe(20);
  });

  /** The warnings are ticked inside the dialog, and travel with the request. */
  it('sends what the dialog collected through the facade', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    facade.acknowledgeableWarnings.set([
      { code: 'FEW_MEMBERS', severity: 'WARN', passed: false, acknowledgeable: true },
    ]);
    await fixture.whenStable();

    click(fixture, 'admin.publications.issue.publish');
    await fixture.whenStable();

    document.querySelector<HTMLInputElement>('#publish-ack-FEW_MEMBERS')!.click();
    await fixture.whenStable();
    document.querySelector<HTMLButtonElement>('.dialog-panel .btn-primary')!.click();
    await fixture.whenStable();

    expect(facade.publish).toHaveBeenCalledTimes(1);
    const request = facade.publish.mock.calls[0][0] as PublishRequest;
    expect(request.acknowledgedWarnings).toEqual(['FEW_MEMBERS']);
    expect(request.cutoff).toBeUndefined();
  });

  /**
   * A CUT-OFF MOVED FROM THE DIALOG IS THE SAME WRITE THE EDIT PANEL MAKES.
   *
   * It has to be: the numbers on this screen all derive from the period on file,
   * so a release dialog that kept an instant of its own would show one set of
   * counts while the screen behind it showed another — for the same issue, at
   * the same moment.
   */
  it('moves the planned cut-off through the issue’s own edit path', async () => {
    const { fixture } = setup({ status: 'OPEN' }, SERIES_WITH_REPORT);
    await fixture.whenStable();
    const moved = Date.now() - 3 * 60 * 60 * 1000;

    const dialog = fixture.debugElement.query(By.directive(PublishDialog))
      .componentInstance as PublishDialog;
    dialog.plannedCutoffChanged.emit(moved);
    await fixture.whenStable();

    expect(facade.edit).toHaveBeenCalledWith({ intervalTo: moved });
  });

  /**
   * And it is refused wherever the panel's own edit would be.
   *
   * A published issue's period is settled; so is one belonging to another desk.
   * The dialog cannot know either, so the screen that owns the write answers.
   */
  it('writes no cut-off for an issue whose period is settled', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(PublishDialog))
      .componentInstance as PublishDialog;
    dialog.plannedCutoffChanged.emit(Date.now() - 60 * 60 * 1000);
    await fixture.whenStable();

    expect(facade.edit).not.toHaveBeenCalled();
  });

  /**
   * Withdrawing asks why.
   *
   * The reason is the only part of a withdrawal that survives into the history,
   * and the copy says what withdrawing actually does — the file stays where it
   * is, so citations keep resolving.
   */
  it('withdraws through a dialog carrying a reason and the public-effect copy', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    await fixture.whenStable();

    click(fixture, 'admin.publications.issue.retire');
    await fixture.whenStable();

    expect(panel()!.textContent).toContain('admin.publications.retire.effect');
    expect(facade.retire).not.toHaveBeenCalled();

    const box = document.querySelector<HTMLTextAreaElement>('#publication-reason')!;
    box.value = 'Superseded by an amended edition';
    box.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();

    document.querySelector<HTMLButtonElement>('.dialog-panel .btn-danger')!.click();
    await fixture.whenStable();

    // The reason goes out with a completion hook, and the dialog waits for it:
    // a refusal is shown where the reason was typed, not behind a closed dialog.
    expect(facade.retire).toHaveBeenCalledTimes(1);
    const [reason, onRetired] = facade.retire.mock.calls[0] as [string, () => void];
    expect(reason).toBe('Superseded by an amended edition');
    expect(panel()).not.toBeNull();

    onRetired();
    await fixture.whenStable();
    expect(panel()).toBeNull();
  });

  /** Cancelling withdraws nothing. */
  it('withdraws nothing when the reason dialog is cancelled', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, SERIES_WITH_REPORT);
    facade.checklist.set(undefined);
    await fixture.whenStable();

    click(fixture, 'admin.publications.issue.retire');
    await fixture.whenStable();

    const box = document.querySelector<HTMLTextAreaElement>('#publication-reason')!;
    box.value = 'Superseded by an amended edition';
    box.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();

    document.querySelector<HTMLButtonElement>('.dialog-panel .btn-tertiary')!.click();
    await fixture.whenStable();

    expect(facade.retire).not.toHaveBeenCalled();
    expect(panel()).toBeNull();
  });
});

/**
 * An issue whose series another desk owns is refused, not shown read-only.
 *
 * An issue has no owner of its own — it inherits its series', which is the only
 * place the accountability is recorded — so the answer comes off the series the
 * panel was already handed rather than a second lookup that could disagree. What
 * replaces the workbench is the same refusal the series and one-off screens
 * show: a panel of controls nobody may press explains less than one sentence
 * naming the desk that may.
 */
describe('IssueDetail — a series another desk owns', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const OWNED_ELSEWHERE = {
    seriesId: 's-1',
    reportId: 'ntm',
    domainId: 'niord-dk',
    cutoffDefault: 'RELEASE_MOMENT',
  } as PublicationSeries;

  /** A series that names no desk: every desk may open it and change it. */
  const OWNED_BY_NOBODY = {
    seriesId: 's-1',
    reportId: 'ntm',
    cutoffDefault: 'RELEASE_MOMENT',
  } as PublicationSeries;

  function refusal(fixture: { nativeElement: unknown }): HTMLElement | null {
    return root(fixture).querySelector('[data-testid="foreign-domain-refusal"]');
  }

  it('refuses the workbench and names the desk that owns the series', () => {
    const { fixture } = setup({ status: 'OPEN' }, OWNED_ELSEWHERE, 'niord-de');

    expect(refusal(fixture)).not.toBeNull();
    expect(text(fixture)).toContain('admin.publications.foreignDomain.title');
    // Nothing of the issue itself: no header, no rail, no member table.
    expect(root(fixture).querySelector('header')).toBeNull();
    expect(root(fixture).querySelector('table')).toBeNull();
  });

  /**
   * And nothing is read for it.
   *
   * The workbench is the one call this screen makes, and it answers members,
   * history, curation decisions and the release rail about an issue the reader
   * has just been told is not theirs to open.
   */
  it('asks for nothing behind a refused issue', () => {
    setup({ status: 'OPEN' }, OWNED_ELSEWHERE, 'niord-de');

    expect(facade.load).not.toHaveBeenCalled();
  });

  /** The one action: the desk it belongs to, applied the way the picker applies one. */
  it('switches to the owning desk when the refusal is acted on', () => {
    const { fixture } = setup({ status: 'OPEN' }, OWNED_ELSEWHERE, 'niord-de');

    root(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="foreign-domain-refusal"] button')!
      .click();

    // Keeping the address is the point of this one switch: the desk being chosen
    // is the one that can render this issue.
    expect(selectSessionDomain).toHaveBeenCalledWith('niord-dk', { keepAddress: true });
  });

  /**
   * A desk the reader holds no role in is named, and nothing is offered.
   *
   * The switch resolves the desk out of the catalogue the picker uses, so one
   * they are not a member of cannot be switched to.
   */
  it('offers no switch to a desk the reader is not a member of', () => {
    const { fixture } = setup({ status: 'OPEN' }, OWNED_ELSEWHERE, 'niord-de', [
      { domainId: 'niord-de', name: 'German bight' } as Domain,
    ]);

    expect(text(fixture)).toContain('admin.publications.foreignDomain.noAccess');
    expect(root(fixture).querySelector('[data-testid="foreign-domain-refusal"] button')).toBeNull();
  });

  /** A series that names no desk belongs to every desk, and its issue opens. */
  it('opens an issue whose series names no owning desk', () => {
    const { fixture } = setup({ status: 'OPEN' }, OWNED_BY_NOBODY, 'niord-de');

    expect(refusal(fixture)).toBeNull();
    expect(facade.load).toHaveBeenCalledWith('i-1');
  });

  /** From the desk that owns it, nothing about the panel has changed. */
  it('offers everything from the desk that owns the series', async () => {
    const { fixture } = setup({ status: 'OPEN' }, OWNED_ELSEWHERE, 'niord-dk');
    await fixture.whenStable();

    expect(refusal(fixture)).toBeNull();
    expect(facade.load).toHaveBeenCalledWith('i-1');
    const publish = [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('admin.publications.issue.publish'),
    );
    expect(publish?.disabled).toBe(false);
  });
});
