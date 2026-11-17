import {
  HttpInterceptorFn,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import type { MessageTag } from '../../../../../shared/models/message-tag.model';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideFakeAuthFacade } from '../../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { AreasAdminApiService } from '../../../services/api/areas-admin-api.service';
import { FiringGenerateDialog } from './firing-generate-dialog';

/** The stub loader resolves every key to itself, so labels are the keys. */
const TAG_LABEL = 'admin.areas.firing.tag';
const GENERATE = 'admin.areas.firing.generate';

/**
 * 150 tags in name order. The tags endpoint returns at most 100 rows unless asked for
 * another limit (MessageTagRestService.searchTags), so "Range 120" is never in a list
 * fetched without a name. "Range 140" is locked: the generator adds messages to a
 * locked tag as to any other, and the dialog always offered locked tags.
 */
const CATALOG: readonly MessageTag[] = Array.from({ length: 150 }, (_, i) => {
  const n = String(i).padStart(3, '0');
  return { tagId: `t${n}`, name: `Range ${n}`, type: 'DOMAIN', messageCount: 0, locked: i === 140 };
});

/** Answers GET /rest/tags/search the way the backend does: name, locked, sorted by name, limit 100. */
const fakeTagsBackend: HttpInterceptorFn = (req, next) => {
  if (req.method !== 'GET' || !req.url.endsWith('/tags/search')) return next(req);
  const name = (req.params.get('name') ?? '').toLowerCase();
  const locked = req.params.get('locked');
  const limit = Number(req.params.get('limit') ?? '100');
  const body = CATALOG.filter((t) => t.name.toLowerCase().includes(name))
    .filter((t) => locked === null || String(t.locked) === locked)
    .slice(0, limit);
  return of(new HttpResponse({ status: 200, body }));
};

function setup() {
  const api = { generateFiringAreaMessages: vi.fn(() => of(null)) };
  const notifications = { error: vi.fn(), info: vi.fn(), success: vi.fn(), warn: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(withInterceptors([fakeTagsBackend])),
      provideTestTransloco(),
      provideNiordIcons(),
      provideFakeAuthFacade({ isAuthenticated: true, admin: true }),
      { provide: AreasAdminApiService, useValue: api },
      { provide: NotificationService, useValue: notifications },
      {
        provide: DomainFacade,
        useValue: { domainMessageSeriesIds: signal<readonly string[]>(['FE-2026']) },
      },
    ],
  });
  const fixture = TestBed.createComponent(FiringGenerateDialog);
  fixture.detectChanges();
  return { fixture, api };
}

async function open(fixture: ComponentFixture<FiringGenerateDialog>): Promise<void> {
  fixture.componentInstance.visible.set(true);
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
}

/** The dialog and the picker are portalled to the body-level overlay container. */
function buttonByText(text: string): HTMLButtonElement | undefined {
  return [...document.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === text,
  );
}

function tagRows(): string[] {
  // Each row's name is its one truncating span (the message count follows it).
  return [...document.querySelectorAll('.picker-panel li button span.truncate')].map(
    (s) => s.textContent?.trim() ?? '',
  );
}

async function openPicker(fixture: ComponentFixture<FiringGenerateDialog>): Promise<void> {
  const trigger = document.querySelector<HTMLButtonElement>(`button[aria-label="${TAG_LABEL}"]`);
  expect(trigger, 'the tag field is in the dialog').not.toBeNull();
  trigger!.click();
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
}

async function typeSearch(fixture: ComponentFixture<FiringGenerateDialog>, text: string) {
  const input = document.querySelector<HTMLInputElement>(`input[aria-label="${TAG_LABEL}"]`);
  expect(input, 'the tag picker has a search box').not.toBeNull();
  input!.value = text;
  input!.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(250);
  fixture.detectChanges();
}

async function pickRow(fixture: ComponentFixture<FiringGenerateDialog>, name: string) {
  const row = [...document.querySelectorAll<HTMLButtonElement>('.picker-panel li button')].find(
    (b) => b.textContent?.includes(name),
  );
  expect(row, `a row for ${name}`).toBeDefined();
  row!.click();
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
}

describe('FiringGenerateDialog: the message tag', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    document.querySelectorAll('.cdk-overlay-container').forEach((c) => (c.innerHTML = ''));
  });

  it('a tag that sorts after the 100th is found by typing and is sent with the series', async () => {
    const { fixture, api } = setup();
    await open(fixture);
    await openPicker(fixture);
    // The list fetched without a name stops at the 100th tag.
    expect(tagRows()).toHaveLength(100);
    expect(tagRows()).not.toContain('Range 120');

    await typeSearch(fixture, 'range 120');
    expect(tagRows()).toEqual(['Range 120']);
    await pickRow(fixture, 'Range 120');

    buttonByText(GENERATE)!.click();
    expect(api.generateFiringAreaMessages).toHaveBeenCalledWith('FE-2026', 't120');
  });

  it('a locked tag can still be picked, as before', async () => {
    const { fixture, api } = setup();
    await open(fixture);
    await openPicker(fixture);
    await typeSearch(fixture, 'Range 140');
    await pickRow(fixture, 'Range 140');

    buttonByText(GENERATE)!.click();
    expect(api.generateFiringAreaMessages).toHaveBeenCalledWith('FE-2026', 't140');
  });

  it('with no tag picked the messages are generated without one', async () => {
    const { fixture, api } = setup();
    await open(fixture);

    buttonByText(GENERATE)!.click();
    expect(api.generateFiringAreaMessages).toHaveBeenCalledWith('FE-2026', undefined);
  });

  it('a reopened dialog starts again with no tag', async () => {
    const { fixture, api } = setup();
    await open(fixture);
    await openPicker(fixture);
    await typeSearch(fixture, 'Range 120');
    await pickRow(fixture, 'Range 120');

    fixture.componentInstance.visible.set(false);
    fixture.detectChanges();
    await open(fixture);

    buttonByText(GENERATE)!.click();
    expect(api.generateFiringAreaMessages).toHaveBeenCalledWith('FE-2026', undefined);
  });
});
