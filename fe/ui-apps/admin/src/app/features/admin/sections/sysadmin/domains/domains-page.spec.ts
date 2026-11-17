import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainEditorModel, DomainsPage } from './domains-page';

interface PageInternals {
  editorModel: () => DomainEditorModel | undefined;
  cogItems: () => MenuItem[];
  pager: { first: () => number; setFirst: (first: number) => void };
  onSearch: (value: string) => void;
  onImportClosed: () => void;
  addDomain: () => void;
  editDomain: (domain: AdminDomain) => void;
  copyDomain: (domain: AdminDomain) => void;
  deleteDomain: (domain: AdminDomain) => void;
  onEditorSaved: () => void;
  onEditorClosed: () => void;
}

const NW: AdminDomain = {
  domainId: 'niord-nw',
  name: 'Warnings',
  active: true,
  sortOrder: 1,
  timeZone: 'Europe/Copenhagen',
  inKeycloak: true,
  areas: [
    { id: 1, descs: [{ lang: 'en', name: 'Kattegat' }] },
    { id: 2, descs: [{ lang: 'en', name: 'Skagerrak' }] },
  ],
  categories: [{ id: 7, descs: [{ lang: 'en', name: 'Firing Exercises' }] }],
  messageSeries: [{ seriesId: 'dma-nw' }, { seriesId: 'dma-fa' }],
};
// Filter positive on NAME (not id) + a false inKeycloak.
const NM: AdminDomain = {
  domainId: 'niord-nm',
  name: 'NW Anchorland',
  active: false,
  sortOrder: 2,
  inKeycloak: false,
};
// Negative control: no name (null-safety) and the filter term buried in a column
// (timezone) the narrow haystack must ignore; inKeycloak absent (null state).
const NAMELESS: AdminDomain = {
  domainId: 'niord-fa',
  active: true,
  sortOrder: 3,
  timeZone: 'NW/Zone',
};

/** Drain the effect scheduler + microtask queue (zoneless — flush manually). */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

const isListRequest = (r: { url: string }) => r.url === '/rest/domains/all';

async function setup(domains: readonly AdminDomain[], listResponse?: { status: number }) {
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          helpUrls: {},
          // No trailing slash — the console link join must add it.
          keycloak: { url: 'https://kc.example.com/auth', realm: 'dma-test', clientId: 'web' },
        },
      },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
      { provide: TicketedDownloadService, useValue: { openPublic: vi.fn() } },
      { provide: DomainService, useValue: { refresh: vi.fn() } },
    ],
  });
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.domains.confirmDelete.header': 'Delete domain?',
      'admin.domains.confirmDelete.message': "Delete domain ID '{{domainId}}'?",
    },
    'en',
    { merge: true },
  );
  const fixture = TestBed.createComponent(DomainsPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  TestBed.tick();
  const listRequest = httpTesting.expectOne(isListRequest);
  if (listResponse) {
    listRequest.flush('load failed', { status: listResponse.status, statusText: 'Server Error' });
  } else {
    listRequest.flush([...domains]);
  }
  await settle();
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    confirmService: fixture.debugElement.injector.get(ConfirmService),
  };
}

function rows(fixture: { nativeElement: HTMLElement }): HTMLTableRowElement[] {
  return Array.from(fixture.nativeElement.querySelectorAll('tbody tr'));
}

function cellTexts(fixture: { nativeElement: HTMLElement }, column: number): string[] {
  return rows(fixture).map((row) => row.cells[column].textContent?.trim() ?? '');
}

