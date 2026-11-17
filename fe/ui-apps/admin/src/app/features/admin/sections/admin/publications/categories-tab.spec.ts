import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import type {
  PublicationCategory,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { PublicationCategoriesTab } from './categories-tab';
import { ModelLanguageSelection, type ModelText } from '../../../services/model-language-selection';

@Component({
  imports: [PublicationCategoriesTab],
  template: `<app-publication-categories-tab />`,
})
class Host {}

interface TabInternals {
  categories: () => readonly PublicationCategory[];
  filtered: () => readonly PublicationCategory[];
  publicationCount: (category: PublicationCategory) => string;
  editorModel: () => unknown;
  editing: () => boolean;
  removeCurrent: () => void;
  search: { (): string; set: (v: string) => void };
  /** Driven directly: the drawer portals to an overlay that attaches asynchronously,
   *  and racing that would make these tests about timing rather than about payloads. */
  form: PublicationCategoriesTab['form'];
  names: () => Record<string, string | undefined>;
  descriptions: () => Record<string, string | undefined>;
  setName: (lang: string, value: string) => void;
  setDescription: (lang: string, value: string) => void;
  changed: () => boolean;
  saveDisabled: () => boolean;
  add: () => void;
  edit: (category: PublicationCategory) => void;
  save: () => void;
  remove: (category: PublicationCategory) => void;
  name: (category: PublicationCategory) => ModelText;
  exportCategories: () => void;
  confirmImport: () => void;
  onFileSelected: (event: Event) => void;
}

const INTERNAL: PublicationCategory = {
  categoryId: 'dk-dma-internal-publications',
  priority: 60,
  publish: false,
  descs: [
    { lang: 'da', name: 'Interne publikationer' },
    { lang: 'en', name: 'Internal publications' },
  ],
};

/** A category that carries the field this screen used to erase on every save. */
const DESCRIBED: PublicationCategory = {
  categoryId: 'dk-dma-public-publications',
  priority: 10,
  publish: false,
  descs: [
    { lang: 'da', name: 'Offentlige publikationer', description: 'Alt offentligheden må læse' },
    { lang: 'en', name: 'Public publications', description: 'Everything the public may read' },
  ],
};

/** A category that reaches the public site, to read the "yes" case of the publish cell. */
const EXTERNAL: PublicationCategory = {
  categoryId: 'dk-external-publications',
  priority: 100,
  publish: true,
  descs: [{ lang: 'en', name: 'External publications' }],
};

function series(over: Partial<PublicationSeries> = {}): PublicationSeries {
  return {
    seriesId: 's-' + Math.random().toString(36).slice(2, 8),
    status: 'ACTIVE',
    domainId: 'niord-nm',
    descs: [{ lang: 'en', name: 'A series' }],
    cadence: 'WEEKLY',
    kind: 'SCHEDULED',
    contentMode: 'GENERATED_FROM_QUERY',
    languages: ['en'],
    ...over,
  };
}

/**
 * The estate the count column is derived from.
 *
 * Two scheduled series and a one-off point at the internal category, one series
 * at the external one, and one belongs to nothing — so the counts a correct
 * derivation produces are 3 and 1, and neither is the length of the list.
 */
const ESTATE: readonly PublicationSeries[] = [
  series({ categoryId: 'dk-dma-internal-publications' }),
  series({ categoryId: 'dk-dma-internal-publications' }),
  series({ kind: 'ONE_OFF', cadence: 'NONE', categoryId: 'dk-dma-internal-publications' }),
  series({ categoryId: 'dk-external-publications' }),
  series(),
];

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '' },
];

/** The confirm bus is scoped to the tab, so it is resolved from the tab's own injector. */
let confirmService: ConfirmService | undefined;

/** Answer "yes" to the pending confirm, the way the mounted dialog would. */
function acceptConfirm(): void {
  const pending = confirmService?.active();
  expect(pending, 'expected a confirm to be pending').toBeDefined();
  pending?.accept();
  confirmService?.dismiss();
}

/**
 * @param categories what the category list returns.
 * @param deferEstate leave the estate request open, so the count column can be
 *   read before it has been answered; the test flushes the returned request.
 */
