import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { EMPTY } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MailingListReportsDialog } from './mailing-list-reports-dialog';

interface Internals {
  reports: () => unknown[] | undefined;
  result: () => string | undefined;
  resultReport: () => { id: string } | undefined;
  open: () => void;
  execute: (report: { id: string }) => void;
  openInNewWindow: (report: { id: string }) => void;
}

function setup() {
  const openTicketed = vi.fn();
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
      { provide: TicketedDownloadService, useValue: { openTicketed } },
    ],
  });
  const fixture = TestBed.createComponent(MailingListReportsDialog);
  fixture.detectChanges();
  return {
    fixture,
    dialog: fixture.componentInstance as unknown as Internals,
    httpTesting: TestBed.inject(HttpTestingController),
    openTicketed,
  };
}

describe('MailingListReportsDialog', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    // The dialog portals its panel to the body-level CDK overlay; dispose leaks.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('open loads the reports; the pre-flush state is loading (undefined), distinct from empty', () => {
    const { dialog, httpTesting } = setup();
    dialog.open();
    expect(dialog.reports()).toBeUndefined(); // loading, NOT the "No report defined" empty state
    httpTesting
      .expectOne('/rest/mailing-lists/reports?lang=en')
      .flush([{ id: 'r1', name: 'Daily NW' }]);
    expect(dialog.reports()).toHaveLength(1);
  });

  it('a report list of [] is the empty state (not loading)', () => {
    const { dialog, httpTesting } = setup();
    dialog.open();
    httpTesting.expectOne('/rest/mailing-lists/reports?lang=en').flush([]);
    expect(dialog.reports()).toEqual([]);
  });

  it('execute fetches the report HTML and shows it as the result', () => {
    const { dialog, httpTesting } = setup();
    dialog.open();
    httpTesting.expectOne('/rest/mailing-lists/reports?lang=en').flush([{ id: 'r1', name: 'R' }]);

    dialog.execute({ id: 'r1' });
    const req = httpTesting.expectOne('/rest/mailing-lists/execute-report/r1.html?lang=en');
    expect(req.request.responseType).toBe('text');
    req.flush('<h1>Report</h1>');
    expect(dialog.result()).toBe('<h1>Report</h1>');
    expect(dialog.resultReport()?.id).toBe('r1');
  });

  it('open in new window requests a user-role ticketed download of the report URL', () => {
    const { dialog, httpTesting, openTicketed } = setup();
    dialog.open();
    httpTesting.expectOne('/rest/mailing-lists/reports?lang=en').flush([]);
    dialog.openInNewWindow({ id: 'r1' });
    expect(openTicketed).toHaveBeenCalledWith(
      '/rest/mailing-lists/execute-report/r1.html?lang=en',
      'user',
    );
  });

  it('renders a help anchor in the header pointing at the report-action manual section', async () => {
    const router = {
      navigate: vi.fn(),
      url: '/',
      // HelpAnchor's HelpUrlService reads router.events + routerState.snapshot eagerly.
      events: EMPTY,
      routerState: { snapshot: { root: { data: {}, firstChild: null } } },
    } as unknown as Router;

    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
        {
          provide: APP_CONFIG,
          useValue: {
            apiBaseUrl: '/rest',
            appName: 'Niord',
            documentationUrl: 'https://docs.example',
          },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestTransloco(),
        {
          provide: NotificationService,
          useValue: { success: () => undefined, error: () => undefined },
        },
        { provide: TicketedDownloadService, useValue: { openTicketed: vi.fn() } },
      ],
    });

    const fixture = TestBed.createComponent(MailingListReportsDialog);
    const httpTesting = TestBed.inject(HttpTestingController);
    fixture.autoDetectChanges();
    (fixture.componentInstance as unknown as Internals).open();
    httpTesting.expectOne('/rest/mailing-lists/reports?lang=en').flush([]);
    await fixture.whenStable();

    const link = document.querySelector<HTMLAnchorElement>(
      'a[href="https://docs.example/editor-manual/manual.html#report-action"]',
    );
    expect(link).not.toBeNull();
    expect(link?.target).toBe('_blank');
  });
});
