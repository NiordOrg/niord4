import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { TEST_LANGUAGES } from '../../../../../shared/testing/language-testing';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import type { AdminPromulgationType, PromulgationServiceRef } from './promulgation-admin.model';
import { PromulgationTypeEditorModel, PromulgationTypesPage } from './promulgation-types-page';

interface PageInternals {
  editorModel: () => PromulgationTypeEditorModel | undefined;
  addItems: () => MenuItem[];
  cogItems: () => MenuItem[];
  pager: { first: () => number; setFirst: (first: number) => void };
  onSearch: (value: string) => void;
  onImportClosed: () => void;
  addType: (serviceId: string) => void;
  editType: (type: AdminPromulgationType) => void;
  deleteType: (type: AdminPromulgationType) => void;
  onEditorSaved: () => void;
  onEditorClosed: () => void;
}

const TYPES_URL = '/rest/promulgations/promulgation-types/all';
const SERVICES_URL = '/rest/promulgations/promulgation-services/all';

const NAVTEX: AdminPromulgationType = {
  typeId: 'navtex-dk',
  serviceId: 'navtex',
  name: 'NAVTEX',
  priority: 10,
  active: true,
  requirement: 'MANDATORY',
  language: 'da',
  domains: [
    { domainId: 'niord-nw', name: 'NW' },
    { domainId: 'niord-nm', name: 'NM' },
  ],
  messageTypes: ['COASTAL_WARNING', 'SUBAREA_WARNING'],
};
// Filter positive on NAME; no language/domains and no restriction set → blank cells.
const TWITTER: AdminPromulgationType = {
  typeId: 'tw-all',
  serviceId: 'twitter',
  name: 'Tweet warnings',
  priority: 0,
  active: false,
  requirement: 'OPTIONAL',
};
// Negative control: the filter term buried in a DOMAIN NAME the narrow haystack
// must ignore; an EMPTY restriction set must render a blank Types cell too.
const SAFETYNET: AdminPromulgationType = {
  typeId: 'sn-dk',
  serviceId: 'safetynet',
  name: 'SafetyNET',
  priority: 5,
  active: true,
  requirement: 'DEFAULT',
  domains: [{ domainId: 'x', name: 'tweet-domain' }],
  messageTypes: [],
};

const SERVICES: readonly PromulgationServiceRef[] = [
  { serviceId: 'navtex', serviceName: 'NAVTEX mailing list' },
  { serviceId: 'safetynet', serviceName: 'SafetyNET mailing list' },
  { serviceId: 'twitter', serviceName: 'Twitter' },
  { serviceId: 'audio', serviceName: 'Audio mailing list' },
];

/** Drain the effect scheduler + microtask queue (zoneless — flush manually). */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

interface SetupOptions {
  readonly services?: readonly PromulgationServiceRef[];
  readonly typesError?: boolean;
  readonly downloads?: { openTicketed: ReturnType<typeof vi.fn> };
}

async function setup(types: readonly AdminPromulgationType[], options: SetupOptions = {}) {
  const downloads = options.downloads ?? { openTicketed: vi.fn(() => Promise.resolve()) };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: () => 'en',
          modelLanguages: TEST_LANGUAGES.availableLanguages,
        },
      },
      { provide: DomainFacade, useValue: { availableDomains: signal([]) } },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
      { provide: TicketedDownloadService, useValue: downloads },
    ],
  });
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.promulgation.confirmDelete.header': 'Delete Promulgation Type?',
      'admin.promulgation.confirmDelete.message': "Delete promulgation type '{{typeId}}'?",
      'admin.promulgation.requirement.OPTIONAL': 'Optional',
      'admin.promulgation.requirement.DEFAULT': 'Default',
      'admin.promulgation.requirement.MANDATORY': 'Mandatory',
      'msg.type.COASTAL_WARNING': 'Coastal',
      'msg.type.SUBAREA_WARNING': 'Sub-area',
    },
    'en',
    { merge: true },
  );
  const fixture = TestBed.createComponent(PromulgationTypesPage);
  const httpTesting = TestBed.inject(HttpTestingController);
  TestBed.tick();
  const typesRequest = httpTesting.expectOne(TYPES_URL);
  if (options.typesError) {
    typesRequest.flush('load failed', { status: 500, statusText: 'Server Error' });
  } else {
    typesRequest.flush([...types]);
  }
  httpTesting.expectOne(SERVICES_URL).flush([...(options.services ?? SERVICES)]);
  await settle();
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    downloads,
    confirmService: fixture.debugElement.injector.get(ConfirmService),
  };
}

