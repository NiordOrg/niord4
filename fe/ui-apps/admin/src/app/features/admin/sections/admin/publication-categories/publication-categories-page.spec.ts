import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AppConfig } from '../../../../../core/config/app-config.interface';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import adminRoutes from '../../../admin.routes';

const TEST_CONFIG = { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } as unknown as AppConfig;

const LANGUAGES = [
  { value: 'da', label: 'Dansk', locale: 'da', flagPath: '' },
  { value: 'en', label: 'English', locale: 'en-GB', flagPath: '' },
];

/**
 * The section as the router actually builds it.
 *
 * Mounted through `admin.routes` rather than through a hand-made route, because
 * what is under test is that the descriptor, the override and the component
 * agree — a spec that instantiates the page directly passes just as well when
 * the route is missing entirely.
 */
async function setup(url = '/admin/publication-categories') {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: TEST_CONFIG },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: LanguageService,
        useValue: { selectedLanguage: () => 'en', modelLanguages: LANGUAGES },
      },
      { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      provideRouter([{ path: 'admin', children: adminRoutes }]),
    ],
  });
  const harness = await RouterTestingHarness.create(url);
  harness.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  // The projected list fetches on mount; answered here so every test is about
  // the page rather than about the request under it.
  httpTesting
    .match((r) => r.url.includes('/publication-categories/all'))
    .forEach((r) => r.flush([]));
  // The estate the list derives its publication counts from.
  httpTesting
    .match((r) => r.url.includes('/publication-series/search-details'))
    .forEach((r) => r.flush([]));
  harness.detectChanges();
  return { doc: harness.routeNativeElement!.ownerDocument, httpTesting };
}

describe('the publication-categories section', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('resolves its route to the page rather than to the coming-soon stub', async () => {
    const { doc } = await setup();

    expect(doc.querySelector('app-publication-categories-page')).not.toBeNull();
    expect(doc.querySelector('app-coming-soon')).toBeNull();
  });

  /** The page owns the heading; the list it wraps is unchanged by the move. */
  it('renders its own title and description above the categories list', async () => {
    const { doc } = await setup();

    const header = doc.querySelector('app-admin-section-page header');
    expect(header?.textContent).toContain('admin.sections.publication-categories.label');
    expect(header?.textContent).toContain('admin.sections.publication-categories.description');
    expect(doc.querySelector('app-publication-categories-tab')).not.toBeNull();
  });

  /**
   * The rail entry sits directly under Publications.
   *
   * They are read as a pair — a series is filed under a category, and the
   * category decides whether that series reaches the public site — so anything
   * between them makes the reader hunt for the second half.
   */
  it('is listed in the rail immediately after Publications', async () => {
    const { doc } = await setup();

    const hrefs = [...doc.querySelectorAll('app-admin-area-shell nav a')].map((a) =>
      a.getAttribute('href'),
    );
    expect(hrefs.indexOf('/admin/publication-categories')).toBe(
      hrefs.indexOf('/admin/publications') + 1,
    );
  });
});
