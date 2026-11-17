import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { DestroyRef, Injectable, PLATFORM_ID, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import type { Message } from '../models/message.model';

/**
 * "Hold E + click a message → open it straight in the editor" shortcut.
 *
 * Tracks whether the plain `E` key is currently held (ignoring presses inside
 * text fields and modifier combos) and, while it is, lets a message activation
 * route directly to the editor instead of the details dialog — gated by the same
 * permission as the action menu's Edit item (logged in + an editable series).
 */
@Injectable({ providedIn: 'root' })
export class MessageEditShortcutService {
  private readonly auth = inject(AuthFacade);
  private readonly domain = inject(DomainFacade);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);

  private readonly held = signal(false);
  /** Whether the `E` (edit) shortcut key is currently held down. */
  readonly editKeyHeld = this.held.asReadonly();

  constructor() {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (!isEditKey(event) || event.ctrlKey || event.metaKey || event.altKey) {
        return;
      }
      // Don't arm while typing in a field (e.g. an "e" in the search box).
      if (isEditableTarget(event.target)) {
        return;
      }
      this.held.set(true);
    };
    const onKeyUp = (event: KeyboardEvent): void => {
      if (isEditKey(event)) {
        this.held.set(false);
      }
    };
    // A window blur (alt-tab) can swallow the keyup, so release on blur too —
    // otherwise the shortcut could get stuck armed.
    const release = (): void => this.held.set(false);

    this.document.addEventListener('keydown', onKeyDown, true);
    this.document.addEventListener('keyup', onKeyUp, true);
    this.document.defaultView?.addEventListener('blur', release);
    this.destroyRef.onDestroy(() => {
      this.document.removeEventListener('keydown', onKeyDown, true);
      this.document.removeEventListener('keyup', onKeyUp, true);
      this.document.defaultView?.removeEventListener('blur', release);
    });
  }

  /** Whether the current user may open this message in the editor (mirrors the menu's Edit gate). */
  canEdit(message: Message): boolean {
    if (!this.auth.hasRole('user')()) {
      return false;
    }
    const series = message.messageSeries?.seriesId;
    if (!series) {
      return false;
    }
    return this.domain.canEditMessageSeries(series)();
  }

  /**
   * If the E shortcut is armed and the message is editable, navigate to the
   * editor and return `true`; otherwise return `false` so the caller falls back
   * to its normal open-details behaviour.
   */
  tryEdit(message: Message): boolean {
    if (!this.held() || !this.canEdit(message)) {
      return false;
    }
    void this.router.navigate(['/editor/edit', message.id]);
    return true;
  }
}

function isEditKey(event: KeyboardEvent): boolean {
  return event.key === 'e' || event.key === 'E';
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}
