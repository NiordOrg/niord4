import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { PublicationRef } from '../../models/messages-filter-params.model';
import {
  PublicationsApiService,
  type PublicationSearchPage,
} from '../../services/api/publications-api.service';
import { PublicationsField } from './publications-field';

@Component({
  imports: [PublicationsField],
  template: `<app-publications-field
    [multiple]="multiple()"
    [domain]="domain()"
    [nameOnly]="nameOnly()"
  />`,
})
class Host {
  readonly multiple = signal(true);
  /** The domain the consumer scopes the picker to; null offers the catalogue. */
  readonly domain = signal<string | null>(null);
  readonly nameOnly = signal(false);
}

let api: { search: ReturnType<typeof vi.fn>; getByIds: ReturnType<typeof vi.fn> };

function page(items: readonly PublicationRef[], total = items.length): PublicationSearchPage {
  return { items, total };
}

function setup(appLanguage = 'en') {
  api = {
    search: vi.fn(() => of(page([]))),
    getByIds: vi.fn(() => of([] as PublicationRef[])),
  };

  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: PublicationsApiService, useValue: api },
      { provide: LocaleFormatService, useValue: { formatDate: () => '15.07.2026' } },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: signal(appLanguage),
          modelLanguages: [
            { value: 'en', label: 'English', flagPath: 'en.png', locale: 'en-GB' },
            { value: 'da', label: 'Dansk', flagPath: 'da.png', locale: 'da' },
          ],
        },
      },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const field = fixture.debugElement.query(By.directive(PublicationsField))
    .componentInstance as PublicationsField;
  return { fixture, field };
}

/** Open the results panel with a fixed set of rows, bypassing the debounced search. */
async function openWith(
  ctx: ReturnType<typeof setup>,
  rows: readonly PublicationRef[],
): Promise<void> {
  ctx.field.suggestions.set([...rows]);
  // The rows have to reach the autocomplete's input binding before the focus, or it
  // opens against an empty list and closes again.
  await ctx.fixture.whenStable();
  const input = ctx.fixture.nativeElement.querySelector('input.ac-input') as HTMLInputElement;
  input.dispatchEvent(new Event('focus'));
  await ctx.fixture.whenStable();
}

const optionRows = () => Array.from(document.querySelectorAll('.ac-panel .ac-option'));

