import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
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
  IssueAuditEntry,
  IssueMember,
  IssueOverride,
  PreviewMiss,
  PublicationIssue,
  PublicationSeries,
  PublishCheck,
} from '../../../services/api/publication-series-api.service';
import type { CitingMessage } from './publication-errors';
import { IssueDetail } from './issue-detail';
import {
  IssueDetailFacade,
  type IssueViewInstant,
  type PeriodProblem,
} from './issue-detail.facade';

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '/img/flags/da.png' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '/img/flags/en.png' },
];

@Component({
  imports: [IssueDetail],
  template: `<app-issue-detail
    publicId="i-1"
    [series]="series()"
    [previewDebounceMs]="0"
    (openIssue)="opened.set($event)"
    (closed)="left.set(true)"
  />`,
})
class Host {
  readonly series = signal<PublicationSeries | undefined>(undefined);
  readonly opened = signal<string | undefined>(undefined);
  readonly left = signal(false);
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
  previewRows: signal([]),
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
    source: 'OVERRIDE_INCLUDE',
    reasonCode: 'MANUAL_INCLUDE',
    curation: { author: 'rasmus', at: 1_700_000_000_000, reason: 'Requested by the author' },
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

function setup(
  issue: Partial<PublicationIssue>,
  options: {
    members?: readonly IssueMember[];
    audit?: readonly IssueAuditEntry[];
    overrides?: readonly IssueOverride[];
    series?: PublicationSeries;
    curatable?: boolean;
  } = {},
) {
  vi.clearAllMocks();
  facade.issue.set({ publicId: 'i-1', status: 'OPEN', descs: [], ...issue } as PublicationIssue);
  facade.members.set(options.members ?? MEMBERS);
  // Every screen starts as of now with nothing late: the switch and the section
  // above the table are the exception, and a test that wants them says so.
  lateMembers.set([]);
  viewInstant.set('now');
  facade.audit.set(options.audit ?? []);
  facade.overrides.set(options.overrides ?? []);
  facade.checklist.set(issue.status === 'OPEN' ? { rows: [], canPublish: true } : undefined);
  facade.curatable.set(options.curatable ?? issue.status !== 'PUBLISHED');
  facade.working.set(false);
  facade.publishing.set(false);
  facade.amending.set(false);
  facade.lastActionError.set(undefined);
  facade.lastActionMessage.set(undefined);
  facade.unacknowledgedWarnings.set([]);
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
      // `resolveById` names the desk a series belongs to; nothing here is in the
      // catalogue, so it falls back to the id — the case a deleted desk leaves.
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain,
          resolveById: () => undefined,
          // Enough of the catalogue for the refusal a foreign series would draw.
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
  fixture.componentInstance.series.set(options.series);
  fixture.autoDetectChanges();
  return { fixture };
}

type Fixture = ReturnType<typeof setup>['fixture'];

const root = (fixture: Fixture) => fixture.nativeElement as HTMLElement;
const text = (fixture: Fixture) => root(fixture).textContent ?? '';
const q = <T extends Element>(sel: string) => document.querySelector(sel) as T | null;

function button(fixture: Fixture, label: string): HTMLButtonElement | undefined {
  return [...root(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) =>
    b.textContent?.includes(label),
  );
}

/**
 * The lifecycle actions live in the `⋯` menu, so reaching one means opening it.
 *
 * Absent entirely when there is nothing in it: a menu button that opens onto
 * nothing is worse than no button, so a missing trigger is itself the assertion
 * that no action is on offer.
 */
async function menuItem(fixture: Fixture, label: string): Promise<HTMLElement | undefined> {
  const trigger = root(fixture).querySelector<HTMLButtonElement>(
    '[aria-label="admin.publications.actionsAriaLabel"]',
  );
  if (!trigger) {
    return undefined;
  }
  trigger.click();
  await fixture.whenStable();
  return [...document.querySelectorAll<HTMLElement>('.cdk-overlay-container .menu-item')].find(
    (el) => el.textContent?.includes(label),
  );
}

/** The checkboxes in the member table, in row order. Header box excluded. */
function rowBoxes(fixture: Fixture): HTMLInputElement[] {
  return [...root(fixture).querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')];
}

async function reason(fixture: Fixture, value: string) {
  const field = q<HTMLTextAreaElement>('#publication-reason')!;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await fixture.whenStable();
}

async function confirmDialog(fixture: Fixture) {
  q<HTMLButtonElement>('.dialog-panel .btn-danger')!.click();
  await fixture.whenStable();
}

describe('IssueDetail — curation', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * The actions column is announced and not printed.
   *
   * A word above a column of one-word buttons reads as a value the rows carry
   * rather than as what pressing one does. Dropping the heading outright would
   * leave a screen reader announcing "column six", so the word stays and only
   * the ink goes.
   */
  it('heads the actions column for a screen reader alone', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const header = root(fixture).querySelector('[data-testid="issue-members"] thead');
    const actions = [...header!.querySelectorAll('th')].at(-1)!;

    // The whole of the cell IS the announcement: nothing sits outside the span.
    expect(actions.children.length).toBe(1);
    expect(actions.querySelector('.sr-only')?.textContent?.trim()).toBe(
      'admin.publications.issue.actions',
    );
    expect(actions.textContent?.trim()).toBe('admin.publications.issue.actions');
  });

  /**
   * The why-line on an override row names the person, the moment and the reason.
   *
   * Read from the curation record the server sends. A row the query selected has
   * no such line: nobody decided it.
   */
  it('shows author, time and reason on a row somebody decided by hand', () => {
    const { fixture } = setup({ status: 'OPEN' });

    expect(text(fixture)).toContain('rasmus · 14.05.2026 09.35 · Requested by the author');
  });

  /** An older backend sends no curation object; the stored note is the fallback. */
  it('falls back to the stored note when no curation record is sent', () => {
    const { fixture } = setup(
      { status: 'OPEN' },
      {
        members: [
          {
            messageUid: 'uid-c',
            frozenShortId: 'NM-103-26',
            source: 'IMPORTED',
            reasonCode: 'IMPORTED',
            reasonNote: 'Carried over from the legacy issue',
          },
        ],
      },
    );

    expect(text(fixture)).toContain('Carried over from the legacy issue');
  });

  /** Excluding asks why first, and sends the decision only when the dialog is answered. */
  it('excludes one row through the reason dialog', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    button(fixture, 'admin.publications.issue.exclude')!.click();
    await fixture.whenStable();
    await reason(fixture, 'Withdrawn by the author');
    await confirmDialog(fixture);

    expect(facade.excludeMembers).toHaveBeenCalledWith(
      ['uid-a'],
      'Withdrawn by the author',
      expect.any(Function),
    );
  });

