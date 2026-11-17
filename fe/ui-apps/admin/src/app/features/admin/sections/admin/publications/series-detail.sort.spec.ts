import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
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
  PublicationIssueRow,
  PublishCheck,
  PublishChecklist,
} from '../../../services/api/publication-series-api.service';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { SeriesDetail } from './series-detail';
import { SeriesDetailFacade } from './series-detail.facade';
import { PublicationTransferTargets } from './transfer-owner';

@Component({
  imports: [SeriesDetail],
  template: `<app-series-detail [seriesId]="seriesId()" />`,
})
class Host {
  readonly seriesId = signal('weekly-ntm');
}

const DAY = 86_400_000;

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '/img/flags/da.png' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '/img/flags/en.png' },
];

/** The desk the admin is sitting at, and the catalogue the owner is named from. */
const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-nm', name: 'NM' } as Domain);
const availableDomains = signal<readonly Domain[]>([]);

/**
 * The series this screen is drawn for.
 *
 * `YEAR_EDITION` on purpose: the edition column exists only for the two schemes
 * that number an issue in a way its NAME does not already carry, and most of the
 * assertions below are about where the sortable headers sit.
 */
function weekly(over: Record<string, unknown> = {}) {
  return {
    seriesId: 'weekly-ntm',
    status: 'ACTIVE',
    descs: [],
    cadence: 'WEEKLY',
    numberingScheme: 'YEAR_EDITION',
    ...over,
  };
}

/**
 * ONE facade mock, reused.
 *
 * Angular caches the compiled definition `overrideComponent` produces, so a fresh
 * mock per test would leave the component bound to the first one and every table
 * would keep answering with the previous test's rows.
 */
const facade = {
  series: signal<unknown>(weekly()),
  issues: signal<readonly PublicationIssueRow[]>([]),
  issueCount: signal(0),
  loading: signal(false),
  error: signal(false),
  awaitingFirstRead: signal(false),
  gapDetection: signal({ enabled: true, reasonCode: 'CADENCED_SERIES', reason: '' }),
  gapCount: signal(0),
  publicNowIssue: signal<PublicationIssueRow | undefined>(undefined),
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
  // Which of the two views is on screen; the archive read is gated on it.
  setArchiveShown: vi.fn(),
  load: vi.fn(),
  refresh: vi.fn(),
};

/** Three real issues whose name, edition and period disagree. */
const ROWS: readonly PublicationIssueRow[] = [
  {
    publicId: 'w3',
    status: 'PUBLISHED',
    descs: [{ lang: 'da', name: 'Uge 3' }],
    edition: '3',
    sortKey: 3 * DAY,
    effectiveCutoff: 3 * DAY,
    cutoffStampedAt: 3 * DAY,
    publishedAt: 3 * DAY,
  },
  {
    publicId: 'w10',
    status: 'PUBLISHED',
    descs: [{ lang: 'da', name: 'Uge 10' }],
    edition: '10',
    sortKey: 2 * DAY,
    effectiveCutoff: 2 * DAY,
    cutoffStampedAt: 2 * DAY,
    publishedAt: 2 * DAY,
  },
  {
    publicId: 'w1',
    status: 'PUBLISHED',
    descs: [{ lang: 'da', name: 'Uge 1' }],
    edition: '1',
    sortKey: 1 * DAY,
    effectiveCutoff: 1 * DAY,
    cutoffStampedAt: 1 * DAY,
    publishedAt: 1 * DAY,
  },
];

/** The header cells, in render order, with the edition column present. */
const NAME = 0;
const EDITION = 2;
const CUTOFF = 3;
const PUBLIC_WINDOW = 4;

/**
 * The one thing read off the panel rather than through the DOM.
 *
 * Which sentence the clause reaches for is a choice the component makes and the
 * stub loader renders keys without their parameters, so the resolved key is
 * invisible in the markup — and it is the half of the clause that matters.
 */
interface DetailInternals {
  checklistSummary: () =>
    | Readonly<{
        state: string;
        count: number;
        detailKey?: string;
        detailText: string;
      }>
    | undefined;
}

/**
 * What the transfer picker would offer. Empty unless a test says otherwise.
 *
 * A SIGNAL, not a plain variable: the gate that reads it is a `computed()`, and a
 * value it cannot track leaves the first (empty) answer cached for the life of
 * the fixture.
 */