describe('DomainsPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters on domainId OR name only — case-insensitive, null-safe, other columns ignored', async () => {
    const { fixture, page } = await setup([NW, NM, NAMELESS]);
    // 'nw' hits NW's id and NM's name; NAMELESS carries it in its timezone only
    // (and has no name at all — the haystack must not throw on it).
    page.onSearch('nw');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['niord-nw', 'niord-nm']);

    // Case-insensitive name-only match.
    page.onSearch('WARN');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['niord-nw']);

    page.onSearch('');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['niord-nw', 'niord-nm', 'niord-fa']);
  });

  it('search resets the pager to the first page', async () => {
    const many = Array.from({ length: 25 }, (_, i) => ({
      ...NW,
      domainId: `domain-${String(i + 1).padStart(2, '0')}`,
    }));
    const { page } = await setup(many);
    page.pager.setFirst(20);
    expect(page.pager.first()).toBe(20);

    page.onSearch('domain');
    expect(page.pager.first()).toBe(0);
  });

  it('renders order, name, timezone and the per-line area/category/series cells', async () => {
    const { fixture } = await setup([NW]);
    expect(cellTexts(fixture, 1)).toEqual(['1']);
    expect(cellTexts(fixture, 3)).toEqual(['Warnings']);
    expect(cellTexts(fixture, 4)).toEqual(['Europe/Copenhagen']);

    const lines = (column: number) =>
      Array.from(rows(fixture)[0].cells[column].querySelectorAll('div')).map(
        (line) => line.textContent?.trim() ?? '',
      );
    expect(lines(5)).toEqual(['Kattegat', 'Skagerrak']);
    expect(lines(6)).toEqual(['Firing Exercises']);
    expect(lines(7)).toEqual(['dma-nw', 'dma-fa']);
  });

  it('shows the in-Keycloak indicator only for true — false and null render nothing', async () => {
    const { fixture } = await setup([NW, NM, NAMELESS]);
    const indicators = rows(fixture).map(
      (row) => row.cells[2].querySelector('app-icon[name="lucideCheck"]') !== null,
    );
    expect(indicators).toEqual([true, false, false]);
  });

  it('marks the active flag with the boolean status dot', async () => {
    const { fixture } = await setup([NW, NM]);
    const dotStates = rows(fixture).map((row) => {
      const dot = row.cells[0].querySelector('.status-dot');
      return dot?.classList.contains('is-active') ?? false;
    });
    expect(dotStates).toEqual([true, false]);
  });

  it('builds the Keycloak console link from the config (trailing-slash join, realm from config)', async () => {
    const { fixture } = await setup([]);
    const link = fixture.nativeElement.querySelector('a[target="_blank"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe(
      'https://kc.example.com/auth/admin/dma-test/console/#/dma-test/clients',
    );
    expect(link.getAttribute('rel')).toBe('noopener');
  });

  it('row actions: edit opens edit mode, copy clones into add mode with a blanked id', async () => {
    const { fixture, page } = await setup([NW]);
    const actionButtons = rows(fixture)[0].cells[8].querySelectorAll('button');

    (actionButtons[0] as HTMLButtonElement).click();
    expect(page.editorModel()).toEqual({ mode: 'edit', domain: NW });

    (actionButtons[1] as HTMLButtonElement).click();
    expect(page.editorModel()).toEqual({ mode: 'add', domain: { ...NW, domainId: '' } });
  });

  it('New seeds the defaults and leaves the timezone unanswered — never the browser one', async () => {
    const { page } = await setup([]);
    page.addDomain();
    expect(page.editorModel()?.domain.timeZone).toBe('');
    expect(page.editorModel()?.domain.timeZone).not.toBe(
      Intl.DateTimeFormat().resolvedOptions().timeZone,
    );
    expect(page.editorModel()).toEqual({
      mode: 'add',
      domain: {
        domainId: '',
        active: true,
        sortOrder: 0,
        timeZone: '',
        messageSortOrder: 'AREA ASC',
        publish: false,
        atons: false,
        templates: false,
      },
    });
  });

  it('delete confirms with the confirm text and DELETEs + refreshes on accept', async () => {
    const { fixture, httpTesting, confirmService } = await setup([NW]);
    (rows(fixture)[0].cells[8].querySelectorAll('button')[2] as HTMLButtonElement).click();

    const active = confirmService.active();
    expect(active?.header).toBe('Delete domain?');
    expect(active?.message).toBe("Delete domain ID 'niord-nw'?");

    active?.accept();
    const req = httpTesting.expectOne('/rest/domains/domain/niord-nw');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.domains.toast.deleted',
    );
    // The list refetches AND the live session catalog follows (header
    // switcher/capabilities update without a reload).
    expect(TestBed.inject(DomainService).refresh).toHaveBeenCalled();
    await settle();
    httpTesting.expectOne(isListRequest).flush([]);
  });

  it('delete error toasts and refreshes neither the list nor the live catalog', async () => {
    const { page, httpTesting, confirmService } = await setup([NW]);
    page.deleteDomain(NW);
    confirmService.active()?.accept();
    httpTesting
      .expectOne('/rest/domains/domain/niord-nw')
      .flush('nope', { status: 500, statusText: 'Server Error' });
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.domains.toast.deleteError',
    );
    expect(TestBed.inject(DomainService).refresh).not.toHaveBeenCalled();
    await settle();
    httpTesting.expectNone(isListRequest);
  });

  it('editor saved: closes the drawer, refetches the list and refreshes the live catalog', async () => {
    const { page, httpTesting } = await setup([NW]);
    page.editDomain(NW);
    page.onEditorSaved();
    expect(page.editorModel()).toBeUndefined();
    expect(TestBed.inject(DomainService).refresh).toHaveBeenCalled();
    await settle();
    httpTesting.expectOne(isListRequest).flush([]);
  });

  it('editor closed (pure cancel): closes only — no list refetch, no catalog refresh', async () => {
    const { page, httpTesting } = await setup([NW]);
    page.editDomain(NW);
    page.onEditorClosed();
    expect(page.editorModel()).toBeUndefined();
    expect(TestBed.inject(DomainService).refresh).not.toHaveBeenCalled();
    await settle();
    httpTesting.expectNone(isListRequest);
  });

  it('cog Export opens the public export URL including inactive domains', async () => {
    const { page } = await setup([]);
    page.cogItems()[0].command?.();
    expect(TestBed.inject(TicketedDownloadService).openPublic).toHaveBeenCalledWith(
      '/rest/domains/all?inactive=true',
    );
  });

  it('wires the import dialog to the upload endpoint and refetches when it closes', async () => {
    const { fixture, page, httpTesting } = await setup([]);
    const dialog = fixture.debugElement.query(By.directive(JsonImportDialog))
      .componentInstance as JsonImportDialog;
    expect(dialog.uploadUrl()).toBe('/rest/domains/upload-domains');
    expect(dialog.accept()).toBe('.json');
    expect(dialog.jobLink()).toBe('/sysadmin/batch/domain-import');

    page.onImportClosed();
    await settle();
    httpTesting.expectOne(isListRequest).flush([]);
  });

  it('shows the empty state when there are no domains at all', async () => {
    const { fixture } = await setup([]);
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('shows the no-matches empty state when the filter excludes every domain', async () => {
    const { fixture, page } = await setup([NW]);
    page.onSearch('zzz');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('shows the error banner when the list load fails', async () => {
    const { fixture } = await setup([], { status: 500 });
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });
});