  /** A selection is ONE decision over a list, not one request per row. */
  it('excludes every selected row in a single decision', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    for (const box of rowBoxes(fixture)) {
      box.click();
    }
    await fixture.whenStable();

    const bulk = button(fixture, 'admin.publications.curation.excludeSelected')!;
    expect(bulk.disabled).toBe(false);
    bulk.click();
    await fixture.whenStable();
    await reason(fixture, 'Superseded before release');
    await confirmDialog(fixture);

    expect(facade.excludeMembers).toHaveBeenCalledWith(
      ['uid-a', 'uid-b'],
      'Superseded before release',
      expect.any(Function),
    );
  });

  /** Absent, not disabled: a bulk button over an empty selection acts on nothing. */
  it('offers no bulk decision while nothing is selected', () => {
    const { fixture } = setup({ status: 'OPEN' });

    expect(button(fixture, 'admin.publications.curation.excludeSelected')).toBeUndefined();
  });

  /** Including resolves whatever the admin had to hand — a short id, or a uid. */
  it('includes by the message id that was typed', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    button(fixture, 'admin.publications.curation.include')!.click();
    await fixture.whenStable();

    const id = q<HTMLInputElement>('#publication-include-message')!;
    id.value = 'NM-123-24';
    id.dispatchEvent(new Event('input', { bubbles: true }));
    const why = q<HTMLTextAreaElement>('#publication-include-reason')!;
    why.value = 'Requested by the author';
    why.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();

    q<HTMLButtonElement>('.dialog-panel .btn-primary')!.click();
    await fixture.whenStable();

    expect(facade.includeByMessageId).toHaveBeenCalledWith(
      'NM-123-24',
      'Requested by the author',
      expect.any(Function),
    );
  });

  /** Only a row that stands on a decision has a decision to withdraw. */
  it('offers a withdrawal on an override row and not on a resolved one', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const withdrawals = [
      ...root(fixture).querySelectorAll<HTMLButtonElement>('tbody button'),
    ].filter((b) => b.textContent?.includes('admin.publications.curation.clear'));
    expect(withdrawals).toHaveLength(1);
  });

  /**
   * An exclusion is in no member list, so it is read back from the decisions the
   * server still holds — otherwise a curator who excluded the wrong message has
   * nothing to undo.
   */
  it('lists the exclusions that still stand, each withdrawable', async () => {
    const { fixture } = setup(
      { status: 'OPEN' },
      {
        overrides: [
          {
            messageUid: 'uid-z',
            messageId: 'NM-815-26',
            title: 'Buoy off station',
            kind: 'EXCLUDE',
            author: 'rasmus',
            at: 1_700_000_000_000,
            reason: 'Withdrawn by the author',
          },
        ],
      },
    );

    // In the member table itself, struck through: "in this issue" and "taken out
    // of it" are one list to anybody checking a document. Named the way a member
    // row is named — by the short id, never by the uid that addresses it.
    const row = root(fixture).querySelector('[data-testid="issue-exclusion"]')!;
    expect(row.textContent).toContain('NM-815-26');
    expect(row.textContent).toContain('Buoy off station');
    expect(row.textContent).not.toContain('uid-z');
    expect(row.textContent).toContain('admin.publications.curation.excludedBy');
    expect(row.querySelector('.line-through')).not.toBeNull();

    row.querySelector<HTMLButtonElement>('button')!.click();
    await fixture.whenStable();
    await reason(fixture, 'Excluded in error');
    await confirmDialog(fixture);

    expect(facade.clearOverride).toHaveBeenCalledWith(
      'uid-z',
      'Excluded in error',
      expect.any(Function),
    );
  });

  /**
   * An include is not listed here: it already has a row.
   *
   * A message somebody forced in IS a member, so its why-line and its withdrawal
   * sit on the member row — repeating it under "kept out" would say the opposite
   * of what the decision was.
   */
  it('leaves a standing include out of the kept-out list', () => {
    const { fixture } = setup(
      { status: 'OPEN' },
      {
        overrides: [
          { messageUid: 'uid-b', kind: 'INCLUDE', author: 'rasmus', reason: 'Requested' },
        ],
      },
    );

    expect(root(fixture).querySelector('[data-testid="issue-exclusion"]')).toBeNull();
  });

  /** A frozen member set is not curated: the issue has already gone out. */
  it('offers no curation once the member set is frozen', () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });

    expect(button(fixture, 'admin.publications.curation.include')).toBeUndefined();
    expect(rowBoxes(fixture)).toHaveLength(0);
  });
});