const transferTargets = signal<readonly Domain[]>([]);

function setup(rows: readonly PublicationIssueRow[] = ROWS) {
  vi.clearAllMocks();
  facade.issues.set(rows);
  facade.issueCount.set(rows.filter((r) => !r.pseudo).length);
  facade.isEmpty.set(false);

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
      // same Keycloak chain through the live facade.
      {
        provide: DomainFacade,
        useValue: { sessionDomain, availableDomains } as unknown as DomainFacade,
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
      { provide: Router, useValue: { ...fakeRowLinkRouter(), navigate: vi.fn() } },
      {
        provide: ActivatedRoute,
        useValue: { queryParamMap: of(convertToParamMap({})) },
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
  fixture.detectChanges();
  return fixture;
}

function headers(fixture: { nativeElement: HTMLElement }): HTMLTableCellElement[] {
  return [...fixture.nativeElement.querySelectorAll('thead th')] as HTMLTableCellElement[];
}

function ariaSorts(fixture: { nativeElement: HTMLElement }): (string | null)[] {
  return headers(fixture).map((th) => th.getAttribute('aria-sort'));
}

/** The name each row renders, which is the only handle the cell still carries. */
function rowNames(fixture: { nativeElement: HTMLElement }): string[] {
  return [...fixture.nativeElement.querySelectorAll('tbody tr td:first-child')].map((cell) =>
    (cell.textContent ?? '').trim(),
  );
}

function click(fixture: { nativeElement: HTMLElement; detectChanges(): void }, index: number) {
  (headers(fixture)[index].querySelector('button') as HTMLButtonElement).click();
  fixture.detectChanges();
}

describe('SeriesDetail — the id it loads', () => {
  /**
   * The panel loads from its input, not once at construction.
   *
   * A one-shot handoff made "the id never changes" part of the contract, so the
   * first in-page link from one series to another would have shown the previous
   * series' issues under the new one's name.
   */
  it('reloads when the series it was handed changes', () => {
    const fixture = setup();
    expect(facade.load).toHaveBeenCalledWith('weekly-ntm');

    fixture.componentInstance.seriesId.set('firing-areas');
    fixture.detectChanges();

    expect(facade.load).toHaveBeenCalledWith('firing-areas');
  });
});

describe('SeriesDetail — the sortable issue list', () => {
  /**
   * Exactly one header claims a sort at a time.
   *
   * `aria-sort` is the only thing that tells a screen-reader user which column
   * the table is ordered by — the arrow is decorative — so a stale value on a
   * second header describes an order that is not there.
   */
  it('marks the default column descending and every other header as unsorted', () => {
    const fixture = setup();

    // Issue, status, edition, cut-off, public period, messages, actions.
    expect(ariaSorts(fixture)).toEqual(['none', null, 'none', 'descending', null, null, null]);
  });

  /** Every sortable header is a real button, so the table sorts without a mouse. */
  it('puts a button in every sortable header', () => {
    const fixture = setup();
    const sortable = headers(fixture).filter((th) => th.hasAttribute('aria-sort'));

    expect(sortable).toHaveLength(3);
    for (const th of sortable) {
      expect(th.querySelector('button')).not.toBeNull();
    }
  });

  /** Picking another column moves the claim rather than adding a second one. */
  it('moves aria-sort to the column that was picked', () => {
    const fixture = setup();

    click(fixture, EDITION);

    expect(ariaSorts(fixture)[CUTOFF]).toBe('none');
    expect(ariaSorts(fixture)[EDITION]).toBe('ascending');
  });

  /** Clicking the active column reverses it. */
  it('toggles the direction of the active column', () => {
    const fixture = setup();

    click(fixture, CUTOFF);
    expect(ariaSorts(fixture)[CUTOFF]).toBe('ascending');

    click(fixture, CUTOFF);
    expect(ariaSorts(fixture)[CUTOFF]).toBe('descending');
  });

  /** Editions are numbered, so 9 comes before 10 rather than after it. */
  it('orders editions numerically', () => {
    const fixture = setup();

    click(fixture, EDITION);

    expect(rowNames(fixture)).toEqual(['Uge 1', 'Uge 3', 'Uge 10']);
  });

  /** And the same for names, which are short human strings carrying a number. */
  it('orders names numerically', () => {
    const fixture = setup();

    click(fixture, NAME);

    expect(rowNames(fixture)).toEqual(['Uge 1', 'Uge 3', 'Uge 10']);
  });

  /**
   * The number column is offered only where the NAME does not already carry it.
   *
   * A week-and-year name repeats itself in an edition column, and a series that
   * numbers nothing fills it with em dashes — so the column follows the series'
   * numbering scheme rather than being always on.
   */
  it('drops the edition column on a series whose names carry their own number', () => {
    facade.series.set(weekly({ numberingScheme: 'ISO_WEEK_YEAR' }));
    const fixture = setup();

    const labels = headers(fixture).map((th) => (th.textContent ?? '').trim());
    expect(labels).not.toContain('admin.publications.column.edition');
    expect(ariaSorts(fixture)).toEqual(['none', null, 'descending', null, null, null]);

    facade.series.set(weekly());
  });

  it('keeps the edition column on an edition-sequence series', () => {
    facade.series.set(weekly({ numberingScheme: 'EDITION_SEQUENCE' }));
    const fixture = setup();

    const labels = headers(fixture).map((th) => (th.textContent ?? '').trim());
    expect(labels.some((label) => label.includes('admin.publications.column.edition'))).toBe(true);

    facade.series.set(weekly());
  });

  /**
   * The action column is named for a screen reader.
   *
   * The cell is visually empty and has to stay that way, but a column with no
   * header is announced as a nameless one in a table whose other six are named.
   */
  it('gives the action column a visually hidden header', () => {
    const fixture = setup();
    const last = headers(fixture).at(-1);

    expect(last?.querySelector('.sr-only')?.textContent).toContain(
      'admin.publications.column.actions',
    );
  });

  /**
   * A clickable row says so, through the shared class rather than a bare cursor.
   *
   * `.row-interactive` carries the hover wash AND the keyboard focus ring; the
   * `cursor-pointer` it replaced carried neither.
   */
  it('marks the issue rows as interactive', () => {
    const fixture = setup();
    const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')];

    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row.classList.contains('row-interactive')).toBe(true);
    }
  });

  /**
   * An issue has an address of its own — `?issue=` on the series — so the row's
   * name is a real link and the issue can be opened in a second tab.
   */
  it('gives every real issue row a link addressing the issue it opens', () => {
    const fixture = setup();
    const links = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>(
        'tbody tr a.row-link',
      ),
    ];

    expect(links.length).toBeGreaterThan(0);
    for (const link of links) {
      expect(link.getAttribute('href')).toContain('issue=');
    }
  });

  /**
   * The open issue is the row the screen is about, and reads that way.
   *
   * A wash and a weight rather than a colour on the text: the badge already
   * carries the hue, and two colour cues for one state read as two states.
   */
  it('washes the open row and leaves every published one plain', () => {
    const fixture = setup([
      { ...ROWS[0], publicId: 'w4', status: 'OPEN', sortKey: 5 * DAY },
      ...ROWS,
    ]);
    const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')];

    expect(rows[0].classList.contains('status-row')).toBe(true);
    expect(rows[0].classList.contains('is-issue-open')).toBe(true);
    expect(rows[0].classList.contains('font-semibold')).toBe(true);
    expect(rows[1].classList.contains('status-row')).toBe(false);
  });

  /**
   * A pager for one page of rows is chrome that answers nothing.
   *
   * Three issues here, well under a page, so it must not render at all — which
   * is what the seventeen sibling admin lists do.
   */
  it('hides the pager while everything fits on one page', () => {
    const fixture = setup();

    expect((fixture.nativeElement as HTMLElement).querySelector('app-pager')).toBeNull();
  });

  /**
   * The pager is the table's, so it sits in the table's card.
   *
   * Outside it the controls float under a card that has already ended, which
   * reads as chrome for the page rather than for this list — and the rule above
   * them is what separates them from the last row.
   */
  it('puts the pager in the card foot once the archive runs past one page', () => {
    const many = Array.from({ length: ADMIN_PAGE_SIZE + 1 }, (_, index) => ({
      ...ROWS[0],
      publicId: `w${index}`,
      descs: [{ lang: 'da', name: `Uge ${index}` }],
      sortKey: index * DAY,
    }));
    const fixture = setup(many);
    const element = fixture.nativeElement as HTMLElement;

    const pager = element.querySelector('.admin-card app-pager');
    expect(pager).not.toBeNull();
    expect(pager?.parentElement?.className).toContain('border-t');
  });
});

