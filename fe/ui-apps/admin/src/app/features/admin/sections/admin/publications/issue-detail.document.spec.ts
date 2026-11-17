import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { wallClockIn } from '../../../../../shared/testing/zone-testing';
import type {
  IssueMember,
  PreviewMiss,
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import type { CitingMessage } from './publication-errors';
import { IssueDetail } from './issue-detail';
import {
  IssueDetailFacade,
  type IssueViewInstant,
  type PeriodProblem,
} from './issue-detail.facade';
import { SeriesCriteriaEditor } from './series-criteria-editor';

/**
 * The series the issue belongs to. None by default, which reads as a series that
 * generates its document; a test about an uploaded-document series sets one.
 */
const hostSeries = signal<PublicationSeries | undefined>(undefined);

@Component({
  imports: [IssueDetail],
  template: `<app-issue-detail publicId="i-1" [previewDebounceMs]="0" [series]="series()" />`,
})
class Host {
  protected readonly series = hostSeries;
}

interface DocumentRow {
  lang: string;
  fileName?: string;
  href?: string;
  uploaded: boolean;
  sticky: boolean;
  editable: boolean;
}

/** The protected surface these assertions reach for. */
interface Internals {
  editing(): boolean;
  editable(): boolean;
  editDirty(): boolean;
  toggleEditing(): void;
  close(): Promise<void>;
  editionInput(): string;
  onEditionInput(value: string): void;
  editionInvalid(): boolean;
  headerEdition(): string | undefined;
  saveEdit(): void;
  editingCriteria: { (): boolean; set: (v: boolean) => void };
  /** The two date boxes, reached the way the fields themselves write them. */
  intervalForm: {
    from: () => { value: { set: (value: Date | null) => void } };
    to: () => { value: { set: (value: Date | null) => void } };
  };
  criteriaOverridden(): boolean;
  effectiveCriteria(): unknown;
  onCriteriaChange(c: unknown): void;
  resetCriteria(): void;
  documentRows(): readonly DocumentRow[];
  anySticky(): boolean;
  linkDraft(lang: string): string;
  onLinkInput(lang: string, value: string): void;
  saveLink(lang: string): void;
  onFileChosen(lang: string, event: Event): void;
  fileNameConflict(lang: string): string | undefined;
  clearFile(lang: string): void;
  actionErrorKey(): string | undefined;
  previewErrorKey(): string | undefined;
}

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '/img/flags/da.png' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '/img/flags/en.png' },
];

/** The session domain's zone — half an hour west of the runner's, so the two never agree. */
const SESSION_ZONE = 'America/Halifax';

/**
 * An hour in the open time popover, by its label.
 *
 * Hours and minutes are separate columns that both hold e.g. "14", so the column
 * decides which is meant; the popover is body-level, like every overlay here.
 */
function hourOption(label: string): HTMLButtonElement | undefined {
  const column = document.querySelectorAll<HTMLElement>('.time-col')[0];
  return Array.from(column?.querySelectorAll<HTMLButtonElement>('.time-option') ?? []).find(
    (b) => b.textContent?.trim() === label,
  );
}

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
 * Angular caches the compiled component definition that overrideComponent
 * produces, so a fresh mock per test would leave the component bound to the
 * first one — the rows would keep answering with the previous test's issue.
 * A single object whose signals are reset per test cannot go stale that way.
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
  // Why the typed period cannot be read, when it cannot: no request goes out in
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

/**
 * The discard prompt's answer, decided per test.
 *
 * The real service resolves from a dialog the app-root host renders, which does
 * not exist here — an unstubbed prompt would hang instead of failing.
 */
let discardAnswer = true;
const confirmDiscard = vi.fn(() => Promise.resolve(discardAnswer));