describe('IssueDetail — amend and new edition', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * An open issue has neither to offer.
   *
   * The menu is still rendered — an open issue can be removed — so a missing row
   * is what says the action is not on offer.
   */
  it('offers neither on an open issue', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    // One open, both read: clicking the trigger again would toggle the menu shut.
    await menuItem(fixture, 'admin.publications.amend.action');
    const labels = [...document.querySelectorAll('.cdk-overlay-container .menu-item')].map(
      (el) => el.textContent ?? '',
    );
    expect(labels.some((l) => l.includes('admin.publications.amend.action'))).toBe(false);
    expect(labels.some((l) => l.includes('admin.publications.newEdition.action'))).toBe(false);
  });

  it('offers both on a published issue', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });

    // One open, both read: clicking the trigger again would toggle the menu shut.
    await menuItem(fixture, 'admin.publications.amend.action');
    const labels = [...document.querySelectorAll('.cdk-overlay-container .menu-item')].map(
      (el) => el.textContent ?? '',
    );
    expect(labels.some((l) => l.includes('admin.publications.amend.action'))).toBe(true);
    expect(labels.some((l) => l.includes('admin.publications.newEdition.action'))).toBe(true);
  });

  /**
   * The menu is a computed, so a label it cannot track is a label that never
   * changes again. Built from a one-shot translate call, the whole menu stayed in
   * whichever language the panel first opened in.
   */
  it('re-labels itself when the reader switches language', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });
    const transloco = TestBed.inject(TranslocoService);
    transloco.setTranslation({ 'admin.publications.amend.action': 'Amend' }, 'en', { merge: true });
    // Warm the (stub) loader for the second language first: its empty bundle
    // lands on whatever is already there, so a seed written before the load is
    // wiped by it.
    transloco.load('da').subscribe();
    transloco.setTranslation({ 'admin.publications.amend.action': 'Ret' }, 'da', { merge: true });
    await fixture.whenStable();

    const labels = () =>
      [...document.querySelectorAll('.cdk-overlay-container .menu-item')].map(
        (el) => el.textContent ?? '',
      );

    await menuItem(fixture, 'Amend');
    expect(labels().some((l) => l.includes('Amend'))).toBe(true);

    transloco.setActiveLang('da');
    await fixture.whenStable();

    expect(labels().some((l) => l.includes('Ret'))).toBe(true);
    expect(labels().some((l) => l.includes('Amend'))).toBe(false);
  });

  /**
   * An imported issue's document is what people cited. Hidden rather than offered
   * and refused: re-deciding it is not a correction but a rewrite of the record.
   */
  it('hides the amend on an issue carried in from the archive', async () => {
    const { fixture } = setup(
      { status: 'PUBLISHED', legacyPublicationId: 'legacy-1' },
      { curatable: false },
    );

    expect(await menuItem(fixture, 'admin.publications.amend.action')).toBeUndefined();
  });

  /**
   * An issue that is already being rewritten is not offered a second rewrite.
   *
   * The flag covers the amend this tab started AND the one the server says is
   * running, so the guard holds for a reloaded page, a second tab and another
   * admin alike — two amends over one issue would archive and regenerate the
   * same documents at once, and the loser would overwrite the winner's files.
   */
  it('offers no amend while a release or an amend is running on the issue', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });
    facade.working.set(true);
    facade.amending.set(true);
    await fixture.whenStable();

    expect(await menuItem(fixture, 'admin.publications.amend.action')).toBeUndefined();
  });

  it('hides the amend when the member snapshot was frozen at import', async () => {
    const { fixture } = setup(
      { status: 'PUBLISHED', membershipProvenance: 'IMPORTED' },
      { curatable: false },
    );

    expect(await menuItem(fixture, 'admin.publications.amend.action')).toBeUndefined();
  });

  /** A one-off holds exactly one issue, so there is no second edition to open. */
  it('offers no new edition on a one-off publication', async () => {
    const { fixture } = setup(
      { status: 'PUBLISHED' },
      { curatable: false, series: { seriesId: 's-1', kind: 'ONE_OFF' } as PublicationSeries },
    );

    expect(await menuItem(fixture, 'admin.publications.newEdition.action')).toBeUndefined();
  });

  /**
   * A series that opens its own next issue is not offered a second one.
   *
   * The successor already exists by the time this screen is looking at the
   * published predecessor, and creating another would be creating a period that
   * overlaps the one the release just opened.
   */
  it('offers no new edition where publishing already opened one', async () => {
    const { fixture } = setup(
      { status: 'PUBLISHED' },
      {
        curatable: false,
        series: {
          seriesId: 's-1',
          cadence: 'WEEKLY',
          nextIssueCreation: 'AUTO_ON_PUBLISH',
        } as PublicationSeries,
      },
    );

    expect(await menuItem(fixture, 'admin.publications.newEdition.action')).toBeUndefined();
  });

  it('amends with what the dialog collected', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });

    (await menuItem(fixture, 'admin.publications.amend.action'))!.click();
    await fixture.whenStable();

    const why = q<HTMLTextAreaElement>('#publication-amend-reason')!;
    why.value = 'Wrong chart number';
    why.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();

    q<HTMLButtonElement>('.dialog-panel .btn-primary')!.click();
    await fixture.whenStable();

    expect(facade.amend).toHaveBeenCalledWith(
      { reason: 'Wrong chart number', acknowledgedWarnings: [] },
      expect.any(Function),
    );
  });

  /**
   * The successor is a different issue at a different address, so the panel hands
   * over rather than staying where it was.
   */
  it('opens the successor the new edition created', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });
    facade.newEdition.mockImplementation((onCreated: (publicId: string) => void) =>
      onCreated('i-2'),
    );

    (await menuItem(fixture, 'admin.publications.newEdition.action'))!.click();
    await fixture.whenStable();

    // The confirm surface renders its accept button in the overlay.
    const accept = [...document.querySelectorAll<HTMLButtonElement>('.confirm-panel button')]
      .filter((b) => !b.classList.contains('btn-tertiary'))
      .pop();
    accept!.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.opened()).toBe('i-2');
  });
});