/**
 * The dates in the archive, and which of them are records.
 *
 * A cut-off is stamped by the act of publishing, so an open issue's is a
 * projection of what WOULD be written — and a published one whose release ran
 * later than the freeze is two facts, not one.
 */
describe('SeriesDetail — how the archive marks its dates', () => {
  const expected = (fixture: { nativeElement: HTMLElement }) =>
    [...fixture.nativeElement.querySelectorAll('tbody .expected-date')].length;

  /** The public-period cell of the first row — the edition column is rendered. */
  const publicCell = (fixture: { nativeElement: HTMLElement }): HTMLTableCellElement =>
    fixture.nativeElement.querySelectorAll('tbody tr td')[PUBLIC_WINDOW] as HTMLTableCellElement;

  it('marks an open issue’s cut-off as expected', () => {
    const fixture = setup([
      { ...ROWS[0], publicId: 'w4', status: 'OPEN', cutoffStampedAt: undefined },
    ]);

    expect(expected(fixture)).toBe(1);
  });

  it('leaves a stamped cut-off as the record it is', () => {
    const fixture = setup([ROWS[0]]);

    expect(expected(fixture)).toBe(0);
  });

  /**
   * The public window's end is a projection on the issue that is live now.
   *
   * It closes when the successor goes out, and nobody has done that yet — so
   * the end of the CURRENT window is expected while every window behind it is
   * settled.
   */
  it('leaves the end of a window that has already closed unmarked', () => {
    facade.publicNowIssue.set(undefined);
    const fixture = setup([{ ...ROWS[0], publicFrom: 1 * DAY, publicTo: 2 * DAY }]);

    expect(expected(fixture)).toBe(0);
  });

  it('marks the end of the window that has not closed yet', () => {
    facade.publicNowIssue.set({ publicId: 'w3' } as unknown as PublicationIssueRow);
    const fixture = setup([{ ...ROWS[0], publicFrom: 1 * DAY, publicTo: 2 * DAY }]);

    expect(expected(fixture)).toBe(1);

    facade.publicNowIssue.set(undefined);
  });

  /**
   * The live window opened at an instant somebody recorded, and only its end is
   * still a projection — so the start stays a plain date beside the marked end.
   */
  it('leaves the start of the live window as the record it is', () => {
    facade.publicNowIssue.set({ publicId: 'w3' } as unknown as PublicationIssueRow);
    const fixture = setup([{ ...ROWS[0], publicFrom: 1 * DAY, publicTo: 2 * DAY }]);
    const mark = publicCell(fixture).querySelector('.expected-date');

    expect(mark?.textContent).toContain(String(2 * DAY));
    expect(mark?.textContent).not.toContain(String(1 * DAY));

    facade.publicNowIssue.set(undefined);
  });

  /**
   * An OPEN issue's window is one expectation, marked once.
   *
   * Nothing of it has been stamped: it opens when somebody releases the issue
   * and closes when the successor goes out. Marking only the end leaves the
   * start reading as a date that was recorded — which is the reading that is
   * wrong — so the whole range carries a single marker.
   */
  it('marks an open issue’s whole public window as one range', () => {
    const fixture = setup([
      {
        ...ROWS[0],
        publicId: 'w4',
        status: 'OPEN',
        cutoffStampedAt: undefined,
        publicFrom: 5 * DAY,
        publicTo: 12 * DAY,
      },
    ]);
    const marks = publicCell(fixture).querySelectorAll('.expected-date');

    expect(marks.length).toBe(1);
    expect(marks[0].textContent).toContain(String(5 * DAY));
    expect(marks[0].textContent).toContain(String(12 * DAY));
  });

  /**
   * An unreleased issue has NO stored window — the server projects one instead.
   *
   * Nothing is stamped until somebody releases, so a date written on the issue in
   * advance is a claim about an action nobody has taken. The column reads the
   * projection and marks the whole range once, exactly as it does for a window
   * the previous build stored.
   */
  it('draws an open issue’s projected window as one marked range', () => {
    const fixture = setup([
      {
        ...ROWS[0],
        publicId: 'w4',
        status: 'OPEN',
        cutoffStampedAt: undefined,
        publicFrom: undefined,
        publicTo: undefined,
        expectedPublicFrom: 5 * DAY,
        expectedPublicTo: 12 * DAY,
      },
    ]);
    const marks = publicCell(fixture).querySelectorAll('.expected-date');

    expect(marks.length).toBe(1);
    expect(marks[0].textContent).toContain(String(5 * DAY));
    expect(marks[0].textContent).toContain(String(12 * DAY));
  });

  /**
   * A published issue whose successor is open ends when that successor goes out.
   *
   * Its stored end is left open, because nothing has closed it — the instant the
   * successor's release will stamp on is what the site is going to do, and the
   * column says it as the projection it is beside the start that WAS recorded.
   */
  it('shows the recorded start beside the end its successor will stamp', () => {
    facade.publicNowIssue.set(undefined);
    const fixture = setup([
      {
        ...ROWS[0],
        publicFrom: 1 * DAY,
        publicTo: undefined,
        expectedPublicTo: 9 * DAY,
      },
    ]);
    const cell = publicCell(fixture);
    const marks = cell.querySelectorAll('.expected-date');

    // One marker, over the end only: the start is a record.
    expect(marks.length).toBe(1);
    expect(marks[0].textContent).toContain(String(9 * DAY));
    expect(marks[0].textContent).not.toContain(String(1 * DAY));
    expect(cell.textContent).toContain(String(1 * DAY));
    // A projected end is still an end: the range stands, and the open-ended
    // phrase would deny the successor that is going to close it.
    expect(cell.textContent).not.toContain('admin.publications.visibility.compact.since');
  });

  /** A row the server projected nothing for reads exactly as it always did. */
  it('leaves a row with no projection alone', () => {
    facade.publicNowIssue.set(undefined);
    const fixture = setup([{ ...ROWS[0], publicFrom: 1 * DAY, publicTo: 2 * DAY }]);
    const cell = publicCell(fixture);

    expect(cell.querySelectorAll('.expected-date').length).toBe(0);
    expect(cell.textContent).toContain(String(1 * DAY));
    expect(cell.textContent).toContain(String(2 * DAY));
    expect(cell.textContent).not.toContain('admin.publications.visibility.compact.since');
  });

  /**
   * A window nothing has closed is a whole answer, not half a range.
   *
   * "9 Sep –" reads as a date somebody stopped typing, and the dash sends the
   * eye looking for an end that is deliberately not there. The phrase comes from
   * the shared public-standing rule, which every one-off screen already says it
   * with.
   */
  it('writes an open-ended window as one phrase instead of a date and a dash', () => {
    facade.publicNowIssue.set(undefined);
    const fixture = setup([{ ...ROWS[0], publicFrom: 1 * DAY, publicTo: undefined }]);
    const cell = publicCell(fixture);
    const text = cell.textContent ?? '';

    expect(text).toContain('admin.publications.visibility.compact.since');
    expect(text).not.toContain('–');
    expect(cell.querySelectorAll('.expected-date').length).toBe(0);
  });

  /**
   * The release moment is a footnote on the cut-off, not a column of its own.
   *
   * Publishing freezes membership and then does the work, so the two are
   * normally one instant printed twice; the suffix appears only where the
   * release was run deliberately later.
   */
  it('says nothing about the release moment when it is the cut-off', () => {
    const fixture = setup([ROWS[0]]);

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'admin.publications.issue.publishedApart',
    );
  });

  it('appends the release moment when it says something the cut-off does not', () => {
    const fixture = setup([{ ...ROWS[0], publishedAt: 3 * DAY + 3_600_000 }]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'admin.publications.issue.publishedApart',
    );
  });

  /**
   * A derived bound says so in words rather than behind an icon.
   *
   * The question a reader is asking of the cell — may I treat this date as
   * evidence — is answered by "reconstructed", not by a glyph they have to
   * hover to decode.
   */
  it('names a reconstructed cut-off beside the date', () => {
    const fixture = setup([{ ...ROWS[0], cutoffSource: 'RECOVERED_FROM_NEXT_TAG' }]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'admin.publications.boundSourceShort.RECOVERED',
    );
  });
});

