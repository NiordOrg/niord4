import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SourcesPage } from './sources-page';

interface PageInternals {
  search: () => string;
  filtered: () => readonly { descs: readonly { name: string }[] }[];
  onSearch: (value: string) => void;
  pager: { first: () => number; setFirst: (first: number) => void };
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
      { provide: TicketedDownloadService, useValue: { openPublic: () => undefined } },
    ],
  });
  const fixture = TestBed.createComponent(SourcesPage);
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const vo = (id: number, name: string | null, abbreviation?: string | null) => ({
  id,
  active: true,
  descs: name === null ? null : [{ lang: 'en', name, abbreviation }],
});

describe('SourcesPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters on name OR abbreviation, case-insensitive, and is crash-safe on missing descs', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting.expectOne('/rest/sources/all?lang=en').flush([
      vo(1, 'Example Maritime Authority', 'EMA'),
      vo(2, 'Norwegian Authority', 'NA'),
      vo(3, null), // descs: null on the wire — such a row must not crash the filter
    ]);
    fixture.detectChanges();

    page.onSearch('ema');
    expect(page.filtered().length).toBe(1);
    expect(page.filtered()[0].descs[0].name).toBe('Example Maritime Authority');

    page.onSearch('AUTHORITY');
    expect(page.filtered().length).toBe(2); // the descs-less row simply never matches

    page.onSearch('');
    expect(page.filtered().length).toBe(3);
  });

  it('every filter change resets paging to the first page', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/sources/all?lang=en')
      .flush(Array.from({ length: 45 }, (_, i) => vo(i, `Source ${i}`)));
    fixture.detectChanges();

    page.pager.setFirst(40);
    expect(page.pager.first()).toBe(40);
    page.onSearch('Source');
    expect(page.pager.first()).toBe(0);
  });

  it('shows the none-yet empty state for an empty catalog and the error banner on failure', () => {
    const { fixture, httpTesting } = setup();
    httpTesting.expectOne('/rest/sources/all?lang=en').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-error-banner')).toBeNull();
  });

  it('shows the error banner with retry when the list load fails', () => {
    const { fixture, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/sources/all?lang=en')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });
});
