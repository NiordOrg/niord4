import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MessageIdField } from '../../components/message-id-field/message-id-field';
import type { Message } from '../../models/message.model';
import { MessageComparisonDialog } from './message-comparison-dialog';
import { MessageComparisonDialogService } from './message-comparison-dialog.service';

const API_BASE = '/rest';

const notifications = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() };

const fakeLanguage = {
  modelLanguages: [
    { value: 'en', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
    { value: 'da', locale: 'da', flagPath: 'assets/img/flags/da.png' },
  ],
  selectedLanguage: signal('en'),
} as unknown as LanguageService;

function message(id: string, titles: Record<string, string>): Message {
  return {
    id,
    descs: Object.entries(titles).map(([lang, title]) => ({ lang, title })),
  } as unknown as Message;
}

/** Flush pending effects + CD (zoneless) without awaiting outstanding HTTP. */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

interface Ctx {
  fixture: ComponentFixture<MessageComparisonDialog>;
  component: MessageComparisonDialog;
  service: MessageComparisonDialogService;
  http: HttpTestingController;
}

function setup(): Ctx {
  notifications.error.mockClear();
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: fakeLanguage },
      { provide: NotificationService, useValue: notifications },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });
  const fixture = TestBed.createComponent(MessageComparisonDialog);
  const component = fixture.componentInstance;
  const service = TestBed.inject(MessageComparisonDialogService);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  return { fixture, component, service, http };
}

const url = (id: string) => `${API_BASE}/messages/message/${id}`;
const q = (sel: string) => document.querySelector(sel);
const qa = (sel: string) => Array.from(document.querySelectorAll(sel));

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('MessageComparisonDialog', () => {
  it('loads and renders both sides once opened', async () => {
    const { service, http } = setup();
    service.open('m1', 'm2');
    await settle();

    http.expectOne(url('m1')).flush(message('m1', { en: 'First' }));
    http.expectOne(url('m2')).flush(message('m2', { en: 'Second' }));
    await settle();

    expect(qa('app-message-details-renderer').length).toBe(2);
    http.verify();
  });

  it('passes includeDeleted to both id pickers', async () => {
    const { service, fixture } = setup();
    service.open('m1', 'm2');
    await settle();

    const fields = fixture.debugElement.queryAll(By.directive(MessageIdField));
    expect(fields.length).toBe(2);
    expect(fields.every((f) => (f.componentInstance as MessageIdField).includeDeleted())).toBe(
      true,
    );
  });

  it('re-renders both sides at the chosen content language when a language pill is clicked', async () => {
    const { service, http } = setup();
    service.open('m1', 'm2');
    await settle();

    http.expectOne(url('m1')).flush(message('m1', { en: 'EN one', da: 'DA one' }));
    http.expectOne(url('m2')).flush(message('m2', { en: 'EN two', da: 'DA two' }));
    await settle();

    const titles = () =>
      qa('app-message-details-renderer strong').map((el) => el.textContent?.trim());
    expect(titles()).toEqual(['EN one', 'EN two']);

    const daPill = qa('[aria-label="messages.compare.language"] button').find((b) =>
      b.textContent?.trim().endsWith('DA'),
    ) as HTMLButtonElement;
    daPill.click();
    await settle();

    expect(titles()).toEqual(['DA one', 'DA two']);
  });

  it('feeds two JSON snapshots to the data diff when both sides are loaded', async () => {
    const { component, service, http } = setup();
    service.open('m1', 'm2');
    await settle();

    http.expectOne(url('m1')).flush(message('m1', { en: 'First' }));
    http.expectOne(url('m2')).flush(message('m2', { en: 'Second' }));
    await settle();

    const snapshots = (component as unknown as { snapshots(): string[] }).snapshots();
    expect(snapshots.length).toBe(2);
    expect(q('app-json-diff-pane .font-mono')).not.toBeNull();
  });

  it('word-diffs the two rendered messages on the Changes tab', async () => {
    const { component, service, http } = setup();
    service.open('m1', 'm2');
    await settle();

    http.expectOne(url('m1')).flush(message('m1', { en: 'First' }));
    http.expectOne(url('m2')).flush(message('m2', { en: 'Second' }));
    await settle();

    (component as unknown as { activeTab: { set(v: string): void } }).activeTab.set('changes');
    await settle();
    await settle();

    const diff = q('.message-diff') as HTMLElement | null;
    expect(diff).not.toBeNull();
    expect(diff!.innerHTML).toContain('<del>First</del>');
    expect(diff!.innerHTML).toContain('<ins>Second</ins>');
    http.verify();
  });

  it('swap exchanges the two ids and re-drives each side', async () => {
    const { service, http } = setup();
    service.open('m1', 'm2');
    await settle();
    http.expectOne(url('m1')).flush(message('m1', { en: 'First' }));
    http.expectOne(url('m2')).flush(message('m2', { en: 'Second' }));
    await settle();

    const swapBtn = qa('.dialog-foot button').find((b) =>
      b.textContent?.includes('messages.compare.swap'),
    ) as HTMLButtonElement;
    swapBtn.click();
    await settle();

    expect(service.messageId1()).toBe('m2');
    expect(service.messageId2()).toBe('m1');
    http.expectOne(url('m2')).flush(message('m2', { en: 'Second' }));
    http.expectOne(url('m1')).flush(message('m1', { en: 'First' }));
    http.verify();
  });

  it('toasts and leaves the side empty on a load error', async () => {
    const { service, http } = setup();
    service.open('bad', 'm2');
    await settle();

    http.expectOne(url('bad')).flush('nope', { status: 404, statusText: 'Not Found' });
    http.expectOne(url('m2')).flush(message('m2', { en: 'Second' }));
    await settle();

    expect(notifications.error).toHaveBeenCalledWith('messages.compare.loadError');
    expect(qa('app-message-details-renderer').length).toBe(1);
  });

  it('does not fetch until an id is supplied', async () => {
    const { http } = setup();
    await settle();
    http.verify();
  });
});