/**
 * Creating an issue by hand, and the states that refuse it.
 *
 * The endpoint checks none of this, so the affordance is the entire guard: an
 * issue opened on a DRAFT or RETIRED series is one nothing will ever publish.
 */
describe('SeriesDetail — opening a new issue', () => {
  function createButton(fixture: { nativeElement: HTMLElement }): HTMLButtonElement | undefined {
    return [...fixture.nativeElement.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('admin.publications.detail.newIssue'),
    );
  }

  it('offers the control on an active series with nothing open', () => {
    facade.seriesAcceptsIssues.set(true);
    facade.canCreateIssue.set(true);
    const fixture = setup();

    expect(createButton(fixture)).toBeDefined();
    expect(fixture.nativeElement.textContent).toContain('admin.publications.detail.noOpenIssue');
  });

  /**
   * A DRAFT series says why, once, in a note that carries the next step.
   *
   * Every imported series is DRAFT — the whole archive — so "no button, no
   * explanation" is what an operator meets on twenty screens out of twenty-two.
   */
  it('explains the refusal on a draft series, with the step that lifts it', () => {
    facade.series.set(weekly({ status: 'DRAFT' }));
    facade.seriesAcceptsIssues.set(false);
    facade.canCreateIssue.set(false);
    const fixture = setup();

    expect(createButton(fixture)).toBeUndefined();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('admin.publications.detail.draftNote');
    expect(text).toContain('admin.publications.detail.activateInline');

    facade.series.set(weekly());
  });

  /** The sentence says the series is a draft, and nothing about where it came from. */
  it('says the same thing about a draft the importer carried in', () => {
    facade.series.set(weekly({ status: 'DRAFT', importSource: 'legacy' }));
    facade.canCreateIssue.set(false);
    const fixture = setup();

    expect(fixture.nativeElement.textContent).toContain('admin.publications.detail.draftNote');

    facade.series.set(weekly());
  });

  /**
   * A RETIRED series is silent rather than instructed.
   *
   * "Activate this series first" names the next step for a DRAFT series. For one
   * retired on purpose it reads as an instruction to undo that decision.
   */
  it('says nothing extra on a retired series', () => {
    facade.series.set(weekly({ status: 'RETIRED' }));
    facade.seriesAcceptsIssues.set(false);
    facade.canCreateIssue.set(false);
    const fixture = setup();

    expect(createButton(fixture)).toBeUndefined();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('admin.publications.detail.draftNote');
    expect(text).not.toContain('admin.publications.detail.activateInline');

    facade.series.set(weekly());
  });

  /** The gap row's create control follows the same rule as the top-of-page one. */
  it('withholds the gap-row create on a series that is not active', () => {
    facade.seriesAcceptsIssues.set(false);
    facade.canCreateIssue.set(false);
    const fixture = setup([
      {
        pseudo: 'MISSING',
        sortKey: 1,
        descs: [],
        suggestedDescs: [{ lang: 'da', name: 'Uge 1' }],
      } as unknown as PublicationIssueRow,
    ]);

    expect(fixture.nativeElement.textContent).not.toContain(
      'admin.publications.detail.gaps.create',
    );

    facade.seriesAcceptsIssues.set(true);
  });

  it('offers the gap-row create on an active series', () => {
    facade.seriesAcceptsIssues.set(true);
    facade.canCreateIssue.set(false);
    const fixture = setup([
      {
        pseudo: 'MISSING',
        sortKey: 1,
        descs: [],
        suggestedDescs: [{ lang: 'da', name: 'Uge 1' }],
      } as unknown as PublicationIssueRow,
    ]);

    expect(fixture.nativeElement.textContent).toContain('admin.publications.detail.gaps.create');
  });

  /** The period being worked toward, on a series with nothing open. */
  const upcoming = {
    pseudo: 'UPCOMING',
    sortKey: 5 * DAY,
    intervalFrom: 4 * DAY,
    intervalTo: 5 * DAY,
    descs: [],
    suggestedDescs: [{ lang: 'da', name: 'Uge 38' }],
  } as unknown as PublicationIssueRow;

  /**
   * A series that has fallen behind is caught up from the row that names the gap.
   *
   * The period being worked toward is normally opened by the series itself when
   * the current issue publishes — but a series sitting with nothing open is not
   * about to open anything, and this row is then the only thing on the screen
   * describing the step that has to be taken.
   */
  it('offers the create on the upcoming period when nothing is open', () => {
    facade.seriesAcceptsIssues.set(true);
    facade.canCreateIssue.set(true);
    const fixture = setup([upcoming]);

    expect(fixture.nativeElement.textContent).toContain('admin.publications.detail.gaps.create');

    facade.canCreateIssue.set(false);
  });

  /** …and stays away while an issue IS open, where it would invite a duplicate. */
  it('withholds the create on the upcoming period while an issue is open', () => {
    facade.seriesAcceptsIssues.set(true);
    facade.canCreateIssue.set(false);
    const fixture = setup([
      upcoming,
      { publicId: 'w37', status: 'OPEN', descs: [], sortKey: 4 * DAY } as PublicationIssueRow,
    ]);

    expect(fixture.nativeElement.textContent).not.toContain(
      'admin.publications.detail.gaps.create',
    );
  });

  /**
   * The action column carries the create and nothing else.
   *
   * Asserted on a row whose issue HAS a document, because that is the row a
   * link control would attach itself to: the archive is a list of what went out
   * and when, and the document behind any one row is reached from the issue's
   * own page.
   */
  it('leaves the archive rows without a link-copying control', () => {
    facade.seriesAcceptsIssues.set(true);
    facade.canCreateIssue.set(false);
    const fixture = setup([
      {
        ...ROWS[0],
        descs: [{ lang: 'da', name: 'Uge 3', href: '/publications/uge-3.pdf' }],
      },
    ]);

    expect(fixture.nativeElement.querySelector('tbody app-copy-link-button')).toBeNull();
  });
});

