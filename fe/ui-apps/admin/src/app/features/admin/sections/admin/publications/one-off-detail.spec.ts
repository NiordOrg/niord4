import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../../../core/domain/models/domain.model';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../../../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { OneOffPublication } from '../../../services/api/one-off-api.service';
import type {
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { OneOffDetail } from './one-off-detail';
import { PublicWindowDialog } from './public-window-dialog';
import { PublicationTransferTargets } from './transfer-owner';

const BASE = '/rest';

/**
 * Where a repository address resolves to.
 *
 * The card hands over an ABSOLUTE address: the server reports the path
 * root-relative and the app is not always served from the host that answers it.
 * With a relative `apiBaseUrl` — production, where the two share an origin —
 * that origin is the page's own.
 */
const ORIGIN = document.location.origin;
const SERIES_ID = 'navigation-through-danish-waters';
const ISSUE_ID = 'i-1';

const UPLOADED_SERIES = {
  seriesId: SERIES_ID,
  status: 'ACTIVE',
  kind: 'ONE_OFF',
  cadence: 'NONE',
  contentMode: 'UPLOADED_FILE',
  categoryId: 'dk-dema-publications',
  domainId: 'niord-annex',
  availability: 'ALL_DOMAINS',
  messagePublication: 'EXTERNAL',
  languageSpecific: false,
  version: 3,
  languages: ['en'],
  descs: [
    {
      lang: 'en',
      name: 'Navigation Through Danish Waters',
      messageReferenceFormat: 'Navigation Through Danish Waters',
    },
  ],
} as unknown as PublicationSeries;

/** A publication that names no desk: every desk may read it and change it. */
const NO_OWNER_SERIES = {
  ...UPLOADED_SERIES,
  domainId: undefined,
} as unknown as PublicationSeries;

/** A public window that opened in February 2017 and closed with that year. */
const CLOSED_FROM = Date.UTC(2017, 1, 22, 0, 0);
const CLOSED_TO = Date.UTC(2017, 11, 31, 22, 59);

const PUBLICATION: OneOffPublication = {
  series: UPLOADED_SERIES,
  active: true,
  issuePublicId: ISSUE_ID,
  // Deliberately different from the issue read's own revision below, so a test
  // can say WHICH of the two a status transition is checked against.
  issueVersion: 7,
  issueStatus: 'PUBLISHED',
  links: [],
  fileNames: [{ lang: 'en', value: 'ntdw-feb-2017.pdf' }],
  publishable: true,
};

const ISSUE = {
  publicId: ISSUE_ID,
  seriesId: SERIES_ID,
  status: 'PUBLISHED',
  version: 2,
  publicFrom: Date.UTC(2017, 1, 12, 9, 0),
  descs: [
    {
      lang: 'en',
      name: 'Navigation Through Danish Waters',
      fileName: 'ntdw-feb-2017.pdf',
      href: '/rest/repo/file/ntdw-feb-2017.pdf',
      fileSource: 'UPLOADED',
    },
  ],
} as unknown as PublicationIssue;

/**
 * The trail as the server sends it: NEWEST FIRST.
 *
 * The order is the server's, and this page renders the array it receives — so
 * the fixture is written in the order the endpoint answers in. Written the other
 * way round it would stay green while describing a wire order that no longer
 * exists.
 */
const AUDIT = [
  {
    id: 2,
    action: 'FILE_UPLOADED',
    actorKind: 'USER',
    actorLabel: 'R. Jensen',
    created: Date.UTC(2026, 7, 29, 14, 5),
  },
  { id: 1, action: 'IMPORTED', actorKind: 'IMPORT', created: Date.UTC(2026, 7, 29, 14, 1) },
];

const sessionDomain = signal<Domain | undefined>({ domainId: 'niord-annex' } as Domain);
const availableDomains = signal<readonly Domain[]>([
  { domainId: 'niord-annex', name: 'NM Annex' } as Domain,
  { domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain,
]);
/** The desks the reader holds a role in — what decides whether a switch is offered. */
const editableDomains = signal<readonly Domain[]>(availableDomains());
/** How the refusal applies the desk it names, which is the picker's own path. */
const selectSessionDomain = vi.fn();
const transferTargets = signal<readonly Domain[]>([]);

const navigate = vi.fn(() => Promise.resolve(true));

/**
 * Everything the page fetches, answered.
 *
 * The publication is read by id and UNSCOPED so a deep link resolves from any
 * desk; the issue and its history are keyed on the issue it carries. Returns
 * how many category reads it drained, which is what the first test asserts on.
 */
function answer(
  httpTesting: HttpTestingController,
  options: {
    publication?: OneOffPublication;
    /** The publication is not in the estate: the server refuses the read. */
    missing?: boolean;
    /** The publication read fails with this status and no code. */
    failure?: number;
    issue?: PublicationIssue;
    audit?: readonly unknown[];
    /** Answer the category catalogue and nothing else, so the reads stay open. */
    defer?: boolean;
  } = {},
): { categoryRequests: number } {
  const categories = httpTesting.match(
    (r) => r.method === 'GET' && r.url.includes('/publication-categories/all'),
  );
  for (const request of categories) {
    request.flush([
      { categoryId: 'dk-dema-publications', descs: [{ lang: 'en', name: 'DEMA publications' }] },
    ]);
  }
  if (options.defer) {
    return { categoryRequests: categories.length };
  }
  for (const request of httpTesting.match(
    (r) => r.method === 'GET' && r.url.includes('/one-off-publications/'),
  )) {
    if (options.missing) {
      // As the server refuses it: a coded 404 (OneOffRestService.get).
      request.flush(
        { code: 'SERIES_NOT_FOUND', message: 'no one-off publication with id x' },
        { status: 404, statusText: 'Not Found' },
      );
    } else if (options.failure) {
      request.flush(null, { status: options.failure, statusText: 'Failed' });
    } else {
      request.flush({ ...(options.publication ?? PUBLICATION) });
    }
  }
  for (const request of httpTesting.match(
    (r) => r.method === 'GET' && r.url.includes('/editable-issue/'),
  )) {
    request.flush(options.issue ?? ISSUE);
  }
  for (const request of httpTesting.match((r) => r.method === 'GET' && r.url.includes('/audit'))) {
    request.flush([...(options.audit ?? AUDIT)]);
  }
  // The editor's own option lists, if its drawer has been opened. Drained
  // rather than asserted on: nothing here is about the pickers.
  for (const request of httpTesting.match(
    (r) =>
      r.method === 'GET' &&
      (r.url.includes('/message-reports/') ||
        r.url.includes('/name-tokens') ||
        r.url.includes('/domains/all')),
  )) {
    request.flush([]);
  }
  return { categoryRequests: categories.length };
}

/**
 * Turn the graph over so that everything just built has issued its reads.
 *
 * Twice: a component created on the first pass — the editor, when its drawer is
 * opened — makes its own requests on the second.
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

async function setup(
  options: Parameters<typeof answer>[1] & {
    /** The desk being worked in. The owner's own, unless a test says otherwise. */
    desk?: string;
    /** The desks the reader holds a role in; every one of them by default. */
    editable?: readonly Domain[];
  } = {},
  seriesId: string = SERIES_ID,
): Promise<{
  fixture: ReturnType<typeof TestBed.createComponent<OneOffDetail>>;
  httpTesting: HttpTestingController;
  element: HTMLElement;
  categoryRequests: number;
}> {
  sessionDomain.set({ domainId: options.desk ?? 'niord-annex' } as Domain);
  editableDomains.set(options.editable ?? availableDomains());
  transferTargets.set([]);
  navigate.mockClear();
  selectSessionDomain.mockClear();
  TestBed.configureTestingModule({
    imports: [OneOffDetail],
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: BASE, languages: ['en'] } },
      { provide: DiscardConfirmService, useValue: { confirmDiscard: () => Promise.resolve(true) } },
      {
        provide: DomainFacade,
        useValue: {
          sessionDomain,
          availableDomains,
          editableDomains,
          resolveById: (domainId: string) =>
            availableDomains().find((domain) => domain.domainId === domainId),
          selectSessionDomain,
        } as unknown as DomainFacade,
      },
      {
        provide: PublicationTransferTargets,
        useValue: { targetsFor: () => transferTargets() } as unknown as PublicationTransferTargets,
      },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => 'en',
          modelLanguages: [{ value: 'en', label: 'English', locale: 'en-GB', flagPath: '' }],
          activeLocale: signal('en-GB'),
        },
      },
      { provide: Router, useValue: { navigate } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: new Map([['seriesId', seriesId]]) } },
      },
    ],
  });

  const fixture = TestBed.createComponent(OneOffDetail);
  fixture.autoDetectChanges();
  await settle();

  const httpTesting = TestBed.inject(HttpTestingController);
  const { categoryRequests } = answer(httpTesting, options);
  await fixture.whenStable();

  return {
    fixture,
    httpTesting,
    element: fixture.nativeElement as HTMLElement,
    categoryRequests,
  };
}

