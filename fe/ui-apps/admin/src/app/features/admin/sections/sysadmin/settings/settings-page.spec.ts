import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { Setting } from '../../../services/api/settings-api.service';
import { SettingsPage } from './settings-page';
import { SettingEditorModel } from './setting-editor';

interface PageInternals {
  search: () => string;
  filtered: () => readonly Setting[];
  onSearch: (value: string) => void;
  editorModel: () => SettingEditorModel | undefined;
  listValue: (setting: Setting) => string;
  editSetting: (setting: Setting) => void;
  onEditorSaved: () => void;
  onEditorClosed: () => void;
  onImportClosed: () => void;
}

function setup(
  notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() },
  downloads: { openTicketed: ReturnType<typeof vi.fn> } = {
    openTicketed: vi.fn(() => Promise.resolve()),
  },
) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
      { provide: TicketedDownloadService, useValue: downloads },
    ],
  });
  const fixture = TestBed.createComponent(SettingsPage);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    notifications,
    downloads,
  };
}

function flushInitial(httpTesting: HttpTestingController, settings: Setting[] = []): void {
  httpTesting.expectOne('/rest/settings/editable-settings').flush(settings);
}

describe('SettingsPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters case-insensitively on the setting KEY or its stringified VALUE', () => {
    const { fixture, page, httpTesting } = setup();
    flushInitial(httpTesting, [
      { key: 'mail.host', type: 'String', value: 'smtp' },
      { key: 'web.title', type: 'String', value: 'Niord' },
      { key: 'cfg', type: 'json', value: { theme: 'dark' } },
    ]);
    fixture.detectChanges();

    // Key match.
    page.onSearch('MAIL');
    expect(page.filtered().map((s) => s.key)).toEqual(['mail.host']);

    // Value substring now MATCHES (the filter spans key and value).
    page.onSearch('smtp');
    expect(page.filtered().map((s) => s.key)).toEqual(['mail.host']);

    // Stringified json value is searchable too.
    page.onSearch('dark');
    expect(page.filtered().map((s) => s.key)).toEqual(['cfg']);

    page.onSearch('');
    expect(page.filtered().length).toBe(3);
  });

  it('filtering NEVER matches a Password secret — only the masked display is searchable', () => {
    const { fixture, page, httpTesting } = setup();
    flushInitial(httpTesting, [{ key: 'mail.pw', type: 'Password', value: 'supersecret' }]);
    fixture.detectChanges();

    // The secret must not leak via search.
    page.onSearch('supersecret');
    expect(page.filtered().length).toBe(0);

    // The key still matches; the masked display ('********') is the only
    // searchable value text for a Password row.
    page.onSearch('mail.pw');
    expect(page.filtered().map((s) => s.key)).toEqual(['mail.pw']);
    page.onSearch('****');
    expect(page.filtered().map((s) => s.key)).toEqual(['mail.pw']);
  });

  it('masks Password values in the list but shows other values verbatim', () => {
    const { page, httpTesting } = setup();
    flushInitial(httpTesting);
    expect(page.listValue({ key: 'pw', type: 'Password', value: 'secret' })).toBe('********');
    expect(page.listValue({ key: 's', type: 'String', value: 'plain' })).toBe('plain');
    expect(page.listValue({ key: 'j', type: 'json', value: { a: 1 } })).toBe('{"a":1}');
  });

  it('editing opens the drawer on a DEEP COPY — the source list row is never mutated', () => {
    const { fixture, page, httpTesting } = setup();
    const row: Setting = { key: 'k', type: 'String', value: 'original' };
    flushInitial(httpTesting, [row]);
    fixture.detectChanges();

    page.editSetting(page.filtered()[0]);
    const model = page.editorModel();
    expect(model).toBeDefined();
    expect(model?.setting.value).toBe('original');
    expect(model?.setting).not.toBe(page.filtered()[0]); // a clone, not the row

    // Mutating the model's copy never touches the list row.
    (model?.setting as { value: unknown }).value = 'edited';
    expect(page.filtered()[0].value).toBe('original');
  });

  it('a successful editor save closes the drawer and refetches (no success toast)', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const { page, httpTesting } = setup(notifications);
    flushInitial(httpTesting);

    page.editSetting({ key: 'k', type: 'String', value: 'v' });
    expect(page.editorModel()).toBeDefined();

    page.onEditorSaved();
    expect(page.editorModel()).toBeUndefined(); // drawer closed
    expect(notifications.success).not.toHaveBeenCalled(); // no success toast
    TestBed.tick();
    httpTesting.expectOne('/rest/settings/editable-settings').flush([]); // refetch
  });

  it('closing the drawer (Cancel/Esc/✕) refetches the list', () => {
    const { page, httpTesting } = setup();
    flushInitial(httpTesting);

    page.editSetting({ key: 'k', type: 'String', value: 'v' });
    expect(page.editorModel()).toBeDefined();
    page.onEditorClosed();
    expect(page.editorModel()).toBeUndefined();
    TestBed.tick();
    httpTesting.expectOne('/rest/settings/editable-settings').flush([]);
  });

  it('export calls openTicketed with the bare URL and the sysadmin role', async () => {
    const downloads = { openTicketed: vi.fn(() => Promise.resolve()) };
    const { fixture, httpTesting } = setup(undefined, downloads);
    flushInitial(httpTesting);

    const cog = fixture.componentInstance as unknown as {
      cogItems: () => { command?: () => void }[];
    };
    cog.cogItems()[0].command?.();
    await Promise.resolve();
    expect(downloads.openTicketed).toHaveBeenCalledWith(
      '/rest/settings/editable-settings',
      'sysadmin',
    );
  });

  it('export toasts when the ticket fetch fails', async () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const downloads = { openTicketed: vi.fn(() => Promise.reject(new Error('no ticket'))) };
    const { fixture, httpTesting } = setup(notifications, downloads);
    flushInitial(httpTesting);

    const cog = fixture.componentInstance as unknown as {
      cogItems: () => { command?: () => void }[];
    };
    cog.cogItems()[0].command?.();
    await Promise.resolve();
    await Promise.resolve();
    expect(notifications.error).toHaveBeenCalledWith('admin.settings.toast.exportError');
  });

  it('import close refetches the list', () => {
    const { page, httpTesting } = setup();
    flushInitial(httpTesting);
    page.onImportClosed();
    TestBed.tick();
    httpTesting.expectOne('/rest/settings/editable-settings').flush([]);
  });
});
