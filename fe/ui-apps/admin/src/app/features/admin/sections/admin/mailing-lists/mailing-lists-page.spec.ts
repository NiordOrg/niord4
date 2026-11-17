import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideFakeAuthFacade } from '../../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MailingListsPage } from './mailing-lists-page';
import type { MailingList } from './mailing-list.model';

interface PageInternals {
  search: () => string;
  filtered: () => readonly MailingList[];
  onSearch: (value: string) => void;
  canManage: () => boolean;
  addMailingList: () => void;
  editorModel: () => { mode: string; mailingList: MailingList } | undefined;
  cogItems: () => { label: string; command: () => void }[];
}

function setup(opts: { sysadmin?: boolean } = {}) {
  const openTicketed = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideFakeAuthFacade({
        isAuthenticated: true,
        admin: true,
        sysadmin: opts.sysadmin ?? false,
      }),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
      { provide: TicketedDownloadService, useValue: { openTicketed } },
    ],
  });
  const fixture = TestBed.createComponent(MailingListsPage);
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    openTicketed,
  };
}

const row = (id: string, extras: Partial<MailingList> = {}): MailingList => ({
  mailingListId: id,
  active: true,
  descs: [{ lang: 'en', name: id, description: '' }],
  ...extras,
});

describe('MailingListsPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters over the current-language name and description, case-insensitive', () => {
    const { fixture, page, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/mailing-lists/search?lang=en')
      .flush([
        row('nm', { descs: [{ lang: 'en', name: 'Navwarn', description: 'coastal' }] }),
        row('firing', { descs: [{ lang: 'en', name: 'Firing', description: 'ranges' }] }),
      ]);
    page.onSearch('COAST');
    fixture.detectChanges();
    expect(page.filtered().map((l) => l.mailingListId)).toEqual(['nm']);
  });

  it('exposes create/export/import affordances only to sysadmin (canManage)', () => {
    const admin = setup({ sysadmin: false });
    admin.httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
    expect(admin.page.canManage()).toBe(false);
    TestBed.resetTestingModule();

    const sys = setup({ sysadmin: true });
    sys.httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
    expect(sys.page.canManage()).toBe(true);
    expect(sys.page.cogItems()).toHaveLength(2);
  });

  it('addMailingList opens the editor in add mode with a blank, active list', () => {
    const { page, httpTesting } = setup({ sysadmin: true });
    httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
    page.addMailingList();
    const model = page.editorModel();
    expect(model?.mode).toBe('add');
    expect(model?.mailingList).toMatchObject({ mailingListId: '', active: true });
  });

  it('Export requests a sysadmin-role ticketed download', () => {
    const { page, httpTesting, openTicketed } = setup({ sysadmin: true });
    httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
    page.cogItems()[0].command();
    expect(openTicketed).toHaveBeenCalledWith('/rest/mailing-lists/export', 'sysadmin');
  });
});
