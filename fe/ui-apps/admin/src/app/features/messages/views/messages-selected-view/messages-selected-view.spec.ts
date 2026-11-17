import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { provideFakeMessageTagActions } from '../../testing/fake-message-tag-actions';
import { provideFakeMessageEditShortcut } from '../../testing/fake-message-edit-shortcut';
import { MessagesSelectedView } from './messages-selected-view';
import type { Message } from '../../models/message.model';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import {
  provideFakeAuthFacade,
  provideFakeDomainFacade,
} from '../../../../shared/testing/fake-auth';
import { afterFirstPaint } from '../../../../shared/testing/paint-testing';

interface FakeFacade {
  selection: {
    isEmpty: ReturnType<typeof signal<boolean>>;
    selectedMessages: ReturnType<typeof signal<readonly Message[]>>;
    selectedIds: () => readonly string[];
    isSelected: (id: string) => boolean;
  };
}

function makeFacade(): FakeFacade {
  const selectedMessages = signal<readonly Message[]>([]);
  return {
    selection: {
      isEmpty: signal(true),
      selectedMessages,
      selectedIds: () => selectedMessages().map((m) => m.id),
      isSelected: () => false,
    },
  };
}

function setup(facade: FakeFacade) {
  const dialogOpen = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      { provide: MessagesFacade, useValue: facade },
      {
        provide: NotificationService,
        useValue: {
          success: vi.fn(),
          info: vi.fn(),
          warn: vi.fn(),
          error: vi.fn(),
          notify: vi.fn(),
        },
      },
      provideFakeAuthFacade(),
      provideFakeDomainFacade(),
      { provide: MessageDetailsDialogService, useValue: { open: dialogOpen } },
      provideFakeMessageTagActions(),
      provideFakeMessageEditShortcut(),
    ],
  });

  const fixture = TestBed.createComponent(MessagesSelectedView);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, dialogOpen };
}

describe('MessagesSelectedView', () => {
  it('renders <app-empty-selection> when selection.isEmpty() is true', () => {
    const facade = makeFacade();
    const { fixture } = setup(facade);
    expect(fixture.nativeElement.querySelector('app-empty-selection')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-message-details-row')).toBeNull();
  });

  it('renders one row per selectedMessages entry', async () => {
    const facade = makeFacade();
    facade.selection.isEmpty.set(false);
    facade.selection.selectedMessages.set([{ id: 'a' }, { id: 'b' }, { id: 'c' }]);
    const { fixture } = setup(facade);
    await afterFirstPaint(fixture);
    const rows = fixture.nativeElement.querySelectorAll('app-message-details-row');
    expect(rows.length).toBe(3);
  });

  /**
   * The row opens an address, so it carries a real link — the thumb, which is
   * the one element of a details row that is always there to hang an href on.
   */
  it('gives every row an href addressing the message it opens', async () => {
    const facade = makeFacade();
    facade.selection.isEmpty.set(false);
    facade.selection.selectedMessages.set([{ id: 'a' }]);
    const { fixture } = setup(facade);
    await afterFirstPaint(fixture);
    const link = fixture.nativeElement.querySelector(
      'tr.row-interactive a.row-link',
    ) as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.getAttribute('href')).toContain('messageId=a');
  });

  it('opens the dialog once on a plain click of that link, without navigating itself', async () => {
    const facade = makeFacade();
    facade.selection.isEmpty.set(false);
    facade.selection.selectedMessages.set([{ id: 'a' }]);
    const { fixture, dialogOpen } = setup(facade);
    await afterFirstPaint(fixture);
    const link = fixture.nativeElement.querySelector(
      'tr.row-interactive a.row-link',
    ) as HTMLAnchorElement;
    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
    expect(dialogOpen).toHaveBeenCalledTimes(1);
    expect(dialogOpen).toHaveBeenCalledWith('a', ['a']);
  });

  it('does not mount load-more or list-status (no search runs for this view)', () => {
    const facade = makeFacade();
    facade.selection.isEmpty.set(false);
    facade.selection.selectedMessages.set([{ id: 'a' }]);
    const { fixture } = setup(facade);
    expect(fixture.nativeElement.querySelector('app-load-more-messages')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-message-list-status')).toBeNull();
  });
});
