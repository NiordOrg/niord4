import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import type { Message } from '../messages/models/message.model';

import { MessageComparisonDialog } from '../messages/dialogs/message-comparison-dialog/message-comparison-dialog';
import { MessageCopyDialog } from '../messages/dialogs/message-copy-dialog/message-copy-dialog';
import { MessagePrintDialog } from '../messages/dialogs/message-print-dialog/message-print-dialog';
import { MessageDetailsDialog } from '../messages/message-details-dialog/message-details-dialog';
import { EditorActionMenu } from './components/editor-action-menu/editor-action-menu';
import { EditorShell } from './editor';
import { EditorCommentsStore } from './services/editor-comments.store';
import { EditorFacade } from './services/editor-facade';

@Component({ selector: 'app-editor-action-menu', template: '' })
class StubActionMenu {}

@Component({ selector: 'app-message-details-dialog', template: '' })
class StubDetailsDialog {}

@Component({ selector: 'app-message-comparison-dialog', template: '' })
class StubComparisonDialog {}

@Component({ selector: 'app-message-copy-dialog', template: '' })
class StubCopyDialog {}

@Component({ selector: 'app-message-print-dialog', template: '' })
class StubPrintDialog {}

function setup(opts?: { unsaved?: boolean; commentDraft?: boolean; message?: Message }) {
  const resetWorkingState = vi.fn();
  const clearComposeDraft = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: EditorFacade,
        useValue: {
          hasUnsavedChanges: () => opts?.unsaved ?? false,
          resetWorkingState,
          message: signal<Message | undefined>(opts?.message),
        },
      },
      {
        provide: EditorCommentsStore,
        useValue: { hasComposeDraft: () => opts?.commentDraft ?? false, clearComposeDraft },
      },
    ],
  });
  TestBed.overrideComponent(EditorShell, {
    remove: {
      imports: [
        EditorActionMenu,
        MessageDetailsDialog,
        MessageComparisonDialog,
        MessageCopyDialog,
        MessagePrintDialog,
      ],
    },
    add: {
      imports: [
        StubActionMenu,
        StubDetailsDialog,
        StubComparisonDialog,
        StubCopyDialog,
        StubPrintDialog,
      ],
    },
  });
  const fixture = TestBed.createComponent(EditorShell);
  fixture.detectChanges();
  return { fixture, resetWorkingState, clearComposeDraft };
}

/** A reload or tab close: the browser asks only when the handler prevents the default. */
function unloadIsHeld(): boolean {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

describe('EditorShell', () => {
  it('mounts the print dialog, so Print in the Actions menu has a dialog to open', () => {
    const { fixture } = setup();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('app-message-print-dialog')).not.toBeNull();
  });

  it('mounts the dialogs the editor opens beside the routed view', () => {
    const { fixture } = setup();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('app-editor-action-menu')).not.toBeNull();
    expect(host.querySelector('router-outlet')).not.toBeNull();
    expect(host.querySelector('app-message-details-dialog')).not.toBeNull();
    expect(host.querySelector('app-message-comparison-dialog')).not.toBeNull();
    expect(host.querySelector('app-message-copy-dialog')).not.toBeNull();
  });

  it('says over every tab that a cancellation warning cannot be edited', () => {
    const { fixture } = setup({
      message: { id: 'm-1', status: 'PUBLISHED', mainType: 'NW', kind: 'CANCELLATION' },
    });
    const host = fixture.nativeElement as HTMLElement;
    const banner = host.querySelector('[data-testid="cancellation-warning-banner"]');
    expect(banner).not.toBeNull();
    expect(banner!.getAttribute('role')).toBe('note');
    expect(banner!.textContent).toContain('editor.cancellationWarning.banner');
    fixture.destroy();
  });

  it.each<[string, Message | undefined]>([
    ['a warning', { id: 'm-1', status: 'PUBLISHED', kind: 'WARNING' }],
    ['a message without a kind', { id: 'm-2', status: 'DRAFT' }],
    ['no message yet', undefined],
  ])('shows no such note for %s', (_label, message) => {
    const { fixture } = setup({ message });
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="cancellation-warning-banner"]')).toBeNull();
    fixture.destroy();
  });

  it('drops the working state when the shell goes away', () => {
    const { fixture, resetWorkingState } = setup();
    fixture.destroy();
    expect(resetWorkingState).toHaveBeenCalledTimes(1);
  });

  it('drops a half-typed comment when the shell goes away, so it does not return on the next visit', () => {
    const { fixture, clearComposeDraft } = setup({ commentDraft: true });
    fixture.destroy();
    expect(clearComposeDraft).toHaveBeenCalledTimes(1);
  });

  it('asks the browser before a reload while a half-typed comment is held', () => {
    const { fixture } = setup({ commentDraft: true });
    expect(unloadIsHeld()).toBe(true);
    fixture.destroy();
  });

  it('asks the browser before a reload while the message has unsaved changes', () => {
    const { fixture } = setup({ unsaved: true });
    expect(unloadIsHeld()).toBe(true);
    fixture.destroy();
  });

  it('lets a reload go without asking when nothing is unsaved and no comment is typed', () => {
    const { fixture } = setup();
    expect(unloadIsHeld()).toBe(false);
    fixture.destroy();
  });
});
