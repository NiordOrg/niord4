import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PublicationsComponent } from './publications.component';
import { provideTestNiordConfig, getTranslocoTestingModule } from '../../testing/test-config';
import { settle } from '../../testing/settle';
import { Publication } from '../../core/models/publication.model';
import { LanguageService } from '../../core/services/language.service';

describe('PublicationsComponent', () => {
  let fixture: ComponentFixture<PublicationsComponent>;
  let component: PublicationsComponent;
  let httpTesting: HttpTestingController;

  async function setUp(timeZone?: string) {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [PublicationsComponent, getTranslocoTestingModule()],
      providers: [
        provideTestNiordConfig(timeZone ? { timeZone } : undefined),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();

    fixture = TestBed.createComponent(PublicationsComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
  }

  /** The active list is read on creation; the historical one waits for a range. */
  async function loadActive(publications: Publication[] = []) {
    await settle();
    const request = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/publication'));
    request.flush(publications);
    await settle();
    return request;
  }

  /** The one request a range produces. */
  function historical() {
    return httpTesting.expectOne((r) => r.urlWithParams.includes('from='));
  }

  beforeEach(async () => {
    await setUp();
  });

  afterEach(() => httpTesting.verify());

  it('reads the active publications once, in the active language', async () => {
    const request = await loadActive([
      { publicationId: 'p1', descs: [{ lang: 'en', title: 'Test Pub' }] },
    ]);
    // `lang` is the one name the public API reads the language by; a `language` is dropped and
    // every row comes back in whichever language it was stored in first.
    expect(request.request.params.get('lang')).toBe('en');
    expect(request.request.params.has('language')).toBe(false);
    expect(request.request.urlWithParams).toContain('externalize=false');
    expect(component.activeRows()).toHaveLength(1);
  });

  it('puts the active language first in every row and every heading', async () => {
    await loadActive([
      {
        publicationId: 'p1',
        category: {
          categoryId: 'cat-A',
          descs: [
            { lang: 'da', name: 'Efterretninger' },
            { lang: 'en', name: 'Notices' },
          ],
        },
        descs: [
          { lang: 'da', title: 'EfS uge 38', link: '/rest/repo/file/efs-da.pdf' },
          { lang: 'en', title: 'NtM week 38', link: '/rest/repo/file/ntm-en.pdf' },
        ],
      },
    ]);

    const [row] = component.activeRows();
    expect(row.publication.descs![0]).toEqual({
      lang: 'en',
      title: 'NtM week 38',
      link: '/rest/repo/file/ntm-en.pdf',
    });
    expect(row.heading!.descs![0].name).toBe('Notices');
  });

  it('renders both tab panels, the two date inputs and the year shortcuts', async () => {
    await loadActive();

    const el = fixture.nativeElement as HTMLElement;
    // Panels are mounted eagerly, so the historical one, with its date inputs, exists before its
    // tab is chosen, and has to be constructed without a range.
    expect(el.querySelectorAll('[role="tabpanel"]')).toHaveLength(2);
    expect(el.querySelectorAll('app-publication-list')).toHaveLength(2);

    const dates = el.querySelectorAll<HTMLInputElement>('input[type="date"]');
    expect(dates).toHaveLength(2);
    expect(el.querySelector(`label[for="${dates[0].id}"]`)!.textContent!.trim()).toBe('From');
    expect(el.querySelector(`label[for="${dates[1].id}"]`)!.textContent!.trim()).toBe('To');
    expect(el.querySelectorAll('.chip')).toHaveLength(5);
  });

  it('takes a range from a date input and marks the matching year shortcut', async () => {
    await loadActive();

    const el = fixture.nativeElement as HTMLElement;
    const [from, to] = el.querySelectorAll<HTMLInputElement>('input[type="date"]');
    from.value = '2024-01-01';
    from.dispatchEvent(new Event('change'));
    to.value = '2024-12-31';
    to.dispatchEvent(new Event('change'));

    expect(component.startDate()).toBe('2024-01-01');
    expect(component.endDate()).toBe('2024-12-31');

    await settle();
    historical().flush([]);
    await settle();

    const shortcut = [...el.querySelectorAll('.chip')].find(
      (b) => b.textContent!.trim() === '2024',
    );
    expect(shortcut!.getAttribute('aria-pressed')).toBe('true');
    expect(shortcut!.classList.contains('is-active')).toBe(true);
  });

  it('writes both dates when a year shortcut is pressed', async () => {
    await loadActive();

    const el = fixture.nativeElement as HTMLElement;
    const shortcut = [...el.querySelectorAll<HTMLButtonElement>('.chip')][1];
    const year = shortcut.textContent!.trim();
    shortcut.click();

    expect(component.startDate()).toBe(`${year}-01-01`);
    expect(component.endDate()).toBe(`${year}-12-31`);

    await settle();
    historical().flush([]);
    await settle();
  });

  it('reads nothing until both ends of the range are given', async () => {
    await loadActive();
    component.startDate.set('2024-01-01');
    await settle();
    httpTesting.verify();
    expect(component.historicalRows()).toEqual([]);
  });

  // The epochs a range is sent as are the site's contract with the backend, and they are read
  // off the configured zone, never the browser's. Two zones are asserted: one east of UTC, one
  // west of it, so a boundary computed in the wrong zone cannot pass both. They are literals
  // because an expectation that recomputed them would assert only self-consistency.
  for (const [timeZone, label, from, to] of [
    ['Europe/Copenhagen', 'east of UTC', '1704063600000', '1735685999999'],
    ['America/Halifax', 'west of UTC', '1704081600000', '1735703999999'],
  ]) {
    it(`sends the day boundaries of the configured zone (${label})`, async () => {
      await setUp(timeZone);
      await loadActive();

      component.setDateRange('2024-01-01', '2024-12-31');
      await settle();

      const request = historical();
      expect(request.request.params.get('lang')).toBe('en');
      const params = new URL(request.request.urlWithParams, 'https://example.com').searchParams;
      expect(params.get('from')).toBe(from);
      expect(params.get('to')).toBe(to);
      request.flush([]);
      await settle();
      expect(component.historicalRows()).toEqual([]);
    });
  }

  it('crosses a month and a leap-day boundary the same way', async () => {
    await setUp('Europe/Copenhagen');
    await loadActive();

    component.setDateRange('2024-02-28', '2024-02-29');
    await settle();

    const request = historical();
    const params = new URL(request.request.urlWithParams, 'https://example.com').searchParams;
    expect(params.get('from')).toBe('1709074800000');
    expect(params.get('to')).toBe('1709247599999');
    request.flush([]);
    await settle();
  });

  // A day the calendar does not have is not a filter bound: the parameter is left off rather
  // than sent as whatever the arithmetic rolled it over to.
  it('omits a bound it cannot resolve to a real day', async () => {
    await setUp('Europe/Copenhagen');
    await loadActive();

    component.setDateRange('2024-01-01', '2024-13-40');
    await settle();

    const request = historical();
    const params = new URL(request.request.urlWithParams, 'https://example.com').searchParams;
    expect(params.get('from')).toBe('1704063600000');
    expect(params.get('to')).toBeNull();
    request.flush([]);
    await settle();
  });

  it('heads the first publication of each category run', async () => {
    const pubs: Publication[] = [
      {
        publicationId: 'p1',
        category: { categoryId: 'cat-A', descs: [{ lang: 'en', name: 'Cat A' }] },
      },
      {
        publicationId: 'p2',
        category: { categoryId: 'cat-A', descs: [{ lang: 'en', name: 'Cat A' }] },
      },
      {
        publicationId: 'p3',
        category: { categoryId: 'cat-B', descs: [{ lang: 'en', name: 'Cat B' }] },
      },
    ];
    await loadActive(pubs);

    const rows = component.activeRows();
    expect(rows[0].heading!.categoryId).toBe('cat-A');
    expect(rows[1].heading).toBeUndefined();
    expect(rows[2].heading!.categoryId).toBe('cat-B');
  });

  const el = () => fixture.nativeElement as HTMLElement;
  /** The year shortcuts, each with whether it reads as pressed. */
  const pressed = () =>
    [...el().querySelectorAll('.chip')].map((chip) => [
      chip.textContent!.trim(),
      chip.getAttribute('aria-pressed'),
    ]);
  /** What each panel's live region says. */
  const notices = () =>
    [...el().querySelectorAll('[role="alert"]')].map((region) => region.textContent!.trim());

  // A failed read that left the previous range's rows on screen had them read as the year just
  // pressed, and a reader downloaded what they took for the edition they asked for.
  it('empties the table and says so when a range fails after another one loaded', async () => {
    await loadActive();
    const [thisYear, lastYear] = component.ranges;

    el().querySelectorAll<HTMLButtonElement>('.chip')[0].click();
    await settle();
    historical().flush([{ publicationId: 'p-this', descs: [{ lang: 'en', title: 'This year' }] }]);
    await settle();
    fixture.detectChanges();
    expect(component.historicalRows()).toHaveLength(1);
    expect(pressed()).toContainEqual([thisYear.name, 'true']);

    el().querySelectorAll<HTMLButtonElement>('.chip')[1].click();
    await settle();
    fixture.detectChanges();
    // While the new read is on its way the rows on screen are still this year's, and it is this
    // year's shortcut that says so.
    expect(component.historicalRows()).toHaveLength(1);
    expect(pressed()).toContainEqual([thisYear.name, 'true']);
    expect(pressed()).toContainEqual([lastYear.name, 'false']);

    historical().flush('no', { status: 500, statusText: 'Server Error' });
    await settle();
    fixture.detectChanges();

    expect(component.historicalRows()).toEqual([]);
    expect(pressed().every(([, state]) => state === 'false')).toBe(true);
    expect(notices()).toEqual(['', 'Failed to load publications']);
  });

  it('marks the shortcut once its range has loaded', async () => {
    await loadActive();
    el().querySelectorAll<HTMLButtonElement>('.chip')[1].click();
    await settle();
    fixture.detectChanges();
    expect(pressed().every(([, state]) => state === 'false')).toBe(true);

    historical().flush([]);
    await settle();
    fixture.detectChanges();
    expect(pressed()[1]).toEqual([component.ranges[1].name, 'true']);
    expect(notices()).toEqual(['', '']);
  });

  // A From after the To is no range: the backend would answer with what was in force on both
  // days, read as the publications of a span that does not exist. Nothing is read, and the tab
  // looks as it does before both inputs are filled: no rows, no notice, no shortcut pressed.
  it('reads nothing while From is after To', async () => {
    await loadActive();
    fixture.detectChanges();
    const idle = { rows: component.historicalRows(), pressed: pressed(), notices: notices() };

    component.setDateRange('2024-12-31', '2024-01-01');
    await settle();
    fixture.detectChanges();

    httpTesting.expectNone((r) => r.urlWithParams.includes('from='));
    expect({ rows: component.historicalRows(), pressed: pressed(), notices: notices() }).toEqual(
      idle,
    );
  });

  it('takes the shown range down when From is moved past To', async () => {
    await loadActive();
    component.setDateRange('2024-01-01', '2024-12-31');
    await settle();
    historical().flush([{ publicationId: 'p-2024', descs: [{ lang: 'en', title: 'NtM 2024' }] }]);
    await settle();
    fixture.detectChanges();
    expect(component.historicalRows()).toHaveLength(1);

    const [from] = el().querySelectorAll<HTMLInputElement>('input[type="date"]');
    from.value = '2025-01-01';
    from.dispatchEvent(new Event('change'));
    await settle();
    fixture.detectChanges();

    httpTesting.expectNone((r) => r.urlWithParams.includes('from='));
    expect(component.historicalRows()).toEqual([]);
    expect(pressed().every(([, state]) => state === 'false')).toBe(true);
    expect(notices()).toEqual(['', '']);
  });

  // The two days are compared as the bounds they are sent as, in the deployment's zone: a range
  // of one day runs from its first millisecond to its last, and is read.
  it('reads a range of one day', async () => {
    await setUp('America/Halifax');
    await loadActive();

    component.setDateRange('2024-03-01', '2024-03-01');
    await settle();

    const request = historical();
    const params = new URL(request.request.urlWithParams, 'https://example.com').searchParams;
    expect(Number(params.get('from'))).toBeLessThan(Number(params.get('to')));
    request.flush([]);
    await settle();
  });

  // The To input's calendar starts at the From day, so a reader picking from it cannot reach a
  // day before it; one typed by hand is caught by the rule above.
  it('starts the To input at the From day', async () => {
    await loadActive();
    fixture.detectChanges();
    const [from, to] = el().querySelectorAll<HTMLInputElement>('input[type="date"]');
    expect(to.min).toBe('');

    from.value = '2024-05-01';
    from.dispatchEvent(new Event('change'));
    await settle();
    fixture.detectChanges();

    expect(to.min).toBe('2024-05-01');
    expect(from.max).toBe('');
  });

  it('empties the current list and says so when its read fails', async () => {
    await settle();
    httpTesting
      .expectOne((r) => r.url.includes('/rest/public/v1/publication'))
      .flush('no', { status: 503, statusText: 'Service Unavailable' });
    await settle();
    fixture.detectChanges();

    expect(component.activeRows()).toEqual([]);
    expect(notices()).toEqual(['Failed to load publications', '']);
  });

  it("does not leave the previous language's rows up when the read in the new one fails", async () => {
    await loadActive([{ publicationId: 'p1', descs: [{ lang: 'en', title: 'NtM week 38' }] }]);
    expect(component.activeRows()).toHaveLength(1);

    TestBed.inject(LanguageService).useLanguage('zz');
    await settle();
    const request = httpTesting.expectOne((r) => r.url.includes('/rest/public/v1/publication'));
    expect(request.request.params.get('lang')).toBe('zz');
    request.flush('no', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(component.activeRows()).toEqual([]);
  });
});