function setup(
  options: {
    categories?: readonly PublicationCategory[];
    deferEstate?: boolean;
    includeKl?: boolean;
  } = {},
) {
  const notifications = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => 'en',
          defaultLanguage: 'da',
          modelLanguages: options.includeKl
            ? [...LANGUAGES, { value: 'kl', label: 'Kalaallisut', locale: 'kl', flagPath: '' }]
            : LANGUAGES,
        },
      },
      { provide: NotificationService, useValue: notifications },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  httpTesting
    .expectOne((r) => r.url.includes('/publication-categories/all'))
    .flush(options.categories ?? [INTERNAL]);
  // The estate, read once, is what the publication count is derived from.
  const estate = httpTesting.expectOne((r) => r.url.includes('/publication-series/search-details'));
  if (!options.deferEstate) {
    estate.flush([...ESTATE]);
  }
  fixture.detectChanges();
  confirmService = fixture.debugElement.children[0].injector.get(ConfirmService);
  return {
    fixture,
    httpTesting,
    notifications,
    estate,
    tab: fixture.debugElement.children[0].componentInstance as unknown as TabInternals,
  };
}

/** The trimmed text of every cell of the one-and-only body row, in document order. */
function rowCells(fixture: { nativeElement: HTMLElement }, row = 0): string[] {
  const rows = fixture.nativeElement.querySelectorAll('tbody tr');
  return [...rows[row].querySelectorAll('td')].map((td) => td.textContent?.trim() ?? '');
}