/** Let the awaited prompt resolve and its continuation run. */
function settled(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * The desk the admin is sitting at.
 *
 * Undefined by default, and every fixture here names no domain -- which is
 * writable from everywhere, so the panel behaves exactly as it did before the
 * rule existed unless a test says otherwise.
 */
const sessionDomain = signal<Domain | undefined>(undefined);

function setup(issue?: Partial<PublicationIssue>, apiBaseUrl = '/rest') {
  vi.clearAllMocks();
  discardAnswer = true;
  hostSeries.set(undefined);
  // Every screen starts as of now with nothing late: the switch and the section
  // above the table are the exception, and a test that wants them says so.
  lateMembers.set([]);
  viewInstant.set('now');
  // Both hold across tests unless reset here: a screen left in the error state,
  // or on a period nothing can be read over, would decide the next test's markup.
  facade.error.set(false);
  // Every write starts each test idle: the card lists its rows, and every
  // control on the screen is offered, unless the test is about what one of the
  // long writes does while it runs.
  facade.working.set(false);
  facade.publishing.set(false);
  facade.amending.set(false);
  facade.periodProblem.set(undefined);
  facade.issue.set(
    issue
      ? ({ publicId: 'i-1', status: 'OPEN', descs: [], ...issue } as PublicationIssue)
      : undefined,
  );

  TestBed.configureTestingModule({
    providers: [
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('da'), modelLanguages: LANGUAGES },
      },
      {
        provide: LocaleFormatService,
        // uses12HourClock is read by the shared time picker, which renders as
        // soon as the edit panel is left open at the end of a test.
        useValue: {
          formatDate: () => '',
          formatDateTime: () => '',
          formatTime: () => '',
          formatWeekdayDateTime: () => '',
          uses12HourClock: () => false,
          // A desk half an hour west of the runner, so an interval read in the
          // browser's own zone lands on another clock — and, on the fixtures
          // below, another day.
          sessionTimeZone: () => SESSION_ZONE,
        },
      },
      { provide: DiscardConfirmService, useValue: { confirmDiscard } },
      // The publish dialog asks the server for the rail of a chosen cut-off, so
      // the API service it injects has to be constructible here.
      { provide: APP_CONFIG, useValue: { apiBaseUrl, appName: 'Niord', helpUrls: {} } },
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
        // Enough of the catalogue for the refusal a foreign series would draw.
        useValue: {
          sessionDomain,
          resolveById: (domainId: string) => ({ domainId, name: domainId }),
          editableDomains: signal<readonly Domain[]>([]),
          selectSessionDomain: vi.fn(),
        } as unknown as DomainFacade,
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
  fixture.detectChanges();
  const host = fixture.debugElement.children[0];
  const detail = host.componentInstance as unknown as Internals;
  // The component re-provides ConfirmService, so the pending confirm lives in
  // ITS injector rather than in the TestBed root.
  return { fixture, facade, detail, confirm: host.injector.get(ConfirmService) };
}

describe('IssueDetail — the document panel', () => {
  /**
   * A row per CONFIGURED language, not per existing desc.
   *
   * A language with no document is exactly what the panel exists to show. Listing
   * only the descs that exist renders a half-published issue as a complete one,
   * which is the state nobody notices until the public site is missing a PDF.
   */
  it('lists every configured language, including one the issue has no desc for', () => {
    const { detail } = setup({
      descs: [
        { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', href: '/rest/repo/file/x/EfS.pdf' },
      ],
    });

    const rows = detail.documentRows();
    expect(rows.map((r) => r.lang)).toEqual(['da', 'en']);
    expect(rows[0].fileName).toBe('EfS.pdf');
    expect(rows[1].href).toBeUndefined();
  });

  /**
   * The address the card hands over points at the BACKEND.
   *
   * The server reports it root-relative, which is what it is on the host that
   * serves it — and the app is not always served from that host. Followed or
   * copied verbatim from a page on another origin it reaches a host with no such
   * path, so the reader gets the app's own page where a PDF was promised.
   */
  it('points the link and the copy control at the API’s own origin', () => {
    const { fixture, detail } = setup(
      {
        descs: [
          { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', href: '/rest/repo/file/x/EfS.pdf' },
        ],
      },
      'https://niord.example.org/rest',
    );

    const absolute = 'https://niord.example.org/rest/repo/file/x/EfS.pdf';
    expect(detail.documentRows()[0].href).toBe(absolute);

    const row = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="issue-document-row"]',
    )!;
    expect(row.querySelector('a')!.getAttribute('href')).toBe(absolute);
    // The copy control hands over the same address the link points at.
    expect(row.querySelector('app-copy-link-button button')).not.toBeNull();
  });

  /**
   * While an amend runs, the card withholds the very files it is replacing.
   *
   * The rows would offer the outgoing documents for download, beside a control
   * that renders another one and a control that uploads over the write in
   * flight. What an admin would take from them is the version the amend exists
   * to supersede.
   */
  it('replaces the document rows with a sentence while an amend is running', async () => {
    const { fixture } = setup({
      status: 'PUBLISHED',
      descs: [
        { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', href: '/rest/repo/file/x/EfS.pdf' },
      ],
    });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('[data-testid="issue-document-row"]').length).toBeGreaterThan(0);

    facade.working.set(true);
    facade.amending.set(true);
    await fixture.whenStable();

    const pending = root.querySelector('[data-testid="issue-document-regenerating"]')!;
    expect(pending).not.toBeNull();
    expect(pending.textContent).toContain('admin.publications.issue.documentsRegenerating');
    expect(pending.querySelector('app-icon')).not.toBeNull();
    expect(root.querySelectorAll('[data-testid="issue-document-row"]').length).toBe(0);
    // The card keeps its place in the rail; only what it lists is withheld.
    expect(root.querySelector('[data-testid="issue-document"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="issue-document-toggle"]')).toBeNull();

    // And the refetch that follows the amend brings the new rows back.
    facade.working.set(false);
    facade.amending.set(false);
    await fixture.whenStable();
    expect(root.querySelector('[data-testid="issue-document-regenerating"]')).toBeNull();
    expect(root.querySelectorAll('[data-testid="issue-document-row"]').length).toBeGreaterThan(0);
  });

  /**
   * A publish writes the documents for the first time, and says so in the same
   * place — the card is where a reader looks for them, whichever action is
   * writing them.
   */
  it('says the documents are being generated while an open issue is published', async () => {
    const { fixture } = setup({
      status: 'OPEN',
      descs: [{ lang: 'da', name: 'Uge 1' }],
    });
    const root = fixture.nativeElement as HTMLElement;

    facade.working.set(true);
    facade.publishing.set(true);
    await fixture.whenStable();

    const pending = root.querySelector('[data-testid="issue-document-regenerating"]')!;
    expect(pending.textContent).toContain('admin.publications.issue.documentsGenerating');
    expect(root.querySelectorAll('[data-testid="issue-document-row"]').length).toBe(0);
  });

  /**
   * A row is a statement first and a pair of controls second.
   *
   * Flag, name, where the file came from — then the one control that hands the
   * document on. With the copy button between the name and the provenance the
   * row read as two halves of a sentence with a button wedged into it.
   */
  it('orders a document row as flag, name, provenance, then the copy control', () => {
    const { fixture } = setup({
      descs: [
        {
          lang: 'da',
          name: 'Uge 1',
          fileName: 'EfS.pdf',
          href: '/rest/repo/file/x/EfS.pdf',
          fileSource: 'UPLOADED',
        },
      ],
    });

    const row = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="issue-document-row"]',
    )!;
    expect([...row.children].map((child) => child.tagName.toLowerCase())).toEqual([
      'app-language-flag',
      'a',
      'span',
      'app-copy-link-button',
    ]);
    // A fact about the file, not a state it moves through: the pill wears
    // neither the lifecycle dot nor a status tint.
    expect(row.querySelector('.status-badge')!.className).toBe('status-badge is-plain ml-auto');
  });

  /**
   * The opener carries no chevron.
   *
   * It is the last line on the card and the only opener there; a glyph pointing
   * at the controls it opens is one mark more than the sentence needs, and the
   * state is still announced through `aria-expanded`.
   */
  it('offers the replace controls behind a bare line', () => {
    const { fixture } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    const toggle = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="issue-document-toggle"]',
    )!;
    expect(toggle.querySelector('app-icon')).toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.className).toContain('is-quiet');
  });

  /**
   * A language the series does not carry cannot receive a document.
   *
   * The server refuses -- there is no desc row to write into -- so offering the
   * buttons would produce NO_SUCH_LANGUAGE on a click that looked available.
   */
  it('marks a language with no desc as not editable', () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    expect(detail.documentRows().map((r) => r.editable)).toEqual([true, false]);
  });

  /** An uploaded file is distinguished from a generated one; sticky is surfaced once. */
  it('reports an uploaded file and whether it survives the next publish', () => {
    const { detail } = setup({
      descs: [
        {
          lang: 'da',
          name: 'Uge 1',
          fileName: 'EfS.pdf',
          fileSource: 'UPLOADED',
          fileSourceSticky: true,
        },
        { lang: 'en', name: 'Week 1', fileName: 'NtM.pdf', fileSource: 'GENERATED' },
      ],
    });

    expect(detail.documentRows()[0].uploaded).toBe(true);
    expect(detail.documentRows()[1].uploaded).toBe(false);
    expect(detail.anySticky()).toBe(true);
  });

  it('says nothing about stickiness when no file is sticky', () => {
    const { detail } = setup({
      descs: [{ lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', fileSource: 'GENERATED' }],
    });

    expect(detail.anySticky()).toBe(false);
  });

  // ------------------------------------------------------------------- links

  /** The box starts on the stored value, so an edit is an edit and not a retype. */
  it('seeds the link box from the issue', () => {
    const { detail } = setup({
      descs: [{ lang: 'da', name: 'Uge 1', link: 'https://example.test/a.pdf' }],
    });

    expect(detail.linkDraft('da')).toBe('https://example.test/a.pdf');
    expect(detail.linkDraft('en')).toBe('');
  });

  /**
   * The draft is dropped once submitted, so the refetched value takes over.
   *
   * The server normalises what it stores -- it trims, and turns blank into
   * nothing -- and a box still showing the raw typing would disagree with what
   * was actually saved, on the screen that just saved it.
   */
  it('drops the draft after saving so the stored value takes over', () => {
    const { detail, facade } = setup({
      descs: [{ lang: 'da', name: 'Uge 1', link: 'https://example.test/a.pdf' }],
    });

    detail.onLinkInput('da', '  https://example.test/b.pdf  ');
    expect(detail.linkDraft('da')).toBe('  https://example.test/b.pdf  ');

    detail.saveLink('da');
    expect(facade.setLink).toHaveBeenCalledWith('da', '  https://example.test/b.pdf  ');
    expect(detail.linkDraft('da')).toBe('https://example.test/a.pdf');
  });

  /** Each language edits its own link. */
  it('keeps one draft per language', () => {
    const { detail } = setup({
      descs: [
        { lang: 'da', name: 'Uge 1' },
        { lang: 'en', name: 'Week 1' },
      ],
    });

    detail.onLinkInput('da', 'https://example.test/da.pdf');
    expect(detail.linkDraft('en')).toBe('');
  });

  // ------------------------------------------------------------------- files

  /**
   * The file input is cleared after a choice.
   *
   * After a refused upload the obvious next action is to retry with the same
   * file, and an input that still holds it raises no change event -- so the
   * retry silently does nothing.
   */
  it('uploads the chosen file and clears the input so the same file can be retried', () => {
    const { detail, facade } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    const file = new File(['pdf'], 'EfS.pdf');
    const input = document.createElement('input');
    input.type = 'file';
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    const setValue = vi.spyOn(input, 'value', 'set');

    detail.onFileChosen('da', { target: input } as unknown as Event);

    expect(facade.uploadFile).toHaveBeenCalledWith('da', file);
    expect(setValue).toHaveBeenCalledWith('');
  });

  /** A cancelled file dialog uploads nothing. */
  it('uploads nothing when no file was chosen', () => {
    const { detail, facade } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    const input = document.createElement('input');
    input.type = 'file';
    Object.defineProperty(input, 'files', { value: [], configurable: true });

    detail.onFileChosen('da', { target: input } as unknown as Event);

    expect(facade.uploadFile).not.toHaveBeenCalled();
  });

  function chooseFile(detail: Internals, lang: string, name: string) {
    const input = document.createElement('input');
    input.type = 'file';
    Object.defineProperty(input, 'files', {
      value: [new File(['pdf'], name)],
      configurable: true,
    });
    detail.onFileChosen(lang, { target: input } as unknown as Event);
  }

  /**
   * A name another language already holds is refused before anything is sent.
   *
   * The issue's languages share one folder, so two languages carrying the same
   * file name is ONE document — and the server writes the bytes before it
   * notices, which means by the time the refusal comes back the other language's
   * file has already been replaced.
   */
  it('refuses a file name another language of this issue already uses', () => {
    const { detail, facade } = setup({
      descs: [
        { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf' },
        { lang: 'en', name: 'Week 1' },
      ],
    });

    chooseFile(detail, 'en', 'EfS.pdf');

    expect(facade.uploadFile).not.toHaveBeenCalled();
    expect(detail.fileNameConflict('en')).toBe('EfS.pdf');
  });

  /** Case-folded, because the repository folder is on a case-folding filesystem. */
  it('refuses a name that differs only in case', () => {
    const { detail, facade } = setup({
      descs: [
        { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf' },
        { lang: 'en', name: 'Week 1' },
      ],
    });

    chooseFile(detail, 'en', 'efs.PDF');

    expect(facade.uploadFile).not.toHaveBeenCalled();
  });

  /** Replacing a language's OWN file keeps its name; that is not a collision. */
  it('allows a language to re-upload under its own current file name', () => {
    const { detail, facade } = setup({
      descs: [
        { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf' },
        { lang: 'en', name: 'Week 1', fileName: 'NtM.pdf' },
      ],
    });

    chooseFile(detail, 'da', 'EfS.pdf');

    expect(facade.uploadFile).toHaveBeenCalledTimes(1);
    expect(detail.fileNameConflict('da')).toBeUndefined();
  });

  /** A distinct name goes through, and clears any refusal still on the row. */
  it('uploads a distinct name and forgets the previous refusal', () => {
    const { detail, facade } = setup({
      descs: [
        { lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf' },
        { lang: 'en', name: 'Week 1' },
      ],
    });

    chooseFile(detail, 'en', 'EfS.pdf');
    expect(detail.fileNameConflict('en')).toBe('EfS.pdf');

    chooseFile(detail, 'en', 'NtM.pdf');

    expect(facade.uploadFile).toHaveBeenCalledTimes(1);
    expect(detail.fileNameConflict('en')).toBeUndefined();
  });

  /**
   * Clearing an uploaded file asks first.
   *
   * It removes a document somebody uploaded by hand and lets the next release
   * render over the slot. There is no undo from this screen.
   */
  it('confirms before clearing an uploaded file', () => {
    const { detail, facade, confirm } = setup({
      descs: [{ lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', fileSource: 'UPLOADED' }],
    });

    detail.clearFile('da');

    expect(facade.clearFile).not.toHaveBeenCalled();
    expect(confirm.active()?.intent).toBe('destructive');

    confirm.active()!.accept();
    expect(facade.clearFile).toHaveBeenCalledWith('da');
  });

  /**
   * On a series that generates its document, the confirm says the next release
   * generates one for that language again, which is true there.
   */
  it('promises a generated document when the series generates one', () => {
    const { detail, confirm } = setup({
      descs: [{ lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', fileSource: 'UPLOADED' }],
    });
    hostSeries.set({ seriesId: 's-1', contentMode: 'GENERATED_FROM_QUERY' } as PublicationSeries);

    detail.clearFile('da');

    expect(confirm.active()?.message).toBe('admin.publications.issue.clearFileMessage');
  });

  /**
   * On a series whose document is uploaded by hand the release generates
   * nothing: it is refused for the missing file. The confirm used to promise a
   * generated document there too; it now says to upload another.
   */
  it('asks for another upload when the series takes an uploaded document', () => {
    const { fixture, detail, confirm } = setup({
      descs: [{ lang: 'da', name: 'Uge 1', fileName: 'EfS.pdf', fileSource: 'UPLOADED' }],
    });
    hostSeries.set({ seriesId: 's-1', contentMode: 'UPLOADED_FILE' } as PublicationSeries);
    fixture.detectChanges();

    detail.clearFile('da');

    expect(confirm.active()?.intent).toBe('destructive');
    expect(confirm.active()?.message).toBe('admin.publications.issue.clearUploadedFileMessage');
  });
});

describe('IssueDetail — the edit panel', () => {
  /**
   * The panel is closed until asked for.
   *
   * Moving an interval re-resolves the member list; a form sitting open on a
   * screen people come to in order to READ invites that by accident.
   */
  it('starts closed and opens on request', () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    expect(detail.editing()).toBe(false);
    detail.toggleEditing();
    expect(detail.editing()).toBe(true);
  });

  /** A published issue's name and interval are settled. */
  it('offers no edit on a published issue', () => {
    const { detail } = setup({ status: 'PUBLISHED', descs: [{ lang: 'da', name: 'Uge 1' }] });

    expect(detail.editable()).toBe(false);
  });

  /** Typing the edition back to what it already was is not a change. */
  it('sends nothing when the drafts match what is stored', () => {
    const { detail, facade } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('1');
    detail.saveEdit();

    expect(facade.edit).not.toHaveBeenCalled();
    expect(detail.editing()).toBe(false);
  });

  /** Cancelling drops the drafts, so reopening starts from the stored values. */
  it('drops the drafts when the panel is closed', async () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('2');
    detail.toggleEditing();
    await settled();
    detail.toggleEditing();

    expect(detail.editionInput()).toBe('1');
  });

  /**
   * Closing a panel with typed work asks first.
   *
   * The panel is a form like any other, and the only reason it is not a drawer is
   * that it sits inside one screen — which is not a reason to discard silently.
   */
  it('asks before discarding a typed edition', async () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('2');
    expect(detail.editDirty()).toBe(true);

    detail.toggleEditing();
    await settled();

    expect(confirmDiscard).toHaveBeenCalled();
    expect(detail.editing()).toBe(false);
  });

  /** And answering "no" leaves the panel exactly as it was. */
  it('keeps the panel open when the discard is declined', async () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });
    discardAnswer = false;

    detail.toggleEditing();
    detail.onEditionInput('2');
    detail.toggleEditing();
    await settled();

    expect(detail.editing()).toBe(true);
    expect(detail.editionInput()).toBe('2');
  });

  /** Leaving the issue is the same loss, so it passes the same gate. */
  it('asks before leaving the issue with unsaved edits', async () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });
    discardAnswer = false;

    detail.toggleEditing();
    detail.onEditionInput('2');
    await detail.close();

    expect(confirmDiscard).toHaveBeenCalled();
  });

  /** Typing the edition back to what it already was is not a change worth prompting over. */
  it('does not ask when nothing was actually changed', async () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('1');
    expect(detail.editDirty()).toBe(false);

    detail.toggleEditing();
    await settled();

    expect(confirmDiscard).not.toHaveBeenCalled();
    expect(detail.editing()).toBe(false);
  });

  /**
   * An interval bound is an INSTANT, typed as the wall clock the domain reads.
   *
   * 2026-03-05T03:45Z is 23:45 on the 4th where the session sits and 00:15 on
   * the 5th in the runner's own zone, so the two disagree about the day as well
   * as the clock: picking hour 14 stores the 4th at 14:45 for the domain, and a
   * field working in the browser's zone would have stored the 5th.
   */
  it('stores an interval bound as the domain’s wall clock, not the browser’s', async () => {
    const { fixture, detail, facade } = setup({
      intervalFrom: Date.UTC(2026, 2, 5, 3, 45),
      intervalTo: Date.UTC(2026, 2, 12, 2, 45),
      descs: [{ lang: 'da', name: 'Uge 1' }],
    });

    detail.toggleEditing();
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const from = fixture.nativeElement.querySelectorAll('app-date-time-field')[0] as HTMLElement;
    // The date trigger is the first button in the field, the time trigger the second.
    from.querySelectorAll('button')[1].click();
    await fixture.whenStable();
    hourOption('14')!.click();
    await fixture.whenStable();

    detail.saveEdit();

    // The instant that reads 14:45 on the 4th where the session sits. The upper
    // bound was not touched, so nothing but the one bound is sent.
    const expected = Date.UTC(2026, 2, 4, 18, 45);
    expect(wallClockIn(SESSION_ZONE, new Date(expected))).toBe('2026-03-04 14:45:00');
    expect(facade.edit).toHaveBeenCalledWith({ intervalFrom: expected });
  });
});

