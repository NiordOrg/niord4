import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { provideRouter } from '@angular/router';
import { PublicationListComponent } from './publication-list.component';
import { PublicationRow } from '../../../../core/models/publication.util';
import { getTranslocoTestingModule, provideTestNiordConfig } from '../../../../testing/test-config';

describe('PublicationListComponent', () => {
  let fixture: ComponentFixture<PublicationListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PublicationListComponent, getTranslocoTestingModule()],
      providers: [provideRouter([]), provideTestNiordConfig()],
      schemas: [NO_ERRORS_SCHEMA],
    }).compileComponents();
    fixture = TestBed.createComponent(PublicationListComponent);
  });

  it('should render publication titles', () => {
    const rows: PublicationRow[] = [
      { publication: { publicationId: 'p1', descs: [{ lang: 'en', title: 'NM Weekly Report' }] } },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('NM Weekly Report');
  });

  it('should show download link when descs[0].link exists', () => {
    const rows: PublicationRow[] = [
      {
        publication: {
          publicationId: 'p1',
          descs: [{ lang: 'en', title: 'Report', link: 'https://example.com/dl' }],
        },
      },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a[href="https://example.com/dl"]');
    expect(link).toBeTruthy();
  });

  // The message list reads the publication from this parameter, and links into it from
  // elsewhere carry the same name, so it is part of the site's address, not a detail.
  it('links Browse on a message report to the message list of that publication', () => {
    const rows: PublicationRow[] = [
      {
        publication: {
          publicationId: '6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b',
          type: 'MESSAGE_REPORT',
          descs: [{ lang: 'en', title: 'NtM week 38' }],
        },
      },
      { publication: { publicationId: 'p2', type: 'REPOSITORY' } },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    // The link cells; the copy under the title, drawn below the sm width instead of them, is
    // asserted below.
    const links = (fixture.nativeElement as HTMLElement).querySelectorAll(
      'td a[href^="/messages/details"]',
    );
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe(
      '/messages/details?publicationId=6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b',
    );
  });

  // Every row has a Download link and many a Browse link, so a link's own text tells a screen
  // reader nothing; the row header is what says which publication it belongs to.
  it('makes each title the header of its row', () => {
    const rows: PublicationRow[] = [
      {
        publication: {
          publicationId: 'p1',
          type: 'MESSAGE_REPORT',
          descs: [{ lang: 'en', title: 'NtM week 38', link: '/rest/repo/file/p1.pdf' }],
        },
      },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    const row = (fixture.nativeElement as HTMLElement).querySelector('tbody tr')!;
    const header = row.firstElementChild!;
    expect(header.tagName).toBe('TH');
    expect(header.getAttribute('scope')).toBe('row');
    expect(header.firstElementChild!.textContent!.trim()).toBe('NtM week 38');
    expect(row.querySelectorAll('td a')).toHaveLength(2);
  });

  // At 320 px the two link columns, which do not wrap, pushed the table past the screen and the
  // page scrolled sideways (WCAG 1.4.10). Below the sm width the links stand under the title, in
  // the row's header cell, and the two link cells and their column headers are not drawn; from
  // sm up it is the other way round. One of the two copies is always display:none, which also
  // takes it out of what a screen reader reads, so each link is met once. jsdom lays nothing
  // out, so the classes are what is asserted here; npm run layout:info measures the page at
  // 320 px in a browser.
  it('puts the links under the title below the sm width, and in their own cells from it', () => {
    const rows: PublicationRow[] = [
      {
        publication: {
          publicationId: 'p1',
          type: 'MESSAGE_REPORT',
          descs: [{ lang: 'en', title: 'NtM week 38', link: '/rest/repo/file/p1.pdf' }],
        },
      },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    const table = fixture.nativeElement as HTMLElement;
    const row = table.querySelector('tbody tr')!;

    const under = row.querySelector('th .sm\\:hidden')!;
    expect(under).toBeTruthy();
    expect([...under.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual([
      '/rest/repo/file/p1.pdf',
      '/messages/details?publicationId=p1',
    ]);
    // The title's own text stays apart from the links, so it alone is in the content's language.
    expect(row.querySelector('th')!.firstElementChild!.contains(under)).toBe(false);

    const cells = [...row.querySelectorAll('td')];
    expect(cells).toHaveLength(2);
    for (const cell of cells) {
      expect(cell.classList).toContain('hidden');
      expect(cell.classList).toContain('sm:table-cell');
    }
    const columns = [...table.querySelectorAll('thead th')];
    expect(columns.map((th) => th.classList.contains('hidden'))).toEqual([false, true, true]);
    expect(columns.map((th) => th.classList.contains('sm:table-cell'))).toEqual([
      false,
      true,
      true,
    ]);
  });

  it('draws no line of links under a title that has none', () => {
    const rows: PublicationRow[] = [
      {
        publication: {
          publicationId: 'p2',
          type: 'REPOSITORY',
          descs: [{ lang: 'en', title: 'List of Lights' }],
        },
      },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    const header = (fixture.nativeElement as HTMLElement).querySelector('tbody th')!;
    expect(header.querySelector('.sm\\:hidden')).toBeNull();
    expect(header.textContent!.trim()).toBe('List of Lights');
  });

  // A publication with no Kalaallisut text arrives in Danish or English on a Kalaallisut page.
  it('marks a title and a category heading that are not in the language of the page', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [PublicationListComponent, getTranslocoTestingModule()],
      providers: [
        provideRouter([]),
        provideTestNiordConfig({ languages: ['kl', 'da', 'en'], defaultLanguage: 'kl' }),
      ],
      schemas: [NO_ERRORS_SCHEMA],
    });
    fixture = TestBed.createComponent(PublicationListComponent);
    const rows: PublicationRow[] = [
      {
        publication: { publicationId: 'p1', descs: [{ lang: 'da', title: 'Fyrliste 2026' }] },
        heading: { categoryId: 'cat-A', descs: [{ lang: 'da', name: 'Efterretninger' }] },
      },
      {
        publication: { publicationId: 'p2', descs: [{ lang: 'en', title: 'List of Lights' }] },
        heading: { categoryId: 'cat-B', descs: [{ lang: 'kl', name: 'Kisarsivik Nunaat' }] },
      },
    ];
    fixture.componentRef.setInput('rows', rows);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(document.documentElement.lang).toBe('kl');
    const marked = [...(fixture.nativeElement as HTMLElement).querySelectorAll('[lang]')].map(
      (el) => [el.getAttribute('lang'), el.textContent!.trim()],
    );
    expect(marked).toEqual([
      ['da', 'Efterretninger'],
      ['da', 'Fyrliste 2026'],
      ['en', 'List of Lights'],
    ]);
  });

  it('should draw a category heading only where the row carries one', () => {
    const category = { categoryId: 'cat-A', descs: [{ lang: 'en', name: 'Cat A' }] };
    const rows: PublicationRow[] = [
      { publication: { publicationId: 'p1' }, heading: category },
      { publication: { publicationId: 'p2' } },
    ];
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
    const headings = fixture.nativeElement.querySelectorAll('.publication-category-heading');
    expect(headings).toHaveLength(1);
    expect(headings[0].textContent.trim()).toBe('Cat A');
  });
});