/** The page plus anything portalled out of it (the drawer, the dialogs). */
function everything(element: HTMLElement): string {
  const overlay = document.querySelector('.cdk-overlay-container')?.textContent ?? '';
  return (element.textContent ?? '') + overlay;
}

/**
 * The one-off detail page.
 *
 * A one-off has no cadence, no sequence and no next issue, so nothing a reader
 * wants from it looks like a series: there is a document, a place on the public
 * site, and a record of what has happened to it. These hold that the page is
 * those three, and that everything it can DO is gated on owner equality.
 */
describe('OneOffDetail', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  /**
   * Three reads, and the first one is the publication itself.
   *
   * Read by id rather than derived from the series, because `active` is not a
   * field on the series — it folds series status, issue status and the public
   * window into the one answer the page shows. Asked unscoped so a deep link
   * resolves from any desk: reads are not scoped, only writes are.
   */
  it('reads the publication, its issue and its history', async () => {
    const { fixture, httpTesting } = await setup();

    // Nothing outstanding: everything the page needs was asked for once.
    httpTesting.verify();
    expect(fixture.componentInstance).toBeDefined();
  });

  /**
   * The issue and its history are asked for together.
   *
   * Both wait for the publication, which names the issue, and neither waits for
   * the other — so the page is two round trips, not three.
   */
  it('asks for the issue and its history together once the publication has answered', async () => {
    const { httpTesting } = await setup({ defer: true });

    httpTesting.expectNone((r) => r.url.includes('/editable-issue/'));
    httpTesting.expectNone((r) => r.url.includes('/audit'));
    httpTesting.expectOne(`${BASE}/one-off-publications/${SERIES_ID}`).flush({ ...PUBLICATION });

    // Both in flight before either has answered.
    const issue = httpTesting.expectOne(`${BASE}/publication-issues/editable-issue/${ISSUE_ID}`);
    const audit = httpTesting.expectOne(`${BASE}/publication-issues/issue/${ISSUE_ID}/audit`);
    issue.flush(ISSUE);
    audit.flush([...AUDIT]);
  });

  /**
   * Either of the two failing fails the page, and the other is called off.
   *
   * The page is all or nothing: a document card beside a history that never
   * arrived would look complete. The read still in flight is cancelled rather
   * than left to land on a page that has stopped waiting for it.
   */
  it('fails the page when the history read fails, and calls off the issue read', async () => {
    const { element, fixture, httpTesting } = await setup({ defer: true });
    httpTesting.expectOne(`${BASE}/one-off-publications/${SERIES_ID}`).flush({ ...PUBLICATION });
    const issue = httpTesting.expectOne(`${BASE}/publication-issues/editable-issue/${ISSUE_ID}`);

    httpTesting
      .expectOne(`${BASE}/publication-issues/issue/${ISSUE_ID}/audit`)
      .flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    // Not flushed: Angular refuses to answer a request that was called off.
    expect(issue.cancelled).toBe(true);
    expect(element.querySelector('app-error-banner')).not.toBeNull();
  });

  /** The same the other way round: the issue read fails and the history is called off. */
  it('fails the page when the issue read fails, and calls off the history read', async () => {
    const { element, fixture, httpTesting } = await setup({ defer: true });
    httpTesting.expectOne(`${BASE}/one-off-publications/${SERIES_ID}`).flush({ ...PUBLICATION });
    const audit = httpTesting.expectOne(`${BASE}/publication-issues/issue/${ISSUE_ID}/audit`);

    httpTesting
      .expectOne(`${BASE}/publication-issues/editable-issue/${ISSUE_ID}`)
      .flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(audit.cancelled).toBe(true);
    expect(element.querySelector('app-error-banner')).not.toBeNull();
  });

  /**
   * The category catalogue is read ONCE while the page loads.
   *
   * The page prints the publication's category name, so it reads the catalogue
   * for itself. The editor it can open does not exist yet and has therefore
   * asked for nothing — the drawer test below is where its own reads happen.
   */
  it('reads the category catalogue once for the page, and nothing for an editor that does not exist', async () => {
    const { categoryRequests } = await setup();

    expect(categoryRequests).toBe(1);
  });

  it('renders the header cluster: the name and the context line', async () => {
    const { element } = await setup();

    expect(element.querySelector('h2')?.textContent?.trim()).toBe(
      'Navigation Through Danish Waters',
    );
    expect(element.querySelector('h2')?.classList.contains('text-xl')).toBe(true);

    // No dot, and nothing anywhere calling a publication "active": the word
    // named a status, a period and a category flag at once, and therefore none
    // of them. The card below says which is which.
    expect(element.querySelector('.status-dot')).toBeNull();
    expect(element.textContent).not.toContain('admin.publications.oneOff.isActive');

    // "One-off · <type> · can be cited from …"
    const text = element.textContent ?? '';
    expect(text).toContain('admin.publications.oneOff.detail.kind');
    expect(text).toContain('admin.publications.oneOff.typeUploaded');
    expect(text).toContain('admin.publications.availability.from.ALL_DOMAINS');
  });

  /** The document card: the file, a way to copy it and a way to replace it. */
  it('renders the document card with the file, its date, a copy control and Replace', async () => {
    const { element } = await setup();

    const link = element.querySelector<HTMLAnchorElement>(
      `a[href="${ORIGIN}/rest/repo/file/ntdw-feb-2017.pdf"]`,
    );
    expect(link?.textContent?.trim()).toBe('ntdw-feb-2017.pdf');
    expect(element.querySelector('app-copy-link-button')).not.toBeNull();
    // The upload date comes from the history, because no file metadata is on
    // the wire — the most recent upload is what describes the file on screen.
    expect(element.textContent).toContain('admin.publications.oneOff.detail.uploadedAt');
    expect(element.textContent).toContain('admin.publications.oneOff.detail.replaceFile');
    expect(element.querySelector('input[type="file"]')).not.toBeNull();
    // One file for every language, because the series does not say otherwise —
    // and it shares a line with what a message prints when it cites the
    // publication, rather than each getting a paragraph of small grey type.
    const footnote = Array.from(element.querySelectorAll('p')).find((p) =>
      (p.textContent ?? '').includes('admin.publications.oneOff.detail.oneFileForAllLanguages'),
    );
    expect(footnote).toBeDefined();
    expect(footnote?.textContent).toContain('admin.publications.oneOff.detail.citation');
    expect(footnote?.className).toContain('flex-wrap');
  });

  /**
   * ONE ROW FOR ONE FILE.
   *
   * The issue carries a desc per language whether or not the publication keeps
   * a file per language, so a row per desc shows the same document twice under
   * different flags. The row with an address is the one it is filed under, and
   * the language tag only appears where the languages really do differ.
   */
  it('renders one document row for a publication with one file for every language', async () => {
    const { element } = await setup({
      issue: {
        ...ISSUE,
        descs: [
          { lang: 'da', name: 'Sejlads i danske farvande' },
          {
            lang: 'en',
            name: 'Navigation Through Danish Waters',
            fileName: 'ntdw-feb-2017.pdf',
            href: '/rest/repo/file/ntdw-feb-2017.pdf',
          },
        ],
      } as unknown as PublicationIssue,
    });

    const links = element.querySelectorAll(`a[href="${ORIGIN}/rest/repo/file/ntdw-feb-2017.pdf"]`);
    expect(links).toHaveLength(1);
    expect(element.textContent).not.toContain('DA');
  });

  /** With a file per language, every language gets its row and its tag. */
  it('renders a row per language when the publication keeps one file each', async () => {
    const { element } = await setup({
      publication: {
        ...PUBLICATION,
        series: { ...UPLOADED_SERIES, languageSpecific: true } as PublicationSeries,
      },
      issue: {
        ...ISSUE,
        descs: [
          { lang: 'da', name: 'Sejlads', fileName: 'sidf.pdf', href: '/rest/repo/file/sidf.pdf' },
          {
            lang: 'en',
            name: 'Navigation',
            fileName: 'ntdw-feb-2017.pdf',
            href: '/rest/repo/file/ntdw-feb-2017.pdf',
          },
        ],
      } as unknown as PublicationIssue,
    });

    expect(element.querySelectorAll(`a[href^="${ORIGIN}/rest/repo/file/"]`)).toHaveLength(2);
    expect(element.textContent).toContain('admin.publications.oneOff.detail.fileForEachLanguage');
  });

  /**
   * A natively uploaded file has no link column, and still has an address.
   *
   * The one-off list resolves one the way the public site does — an explicit
   * link where there is one, the repository address of the file otherwise — so
   * the row points at the document even where the issue desc carries no href.
   */
  it('falls back to the publication’s own address for a file row with no href', async () => {
    const { element } = await setup({
      publication: {
        ...PUBLICATION,
        links: [{ lang: 'en', value: '/rest/repo/file/derived.pdf' }],
      },
      issue: {
        ...ISSUE,
        descs: [{ lang: 'en', name: 'Navigation', fileName: 'ntdw-feb-2017.pdf' }],
      } as unknown as PublicationIssue,
    });

    const link = element.querySelector<HTMLAnchorElement>(
      `a[href="${ORIGIN}/rest/repo/file/derived.pdf"]`,
    );
    expect(link?.textContent?.trim()).toBe('ntdw-feb-2017.pdf');
  });

  it('renders a link-backed publication as its address', async () => {
    const { element } = await setup({
      publication: {
        ...PUBLICATION,
        series: { ...UPLOADED_SERIES, contentMode: 'EXTERNAL_LINK' } as PublicationSeries,
      },
      issue: {
        ...ISSUE,
        descs: [{ lang: 'en', name: 'Journal', link: 'https://example.invalid/j' }],
      } as unknown as PublicationIssue,
    });

    const link = element.querySelector<HTMLAnchorElement>('a[href="https://example.invalid/j"]');
    expect(link).not.toBeNull();
    expect(element.querySelector('input[type="file"]')).toBeNull();
  });

  it('says so when the publication carries no document at all', async () => {
    const { element } = await setup({
      publication: {
        ...PUBLICATION,
        series: { ...UPLOADED_SERIES, contentMode: 'NONE' } as PublicationSeries,
      },
      issue: { ...ISSUE, descs: [] } as unknown as PublicationIssue,
    });

    expect(element.textContent).toContain('admin.publications.oneOff.detail.referenceOnly');
    expect(element.textContent).toContain('admin.publications.oneOff.detail.referenceOnlyBody');
  });

  /**
   * The public card names the category, the date it went on, and the thing
   * people get wrong: deactivating does not break the citations already stored.
   */
  it('renders the public-site card: the derived line, the three facts, the note', async () => {
    const { element } = await setup();

    // The answer first — the publication has been out since February 2017 and
    // its period has no end.
    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.since');

    // Then the facts it follows from, each said in its own right.
    expect(element.querySelector('[data-testid="one-off-status-badge"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.status.PUBLISHED',
    );
    expect(element.querySelector('[data-testid="one-off-period"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.detail.periodNoEnd',
    );
    expect(element.querySelector('[data-testid="one-off-category"]')?.textContent).toContain(
      'DEMA publications',
    );
    expect(element.textContent).toContain('admin.publications.oneOff.detail.citationNote');
  });

  /**
   * The note is said from the side the publication is actually on.
   *
   * Advice about withdrawing a publication is not advice for one that already
   * has been; what the reader of a withdrawn publication needs to know is that
   * its file is still at its link and that publishing it again is one action.
   */
  it('names the way back on a withdrawn publication instead of the way off', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'RETIRED' },
    });

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.withdrawn');
    expect(element.textContent).toContain('admin.publications.oneOff.detail.withdrawnNote');
    expect(element.textContent).not.toContain('admin.publications.oneOff.detail.citationNote');
  });

  /** A publication nobody has released is a draft, and the badge says so. */
  it('shows an unreleased publication as a draft', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'OPEN' },
      issue: { ...ISSUE, status: 'OPEN', publicFrom: undefined } as unknown as PublicationIssue,
    });

    expect(element.querySelector('[data-testid="one-off-status-badge"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.status.DRAFT',
    );
    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.draft');
    expect(element.querySelector('[data-testid="one-off-period"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.detail.periodUnset',
    );
  });

  /**
   * A start still to come is not a mistake: the publication is prepared, and it
   * appears on the public site on the day.
   */
  it('names a period prepared on a publication that is not out yet', async () => {
    const from = Date.now() + 90 * 24 * 60 * 60 * 1000;
    const { element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'OPEN' },
      issue: { ...ISSUE, status: 'OPEN', publicFrom: from } as unknown as PublicationIssue,
    });

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.draftPrepared');
  });

  /** And a released publication whose start has not arrived is scheduled. */
  it('names a published publication whose start is still to come as scheduled', async () => {
    const from = Date.now() + 90 * 24 * 60 * 60 * 1000;
    const { element } = await setup({
      issue: { ...ISSUE, publicFrom: from } as unknown as PublicationIssue,
    });

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.scheduled');
  });

  /**
   * One line per entry, and the person who did it named as one.
   *
   * An entry the import wrote is the exception: the action and the actor are the
   * same fact there — an imported publication was imported BY the import — and
   * saying both reads as two things having happened to it.
   */
  it('renders the history rail, one line per entry', async () => {
    const { element } = await setup();

    const entries = element.querySelectorAll('ol > li');
    expect(entries).toHaveLength(2);
    expect(entries[0].textContent).toContain('admin.publications.action.FILE_UPLOADED');
    expect(entries[0].textContent).toContain('admin.publications.oneOff.detail.by');

    expect(entries[1].textContent).toContain('admin.publications.actor.IMPORT');
    expect(entries[1].textContent).not.toContain('admin.publications.action.IMPORTED');
  });

  /**
   * Re-opening the public site by hand is named, not spelled.
   *
   * This is the one screen that offers the action, so it is the one screen where
   * the entry it writes turns up. An action outside the vocabulary falls back to
   * its own constant — readable, but the only line in the rail written in
   * database English, on the page somebody opens to ask why a publication is back
   * on the public site.
   */
  it('names the entry a re-opened public window writes', async () => {
    const { element } = await setup({
      audit: [
        {
          id: 3,
          action: 'VISIBILITY_WINDOW_CHANGED',
          actorKind: 'USER',
          actorLabel: 'R. Jensen',
          created: Date.UTC(2026, 7, 29, 14, 9),
        },
      ],
    });

    // The first span of the line is the action itself. An action the vocabulary
    // cannot name lands there as the bare constant, in monospace — the tell that
    // this line alone is written in the database's English.
    const action = element.querySelector('ol > li span > span');
    expect(action?.textContent?.trim()).toBe('admin.publications.action.VISIBILITY_WINDOW_CHANGED');
    expect(action?.className).not.toContain('font-mono');
  });

  /**
   * An import's identifier is hover text, not a line.
   *
   * Every other entry records a reason somebody typed; an import records the id
   * the publication had in the system it came out of. Nobody reads a uuid off a
   * history panel, and the one person who needs it is matching it against a
   * record elsewhere.
   */
  it('keeps an import’s identifier out of the visible line', async () => {
    const { element } = await setup({
      audit: [
        {
          id: 1,
          action: 'IMPORTED',
          actorKind: 'IMPORT',
          reason: '9f1c-source-id',
          created: Date.UTC(2026, 7, 29, 14, 1),
        },
      ],
    });

    const entry = element.querySelector('ol > li');
    expect(entry?.textContent).not.toContain('9f1c-source-id');
    expect(entry?.querySelector('[title]')?.getAttribute('title')).toContain('9f1c-source-id');
  });

  /**
   * …and still reaches somebody without a pointer that can hover, because the
   * same identifier sits behind a line they can open.
   */
  it('opens an import’s identifier from the line beneath the date', async () => {
    const { fixture, element } = await setup({
      audit: [
        {
          id: 1,
          action: 'IMPORTED',
          actorKind: 'IMPORT',
          reason: '9f1c-source-id',
          created: Date.UTC(2026, 7, 29, 14, 1),
        },
      ],
    });

    const toggle = element.querySelector<HTMLButtonElement>(
      '[data-testid="one-off-entry-identifier-toggle"]',
    );
    expect(toggle).not.toBeNull();
    expect(toggle?.className).toContain('is-quiet');
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('[data-testid="one-off-entry-identifier"]')).toBeNull();

    toggle?.click();
    await fixture.whenStable();

    expect(
      element.querySelector('[data-testid="one-off-entry-identifier"]')?.textContent,
    ).toContain('9f1c-source-id');
    expect(
      element
        .querySelector('[data-testid="one-off-entry-identifier-toggle"]')
        ?.getAttribute('aria-expanded'),
    ).toBe('true');
  });

  /** An entry carrying neither an id nor an import identifier has nothing to open. */
  it('draws no identifier line for an entry that carries none', async () => {
    const { element } = await setup({
      audit: [
        {
          action: 'PUBLISHED',
          actorKind: 'USER',
          actorLabel: 'Someone',
          created: Date.UTC(2026, 7, 29, 14, 1),
        },
      ],
    });

    expect(element.querySelector('[data-testid="one-off-entry-identifier-toggle"]')).toBeNull();
  });

  it('says so when nothing has happened to the publication yet', async () => {
    const { element } = await setup({ audit: [] });

    expect(element.textContent).toContain('admin.publications.oneOff.detail.noHistory');
  });

  // ------------------------------------------------------------------ actions

  /**
   * Back to the list.
   *
   * `['../..']` because the route is `publications/one-off/:seriesId`: one step
   * pops the id, the second pops the literal segment.
   */
  it('goes back to the publications list', async () => {
    const { element } = await setup();

    const back = element.querySelector<HTMLButtonElement>('button.btn-tertiary');
    expect(back?.textContent).toContain('admin.publications.oneOff.detail.back');
    back?.click();

    expect(navigate).toHaveBeenCalledWith(['../..'], expect.anything());
  });

  it('opens the one-off drawer from the pencil', async () => {
    const { fixture, httpTesting, element } = await setup();

    const edit = Array.from(element.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      (b.textContent ?? '').includes('admin.publications.oneOff.edit'),
    );
    expect(edit).toBeDefined();
    expect(edit?.classList.contains('btn-secondary')).toBe(true);
    edit?.click();
    await settle();

    // The editor is BUILT by the click, so its four option lists go out now —
    // one drawer's worth, once, for a page that is read far more often than it
    // is edited from. They are answered here so the fixture can settle.
    for (const url of [
      '/rest/publication-categories/all',
      '/rest/message-reports/reports',
      '/rest/domains/all',
      '/rest/publication-series/name-tokens',
    ]) {
      httpTesting.expectOne((r) => r.url === url).flush([]);
    }
    await fixture.whenStable();

    expect(everything(element)).toContain('admin.publications.oneOff.editor.editTitle');
  });

  /**
   * The editor lives exactly as long as its drawer is on screen.
   *
   * Closing clears what it edits and starts the slide-out; the component stays
   * until the drawer says it has gone, and is then destroyed — so the next edit
   * builds a fresh one, which reads its lists again rather than showing the ones
   * it had.
   */
  it('destroys the editor once its drawer has gone, and builds it afresh next time', async () => {
    const { fixture, httpTesting, element } = await setup();
    const editor = () => fixture.debugElement.query(By.css('app-one-off-editor'));
    const open = async () => {
      Array.from(element.querySelectorAll<HTMLButtonElement>('button'))
        .find((b) => (b.textContent ?? '').includes('admin.publications.oneOff.edit'))
        ?.click();
      await settle();
      for (const url of [
        '/rest/publication-categories/all',
        '/rest/message-reports/reports',
        '/rest/domains/all',
        '/rest/publication-series/name-tokens',
      ]) {
        httpTesting.expectOne((r) => r.url === url).flush([]);
      }
      await fixture.whenStable();
    };

    await open();
    expect(editor()).not.toBeNull();
    const outputs = editor().componentInstance as {
      closed: { emit(): void };
      hidden: { emit(): void };
    };

    outputs.closed.emit();
    await settle();
    // Still there while the drawer slides out.
    expect(editor()).not.toBeNull();

    outputs.hidden.emit();
    await settle();
    expect(editor()).toBeNull();

    // A second edit is a second editor, and a second read of every list.
    await open();
    expect(editor()).not.toBeNull();
    expect(everything(element)).toContain('admin.publications.oneOff.editor.editTitle');
  });

  /**
   * The overflow menu carries the two things done TO a publication.
   *
   * Neither is a setting, which is why neither is in the drawer; and the move
   * only appears where it can work — there has to be somewhere to move it to.
   */
  /**
   * The menu carries the move and NOTHING about the public site.
   *
   * A status transition and a period are answers to what the visibility card
   * says, so they are buttons on that card: an admin who has just read
   * "Withdrawn" should not have to go looking in a menu for the way back.
   */
  it('offers the move in the actions menu, and nothing else', async () => {
    const { fixture } = await setup();
    transferTargets.set([{ domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain]);
    await fixture.whenStable();

    const items = (
      fixture.componentInstance as unknown as {
        menuItems: () => readonly { label?: string }[];
      }
    ).menuItems();
    expect(items.map((i) => i.label)).toEqual(['admin.publications.transfer.action']);
  });

  /** Nothing to move it to, so the menu holds nothing and the trigger is hidden. */
  it('offers no move while the admin administers only this desk', async () => {
    const { fixture, element } = await setup();

    const items = (
      fixture.componentInstance as unknown as {
        menuItems: () => readonly { label?: string }[];
      }
    ).menuItems();
    expect(items).toEqual([]);
    expect(element.querySelector('app-menu-trigger')).toBeNull();
  });

  /** A published publication is offered the way off the public site, and the period. */
  it('offers withdraw and the period on a published publication', async () => {
    const { element } = await setup();

    expect(element.querySelector('[data-testid="one-off-withdraw"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="one-off-edit-period"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="one-off-republish"]')).toBeNull();
  });

  /**
   * A withdrawn publication is offered the way back — and NOT the period.
   *
   * A period on a publication that is gone from the public site changes nothing
   * anybody can see, and offering it would suggest the period is what is holding
   * the document back.
   */
  it('offers only the way back on a withdrawn publication', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'RETIRED' },
    });

    expect(element.querySelector('[data-testid="one-off-republish"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="one-off-withdraw"]')).toBeNull();
    expect(element.querySelector('[data-testid="one-off-edit-period"]')).toBeNull();
  });

  /** Another desk's publication offers none of the three. */
  it('offers no visibility action on another domain’s publication', async () => {
    const { element } = await setup({ desk: 'niord-nm' });

    expect(element.querySelector('[data-testid="one-off-withdraw"]')).toBeNull();
    expect(element.querySelector('[data-testid="one-off-republish"]')).toBeNull();
    expect(element.querySelector('[data-testid="one-off-edit-period"]')).toBeNull();
  });

  /**
   * Taking it off the public site asks why, and the reason travels with the
   * transition.
   *
   * THROUGH THE ISSUE'S OWN WITHDRAWAL rather than through the one-off save. The
   * save works the status out by comparing what was asked for with whether the
   * publication is live, and "live" folds the public period in — see the two
   * tests below, which are the cases that made the save answer 200 having
   * changed nothing.
   */
  it('withdraws with a reason, through the issue and against the issue revision', async () => {
    const { fixture, httpTesting } = await setup();
    const page = fixture.componentInstance as unknown as {
      openWithdraw: () => void;
      submitWithdraw: (reason: string) => void;
    };

    page.openWithdraw();
    await fixture.whenStable();
    httpTesting.expectNone((r) => r.method === 'PUT');

    page.submitWithdraw('Superseded by the 2027 edition');
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/retire`,
    );
    expect(request.request.params.get('reason')).toBe('Superseded by the 2027 edition');
    // The ISSUE's revision, which is what that endpoint checks — not the
    // series' 3, which is the token for the save and for the public period.
    expect(request.request.params.get('version')).toBe('7');
    // The save is not touched at all: nothing about the publication is edited.
    httpTesting.expectNone(`${BASE}/one-off-publications/${SERIES_ID}`);
    request.flush({ ...ISSUE, status: 'RETIRED' });
    await fixture.whenStable();

    // Refetched rather than patched: the history has a new entry too.
    answer(httpTesting, { publication: { ...PUBLICATION, active: false, issueStatus: 'RETIRED' } });
    await fixture.whenStable();
  });

  /**
   * A publication prepared for a day still to come can be taken off the site.
   *
   * THE CASE THE SAVE COULD NOT TAKE. A scheduled one-off is PUBLISHED and not
   * live, so the endpoint that decides the transition by comparing "live" with
   * what was asked for compared false with false, decided there was nothing to
   * do and answered 200 with the row unchanged — while the dialog closed and the
   * screen said it had worked.
   */
  it('withdraws a scheduled publication, whose period has not started', async () => {
    const from = Date.now() + 90 * 24 * 3600 * 1000;
    const { fixture, httpTesting } = await setup({
      publication: { ...PUBLICATION, publicFrom: from },
      issue: { ...ISSUE, publicFrom: from } as unknown as PublicationIssue,
    });
    const page = fixture.componentInstance as unknown as { submitWithdraw: (r: string) => void };

    page.submitWithdraw('Prepared in error');
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/retire`,
    );
    request.flush({ ...ISSUE, status: 'RETIRED' });
    await fixture.whenStable();

    answer(httpTesting, { publication: { ...PUBLICATION, issueStatus: 'RETIRED' } });
    await fixture.whenStable();
  });

  /** And so can one whose period ran out years ago — the other half of the fold. */
  it('withdraws a historic publication, whose period has closed', async () => {
    const { fixture, httpTesting } = await setup({
      publication: { ...PUBLICATION, publicFrom: CLOSED_FROM, publicTo: CLOSED_TO },
      issue: {
        ...ISSUE,
        publicFrom: CLOSED_FROM,
        publicTo: CLOSED_TO,
      } as unknown as PublicationIssue,
    });
    const page = fixture.componentInstance as unknown as { submitWithdraw: (r: string) => void };

    page.submitWithdraw('Superseded');
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/retire`,
    );
    request.flush({ ...ISSUE, status: 'RETIRED' });
    await fixture.whenStable();

    answer(httpTesting, { publication: { ...PUBLICATION, issueStatus: 'RETIRED' } });
    await fixture.whenStable();
  });

  /** The file goes straight to the issue behind the publication. */
  it('uploads a replacement file against the issue', async () => {
    const { fixture, httpTesting, element } = await setup();

    const input = element.querySelector<HTMLInputElement>('input[type="file"]');
    const file = new File(['%PDF'], 'ntdw-2027.pdf', { type: 'application/pdf' });
    Object.defineProperty(input!, 'files', { value: [file] });
    input!.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      (r) =>
        r.method === 'POST' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/file/en`,
    );
    // The issue's revision, so a concurrent write is refused rather than lost.
    expect(request.request.params.get('version')).toBe('2');
    request.flush(ISSUE);
    await fixture.whenStable();

    answer(httpTesting);
    await fixture.whenStable();
  });

  it('moves the publication through the series owner endpoint', async () => {
    const { fixture, httpTesting } = await setup();
    const page = fixture.componentInstance as unknown as {
      submitTransfer: (decision: { domainId: string; reason: string }) => void;
    };

    page.submitTransfer({ domainId: 'niord-nm', reason: 'the NM desk owns it now' });
    await fixture.whenStable();

    const request = httpTesting.expectOne(`${BASE}/publication-series/series/${SERIES_ID}/owner`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      domainId: 'niord-nm',
      reason: 'the NM desk owns it now',
      version: 3,
    });
    request.flush(UPLOADED_SERIES);
    await fixture.whenStable();

    answer(httpTesting);
    await fixture.whenStable();
  });

  /**
   * A desk changed under an open page goes through the same answer.
   *
   * The picker normally reloads the application, which brings the page back up
   * at this address and refuses it there — but the page must not need the reload
   * to reach the same conclusion: what is on screen the instant the desk changes
   * is another desk's publication, and it is replaced rather than left standing
   * with every action quietly withdrawn.
   */
  it('replaces the page with the refusal when the desk changes under it', async () => {
    const { fixture, element } = await setup();
    expect(element.textContent).toContain('admin.publications.oneOff.edit');

    sessionDomain.set({ domainId: 'niord-nm' } as Domain);
    await fixture.whenStable();

    expect(element.querySelector('[data-testid="foreign-domain-refusal"]')).not.toBeNull();
    expect(element.textContent).not.toContain('admin.publications.oneOff.edit');
    // The file control goes with the rest of the page.
    expect(element.querySelector('input[type="file"]')).toBeNull();
  });

  /**
   * A URL pointing at nothing says so, with the way back and no Retry (L-174).
   *
   * The server refuses the read with a coded 404 for an id it has no one-off
   * for, a series that is not a one-off included. Retry would only fetch the
   * same 404, so the page names the fact instead of a failed load.
   */
  it('says the publication does not exist, without Retry, when the read is a coded 404', async () => {
    const { element } = await setup({ missing: true }, 'no-such-publication');

    const banner = element.querySelector('app-error-banner');
    expect(banner?.textContent).toContain('admin.publications.error.seriesNotFound');
    expect(banner?.textContent).not.toContain('admin.publications.oneOff.detail.loadError');
    expect(banner?.querySelector('button')).toBeNull();
    expect(element.textContent).toContain('admin.publications.oneOff.detail.back');
  });

  /** Any other failure may pass on a second try, so it keeps the load failure and Retry. */
  it.each([404, 500])(
    'keeps the load failure and Retry when the read fails with %s and no code',
    async (status) => {
      const { element } = await setup({ failure: status });

      const banner = element.querySelector('app-error-banner');
      expect(banner?.textContent).toContain('admin.publications.oneOff.detail.loadError');
      expect(banner?.querySelector('button')?.textContent).toContain('admin.publications.retry');
    },
  );

  /** The confirm host is on the page: publishing again asks whether, not why. */
  it('publishes again behind a plain confirm', async () => {
    const { fixture, httpTesting } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'RETIRED' },
    });
    const page = fixture.componentInstance as unknown as { republish: () => void };

    page.republish();
    await fixture.whenStable();
    httpTesting.expectNone((r) => r.method === 'PUT');

    // The component provides its own ConfirmService, so the root injector's is
    // a different instance and would never see the request.
    fixture.debugElement.injector.get(ConfirmService).active()!.accept();
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      (r) =>
        r.method === 'PUT' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/reactivate`,
    );
    // The transition the withdrawal undoes, on the same endpoint pair and
    // against the same revision — and with no reason, because nothing new is
    // being recorded about the publication.
    expect(request.request.params.get('version')).toBe('7');
    expect(request.request.params.has('reason')).toBe(false);
    request.flush(ISSUE);
    await fixture.whenStable();

    answer(httpTesting);
    await fixture.whenStable();
  });

  /**
   * A draft that has everything it needs is one press from the public site.
   *
   * THE RELEASE THE SCREEN DID NOT HAVE. A one-off created here for an uploaded
   * document is created with its series active and its issue still open, and
   * neither the upload nor a later save released it — so the publication read
   * "Not published yet" for ever with nothing on the page able to change that.
   */
  it('releases a draft through the issue, behind a confirm', async () => {
    const { fixture, httpTesting, element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'OPEN', publishable: true },
      issue: { ...ISSUE, status: 'OPEN' } as unknown as PublicationIssue,
    });
    expect(element.querySelector('[data-testid="one-off-publish"]')).not.toBeNull();

    const page = fixture.componentInstance as unknown as { publish: () => void };
    page.publish();
    await fixture.whenStable();
    httpTesting.expectNone((r) => r.method === 'PUT');

    fixture.debugElement.injector.get(ConfirmService).active()!.accept();
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      (r) => r.method === 'PUT' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/publish`,
    );
    expect(request.request.body).toEqual({ acknowledgedWarnings: [], version: 7 });
    request.flush({ publicId: ISSUE_ID, stampedAt: Date.now(), memberCount: 0 });
    await fixture.whenStable();

    answer(httpTesting, { publication: { ...PUBLICATION, issueStatus: 'PUBLISHED' } });
    await fixture.whenStable();
  });

  /**
   * Released a moment ago is public now, not scheduled.
   *
   * The release stamps the start of the public period with the server's own
   * instant, which the publish answer reports as `stampedAt`. The page's clock
   * moves once a minute and the officer's PC may trail the server, so read
   * against that clock alone the start lies ahead and the page said "Public from
   * … (scheduled)" for up to a minute. Here the server is 30 seconds ahead of the
   * PC: the server's instant has to count as now.
   */
  it('a just-published one-off reads as public, not scheduled, even when the PC clock trails the server', async () => {
    const { fixture, httpTesting, element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'OPEN', publishable: true },
      issue: { ...ISSUE, status: 'OPEN', publicFrom: undefined } as unknown as PublicationIssue,
    });
    const releasedAt = Date.now() + 30_000;

    const page = fixture.componentInstance as unknown as { publish: () => void };
    page.publish();
    await fixture.whenStable();
    fixture.debugElement.injector.get(ConfirmService).active()!.accept();
    await fixture.whenStable();

    httpTesting
      .expectOne(
        (r) =>
          r.method === 'PUT' && r.url === `${BASE}/publication-issues/issue/${ISSUE_ID}/publish`,
      )
      .flush({ publicId: ISSUE_ID, stampedAt: releasedAt, memberCount: 0 });
    await settle();

    answer(httpTesting, {
      publication: { ...PUBLICATION, issueStatus: 'PUBLISHED', publicFrom: releasedAt },
      issue: {
        ...ISSUE,
        status: 'PUBLISHED',
        publicFrom: releasedAt,
      } as unknown as PublicationIssue,
    });
    await fixture.whenStable();

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.since');
  });

  /**
   * A draft still waiting for its bytes is not offered the release.
   *
   * `publishable` is the server's own answer to whether the document is
   * complete, and a button that would be refused explains less than the sentence
   * saying what the publication is waiting for.
   */
  it('offers no release on a draft that has no file yet', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'OPEN', publishable: false },
      issue: { ...ISSUE, status: 'OPEN' } as unknown as PublicationIssue,
    });

    expect(element.querySelector('[data-testid="one-off-publish"]')).toBeNull();
  });

  // -------------------------------------------- the period, and the category

  /**
   * A period that has run out is HISTORIC, not "not public".
   *
   * The document is still at its link and every message that cites it still
   * resolves — it is simply no longer something a reader browses to, and both
   * ends are printed because the end alone says when it stopped and not how long
   * it stood.
   */
  it('names a period that has run out as historic, with both of its ends', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, publicTo: CLOSED_TO },
      issue: {
        ...ISSUE,
        publicFrom: CLOSED_FROM,
        publicTo: CLOSED_TO,
      } as unknown as PublicationIssue,
    });

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.historic');
    expect(element.querySelector('[data-testid="one-off-period"]')?.textContent?.trim()).toBe(
      'admin.publications.oneOff.detail.periodBoth',
    );
  });

  /** The period from the ISSUE alone, for a server not reporting it on the publication. */
  it('reads the period off the issue when the publication does not carry it', async () => {
    const { element } = await setup({
      issue: {
        ...ISSUE,
        publicFrom: CLOSED_FROM,
        publicTo: CLOSED_TO,
      } as unknown as PublicationIssue,
    });

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.historic');
  });

  /**
   * The public listing needs the CATEGORY's own publish flag as well as a
   * published issue in an open period, so a publication filed under an internal
   * category has never been on the site — and the card promised it was.
   */
  it('says the category is not public, and promises the public site nowhere', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, categoryPublish: false },
    });

    expect(element.textContent).toContain('admin.publications.oneOff.detail.notOnPublicSite');
    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.notPublicCategory');
    expect(element.querySelector('[data-testid="one-off-category"]')?.textContent).toContain(
      'admin.publications.oneOff.detail.categoryNotPublic',
    );
  });

  /** The same answer whatever the publication's own status is. */
  it('says the same about a withdrawn publication in a non-public category', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'RETIRED', categoryPublish: false },
    });

    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.notPublicCategory');
  });

  /**
   * ABSENT is not FALSE.
   *
   * A server that does not report the flag yet must leave the card saying what
   * it said before: reading absence as "not public" would hide a publication
   * that is on the site — and the category row says nothing about a flag nobody
   * sent.
   */
  it('says nothing about the category while the server reports no flag', async () => {
    const { element } = await setup();

    expect(element.textContent).toContain('admin.publications.oneOff.detail.public');
    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.since');
    const category = element.querySelector('[data-testid="one-off-category"]')?.textContent ?? '';
    expect(category).not.toContain('admin.publications.oneOff.detail.categoryPublic');
    expect(category).not.toContain('admin.publications.oneOff.detail.categoryNotPublic');
  });

  /** A category the site DOES list is named as one. */
  it('names a published category as public', async () => {
    const { element } = await setup({
      publication: { ...PUBLICATION, categoryPublish: true },
    });

    expect(element.querySelector('[data-testid="one-off-category"]')?.textContent).toContain(
      'admin.publications.oneOff.detail.categoryPublic',
    );
    expect(
      element.querySelector('[data-testid="one-off-visibility-line"]')?.textContent?.trim(),
    ).toBe('admin.publications.visibility.since');
  });

  // ------------------------------------------------- setting the period

  /**
   * BOTH ENDS, IN ONE CALL, through the period's own endpoint.
   *
   * The period is one decision — from when, until when — and the version it is
   * written against is the SERIES', the one revision a one-off carries.
   */
  it('sets both ends of the period through the public-window endpoint', async () => {
    const { fixture, httpTesting } = await setup();
    const page = fixture.componentInstance as unknown as {
      openPeriod: () => void;
      submitPeriod: (decision: { publicFrom: number | null; publicTo: number | null }) => void;
    };

    page.openPeriod();
    await fixture.whenStable();
    httpTesting.expectNone((r) => r.method === 'PUT');

    page.submitPeriod({ publicFrom: CLOSED_FROM, publicTo: null });
    await fixture.whenStable();

    const request = httpTesting.expectOne(
      `${BASE}/one-off-publications/${SERIES_ID}/public-window`,
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      publicFrom: CLOSED_FROM,
      publicTo: null,
      version: 3,
    });
    request.flush(PUBLICATION);
    await fixture.whenStable();

    // Refetched rather than patched: the history has a new entry too.
    answer(httpTesting);
    await fixture.whenStable();
  });

  /** The dialog is seeded from the period the publication actually has. */
  it('opens the period dialog on the publication’s own dates', async () => {
    const { fixture, element } = await setup({
      publication: { ...PUBLICATION, publicTo: CLOSED_TO },
      issue: {
        ...ISSUE,
        publicFrom: CLOSED_FROM,
        publicTo: CLOSED_TO,
      } as unknown as PublicationIssue,
    });

    (element.querySelector('[data-testid="one-off-edit-period"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(PublicWindowDialog))
      .componentInstance as PublicWindowDialog;
    expect(dialog.publicFrom()).toBe(CLOSED_FROM);
    expect(dialog.publicTo()).toBe(CLOSED_TO);
    // A published publication must name a start, so the dialog demands one.
    expect(dialog.startRequired()).toBe(true);
  });

  /** A publication still being prepared may leave the start empty. */
  it('does not demand a start on a publication that is not out yet', async () => {
    const { fixture, element } = await setup({
      publication: { ...PUBLICATION, issueStatus: 'OPEN' },
      issue: { ...ISSUE, status: 'OPEN', publicFrom: undefined } as unknown as PublicationIssue,
    });

    (element.querySelector('[data-testid="one-off-edit-period"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(PublicWindowDialog))
      .componentInstance as PublicWindowDialog;
    expect(dialog.startRequired()).toBe(false);
  });

  /** And the refusal that catalogues the rule reaches the reader as a sentence. */
  it('says why a period the server will not take was refused', async () => {
    const { fixture, httpTesting, element } = await setup();
    const page = fixture.componentInstance as unknown as {
      submitPeriod: (decision: { publicFrom: number | null; publicTo: number | null }) => void;
    };

    page.submitPeriod({ publicFrom: null, publicTo: CLOSED_TO });
    await fixture.whenStable();

    httpTesting
      .expectOne(`${BASE}/one-off-publications/${SERIES_ID}/public-window`)
      .flush(
        { code: 'PUBLIC_WINDOW_INVALID', message: 'PUBLIC_WINDOW_INVALID' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();

    expect(element.textContent).toContain('admin.publications.error.publicWindowInvalid');
  });

  /**
   * And it says it ONCE.
   *
   * Every write on this page is refused into one signal, which each dialog
   * renders beside the control that was refused. With the dialog standing open
   * the page behind it printed the same sentence again, and two copies of one
   * refusal read as two things having gone wrong.
   */
  it('leaves the refusal to the dialog that is showing it', async () => {
    const { fixture, httpTesting, element } = await setup();

    (element.querySelector('[data-testid="one-off-edit-period"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    const page = fixture.componentInstance as unknown as {
      submitPeriod: (decision: { publicFrom: number | null; publicTo: number | null }) => void;
    };
    page.submitPeriod({ publicFrom: null, publicTo: CLOSED_TO });
    await fixture.whenStable();

    httpTesting
      .expectOne(`${BASE}/one-off-publications/${SERIES_ID}/public-window`)
      .flush(
        { code: 'PUBLIC_WINDOW_INVALID', message: 'PUBLIC_WINDOW_INVALID' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();

    // The page's own banner is the one that stands down; the dialog keeps it.
    expect(element.querySelector('.message-error')).toBeNull();
    const dialog = fixture.debugElement.query(By.directive(PublicWindowDialog))
      .componentInstance as PublicWindowDialog;
    expect(dialog.errorKey()).toBe('admin.publications.error.publicWindowInvalid');
  });

  // ------------------------------------------- a publication another desk owns

  /**
   * A publication another desk owns is never shown here.
   *
   * However the reader arrived — a colleague's link, a bookmark, the back
   * button, or the desk picker reloading the application onto this address — the
   * answer is the same: the page is refused, the desk that owns it is named, and
   * the switch that would make it theirs is the one thing offered. Showing it
   * with every action quietly withdrawn read as a screen that broke.
   */
  it('refuses the page and names the desk that owns it', async () => {
    const { element } = await setup({ desk: 'niord-nm' });

    expect(element.querySelector('[data-testid="foreign-domain-refusal"]')).not.toBeNull();
    expect(element.textContent).toContain('admin.publications.foreignDomain.title');
    // Nothing about the publication: no document card, no public standing, no
    // history, and no way to edit what is not this desk's.
    expect(element.querySelector('app-card')).toBeNull();
    expect(element.textContent).not.toContain('admin.publications.oneOff.edit');
  });

  /**
   * The two reads behind the page stop at the publication.
   *
   * The owner arrives with it, which is the first moment the question can be
   * asked at all — and the document card and the history rail the issue and
   * audit reads answer are not on screen to ask it.
   */
  it('reads nothing beyond the publication that settled the question', async () => {
    const { httpTesting } = await setup({ desk: 'niord-nm' });

    httpTesting.expectNone((r) => r.url.includes('/editable-issue/'));
    httpTesting.expectNone((r) => r.url.includes('/audit'));
  });

  /** The one action: the desk it belongs to, applied the way the picker applies one. */
  it('switches to the owning desk when the refusal is acted on', async () => {
    const { element } = await setup({ desk: 'niord-nm' });

    element
      .querySelector<HTMLButtonElement>('[data-testid="foreign-domain-refusal"] button')!
      .click();

    // Keeping the address is the point of this one switch: the desk being chosen
    // is the one that can render this one-off.
    expect(selectSessionDomain).toHaveBeenCalledWith('niord-annex', { keepAddress: true });
  });

  /**
   * A desk the reader holds no role in is named, and nothing is offered.
   *
   * The switch resolves the desk out of the catalogue the picker uses, so one
   * they are not a member of cannot be switched to — and a button that fails
   * explains less than a sentence saying why.
   */
  it('offers no switch to a desk the reader is not a member of', async () => {
    const { element } = await setup({
      desk: 'niord-nm',
      editable: [{ domainId: 'niord-nm', name: 'Notices to Mariners' } as Domain],
    });

    expect(element.textContent).toContain('admin.publications.foreignDomain.noAccess');
    expect(element.querySelector('[data-testid="foreign-domain-refusal"] button')).toBeNull();
  });

  /**
   * A publication that names no desk belongs to every desk.
   *
   * There is nowhere else to look at it, so refusing it would make it
   * unreachable from everywhere.
   */
  it('renders a publication that names no owning desk, from any desk', async () => {
    const { element } = await setup({
      desk: 'niord-nm',
      publication: { ...PUBLICATION, series: NO_OWNER_SERIES },
    });

    expect(element.querySelector('[data-testid="foreign-domain-refusal"]')).toBeNull();
    expect(element.querySelector('h2')?.textContent).toContain('Navigation Through Danish Waters');
  });

  /** From the desk that owns it, nothing about the page has changed. */
  it('renders the publication from the desk that owns it', async () => {
    const { element } = await setup();

    expect(element.querySelector('[data-testid="foreign-domain-refusal"]')).toBeNull();
    expect(element.querySelector('h2')?.textContent).toContain('Navigation Through Danish Waters');
  });
});