/**
 * The edition, on every series.
 *
 * It says "this period went out more than once", which is a fact about the
 * issue rather than about the numbering the series uses — the archive holds a
 * weekly whose week 41 came out twice, and until now the field was written once
 * by the import and readable nowhere but a column two of fourteen series have.
 */
describe('IssueDetail — the edition', () => {
  /** An issue that records none is showing the first one. */
  it('starts the box at 1 for an issue that records no edition', () => {
    const { detail } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();

    expect(detail.editionInput()).toBe('1');
    expect(detail.editDirty()).toBe(false);
  });

  it('starts the box at the edition the issue carries', () => {
    const { detail } = setup({ edition: '2', descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();

    expect(detail.editionInput()).toBe('2');
  });

  /** A default nobody typed is not a change, and is not written back. */
  it('sends nothing when the shown default was left alone', () => {
    const { detail, facade } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.saveEdit();

    expect(facade.edit).not.toHaveBeenCalled();
  });

  it('sends the edition when it was changed, and nothing else', () => {
    const { detail, facade } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('2');
    expect(detail.editDirty()).toBe(true);
    detail.saveEdit();

    expect(facade.edit).toHaveBeenCalledWith({ edition: '2' });
  });

  /** Free text, because the column is: the estate holds editions written as words. */
  it('sends a non-numeric edition as it was typed, trimmed', () => {
    const { detail, facade } = setup({ edition: '1', descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('  B  ');
    detail.saveEdit();

    expect(facade.edit).toHaveBeenCalledWith({ edition: 'B' });
  });

  /**
   * An emptied box is not a way to clear the field.
   *
   * The server refuses a blank edition, so the panel refuses it first: a Save
   * that goes out only to come back as a coded refusal teaches nothing the box
   * could have said in place.
   */
  it('refuses to save an emptied edition', () => {
    const { detail, facade } = setup({ descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('   ');

    expect(detail.editionInvalid()).toBe(true);
    detail.saveEdit();
    expect(facade.edit).not.toHaveBeenCalled();
    expect(detail.editing()).toBe(true);
  });

  /** Closing drops it, so reopening starts from what is stored. */
  it('drops the edition draft when the panel is closed', async () => {
    const { detail } = setup({ edition: '2', descs: [{ lang: 'da', name: 'Uge 1' }] });

    detail.toggleEditing();
    detail.onEditionInput('3');
    detail.toggleEditing();
    await settled();
    detail.toggleEditing();

    expect(detail.editionInput()).toBe('2');
  });

  /**
   * The header marks an edition that is not the first, on ANY series.
   *
   * Through the same rule the timeline strip uses, so the two cannot disagree
   * about what "the first edition" is.
   */
  it('marks a later edition in the header and leaves the first one unmarked', async () => {
    const { fixture, detail } = setup({ edition: '2', descs: [{ lang: 'da', name: 'Uge 1' }] });
    await fixture.whenStable();

    expect(detail.headerEdition()).toBe('2');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="issue-edition"]')
        ?.textContent,
    ).toContain('admin.publications.issue.editionMark');

    facade.issue.set({
      publicId: 'i-1',
      status: 'OPEN',
      edition: '1',
      descs: [{ lang: 'da', name: 'Uge 1' }],
    } as PublicationIssue);
    await fixture.whenStable();

    expect(detail.headerEdition()).toBeUndefined();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="issue-edition"]'),
    ).toBeNull();
  });

  /** An edition written as words cannot be compared to the first, so it stands. */
  it('marks a non-numeric edition in the header', () => {
    const { detail } = setup({ edition: 'B', descs: [{ lang: 'da', name: 'Uge 1' }] });

    expect(detail.headerEdition()).toBe('B');
  });
});

describe('IssueDetail — the criteria override', () => {
  const SERIES_CRITERIA = {
    criteria: [{ kind: 'messageSeries' as const, operator: 'IN' as const, values: ['dma-nm'] }],
  };
  const TAILORED = {
    criteria: [
      { kind: 'messageSeries' as const, operator: 'IN' as const, values: ['dma-nm', 'dma-fa'] },
    ],
  };

  /** An issue with no override shows the series' document, labelled as inherited. */
  it('shows the series criteria when the issue inherits', () => {
    const { detail } = setup({ seriesCriteria: SERIES_CRITERIA, descs: [] });

    expect(detail.effectiveCriteria()).toEqual(SERIES_CRITERIA);
    expect(detail.criteriaOverridden()).toBe(false);
  });

  /** An issue with one shows its own, labelled as tailored. */
  it('shows the override when the issue has one', () => {
    const { detail } = setup({
      seriesCriteria: SERIES_CRITERIA,
      criteriaOverride: TAILORED,
      criteriaOverridden: true,
      descs: [],
    });

    expect(detail.effectiveCriteria()).toEqual(TAILORED);
    expect(detail.criteriaOverridden()).toBe(true);
  });

  /** Editing to something new sends it as an override. */
  it('sends an edited document as an override', () => {
    const { detail, facade } = setup({ seriesCriteria: SERIES_CRITERIA, descs: [] });

    detail.toggleEditing();
    detail.onCriteriaChange(TAILORED);
    expect(detail.criteriaOverridden()).toBe(true);

    detail.saveEdit();
    expect(facade.edit).toHaveBeenCalledWith({ criteriaOverride: TAILORED });
  });

  /**
   * Editing back to the series' document sends a CLEAR, not a copy of it.
   *
   * Both end in the same stored state, but only the flag says "go back to
   * inheriting" — sending the document would be sending a copy of the series'
   * criteria, which is a different intention and one the server would then have
   * to guess at. It also matters later: an issue holding a copy stops tracking
   * the series, so a change to the series would silently not reach it.
   */
  it('sends a clear when the document is put back to the series', () => {
    const { detail, facade } = setup({
      seriesCriteria: SERIES_CRITERIA,
      criteriaOverride: TAILORED,
      criteriaOverridden: true,
      descs: [],
    });

    detail.toggleEditing();
    detail.resetCriteria();
    expect(detail.criteriaOverridden()).toBe(false);

    detail.saveEdit();
    expect(facade.edit).toHaveBeenCalledWith({ clearCriteriaOverride: true });
  });

  /**
   * Removing every criterion row is a hand-back, not an empty override.
   *
   * There is no such thing as a tailored issue that selects by nothing: with no
   * document, the issue selects exactly what its series selects. Read the other
   * way, the save set `criteriaOverride` to nothing — a field JSON drops — so the
   * request body came out empty, the server changed nothing, and the panel closed
   * reporting success.
   */
  it('sends a clear when every criterion row is removed', () => {
    const { detail, facade } = setup({
      seriesCriteria: SERIES_CRITERIA,
      criteriaOverride: TAILORED,
      criteriaOverridden: true,
      descs: [],
    });

    detail.toggleEditing();
    detail.onCriteriaChange(undefined);

    // And the badge says inherited, because that is what will be stored.
    expect(detail.criteriaOverridden()).toBe(false);

    detail.saveEdit();
    expect(facade.edit).toHaveBeenCalledWith({ clearCriteriaOverride: true });
  });

  /** Not touching the criteria sends nothing about them. */
  it('says nothing about the criteria when they were not edited', () => {
    const { detail, facade } = setup({
      seriesCriteria: SERIES_CRITERIA,
      criteriaOverride: TAILORED,
      criteriaOverridden: true,
      descs: [{ lang: 'da', name: 'Uge 1' }],
    });

    detail.toggleEditing();
    detail.onEditionInput('2');
    detail.saveEdit();

    expect(facade.edit).toHaveBeenCalledWith({ edition: '2' });
  });

  /** Closing the panel drops the criteria draft with everything else. */
  it('drops the criteria draft when the panel is closed', async () => {
    const { detail } = setup({ seriesCriteria: SERIES_CRITERIA, descs: [] });

    detail.toggleEditing();
    detail.onCriteriaChange(TAILORED);
    detail.toggleEditing();
    await settled();

    expect(detail.effectiveCriteria()).toEqual(SERIES_CRITERIA);
    expect(detail.criteriaOverridden()).toBe(false);
  });
});

/**
 * ONE period, and every number on the screen answers for it.
 *
 * The two date boxes in this panel are the period while it is open — not a form
 * whose effect begins at the save. Read the other way, the member list beside
 * them, the count above it and the criteria editor's own probe answered for the
 * period ON FILE, so the only way to find out what an edit would select was to
 * make it: 195 messages reported beside a list of 193, and nothing on screen
 * moving as the boxes were typed into.
 */
describe('IssueDetail — the period being edited', () => {
  /**
   * A week whose end is 23:45 on the 11th where the session sits, and already
   * the 12th in the runner's zone: the two moved to summer time together on 8 March.
   */
  const PERIOD = {
    intervalFrom: Date.UTC(2026, 2, 5, 3, 45),
    intervalTo: Date.UTC(2026, 2, 12, 2, 45),
    descs: [{ lang: 'da', name: 'Uge 1' }],
  };

  /** What the panel last told the read to answer for. */
  function lastPeriod(): unknown {
    const calls = facade.setPreviewPeriod.mock.calls;
    return calls[calls.length - 1]?.[0];
  }

  /**
   * Opening the panel puts the screen on the period IN THE BOXES.
   *
   * They are seeded from the issue, so the first answer is about the same window
   * as before — but asked as the panel's own question, which is what lets the
   * next keystroke move it. A reader who had switched to the planned cut-off is
   * also brought back here, because that reading is about the stored period and
   * the boxes have replaced it.
   */
  it('hands the period to the read as soon as the panel opens', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    fixture.autoDetectChanges();
    await fixture.whenStable();

    expect(lastPeriod()).toEqual({ from: PERIOD.intervalFrom, to: PERIOD.intervalTo });
  });

  /**
   * Moving the planned cut-off moves the whole screen with it.
   *
   * The instant is the typed one, so the members, the count, the rail and what
   * was left out all describe the period being proposed rather than the saved
   * one — before anybody presses Save.
   */
  it('re-reads over the typed cut-off as the box is changed', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    fixture.autoDetectChanges();
    await fixture.whenStable();

    // The upper bound is the second field; its time trigger is the second button.
    const to = fixture.nativeElement.querySelectorAll('app-date-time-field')[1] as HTMLElement;
    to.querySelectorAll('button')[1].click();
    await fixture.whenStable();
    hourOption('14')!.click();
    await fixture.whenStable();

    const expected = Date.UTC(2026, 2, 11, 17, 45);
    expect(wallClockIn(SESSION_ZONE, new Date(expected))).toBe('2026-03-11 14:45:00');
    expect(lastPeriod()).toEqual({ from: PERIOD.intervalFrom, to: expected });
  });

  /**
   * Cancelling gives the screen back to the issue as stored.
   *
   * Nothing was written, so there is nothing to keep: the read goes back to the
   * period on file at the instant the reader had chosen.
   */
  it('drops the proposed period when the panel is closed', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    fixture.autoDetectChanges();
    await fixture.whenStable();
    expect(lastPeriod()).not.toBeUndefined();

    detail.toggleEditing();
    await settled();
    await fixture.whenStable();

    expect(lastPeriod()).toBeUndefined();
  });

  /**
   * Closing an EMPTIED panel gives the screen back too.
   *
   * A panel whose boxes are both empty proposes a period with two open ends, and
   * a closed panel proposes none — different questions that a comparison of the
   * two ends alone reads as the same one. Compared that way the `undefined` a
   * close pushes was dropped as a repeat, and the screen stayed on the empty
   * period: the view switch withheld, and the reader's choice of instant ignored
   * until another issue was loaded.
   */
  it('gives the screen back when a panel with empty boxes is closed', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    fixture.autoDetectChanges();
    await fixture.whenStable();

    detail.intervalForm.from().value.set(null);
    detail.intervalForm.to().value.set(null);
    await fixture.whenStable();
    expect(lastPeriod()).toEqual({ from: undefined, to: undefined });

    detail.toggleEditing();
    await settled();
    await fixture.whenStable();

    expect(lastPeriod()).toBeUndefined();
  });

  /** And so does saving, which closes the panel and re-reads the stored issue. */
  it('drops the proposed period when the edit is saved', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    detail.onEditionInput('2');
    fixture.autoDetectChanges();
    await fixture.whenStable();

    detail.saveEdit();
    await fixture.whenStable();

    expect(lastPeriod()).toBeUndefined();
  });

  /**
   * The criteria count answers over the same window as the list beside it.
   *
   * Its own probe runs `(from, cut-off]`, so bound to the stored period it
   * reported what the SAVED issue selects next to a member list of the edited
   * one — two counts about one issue, neither of them wrong and only one of them
   * the answer to the question being asked.
   */
  it('probes the criteria over the edited period, not the stored one', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    detail.editingCriteria.set(true);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const to = fixture.nativeElement.querySelectorAll('app-date-time-field')[1] as HTMLElement;
    to.querySelectorAll('button')[1].click();
    await fixture.whenStable();
    hourOption('14')!.click();
    await fixture.whenStable();

    const editor = fixture.debugElement.query(By.directive(SeriesCriteriaEditor))
      .componentInstance as SeriesCriteriaEditor;
    expect(editor.intervalFrom()).toBe(PERIOD.intervalFrom);
    expect(editor.cutoff()).toBe(Date.UTC(2026, 2, 11, 17, 45));
  });

  /**
   * A period that has not closed yet closes NOW, for the probe as for the read.
   *
   * Nothing exists beyond this moment, so the two questions have one answer —
   * and the probe answering the other one is how a count of a period nobody can
   * have any messages for gets onto the screen beside a list that has them.
   */
  it('probes only as far as now for a cut-off in the future', async () => {
    const ahead = Date.now() + 30 * 24 * 60 * 60 * 1000;
    const { fixture, detail } = setup({ ...PERIOD, intervalTo: ahead });

    detail.toggleEditing();
    detail.editingCriteria.set(true);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const editor = fixture.debugElement.query(By.directive(SeriesCriteriaEditor))
      .componentInstance as SeriesCriteriaEditor;
    expect(editor.cutoff()).toBeLessThan(ahead);
    expect(editor.cutoff()).toBeLessThanOrEqual(Date.now());
  });

  /**
   * The clamp is against the clock NOW, not the moment the screen was opened.
   *
   * A panel opened at the start of a sitting and used an hour later clamped the
   * probe to an hour-old instant while the read beside it clamped to the real
   * now — so a message published in between was in the list and not in the
   * count, which is the same two-instant split in miniature.
   */
  it('clamps the criteria probe against the clock, not the moment the screen opened', async () => {
    const end = Date.UTC(2026, 2, 12, 1, 0);
    const hour = 60 * 60 * 1000;
    // Only the clock is faked: the panel's own push runs on real timers.
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date(end - hour));
      const { fixture, detail } = setup({ ...PERIOD, intervalTo: end });
      vi.setSystemTime(new Date(end + hour));

      detail.toggleEditing();
      detail.editingCriteria.set(true);
      fixture.autoDetectChanges();
      await fixture.whenStable();

      const editor = fixture.debugElement.query(By.directive(SeriesCriteriaEditor))
        .componentInstance as SeriesCriteriaEditor;
      expect(editor.cutoff()).toBe(end);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * A period that starts after it ends is named, and cannot be saved.
   *
   * Nothing is read over it either — the resolution would be refused — so the
   * boxes have to say why the numbers below have stopped moving. Before this,
   * moving the start date past the cut-off (the natural first move when carrying
   * a period forward) sent the request anyway and the refusal took the whole
   * panel off the screen, with the edit and the box that caused it going too.
   */
  it('names an inverted period beside the boxes and refuses to save it', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    facade.periodProblem.set('START_AFTER_CUTOFF');
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const panel = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="issue-edit-panel"]',
    )!;
    expect(panel.querySelector('[data-testid="issue-period-note"]')!.textContent).toContain(
      'admin.publications.issue.periodStartAfterCutoff',
    );
    expect(panel.querySelector<HTMLButtonElement>('button.btn-primary')!.disabled).toBe(true);

    detail.saveEdit();
    expect(facade.edit).not.toHaveBeenCalled();
  });

  /**
   * A period that has not begun is a different sentence, and a saveable one.
   *
   * Planning the next issue is the ordinary reason to open this panel. Nothing
   * can be counted in a window that has not started, which is worth saying — and
   * is not a reason to refuse the edit.
   */
  it('says a period that has not started has nothing in it, and still saves it', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    facade.periodProblem.set('START_IN_FUTURE');
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const panel = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="issue-edit-panel"]',
    )!;
    expect(panel.querySelector('[data-testid="issue-period-note"]')!.textContent).toContain(
      'admin.publications.issue.periodNotStarted',
    );
    expect(panel.querySelector<HTMLButtonElement>('button.btn-primary')!.disabled).toBe(false);
  });

  /**
   * A refused refresh leaves the panel standing.
   *
   * The banner used to REPLACE the screen, and the edit panel lives on that
   * screen: a refusal mid-edit took the date boxes and the Cancel button with
   * it, leaving Back — which discards the edit without asking — as the only way
   * out. The answer from before the refusal is still a whole answer, and the
   * banner above it says it is not current.
   */
  it('keeps the edit panel on screen when a refresh is refused', async () => {
    const { fixture, detail } = setup(PERIOD);

    detail.toggleEditing();
    facade.error.set(true);
    fixture.autoDetectChanges();
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('app-error-banner')).not.toBeNull();
    expect(root.querySelector('[data-testid="issue-edit-panel"]')).not.toBeNull();
    expect(detail.editing()).toBe(true);
  });
});