function rows(fixture: { nativeElement: HTMLElement }): HTMLTableRowElement[] {
  return Array.from(fixture.nativeElement.querySelectorAll('tbody tr'));
}

function cellTexts(fixture: { nativeElement: HTMLElement }, column: number): string[] {
  return rows(fixture).map((row) => row.cells[column].textContent?.trim() ?? '');
}

describe('PromulgationTypesPage', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    // The editor drawer portals to the overlay container when a test opens it.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('filters on typeId, name OR serviceId — case-insensitive, other columns ignored', async () => {
    const { fixture, page } = await setup([NAVTEX, TWITTER, SAFETYNET]);
    // 'tweet' hits TWITTER's name only; SAFETYNET carries it in a domain name
    // the haystack must ignore.
    page.onSearch('TWEET');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['tw-all']);

    // serviceId match.
    page.onSearch('safetynet');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['sn-dk']);

    // typeId match.
    page.onSearch('navtex-dk');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['navtex-dk']);

    page.onSearch('');
    fixture.detectChanges();
    expect(cellTexts(fixture, 2)).toEqual(['navtex-dk', 'tw-all', 'sn-dk']);
  });

  it('search resets the pager to the first page', async () => {
    const many = Array.from({ length: 25 }, (_, i) => ({
      ...NAVTEX,
      typeId: `type-${String(i + 1).padStart(2, '0')}`,
    }));
    const { page } = await setup(many);
    page.pager.setFirst(20);
    expect(page.pager.first()).toBe(20);

    page.onSearch('type');
    expect(page.pager.first()).toBe(0);
  });

  it('renders serviceId raw and typeId, name and priority verbatim', async () => {
    const { fixture } = await setup([NAVTEX, TWITTER]);
    expect(cellTexts(fixture, 1)).toEqual(['navtex', 'twitter']);
    expect(cellTexts(fixture, 2)).toEqual(['navtex-dk', 'tw-all']);
    expect(cellTexts(fixture, 3)).toEqual(['NAVTEX', 'Tweet warnings']);
    expect(cellTexts(fixture, 5)).toEqual(['10', '0']);
  });

  it('names the language by its configured label and flag; blank when the type is not language-bound', async () => {
    // A language the configuration does not name reads as stored.
    const greenlandic: AdminPromulgationType = { ...TWITTER, typeId: 'tw-kl', language: 'kl' };
    const { fixture } = await setup([NAVTEX, TWITTER, greenlandic]);
    expect(cellTexts(fixture, 6)).toEqual(['Dansk', '', 'kl']);
    expect(
      rows(fixture).map(
        (row) => row.cells[6].querySelector('app-language-flag img')?.getAttribute('src') ?? null,
      ),
    ).toEqual(['assets/img/flags/da.png', null, null]);
  });

  it('marks the active flag with the boolean status dot', async () => {
    const { fixture } = await setup([NAVTEX, TWITTER]);
    const dotStates = rows(fixture).map((row) => {
      const dot = row.cells[0].querySelector('.status-dot');
      return dot?.classList.contains('is-active') ?? false;
    });
    expect(dotStates).toEqual([true, false]);
  });

  it('localizes the requirement — never the raw enum value', async () => {
    const { fixture } = await setup([NAVTEX, TWITTER, SAFETYNET]);
    expect(cellTexts(fixture, 4)).toEqual(['Mandatory', 'Optional', 'Default']);
  });

  it('joins the domain names with commas; blank when the type has no domains', async () => {
    const { fixture } = await setup([NAVTEX, TWITTER]);
    expect(cellTexts(fixture, 7)).toEqual(['NW, NM', '']);
  });

  it('renders one translated msg.type line per restricted type; absent/empty sets stay blank', async () => {
    const { fixture } = await setup([NAVTEX, TWITTER, SAFETYNET]);
    const lines = (row: number) =>
      Array.from(rows(fixture)[row].cells[8].querySelectorAll('div')).map(
        (line) => line.textContent?.trim() ?? '',
      );
    expect(lines(0)).toEqual(['Coastal', 'Sub-area']);
    expect(lines(1)).toEqual([]);
    expect(lines(2)).toEqual([]);
  });

  it('builds one Add menu item per registered service, labelled with the backend serviceName', async () => {
    const { page } = await setup([]);
    expect(page.addItems().map((item) => item.label)).toEqual([
      'NAVTEX mailing list',
      'SafetyNET mailing list',
      'Twitter',
      'Audio mailing list',
    ]);
  });

  it('renders an empty Add menu when no services are registered (no crash)', async () => {
    const { page } = await setup([], { services: [] });
    expect(page.addItems()).toEqual([]);
  });

  it('an Add menu item seeds the editor with the default values for its service', async () => {
    const { page } = await setup([]);
    page.addItems()[3].command?.();
    expect(page.editorModel()).toEqual({
      mode: 'add',
      type: {
        serviceId: 'audio',
        typeId: '',
        name: '',
        priority: 0,
        active: true,
        requirement: 'OPTIONAL',
        domains: [],
      },
    });
  });

  it('edit opens the editor on the list row itself — no detail refetch', async () => {
    const { fixture, page, httpTesting } = await setup([NAVTEX]);
    (rows(fixture)[0].cells[9].querySelectorAll('button')[0] as HTMLButtonElement).click();
    expect(page.editorModel()).toEqual({ mode: 'edit', type: NAVTEX });
    httpTesting.expectNone((req) => req.url.includes('promulgation-type/'));
  });

  it('delete confirms with the corrected title and DELETEs + refetches on accept', async () => {
    const { fixture, httpTesting, confirmService } = await setup([NAVTEX]);
    (rows(fixture)[0].cells[9].querySelectorAll('button')[1] as HTMLButtonElement).click();

    const active = confirmService.active();
    expect(active?.header).toBe('Delete Promulgation Type?');
    expect(active?.message).toBe("Delete promulgation type 'navtex-dk'?");

    active?.accept();
    const req = httpTesting.expectOne('/rest/promulgations/promulgation-type/navtex-dk');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(TestBed.inject(NotificationService).success).toHaveBeenCalledWith(
      'admin.promulgation.toast.deleted',
    );
    // Mutations refetch the types list only — the service registry is static.
    await settle();
    httpTesting.expectOne(TYPES_URL).flush([]);
    httpTesting.expectNone(SERVICES_URL);
  });

  it('delete failure toasts the distinct delete error and refetches nothing', async () => {
    const { page, httpTesting, confirmService } = await setup([NAVTEX]);
    page.deleteType(NAVTEX);
    confirmService.active()?.accept();
    httpTesting
      .expectOne('/rest/promulgations/promulgation-type/navtex-dk')
      .flush('nope', { status: 500, statusText: 'Server Error' });
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.promulgation.toast.deleteError',
    );
    await settle();
    httpTesting.expectNone(TYPES_URL);
  });

  it('cog Export downloads the catalog with a fresh sysadmin ticket', async () => {
    const { page, downloads } = await setup([]);
    page.cogItems()[0].command?.();
    expect(downloads.openTicketed).toHaveBeenCalledWith(
      '/rest/promulgations/promulgation-types/all',
      'sysadmin',
    );
  });

  it('export failure toasts', async () => {
    const downloads = { openTicketed: vi.fn(() => Promise.reject(new Error('no ticket'))) };
    const { page } = await setup([], { downloads });
    page.cogItems()[0].command?.();
    await settle();
    expect(TestBed.inject(NotificationService).error).toHaveBeenCalledWith(
      'admin.promulgation.toast.exportError',
    );
  });

  it('wires the import dialog to the upload endpoint + batch job and refetches when it closes', async () => {
    const { fixture, page, httpTesting } = await setup([]);
    const dialog = fixture.debugElement.query(By.directive(JsonImportDialog))
      .componentInstance as JsonImportDialog;
    expect(dialog.uploadUrl()).toBe('/rest/promulgations/upload-promulgation-types');
    expect(dialog.accept()).toBe('.json');
    expect(dialog.jobLink()).toBe('/sysadmin/batch/promulgation-type-import');

    page.onImportClosed();
    await settle();
    httpTesting.expectOne(TYPES_URL).flush([]);
  });

  it('editor saved: closes the drawer seam and refetches the list', async () => {
    const { page, httpTesting } = await setup([NAVTEX]);
    page.editType(NAVTEX);
    page.onEditorSaved();
    expect(page.editorModel()).toBeUndefined();
    await settle();
    httpTesting.expectOne(TYPES_URL).flush([]);
  });

  it('editor closed (pure cancel): closes only — no refetch', async () => {
    const { page, httpTesting } = await setup([NAVTEX]);
    page.editType(NAVTEX);
    page.onEditorClosed();
    expect(page.editorModel()).toBeUndefined();
    await settle();
    httpTesting.expectNone(TYPES_URL);
  });

  it('shows the empty state when there are no promulgation types at all', async () => {
    const { fixture } = await setup([]);
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('shows the no-matches empty state when the filter excludes every type', async () => {
    const { fixture, page } = await setup([NAVTEX]);
    page.onSearch('zzz');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('shows the error banner when the list load fails', async () => {
    const { fixture } = await setup([], { typesError: true });
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });
});