/**
 * What the criteria SAW and did not take.
 *
 * A different question from the standing exclusions: those are decisions
 * somebody recorded, these are candidates the query dropped and the reason it
 * dropped them. It is the answer to "why is this message not in the issue",
 * which a member list can never give — it can only show what it contains.
 */
describe('IssueDetail — the omissions panel', () => {
  afterEach(() => {
    facade.omissions.set([]);
    facade.omissionCount.set(0);
    facade.omissionsBounded.set(false);
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  const MISSES: readonly PreviewMiss[] = [
    { messageUid: 'uid-x', messageId: 'NM-815-26', code: 'AFTER_CUTOFF' },
    { messageUid: 'uid-y', messageId: 'NM-816-26', code: 'STATUS_NOT_PUBLIC' },
  ];

  /**
   * Nothing dropped is an ANSWER, and it is given rather than withheld.
   *
   * A foot that disappears at zero leaves the question open: a reader cannot
   * tell a query that dropped nothing from a screen that never asked. The count
   * stands and the disclosure is inert, with the hint beside it saying what the
   * section would hold.
   */
  it('keeps the foot, inert, when the criteria dropped nothing', () => {
    const { fixture } = setup({ status: 'OPEN' });

    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-omissions-toggle"]',
    )!;
    expect(toggle).not.toBeNull();
    expect(toggle.textContent).toContain('(0)');
    expect(toggle.disabled).toBe(true);
    expect(toggle.getAttribute('aria-expanded')).toBeNull();
    expect(text(fixture)).toContain('admin.publications.issue.omittedHint');
    expect(root(fixture).querySelector('[data-testid="issue-omissions"]')).toBeNull();
  });

  /**
   * A published issue never asked the question.
   *
   * Its member list is the record of what went out; re-running the query against
   * today's corpus would answer a resolution that never happened, so there is no
   * count to report and no foot to report it in.
   */
  it('renders no foot on an issue whose members are frozen', () => {
    const { fixture } = setup({ status: 'PUBLISHED' });

    expect(root(fixture).querySelector('[data-testid="issue-omissions-toggle"]')).toBeNull();
  });

  /**
   * Collapsed, with the count on the disclosure.
   *
   * On a healthy issue this is a long list of messages that were never
   * candidates for it, so the count answers the question and the rows are there
   * for the reader who wants them.
   */
  it('offers a collapsed section labelled with the count', async () => {
    facade.omissions.set(MISSES);
    facade.omissionCount.set(2);
    const { fixture } = setup({ status: 'OPEN' });
    await fixture.whenStable();

    const toggle = root(fixture).querySelector<HTMLButtonElement>(
      '[data-testid="issue-omissions-toggle"]',
    );
    expect(toggle).not.toBeNull();
    expect(toggle!.textContent).toContain('admin.publications.issue.omitted');
    expect(toggle!.textContent).toContain('(2)');
    expect(toggle!.getAttribute('aria-expanded')).toBe('false');
    expect(root(fixture).querySelector('[data-testid="issue-omissions"]')).toBeNull();
  });

  /**
   * Opened, each row names the message the way everything else on the screen
   * does — by its short id, and as a link into it.
   *
   * A uid answers "which row is this" and nothing else. The question the panel
   * exists for is "why is THAT notice not in the issue", and it is asked about a
   * message somebody can name and wants to open.
   */
  it('lists each omitted message by short id, linked, with the reason', async () => {
    facade.omissions.set(MISSES);
    facade.omissionCount.set(2);
    const { fixture } = setup({ status: 'OPEN' });
    await fixture.whenStable();

    root(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="issue-omissions-toggle"]')!
      .click();
    await fixture.whenStable();

    const items = [...root(fixture).querySelectorAll('[data-testid="issue-omissions"] li')];
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain('NM-815-26');
    expect(items[0].textContent).toContain('admin.publications.miss.AFTER_CUTOFF');
    expect(items[1].textContent).toContain('admin.publications.miss.STATUS_NOT_PUBLIC');

    // The same address a member row offers, so the two rows behave alike.
    const link = items[0].querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/editor/edit/uid-x');
  });

  /**
   * A message with no short id is still a finding, and still has a name.
   *
   * The TITLE stands in the id's place and the uid opens it — a uuid on the
   * screen names nothing to the reader, and "why is that notice not in the
   * issue" is asked about a message somebody can recognise.
   */
  it('names an unnumbered omission by its title and marks it as not numbered', async () => {
    facade.omissions.set([
      { messageUid: 'uid-x', title: 'Buoy off station', code: 'AFTER_CUTOFF' },
    ]);
    facade.omissionCount.set(1);
    const { fixture } = setup({ status: 'OPEN' });
    await fixture.whenStable();

    root(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="issue-omissions-toggle"]')!
      .click();
    await fixture.whenStable();

    const row = root(fixture).querySelector('[data-testid="issue-omissions"] li')!;
    expect(row.textContent).toContain('Buoy off station');
    expect(row.textContent).toContain('admin.publications.messageRef.notNumbered');
    expect(row.textContent).not.toContain('uid-x');
    // Still one click from the message: the editor resolves its id by uid
    // before it tries the short ids.
    expect(row.querySelector('a')!.getAttribute('href')).toBe('/editor/edit/uid-x');
  });

  /** A code this build has no words for is named as unknown, never as a key path. */
  it('names an unrecognised reason as unknown', async () => {
    facade.omissions.set([{ messageUid: 'uid-z', code: 'SOMETHING_NEW' }]);
    facade.omissionCount.set(1);
    const { fixture } = setup({ status: 'OPEN' });
    await fixture.whenStable();

    root(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="issue-omissions-toggle"]')!
      .click();
    await fixture.whenStable();

    expect(text(fixture)).toContain('admin.publications.miss.UNKNOWN');
    expect(text(fixture)).not.toContain('miss.SOMETHING_NEW');
  });

  /**
   * The rows are a capped sample and the count is the real number.
   *
   * Without the line a reader concludes that what they can see is all there was.
   */
  it('says the rows are a sample when the server counted more than it sent', async () => {
    facade.omissions.set(MISSES);
    facade.omissionCount.set(137);
    facade.omissionsBounded.set(true);
    const { fixture } = setup({ status: 'OPEN' });
    await fixture.whenStable();

    root(fixture)
      .querySelector<HTMLButtonElement>('[data-testid="issue-omissions-toggle"]')!
      .click();
    await fixture.whenStable();

    expect(text(fixture)).toContain('admin.publications.issue.omittedTruncated');
  });
});

/**
 * Removing an issue outright, which is not the same action as withdrawing one.
 *
 * A withdrawal takes an issue off the public list and leaves the document where
 * citations already point at it. This removes the issue and everything recorded
 * against it, so it is offered on exactly the two statuses where nothing can
 * have gone out under it — and never on a published one, where the step that
 * makes the question askable is the withdrawal.
 */
describe('IssueDetail — removing an issue', () => {
  afterEach(() => {
    sessionDomain.set(undefined);
    facade.citingMessages.set([]);
    facade.citingCount.set(0);
    facade.citationsBounded.set(false);
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('offers the removal on an open issue', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    expect(await menuItem(fixture, 'admin.publications.issue.delete')).toBeDefined();
  });

  it('offers the removal on a withdrawn issue', async () => {
    const { fixture } = setup({ status: 'RETIRED' }, { curatable: false });

    expect(await menuItem(fixture, 'admin.publications.issue.delete')).toBeDefined();
  });

  /**
   * A published issue is never removable, and the row is ABSENT rather than
   * present and refused: the menu still opens — amend and the successor edition
   * are there — so a missing row is the assertion that the action is not on
   * offer at all.
   */
  it('never offers the removal on a published issue', async () => {
    const { fixture } = setup({ status: 'PUBLISHED' }, { curatable: false });

    await menuItem(fixture, 'admin.publications.amend.action');
    const labels = [...document.querySelectorAll('.cdk-overlay-container .menu-item')].map(
      (el) => el.textContent ?? '',
    );
    expect(labels.some((l) => l.includes('admin.publications.amend.action'))).toBe(true);
    expect(labels.some((l) => l.includes('admin.publications.issue.delete'))).toBe(false);
  });

  /**
   * From another desk there is no menu at all. An issue inherits the series'
   * owner, which is where the accountability for the publication is recorded,
   * and another desk's issue is refused in place of the whole workbench.
   */
  it('withholds the removal from a domain that does not own the series', async () => {
    sessionDomain.set({ domainId: 'niord-de' } as Domain);
    const { fixture } = setup(
      { status: 'OPEN' },
      { series: { seriesId: 's-1', domainId: 'niord-dk' } as PublicationSeries },
    );

    expect(await menuItem(fixture, 'admin.publications.issue.delete')).toBeUndefined();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="foreign-domain-refusal"]',
      ),
    ).not.toBeNull();
  });

  /** The house destructive confirm — a red accept, not the primary one. */
  it('asks with the destructive confirm and says the history goes too', async () => {
    const { fixture } = setup({ status: 'OPEN' });

    (await menuItem(fixture, 'admin.publications.issue.delete'))!.click();
    await fixture.whenStable();

    const panel = q<HTMLElement>('.confirm-panel')!;
    expect(panel.textContent).toContain('admin.publications.issue.deleteMessage');
    expect(panel.querySelector('.confirm-foot .btn-danger')).not.toBeNull();
  });

  /**
   * A withdrawn issue gets the same sentence as an open one: both are refused
   * while a message still cites them, so both are warned the same way.
   */
  it('warns a withdrawn issue with the same sentence as an open one', async () => {
    const { fixture } = setup({ status: 'RETIRED' }, { curatable: false });

    (await menuItem(fixture, 'admin.publications.issue.delete'))!.click();
    await fixture.whenStable();

    const text = q<HTMLElement>('.confirm-panel')!.textContent;
    expect(text).toContain('admin.publications.issue.deleteMessage');
    expect(text).not.toContain('deleteMessageRetired');
  });

  /**
   * The screen leaves for the series on the transaction, not on the click.
   *
   * There is nothing left to look at, and the series behind re-reads when the
   * panel closes — which is where the period shows as one nothing was released
   * for.
   */
  it('removes the issue and leaves for the series', async () => {
    const { fixture } = setup({ status: 'OPEN' });
    facade.deleteIssue.mockImplementation((onDeleted: () => void) => onDeleted());

    (await menuItem(fixture, 'admin.publications.issue.delete'))!.click();
    await fixture.whenStable();

    q<HTMLButtonElement>('.confirm-foot .btn-danger')!.click();
    await fixture.whenStable();

    expect(facade.deleteIssue).toHaveBeenCalledWith(expect.any(Function));
    expect(fixture.componentInstance.left()).toBe(true);
  });

  /**
   * A refusal over citations names the messages, because the sentence cannot.
   *
   * "Something still cites this issue" is not something an admin can act on; the
   * ids are, and the count says how many the capped list stands for.
   */
  it('names the messages a refused removal was blocked by', async () => {
    // After `setup`, which clears the last refusal on the way in.
    const { fixture } = setup({ status: 'RETIRED' }, { curatable: false });
    facade.lastActionError.set('ISSUE_CITED');
    facade.citingMessages.set([{ messageId: 'NM-814-26', uid: 'uid-a' }, { messageId: 'NM-2-26' }]);
    facade.citingCount.set(2);
    await fixture.whenStable();

    const block = root(fixture).querySelector('[data-testid="issue-citing-messages"]')!;
    expect(block.textContent).toContain('NM-814-26');
    expect(block.textContent).toContain('NM-2-26');
    expect(block.textContent).not.toContain('uid-a');
    // Each one opens, as it does everywhere else this section names a message.
    expect(block.querySelector('a')!.getAttribute('href')).toBe('/editor/edit/uid-a');
    expect(block.textContent).not.toContain('admin.publications.issue.citedMore');
  });

  /**
   * A citing message nobody has numbered yet is named by its title.
   *
   * The refusal exists so an admin can go and change what cites the issue, and
   * a uuid is not something they can look up — so the title names it and the
   * uid takes them there.
   */
  it('names an unnumbered citing message by its title', async () => {
    const { fixture } = setup({ status: 'RETIRED' }, { curatable: false });
    facade.lastActionError.set('ISSUE_CITED');
    facade.citingMessages.set([{ uid: 'uid-a', title: 'Buoy off station' }]);
    facade.citingCount.set(1);
    await fixture.whenStable();

    const block = root(fixture).querySelector('[data-testid="issue-citing-messages"]')!;
    expect(block.textContent).toContain('Buoy off station');
    expect(block.textContent).toContain('admin.publications.messageRef.notNumbered');
    expect(block.textContent).not.toContain('uid-a');
    expect(block.querySelector('a')!.getAttribute('href')).toBe('/editor/edit/uid-a');
  });

  it('says how many more cite it when the server capped the list', async () => {
    const { fixture } = setup({ status: 'RETIRED' }, { curatable: false });
    facade.lastActionError.set('ISSUE_CITED');
    facade.citingMessages.set([{ messageId: 'NM-814-26' }]);
    facade.citingCount.set(31);
    facade.citationsBounded.set(true);
    await fixture.whenStable();

    expect(
      root(fixture).querySelector('[data-testid="issue-citing-messages"]')!.textContent,
    ).toContain('admin.publications.issue.citedMore');
  });

  /** No refusal, no list: the block belongs to the refusal that produced it. */
  it('shows no citation block while nothing has been refused', () => {
    const { fixture } = setup({ status: 'RETIRED' }, { curatable: false });

    expect(root(fixture).querySelector('[data-testid="issue-citing-messages"]')).toBeNull();
  });
});