describe('PublicationCategoriesTab', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('lists the categories and names them in the reading language', () => {
    const { tab } = setup();
    expect(tab.categories()).toHaveLength(1);
    expect(tab.name(INTERNAL)).toEqual({ value: 'Internal publications', lang: 'en' });
  });

  it('uses the category id without a language when all stored names are blank', () => {
    const category = { ...INTERNAL, descs: [{ lang: 'en', name: '   ' }] };
    const { fixture, tab } = setup({ categories: [category] });
    expect(tab.name(category)).toEqual({ value: category.categoryId, lang: undefined });
    const rendered = fixture.nativeElement as HTMLElement;
    const label = rendered.querySelector('tbody td.font-semibold span')!;
    expect(label.textContent).toBe(category.categoryId);
    expect(label.hasAttribute('lang')).toBe(false);
    tab.search.set(category.categoryId);
    expect(tab.filtered()).toEqual([category]);
  });

  it('displays KL names and default-language fallbacks without changing an open editor', () => {
    const translated = {
      ...INTERNAL,
      descs: [...INTERNAL.descs, { lang: 'kl', name: 'Saqqummersitat' }],
    };
    const { fixture, tab } = setup({ categories: [translated, DESCRIBED], includeKl: true });
    tab.edit(translated);
    const original = structuredClone(tab.editorModel());
    fixture.debugElement.children[0].injector.get(ModelLanguageSelection).select('kl');
    fixture.detectChanges();
    expect(tab.name(translated)).toEqual({ value: 'Saqqummersitat', lang: 'kl' });
    expect(tab.name(DESCRIBED)).toEqual({ value: 'Offentlige publikationer', lang: 'da' });
    expect(tab.editorModel()).toEqual(original);
    expect(tab.changed()).toBe(false);
    expect(tab.saveDisabled()).toBe(true);
  });

  /**
   * The cog carries the dropdown chevron, as on the dashboard beside it and on
   * every other admin page. The two tabs sit under one header, so a chevron on
   * only one of them would read as a difference between them that is not there.
   */
  it('draws the cog with a chevron', () => {
    const { fixture } = setup();

    const cog = fixture.nativeElement.querySelector(
      'app-menu-trigger button[aria-label="admin.publications.categories.actionsAriaLabel"]',
    ) as HTMLElement;
    expect(cog.querySelectorAll('app-icon').length).toBe(2);
  });

  /**
   * The order the columns read in.
   *
   * The row's anchor is its name, and the number that sorts it is the narrow
   * muted column in front — reading left to right gives where it sits, what it
   * is called, what a series stores to point at it, whether it reaches the
   * public site, and how much belongs to it.
   */
  it('reads order · name · id · publish · publications, then the row action', () => {
    const { fixture } = setup();
    const headers = [...fixture.nativeElement.querySelectorAll('thead th')].map((th) =>
      (th as HTMLElement).textContent?.trim(),
    );
    expect(headers).toEqual([
      'admin.publications.categories.column.priority',
      'admin.publications.categories.column.name',
      'admin.publications.categories.column.categoryId',
      'admin.publications.categories.column.publish',
      'admin.publications.categories.column.publications',
      'admin.publications.categories.column.actions',
    ]);
    const cells = [...fixture.nativeElement.querySelectorAll('tbody tr')[0].querySelectorAll('td')];
    expect(cells[0].textContent?.trim()).toBe('60');
    // The name cell's own text is the name; the id under it is a separate node.
    expect(cells[1].firstChild?.textContent?.trim()).toBe('Internal publications');
    expect(cells[2].textContent?.trim()).toBe('dk-dma-internal-publications');
  });

  /**
   * What a narrow screen gives up, and where it goes instead.
   *
   * The id is the string a series stores to point here, so it follows the name
   * onto a second line rather than disappearing; the count is the least urgent
   * number on the row and simply drops. Both columns stay in the DOM — the width
   * rules are CSS, which the test renderer does not apply.
   */
  it('moves the id under the name and drops the count on narrow screens', () => {
    const { fixture } = setup();

    const row = fixture.nativeElement.querySelectorAll('tbody tr')[0] as HTMLElement;
    const cells = [...row.querySelectorAll('td')];
    const inlineId = cells[1].querySelector('span.md\\:hidden') as HTMLElement;
    expect(inlineId.textContent?.trim()).toBe('dk-dma-internal-publications');
    expect(cells[2].classList.contains('max-md:hidden')).toBe(true);

    const headers = [...fixture.nativeElement.querySelectorAll('thead th')] as HTMLElement[];
    expect(headers[4].classList.contains('max-lg:hidden')).toBe(true);
    expect(cells[4].classList.contains('max-lg:hidden')).toBe(true);
  });

  /**
   * The publish flag is a word, not a dot.
   *
   * The intro sentence above the table names «Offentliggør» as the thing that
   * decides whether a category's publications reach the public site; a dot with
   * nothing but an aria-label leaves that sentence pointing at no column a
   * sighted reader can find, and no legend explains which colour means which.
   */
  it('states the publish flag as a word, muting the negative', () => {
    const { fixture } = setup({ categories: [INTERNAL, EXTERNAL] });

    const internal = fixture.nativeElement
      .querySelectorAll('tbody tr')[0]
      .querySelectorAll('td')[3];
    const external = fixture.nativeElement
      .querySelectorAll('tbody tr')[1]
      .querySelectorAll('td')[3];
    expect(internal.textContent?.trim()).toBe('admin.publications.categories.no');
    expect(internal.classList.contains('text-fg-muted')).toBe(true);
    expect(external.textContent?.trim()).toBe('admin.publications.categories.yes');
    expect(external.classList.contains('text-fg-muted')).toBe(false);

    expect(fixture.nativeElement.querySelector('tbody .status-dot')).toBeNull();
  });

  /**
   * The count is derived from the estate, and covers one-offs as well as series.
   *
   * A one-off publication is a series of its own kind, so the single unscoped
   * read answers for both — and the answer is per category, never the length of
   * the list: two of the five here point elsewhere or nowhere.
   */
  it('counts the series and one-off publications that point at each category', () => {
    const { fixture, tab } = setup({ categories: [INTERNAL, EXTERNAL] });

    expect(tab.publicationCount(INTERNAL)).toBe('3');
    expect(tab.publicationCount(EXTERNAL)).toBe('1');
    expect(rowCells(fixture, 0)[4]).toBe('3');
    expect(rowCells(fixture, 1)[4]).toBe('1');
  });

  /** An unanswered estate says nothing rather than "nothing belongs to this". */
  it('shows a dash in the count column until the estate has been read', () => {
    const { fixture, estate, tab } = setup({ deferEstate: true });

    expect(tab.publicationCount(INTERNAL)).toBe('—');
    expect(rowCells(fixture)[4]).toBe('—');

    estate.flush([...ESTATE]);
    fixture.detectChanges();
    expect(rowCells(fixture)[4]).toBe('3');
  });

  /** A failed count reads as unknown, not as zero. */
  it('keeps the dash when the estate could not be read', () => {
    const { fixture, estate, tab } = setup({ deferEstate: true });

    estate.flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(tab.publicationCount(INTERNAL)).toBe('—');
  });

  /**
   * The row carries the pencil and nothing else.
   *
   * Delete used to sit one mis-click from it on every row of a list that changes
   * a few times a year.
   */
  it('carries no destructive control on the row', () => {
    const { fixture } = setup();
    const buttons = fixture.nativeElement.querySelectorAll('tbody button');
    expect(buttons).toHaveLength(1);
    expect((buttons[0] as HTMLElement).getAttribute('aria-label')).toBe(
      'admin.publications.categories.editAria',
    );
  });

  it('posts a new category to create rather than to update', () => {
    // Creating one was impossible before, which mattered the moment S-19 made a
    // category mandatory to save a series.
    const { fixture, httpTesting, tab } = setup();

    tab.add();
    fixture.detectChanges();
    expect(tab.form.categoryId().value()).toBe('');
    expect(tab.saveDisabled()).toBe(true); // no id yet

    tab.form.categoryId().value.set('dk-test-category');
    tab.setName('en', 'Test category');
    fixture.detectChanges();

    tab.save();
    const req = httpTesting.expectOne(
      (r) => r.method === 'POST' && r.url === '/rest/publication-categories/publication-category/',
    );
    const body = req.request.body as PublicationCategory;
    expect(body.categoryId).toBe('dk-test-category');
    expect(body.descs.map((d) => d.lang)).toEqual(['en']);
    req.flush(INTERNAL);

    fixture.detectChanges();
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([INTERNAL]);
  });

  it('sends a desc row only for languages that have text', () => {
    // A blank name renders as a nameless row in the picker every series uses,
    // which is worse than the language simply not being covered yet.
    const { fixture, httpTesting, tab } = setup();

    tab.add();
    fixture.detectChanges();
    tab.form.categoryId().value.set('dk-one-language');
    tab.setName('da', 'Kun dansk');
    fixture.detectChanges();

    tab.save();
    const req = httpTesting.expectOne((r) => r.method === 'POST');
    expect((req.request.body as PublicationCategory).descs).toEqual([
      { lang: 'da', name: 'Kun dansk', description: '' },
    ]);
    req.flush(INTERNAL);
    fixture.detectChanges();
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([INTERNAL]);
  });

  /**
   * The description is loaded as well as sent.
   *
   * The screen never showed it and the save never carried it, so every edit of
   * any other field round-tripped the category with its descriptions blanked —
   * text nothing on this screen had ever displayed, deleted by touching the
   * priority.
   */
  it('round-trips the description of every language it loaded', () => {
    const { fixture, httpTesting, tab } = setup();

    tab.edit(DESCRIBED);
    fixture.detectChanges();
    expect(tab.descriptions()['en']).toBe('Everything the public may read');
    expect(tab.descriptions()['da']).toBe('Alt offentligheden må læse');

    // Change something else entirely.
    tab.form.publish().value.set(true);
    fixture.detectChanges();

    tab.save();
    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    expect((req.request.body as PublicationCategory).descs).toEqual([
      { lang: 'da', name: 'Offentlige publikationer', description: 'Alt offentligheden må læse' },
      { lang: 'en', name: 'Public publications', description: 'Everything the public may read' },
    ]);
    req.flush(DESCRIBED);
    fixture.detectChanges();
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([INTERNAL]);
  });

  /**
   * Every category has an Order: the column cannot hold "none", and it
   * decides where the category sorts on the public page. A cleared box used to
   * be sent as `null`, the server kept the stored number, and the toast said
   * "Category saved". The drawer now holds Save back and says why, and what it
   * sends is always a number.
   */
  it('will not save a category whose Order was cleared, and says why', async () => {
    const { fixture, httpTesting, tab } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    tab.form.priority().value.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(tab.changed()).toBe(true);
    expect(tab.saveDisabled()).toBe(true);
    expect(document.querySelector('#category-priority-error')?.textContent?.trim()).toBe(
      'admin.publications.categories.hint.priorityRequired',
    );
    expect(document.querySelector('#category-priority')?.getAttribute('aria-describedby')).toBe(
      'category-priority-error',
    );
    tab.save();
    httpTesting.expectNone((r) => r.method === 'PUT');

    tab.form.priority().value.set(70);
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(false);
    expect(document.querySelector('#category-priority-error')).toBeNull();

    tab.save();
    const req = httpTesting.expectOne((r) => r.method === 'PUT');
    expect((req.request.body as PublicationCategory).priority).toBe(70);
    req.flush(INTERNAL);
    fixture.detectChanges();
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([INTERNAL]);
  });

  /** A new category: its Order starts filled in, and clearing it holds Save back too. */
  it('will not create a category whose Order was cleared', () => {
    const { fixture, httpTesting, tab } = setup();

    tab.add();
    fixture.detectChanges();
    tab.form.categoryId().value.set('dk-test');
    tab.setName('en', 'Test category');
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(false);

    tab.form.priority().value.set(null);
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(true);
    tab.save();
    httpTesting.expectNone((r) => r.method === 'POST');
  });

  /**
   * A refused save says why. The handler used to ignore the answer, so a
   * duplicate id on create read only "The category could not be saved".
   */
  it('names a duplicate id on create, and keeps the drawer open', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    tab.add();
    fixture.detectChanges();
    tab.form.categoryId().value.set('dk-dma-internal-publications');
    tab.setName('en', 'Test category');
    fixture.detectChanges();

    tab.save();
    httpTesting
      .expectOne((r) => r.method === 'POST')
      .flush(
        { code: 'CATEGORY_ID_TAKEN', message: "category 'dk-dma-internal-publications' exists" },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();

    expect(notifications.error).toHaveBeenCalledWith('admin.publications.error.categoryIdTaken');
    expect(tab.editorModel()).toBeDefined();
    expect(tab.saveDisabled()).toBe(false);
  });

  /** The server's own checks of a category (id, name, Order) reach the screen too. */
  it('names a category the server refused as it stands', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    tab.setName('en', 'Renamed');
    fixture.detectChanges();

    tab.save();
    httpTesting
      .expectOne((r) => r.method === 'PUT')
      .flush(
        { code: 'CATEGORY_INVALID', message: 'priority is a whole number' },
        { status: 400, statusText: 'Bad Request' },
      );

    expect(notifications.error).toHaveBeenCalledWith('admin.publications.error.categoryInvalid');
  });

  /** Any other failure of a save keeps the generic sentence. */
  it('keeps the generic sentence for a save failure it does not recognise', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    tab.setName('en', 'Renamed');
    fixture.detectChanges();

    tab.save();
    httpTesting
      .expectOne((r) => r.method === 'PUT')
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.publications.categories.toast.saveError',
    );
  });

  it('puts to update when the category already exists, and locks its id', () => {
    const { fixture, httpTesting, tab } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    expect(tab.form.categoryId().value()).toBe('dk-dma-internal-publications');
    expect(tab.names()['en']).toBe('Internal publications');

    tab.setName('en', 'Renamed');
    fixture.detectChanges();
    expect(tab.changed()).toBe(true);

    tab.save();
    const req = httpTesting.expectOne(
      (r) =>
        r.method === 'PUT' &&
        r.url === '/rest/publication-categories/publication-category/dk-dma-internal-publications',
    );
    expect((req.request.body as PublicationCategory).descs.find((d) => d.lang === 'en')?.name).toBe(
      'Renamed',
    );
    req.flush(INTERNAL);
    fixture.detectChanges();
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([INTERNAL]);
  });

  it('will not save an unchanged category', () => {
    const { fixture, tab } = setup();
    tab.edit(INTERNAL);
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(true);
  });

  /**
   * The id is the form's own required field, so a blank one is invalid rather
   * than merely un-saveable: `[formField]` reflects `required` onto the control,
   * which is what marks it on the screen.
   */
  it('holds the blank id invalid through the form, not through a hand-written check', () => {
    const { fixture, tab } = setup();

    tab.add();
    fixture.detectChanges();
    // Named first, so the id is the one thing the form still waits for.
    tab.setName('en', 'Test category');
    expect(tab.form().invalid()).toBe(true);
    expect(tab.saveDisabled()).toBe(true);

    tab.form.categoryId().value.set('dk-test-category');
    fixture.detectChanges();
    expect(tab.form().invalid()).toBe(false);
    expect(tab.saveDisabled()).toBe(false);
  });

  /**
   * PUBCAT2. The id is the last segment of every address that reads, changes or
   * deletes the category, and the server's firewall refuses one that holds a
   * slash, a semicolon and the like. A category created under it could never be
   * opened or deleted again, so the drawer holds it back and says why.
   */
  it('will not create a category under an id the server could not serve, and says why', async () => {
    const { fixture, httpTesting, tab } = setup();

    tab.add();
    fixture.detectChanges();
    tab.setName('en', 'Test category');
    tab.form.categoryId().value.set('dk/test');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(tab.saveDisabled()).toBe(true);
    expect(document.querySelector('#category-id-error')?.textContent?.trim()).toBe(
      'admin.publications.error.idUnservable',
    );
    expect(document.querySelector('#category-id')?.getAttribute('aria-describedby')).toBe(
      'category-id-error',
    );
    tab.save();
    httpTesting.expectNone((r) => r.method === 'POST');

    for (const id of ['dk;test', 'dk%2Ftest', 'dk\\test', 'dk\ntest', '.', '..']) {
      tab.form.categoryId().value.set(id);
      expect(tab.saveDisabled(), JSON.stringify(id)).toBe(true);
    }

    tab.form.categoryId().value.set('dk-test');
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(false);
    expect(document.querySelector('#category-id-error')).toBeNull();
  });

  /**
   * PUB4. A category's name heads its section on the public page, and the
   * server refuses a save that leaves it with no name in any language.
   */
  it('will not save a category with no name in any language, and says why', async () => {
    const { fixture, httpTesting, tab } = setup();

    tab.add();
    fixture.detectChanges();
    tab.form.categoryId().value.set('dk-test');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(tab.saveDisabled()).toBe(true);
    const drawerText = () => document.querySelector('.cdk-overlay-container')?.textContent ?? '';
    expect(drawerText()).toContain('admin.publications.categories.hint.nameRequired');
    tab.save();
    httpTesting.expectNone((r) => r.method === 'POST');

    tab.setName('da', '   ');
    expect(tab.saveDisabled()).toBe(true);

    tab.setName('da', 'Test');
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(false);
    expect(drawerText()).not.toContain('admin.publications.categories.hint.nameRequired');
  });

  /** PUB4 on an existing category: emptying every name holds the save back too. */
  it('will not save an existing category whose names were all cleared', () => {
    const { fixture, tab } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    tab.setName('da', '');
    tab.setName('en', '');
    fixture.detectChanges();
    expect(tab.changed()).toBe(true);
    expect(tab.saveDisabled()).toBe(true);
  });

  /**
   * PUBCAT3. The column is a 32-bit integer: the server used to store 3000000000
   * as a negative number and 1.7 as 1, and now refuses both. The drawer holds
   * them back and names the range.
   */
  it('will not save an Order that is a fraction or past the column, and names the range', async () => {
    const { fixture, httpTesting, tab } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    for (const priority of [3000000000, 2147483648, -2147483649, 1.7]) {
      tab.form.priority().value.set(priority);
      expect(tab.saveDisabled(), String(priority)).toBe(true);
    }
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.querySelector('#category-priority-error')?.textContent?.trim()).toBe(
      'admin.publications.categories.hint.priorityRange',
    );
    tab.save();
    httpTesting.expectNone((r) => r.method === 'PUT');

    for (const priority of [2147483647, -2147483648, 0]) {
      tab.form.priority().value.set(priority);
      expect(tab.saveDisabled(), String(priority)).toBe(false);
    }
    fixture.detectChanges();
    expect(document.querySelector('#category-priority-error')).toBeNull();
  });

  /**
   * ERR7. A name and a description each go into a varchar(255) column; a longer
   * one used to be sent, refused with a 500 and reported as "could not be saved".
   */
  it('will not save a name or description longer than its column, and counts under each field', async () => {
    const { fixture, httpTesting, tab } = setup();

    tab.edit(DESCRIBED);
    fixture.detectChanges();
    await fixture.whenStable();
    // Two languages, a counter under each name and each description.
    expect(document.querySelectorAll('app-length-counter p')).toHaveLength(4);

    tab.setName('en', 'a'.repeat(256));
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(true);
    expect(document.querySelector('app-length-counter p[role="alert"]')).not.toBeNull();
    // Too long is not "no name": the missing-name line stays away.
    const drawerText = () => document.querySelector('.cdk-overlay-container')?.textContent ?? '';
    expect(drawerText()).not.toContain('admin.publications.categories.hint.nameRequired');
    tab.save();
    httpTesting.expectNone((r) => r.method === 'PUT');

    tab.setName('en', 'a'.repeat(255));
    tab.setDescription('da', 'b'.repeat(256));
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(true);

    tab.setDescription('da', 'b'.repeat(255));
    fixture.detectChanges();
    expect(tab.saveDisabled()).toBe(false);
    expect(document.querySelector('app-length-counter p[role="alert"]')).toBeNull();
  });

  /** The id is the key every series points at — locked once the category exists. */
  it('locks the id on edit through the schema', () => {
    const { fixture, tab } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    expect(tab.form.categoryId().disabled()).toBe(true);

    tab.add();
    fixture.detectChanges();
    expect(tab.form.categoryId().disabled()).toBe(false);
  });

  /** The filter every other admin list carries, over both the name and the id. */
  it('filters the list by name and by id, and says so when nothing matches', () => {
    const { fixture, tab } = setup();

    tab.search.set('internal');
    fixture.detectChanges();
    expect(tab.filtered()).toHaveLength(1);

    tab.search.set('dk-dma');
    fixture.detectChanges();
    expect(tab.filtered()).toHaveLength(1);

    tab.search.set('nothing here');
    fixture.detectChanges();
    expect(tab.filtered()).toHaveLength(0);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'admin.publications.categories.empty.noMatches',
    );
  });

  /**
   * Delete is reached from the editor's footer, and only for a category that
   * exists: a drawer opened on a new one has nothing to delete.
   */
  it('deletes from the editor footer, on an existing category only', () => {
    const { fixture, httpTesting, tab } = setup();

    tab.add();
    fixture.detectChanges();
    expect(tab.editing()).toBe(false);
    tab.removeCurrent();
    fixture.detectChanges();
    httpTesting.expectNone((r) => r.method === 'DELETE');

    tab.edit(INTERNAL);
    fixture.detectChanges();
    expect(tab.editing()).toBe(true);

    tab.removeCurrent();
    fixture.detectChanges();
    acceptConfirm();
    httpTesting
      .expectOne(
        (r) =>
          r.method === 'DELETE' &&
          r.url ===
            '/rest/publication-categories/publication-category/dk-dma-internal-publications',
      )
      .flush(null);
    fixture.detectChanges();

    // The drawer closes only once the category is gone, and the list is re-read.
    expect(tab.editorModel()).toBeUndefined();
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([]);
  });

  /** A refused delete leaves the admin on the category the message is about. */
  it('keeps the editor open when the delete is refused', () => {
    const { fixture, httpTesting, tab } = setup();

    tab.edit(INTERNAL);
    fixture.detectChanges();
    tab.removeCurrent();
    fixture.detectChanges();
    acceptConfirm();

    httpTesting
      .expectOne((r) => r.method === 'DELETE')
      .flush({ code: 'CATEGORY_IN_USE', message: 'nope' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(tab.editorModel()).toBeDefined();
  });

  it('asks before deleting, and does nothing until confirmed', () => {
    // The confirm is a request bus: nothing is sent until the mounted dialog
    // calls accept, so an unconfirmed delete must produce no request at all.
    const { fixture, httpTesting, tab } = setup();

    tab.remove(INTERNAL);
    fixture.detectChanges();
    httpTesting.expectNone((r) => r.method === 'DELETE');
  });

  /**
   * A refused delete is re-stated in the reader's language, with the counts.
   *
   * The server's sentence is English prose in an otherwise Danish UI, and it
   * names "publications" the admin has no screen for. What an admin acts on is
   * the two numbers, so they are lifted out and the sentence is discarded.
   */
  it('translates the in-use refusal and renders its counts, never the server sentence', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    tab.remove(INTERNAL);
    fixture.detectChanges();
    acceptConfirm();

    httpTesting
      .expectOne((r) => r.method === 'DELETE')
      .flush(
        {
          code: 'CATEGORY_IN_USE',
          message: "3 series and 12 publication(s) still belong to 'x'. Move them first.",
        },
        { status: 409, statusText: 'Conflict' },
      );

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.publications.categories.toast.deleteInUse',
      { name: 'Internal publications', series: 3, publications: 12 },
    );
  });

  /**
   * A refusal whose sentence does not carry the counts still reads as a refusal.
   *
   * Guessing a number would be worse than not having one, and falling back to
   * "could not be deleted" would drop the only actionable part of the answer.
   */
  it('states the in-use refusal without counts when the message does not carry them', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    tab.remove(INTERNAL);
    fixture.detectChanges();
    acceptConfirm();

    httpTesting
      .expectOne((r) => r.method === 'DELETE')
      .flush({ code: 'CATEGORY_IN_USE', message: 'nope' }, { status: 409, statusText: 'Conflict' });

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.publications.categories.toast.deleteInUseUnknown',
      { name: 'Internal publications' },
    );
  });

  /** Any other failure keeps the generic message — and still says nothing in English prose. */
  it('falls back to the generic failure for a refusal it does not recognise', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    tab.remove(INTERNAL);
    fixture.detectChanges();
    acceptConfirm();

    httpTesting
      .expectOne((r) => r.method === 'DELETE')
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.publications.categories.toast.deleteError',
    );
  });

  /**
   * The export is an import file, so it is saved as the server's own bytes.
   *
   * Re-serializing it from a parsed object silently drops any field this client
   * does not model — which is exactly the field somebody will one day need moved
   * between installations.
   */
  it('exports the categories as a blob rather than as a reparsed object', () => {
    const { httpTesting, tab } = setup();

    tab.exportCategories();
    const request = httpTesting.expectOne('/rest/publication-categories/all');
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['[]'], { type: 'application/json' }));
  });

  it('names the export failure rather than leaving it silent', () => {
    const { httpTesting, tab, notifications } = setup();

    tab.exportCategories();
    httpTesting
      .expectOne('/rest/publication-categories/all')
      .flush(null, { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith(
      'admin.publications.categories.toast.exportError',
    );
  });

  /**
   * The picker opens only after the confirm has said what the file overwrites.
   *
   * The upload is not additive: every category the file names is rewritten field
   * for field, and by the time the job has run it is done.
   */
  it('confirms before opening the file picker', () => {
    const { fixture, tab } = setup();
    const input = fixture.nativeElement.querySelector(
      'input[type="file"]',
    ) as HTMLInputElement | null;
    expect(input).not.toBeNull();
    const click = vi.spyOn(input!, 'click').mockImplementation(() => undefined);

    tab.confirmImport();
    expect(click).not.toHaveBeenCalled();

    acceptConfirm();
    expect(click).toHaveBeenCalled();
  });

  it('uploads the chosen file as multipart and refetches', () => {
    const { fixture, httpTesting, tab, notifications } = setup();

    const file = new File(['[]'], 'categories.json', { type: 'application/json' });
    tab.onFileSelected({
      target: { files: [file], value: 'categories.json' },
    } as unknown as Event);

    const request = httpTesting.expectOne(
      '/rest/publication-categories/upload-publication-categories',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeInstanceOf(FormData);
    expect(((request.request.body as FormData).get('file') as File).name).toBe('categories.json');
    request.flush('Batch job submitted');
    fixture.detectChanges();

    expect(notifications.success).toHaveBeenCalledWith(
      'admin.publications.categories.toast.importSubmitted',
    );
    httpTesting.expectOne((r) => r.url.includes('/publication-categories/all')).flush([INTERNAL]);
  });

  /** A refusal is named through the section's own mapping, never as server prose. */
  it('names a refused import through the coded mapping', () => {
    const { httpTesting, tab, notifications } = setup();

    tab.onFileSelected({
      target: { files: [new File(['[]'], 'categories.json')], value: '' },
    } as unknown as Event);

    httpTesting
      .expectOne('/rest/publication-categories/upload-publication-categories')
      .flush({ code: 'NO_FILE' }, { status: 400, statusText: 'Bad Request' });

    expect(notifications.error).toHaveBeenCalledWith('admin.publications.error.noFile');
  });
});