/**
 * The card above the archive: what this series is in the middle of.
 *
 * The archive answers "what went out"; this answers "what am I working on",
 * which is the question somebody opening the screen mid-week actually has.
 */
describe('SeriesDetail — the current issue', () => {
  const OPEN: PublicationIssueRow = {
    publicId: 'w36',
    status: 'OPEN',
    descs: [{ lang: 'da', name: 'Uge 36' }],
    sortKey: 4 * DAY,
    effectiveCutoff: 4 * DAY,
    memberCount: 1,
  };

  it('names the open issue, its expected cut-off and its message count', () => {
    facade.currentIssue.set(OPEN);
    const fixture = setup([OPEN, ...ROWS]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('admin.publications.detail.currentIssue');
    expect(text).toContain('Uge 36');
    expect(text).toContain('admin.publications.detail.cutoffOnPublish');
    expect(text).toContain('admin.publications.detail.memberCount');
    expect(text).toContain('admin.publications.detail.openIssue');

    facade.currentIssue.set(undefined);
  });

  /** The cut-off has not been stamped, so the card says the date is a projection. */
  it('renders the cut-off on the card as an expected date', () => {
    facade.currentIssue.set(OPEN);
    const fixture = setup([OPEN, ...ROWS]);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('app-card .expected-date'),
    ).not.toBeNull();

    facade.currentIssue.set(undefined);
  });

  it('offers the new-issue control in the card when nothing is open', () => {
    facade.currentIssue.set(undefined);
    facade.canCreateIssue.set(true);
    const fixture = setup();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('admin.publications.detail.noOpenIssue');
    expect(text).toContain('admin.publications.detail.newIssue');
  });

  /** One message is one message. The count line is said in the form it fits. */
  it('says the count in the singular when the issue holds one message', () => {
    facade.currentIssue.set(OPEN);
    const fixture = setup([OPEN, ...ROWS]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'admin.publications.detail.memberCountOne',
    );

    facade.currentIssue.set(undefined);
  });
});