/**
 * ISSUE_NOT_OPEN said "Only an open issue can be previewed" for every
 * write it refused: an edit, a curation, an upload or a file clear on a page
 * that had gone stale. Those now read the neutral sentence, and the preview
 * keeps its own.
 */
describe('IssueDetail — an issue that is no longer open', () => {
  it('says the issue is no longer open when a write is refused for it', () => {
    const { fixture, detail } = setup({ descs: [] });
    facade.lastActionError.set('ISSUE_NOT_OPEN' as never);
    fixture.detectChanges();

    expect(detail.actionErrorKey()).toBe('admin.publications.error.issueNotOpen');
    facade.lastActionError.set(undefined);
  });

  it('keeps the preview sentence when the preview is refused for it', () => {
    const { fixture, detail } = setup({ descs: [] });
    facade.lastPreviewError.set({ status: 409, error: { code: 'ISSUE_NOT_OPEN' } } as never);
    fixture.detectChanges();

    expect(detail.previewErrorKey()).toBe('admin.publications.preview.notOpen');
    facade.lastPreviewError.set(undefined);
  });

  it('keeps the shared mapping for any other preview refusal', () => {
    const { fixture, detail } = setup({ descs: [] });
    facade.lastPreviewError.set({ status: 404, error: { code: 'NO_PREVIEW' } } as never);
    fixture.detectChanges();

    expect(detail.previewErrorKey()).toBe('admin.publications.error.noPreview');
    facade.lastPreviewError.set(undefined);
  });
});
