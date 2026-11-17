import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { Message } from '../../../messages/models/message.model';
import { MessageDetailsDialogService } from '../../../messages/services/message-details-dialog.service';
import { provideFakeMessageEditShortcut } from '../../../messages/testing/fake-message-edit-shortcut';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { RecentlyEditedDrafts } from './recently-edited-drafts';

// The payload the backend actually returns for this endpoint: its DataFilter is trimmed to
// `MessageDesc.title`, so there is deliberately NO messageSeries here. A shortcut wired through
// the per-message permission gate would silently never fire on these rows.
const DRAFTS = [
  { id: 'uuid-1', descs: [{ title: 'First draft' }] },
  { id: 'uuid-2', descs: [{ title: 'Second draft' }] },
] as unknown as Message[];

function setup(editKeyHeld = false) {
  const navigate = vi.fn();
  const dialog = { open: vi.fn(), close: vi.fn() };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: { selectedLanguage: signal('en') } },
      { provide: MessageDetailsDialogService, useValue: dialog },
      { provide: Router, useValue: { navigate } },
      provideFakeMessageEditShortcut(
        () => false,
        () => editKeyHeld,
      ),
    ],
  });

  const fixture = TestBed.createComponent(RecentlyEditedDrafts);
  fixture.autoDetectChanges();
  return { fixture, navigate, dialog };
}

/**
 * `TestBed.tick()` runs the effects so the resource issues its GET; `whenStable()` must only
 * run AFTER the flush, because it awaits pending requests and would otherwise time out.
 */
async function flushDrafts(
  fixture: { whenStable: () => Promise<unknown> },
  drafts: readonly Message[] = DRAFTS,
): Promise<void> {
  TestBed.tick();
  TestBed.inject(HttpTestingController)
    .expectOne((r) => r.url.endsWith('/messages/recently-edited-drafts'))
    .flush(drafts);
  await fixture.whenStable();
}

function titleButton(host: HTMLElement): HTMLButtonElement {
  const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('button'));
  const found = buttons.find((b) => b.textContent?.includes('First draft'));
  if (!found) {
    throw new Error('draft title button not rendered');
  }
  return found;
}

describe('RecentlyEditedDrafts', () => {
  it('opens the details dialog seeded with the whole list on a plain click', async () => {
    const { fixture, dialog, navigate } = setup();
    await flushDrafts(fixture);

    titleButton(fixture.nativeElement).click();
    await fixture.whenStable();

    expect(dialog.open).toHaveBeenCalledWith('uuid-1', ['uuid-1', 'uuid-2']);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('goes straight to the editor while the E shortcut is held', async () => {
    const { fixture, dialog, navigate } = setup(true);
    await flushDrafts(fixture);

    titleButton(fixture.nativeElement).click();
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith(['/editor/edit', 'uuid-1']);
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('renders nothing when there are no drafts', async () => {
    const { fixture } = setup();
    await flushDrafts(fixture, []);

    expect(fixture.nativeElement.querySelector('app-card')).toBeNull();
  });
});