/**
 * How close the open issue is to going out, said on the card.
 *
 * The rail in the workbench is where a warning gets dealt with; this is the
 * clause that tells somebody there IS one before they open it. Its read is its
 * own — a failure closes the line rather than the page — and the counts come
 * from the same function the workbench's own verdict does, so the two cannot
 * disagree about one issue.
 */
describe('SeriesDetail — the checklist clause on the card', () => {
  const OPEN: PublicationIssueRow = {
    publicId: 'w36',
    status: 'OPEN',
    descs: [{ lang: 'da', name: 'Uge 36' }],
    sortKey: 4 * DAY,
    effectiveCutoff: 4 * DAY,
    memberCount: 1,
  };

  function checklist(rows: readonly Partial<PublishCheck>[]): PublishChecklist {
    return {
      rows: rows.map((row) => ({
        code: 'MEMBERS_RESOLVED',
        severity: 'WARN' as const,
        passed: false,
        acknowledgeable: true,
        ...row,
      })),
      canPublish: true,
      blockingCodes: [],
    };
  }

  function card(rows: readonly Partial<PublishCheck>[] | undefined) {
    facade.currentIssue.set(OPEN);
    facade.currentChecklist.set(rows ? checklist(rows) : undefined);
    const fixture = setup([OPEN, ...ROWS]);
    const element = fixture.nativeElement as HTMLElement;
    return {
      clause: element.querySelector('[data-testid="series-checklist"]'),
      detail: fixture.debugElement.children[0].componentInstance as unknown as DetailInternals,
    };
  }

  afterEach(() => {
    facade.currentIssue.set(undefined);
    facade.currentChecklist.set(undefined);
  });

  /** Nothing standing in the way is worth saying quietly, in the running colour. */
  it('says the issue is ready when every check that applies passed', () => {
    const { clause } = card([{ code: 'ISSUE_OPEN', severity: 'OK', passed: true }]);

    expect(clause?.textContent).toContain('admin.publications.checklist.verdict.ready');
    expect(clause?.className).not.toContain('text-warning');
  });

  /**
   * A warning is named, not just counted.
   *
   * "1 warning" alone tells somebody only to go and look; the clause for the
   * first one is what lets them decide whether it is worth opening the issue.
   * The SHORT clause: this line already carries the cut-off and the member
   * count, and the full sentence lives on the screen somebody would act on.
   */
  it('names the first warning in the warning colour, in the singular', () => {
    const { clause, detail } = card([
      { code: 'ISSUE_OPEN', severity: 'OK', passed: true },
      { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false },
    ]);

    expect(clause?.textContent).toContain('admin.publications.detail.checklistWarningsOne');
    expect(clause?.className).toContain('text-warning');
    expect(detail.checklistSummary()).toMatchObject({
      state: 'warnings',
      count: 1,
      detailKey: 'admin.publications.checkSummary.INTERVAL_CHAINED',
    });
  });

  /** Two of them take the plural sentence — "1 warning(s)" is nobody's writing. */
  it('takes the plural sentence once there is more than one warning', () => {
    const { clause, detail } = card([
      { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false },
      { code: 'MEMBER_LIMIT', severity: 'WARN', passed: false },
    ]);

    expect(clause?.textContent).toContain('admin.publications.detail.checklistWarnings');
    expect(clause?.textContent).not.toContain('checklistWarningsOne');
    expect(detail.checklistSummary()).toMatchObject({ count: 2 });
  });

  /** A check nobody has worded yet falls back to the server's own sentence. */
  it('falls back to the server’s text for a code this build has no words for', () => {
    const { detail } = card([
      { code: 'SOMETHING_NEW', severity: 'WARN', passed: false, detail: 'nobody wrote this one' },
    ]);

    expect(detail.checklistSummary()?.detailKey).toBeUndefined();
    expect(detail.checklistSummary()?.detailText).toBe('nobody wrote this one');
  });

  /** What stops a release is said in the colour that stops people. */
  it('reports a blocker in the error colour', () => {
    const { clause, detail } = card([
      { code: 'INTERVAL_PRESENT', severity: 'BLOCK', passed: false, acknowledgeable: false },
      { code: 'INTERVAL_CHAINED', severity: 'WARN', passed: false },
    ]);

    expect(clause?.textContent).toContain('admin.publications.checklist.verdict.blocked');
    expect(clause?.className).toContain('text-danger');
    expect(detail.checklistSummary()).toMatchObject({ state: 'blocked', count: 1 });
  });

  /** Rows the server ruled out of scope are not counted — nobody asked them. */
  it('ignores the checks that do not apply to this series', () => {
    const { clause } = card([
      { code: 'REPORT_CONFIGURED', severity: 'WARN', passed: false, applicable: false },
      { code: 'ISSUE_OPEN', severity: 'OK', passed: true },
    ]);

    expect(clause?.textContent).toContain('admin.publications.checklist.verdict.ready');
  });

  /**
   * A read that did not answer says nothing.
   *
   * The alternative is a card claiming an issue is ready on the strength of a
   * request that failed, on the screen somebody decides from.
   */
  it('closes the line at the count when there is no checklist to speak of', () => {
    const { clause } = card(undefined);

    expect(clause).toBeNull();
  });
});