/** Reach the protected search hook the way the base class does. */
function runSearch(field: PublicationsField, query: string): void {
  (field as unknown as { searchEntities(q: string): { subscribe(): void } })
    .searchEntities(query)
    .subscribe();
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('PublicationsField', () => {
  it('defaults to the two citable statuses and the message-report type', () => {
    const { field } = setup();
    runSearch(field, 'nav');

    expect(api.search).toHaveBeenCalledTimes(1);
    const [query, options] = api.search.mock.calls[0];
    expect(query).toBe('nav');
    expect(options.statuses).toEqual(['PUBLISHED', 'OPEN']);
    expect(options.type).toBe('MESSAGE_REPORT');
  });

  /**
   * The consumer decides the domain, and the field passes it through.
   *
   * The two consumers mean different domains — the editor cites from the desk
   * being worked at, the messages list filters within the domain being searched
   * — so reading one here would be right for one of them and wrong for the other.
   */
  it('passes the consumer’s domain to the search, and null when there is none', () => {
    const ctx = setup();
    runSearch(ctx.field, 'nav');
    expect(api.search.mock.calls[0][1].domain).toBeNull();

    ctx.fixture.componentInstance.domain.set('niord-nm');
    ctx.fixture.detectChanges();
    runSearch(ctx.field, 'nav');
    expect(api.search.mock.calls[1][1].domain).toBe('niord-nm');
  });

  /** Resolution by id stays unscoped: a chip must render whatever it names. */
  it('never scopes the by-id hydration', () => {
    const ctx = setup();
    ctx.fixture.componentInstance.domain.set('niord-nm');
    ctx.fixture.detectChanges();

    (ctx.field as unknown as { resolveByIds(ids: readonly string[]): { subscribe(): void } })
      .resolveByIds(['a'])
      .subscribe();

    expect(api.getByIds).toHaveBeenCalledWith(['a']);
  });

  it('browses a bounded page rather than one oversized window', () => {
    const { field } = setup();
    runSearch(field, '');
    expect(api.search.mock.calls[0][1].maxSize).toBe(50);

    runSearch(field, 'nav');
    // A type-ahead keeps the service's own small default.
    expect(api.search.mock.calls[1][1].maxSize).toBeUndefined();
  });

  it('says how many rows are beyond the page instead of truncating in silence', async () => {
    const ctx = setup();
    const row: PublicationRef = { publicationId: 'a', descs: [] };
    api.search.mockReturnValue(of(page([row], 41)));
    runSearch(ctx.field, '');
    ctx.field.suggestions.set([row]);
    await ctx.fixture.whenStable();

    expect(ctx.fixture.nativeElement.textContent).toContain('messages.filter.publication.more');
  });

  it('renders no overflow line when the page is the whole answer', async () => {
    const ctx = setup();
    const row: PublicationRef = { publicationId: 'a', descs: [] };
    api.search.mockReturnValue(of(page([row], 1)));
    runSearch(ctx.field, '');
    ctx.field.suggestions.set([row]);
    await ctx.fixture.whenStable();

    expect(ctx.fixture.nativeElement.textContent).not.toContain('messages.filter.publication.more');
  });

  /** A count left standing after the list has been cleared describes nothing. */
  it('drops the overflow line with the suggestion list it describes', async () => {
    const ctx = setup();
    const row: PublicationRef = { publicationId: 'a', descs: [] };
    api.search.mockReturnValue(of(page([row], 41)));
    runSearch(ctx.field, '');
    ctx.field.suggestions.set([row]);
    await ctx.fixture.whenStable();
    expect(ctx.fixture.nativeElement.textContent).toContain('messages.filter.publication.more');

    ctx.field.suggestions.set([]);
    await ctx.fixture.whenStable();
    expect(ctx.fixture.nativeElement.textContent).not.toContain('messages.filter.publication.more');
  });

  it('labels a row in the active language', () => {
    const { field } = setup('da');
    expect(
      field.labelOf({
        publicationId: 'p1',
        descs: [
          { lang: 'en', title: 'Notices to Mariners' },
          { lang: 'da', title: 'Efterretninger for Søfarende' },
        ],
      }),
    ).toBe('Efterretninger for Søfarende');
  });

  /**
   * A publication named in one language is still one an editor may need to cite;
   * rendering it as a bare identifier hides it behind a translation gap.
   */
  it('falls back through the configured languages rather than showing a raw id', () => {
    const { field } = setup('da');
    expect(
      field.labelOf({ publicationId: 'a4d3-uuid', descs: [{ lang: 'en', title: 'Danish Only' }] }),
    ).toBe('Danish Only');
  });

  it('falls back to the id only when nothing carries a title', () => {
    const { field } = setup();
    expect(field.labelOf({ publicationId: 'a4d3-uuid', descs: [{ lang: 'en' }] })).toBe(
      'a4d3-uuid',
    );
    expect(field.labelOf({ publicationId: 'a4d3-uuid' })).toBe('a4d3-uuid');
  });

  it('badges each row with its issue status, its series and the date it went out', async () => {
    const ctx = setup();
    await openWith(ctx, [
      {
        publicationId: 'i1',
        publicationSeriesId: 'weekly-ntm',
        seriesNames: { en: 'Notices to Mariners' },
        status: 'PUBLISHED',
        publicFrom: 1_700_000_000_000,
        descs: [{ lang: 'en', title: 'NtM week 33' }],
      },
      {
        publicationId: 'i2',
        publicationSeriesId: 'weekly-ntm',
        seriesNames: { en: 'Notices to Mariners' },
        status: 'OPEN',
        descs: [{ lang: 'en', title: 'NtM week 34' }],
      },
      {
        publicationId: 'i3',
        status: 'RETIRED',
        descs: [{ lang: 'en', title: 'Withdrawn one' }],
      },
    ]);

    const rows = optionRows();
    expect(rows).toHaveLength(3);

    expect(rows[0].querySelector('.status-badge')?.classList).toContain('is-issue-published');
    expect(rows[0].textContent).toContain('admin.publications.issueStatus.PUBLISHED');
    expect(rows[0].textContent).toContain('NtM week 33');
    // The series is NAMED, not keyed: `weekly-ntm` identifies it only to a
    // reader who already knows the catalogue.
    expect(rows[0].textContent).toContain('Notices to Mariners');
    expect(rows[0].textContent).not.toContain('weekly-ntm');
    // The date goes through the one locale formatter, never a raw epoch.
    expect(rows[0].textContent).toContain('15.07.2026');
    expect(rows[0].textContent).not.toContain('1700000000000');

    expect(rows[1].querySelector('.status-badge')?.classList).toContain('is-issue-open');
    // Withdrawn is neither settled nor pending: the neutral badge, and it still renders.
    expect(rows[2].textContent).toContain('admin.publications.issueStatus.RETIRED');
  });

  /**
   * A name in another language beats the id; the id is the last resort.
   *
   * The pickers send no `lang`, so a series named only in Danish comes back with
   * exactly that — and falling straight to the key would hide a real publication
   * behind a translation gap, which is the failure the title ladder exists for.
   */
  it('falls back through the configured languages before showing the series id', async () => {
    const ctx = setup();
    await openWith(ctx, [
      {
        publicationId: 'i1',
        publicationSeriesId: 'weekly-ntm',
        seriesNames: { da: 'Efterretninger for Søfarende' },
        descs: [{ lang: 'en', title: 'NtM week 33' }],
      },
      {
        publicationId: 'i2',
        publicationSeriesId: 'aftaler',
        descs: [{ lang: 'en', title: 'Something else' }],
      },
    ]);

    const rows = optionRows();
    expect(rows[0].textContent).toContain('Efterretninger for Søfarende');
    expect(rows[0].textContent).not.toContain('weekly-ntm');
    // Nothing named it, so the key is all there is — and it still says something.
    expect(rows[1].textContent).toContain('aftaler');
  });

  /**
   * `nameOnly` is for the surfaces that have already narrowed the set, where the
   * three qualifiers say the same thing on every row and crop the one that picks.
   */
  it('nameOnly renders the title alone — no badge, no series, no date', async () => {
    const ctx = setup();
    ctx.fixture.componentInstance.nameOnly.set(true);
    await ctx.fixture.whenStable();

    await openWith(ctx, [
      {
        publicationId: 'i1',
        publicationSeriesId: 'weekly-ntm',
        seriesNames: { en: 'Notices to Mariners' },
        status: 'PUBLISHED',
        publicFrom: 1_700_000_000_000,
        descs: [{ lang: 'en', title: 'NtM week 33' }],
      },
    ]);

    const rows = optionRows();
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('NtM week 33');
    expect(rows[0].querySelector('.status-badge')).toBeNull();
    expect(rows[0].textContent).not.toContain('admin.publications.issueStatus.PUBLISHED');
    expect(rows[0].textContent).not.toContain('Notices to Mariners');
    expect(rows[0].textContent).not.toContain('15.07.2026');
  });

  /** A legacy-half row structurally cannot carry a status; it must still be offerable. */
  it('renders a row with no status without a badge', async () => {
    const ctx = setup();
    await openWith(ctx, [
      { publicationId: 'old', descs: [{ lang: 'en', title: 'Legacy publication' }] },
    ]);

    const rows = optionRows();
    expect(rows).toHaveLength(1);
    expect(rows[0].querySelector('.status-badge')).toBeNull();
    expect(rows[0].textContent).toContain('Legacy publication');
  });
});
