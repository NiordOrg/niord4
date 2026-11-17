import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { provideFakeAuthFacade, provideFakeDomainFacade } from '../../../shared/testing/fake-auth';
import type { Message } from '../models/message.model';
import { MessageEditShortcutService } from './message-edit-shortcut.service';

const MSG = { id: 'm1', messageSeries: { seriesId: 's1' } } as Message;

function setup(opts: { loggedIn?: boolean; editor?: boolean; canEditSeries?: boolean } = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideFakeAuthFacade({
        isAuthenticated: opts.loggedIn ?? true,
        editor: opts.editor ?? false,
      }),
      provideFakeDomainFacade({ canEditSeries: opts.canEditSeries ?? true }),
    ],
  });
  const service = TestBed.inject(MessageEditShortcutService);
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  return { service, navigate };
}

function pressE(target?: EventTarget): void {
  const event = new KeyboardEvent('keydown', { key: 'e', bubbles: true });
  (target ?? document).dispatchEvent(event);
}
function releaseE(): void {
  document.dispatchEvent(new KeyboardEvent('keyup', { key: 'e', bubbles: true }));
}

describe('MessageEditShortcutService', () => {
  describe('canEdit (mirrors the action-menu Edit gate)', () => {
    it('is true for a logged-in user on a domain-editable series (no editor role needed)', () => {
      expect(
        setup({ loggedIn: true, editor: false, canEditSeries: true }).service.canEdit(MSG),
      ).toBe(true);
    });
    it('is false for an anonymous user', () => {
      expect(setup({ loggedIn: false, canEditSeries: true }).service.canEdit(MSG)).toBe(false);
    });
    it('is false when the message has no series', () => {
      expect(setup({ loggedIn: true }).service.canEdit({ id: 'x' } as Message)).toBe(false);
    });
    it('is false when the series is not domain-editable', () => {
      expect(setup({ loggedIn: true, canEditSeries: false }).service.canEdit(MSG)).toBe(false);
    });
  });

  describe('tryEdit', () => {
    it('does nothing and returns false when E is not held', () => {
      const { service, navigate } = setup();
      expect(service.tryEdit(MSG)).toBe(false);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('navigates to the editor and returns true when E is held + editable', () => {
      const { service, navigate } = setup({ loggedIn: true, canEditSeries: true });
      pressE();
      expect(service.tryEdit(MSG)).toBe(true);
      expect(navigate).toHaveBeenCalledWith(['/editor/edit', 'm1']);
      releaseE();
    });

    it('does not navigate when E is held but the message is not editable', () => {
      const { service, navigate } = setup({ loggedIn: false });
      pressE();
      expect(service.tryEdit(MSG)).toBe(false);
      expect(navigate).not.toHaveBeenCalled();
      releaseE();
    });
  });

  describe('editKeyHeld tracking', () => {
    it('arms on E keydown and disarms on keyup', () => {
      const { service } = setup();
      expect(service.editKeyHeld()).toBe(false);
      pressE();
      expect(service.editKeyHeld()).toBe(true);
      releaseE();
      expect(service.editKeyHeld()).toBe(false);
    });

    it('does not arm when E is pressed inside a text field', () => {
      const { service } = setup();
      const input = document.createElement('input');
      document.body.appendChild(input);
      try {
        pressE(input);
        expect(service.editKeyHeld()).toBe(false);
      } finally {
        input.remove();
      }
    });
  });
});
