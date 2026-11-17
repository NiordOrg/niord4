import { isPlatformBrowser } from '@angular/common';
import { Injectable, PLATFORM_ID, Signal, computed, effect, inject, signal } from '@angular/core';

import { DomainService } from '../../../core/domain/services/domain.service';
import type { TagSelectionStore } from '../../../core/domain/services/tag-selection-store.token';
import type { MessageTag } from '../../../shared/models/message-tag.model';

const LS_TAG_KEY = 'niord.lastTagSelection';
const LS_DOMAIN_KEY = 'niord.lastTagDomain';

@Injectable({ providedIn: 'root' })
export class LastTagSelectionService implements TagSelectionStore {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly domain = inject(DomainService);

  /**
   * Mirrors sessionStorage. Manual signal because sessionStorage doesn't emit
   * events on same-document writes; we update this signal alongside every save
   * so the menu's computed-derived visibility recomputes.
   *
   * No `storage`-event listener: sessionStorage is per-tab by design, so
   * cross-tab sync is intentionally not supported.
   */
  private readonly storedTag = signal<MessageTag | undefined>(this.read());

  private readonly storedDomain = signal<string | undefined>(this.readDomain());

  /**
   * Reactive last-selection. Returns the stored tag when the stored domain
   * matches the current session domain; otherwise undefined.
   */
  private readonly currentSelection = computed<MessageTag | undefined>(() => {
    const tag = this.storedTag();
    if (!tag) return undefined;
    const domainId = this.domain.sessionDomain()?.domainId;
    if (this.storedDomain() !== domainId) return undefined;
    return tag;
  });

  constructor() {
    // Wipe the persisted selection when the session domain changes.
    // This lives here — not in the root DomainService, which can only ever resolve the no-op
    // TAG_SELECTION_STORE stub — so the clear actually runs against the real store.
    let lastDomainId: string | undefined;
    let initialised = false;
    effect(() => {
      const id = this.domain.sessionDomain()?.domainId;
      if (!initialised) {
        initialised = true;
        lastDomainId = id;
        return;
      }
      if (id === lastDomainId) return;
      lastDomainId = id;
      this.clearLastSelection();
    });
  }

  saveLastSelection(tag: MessageTag): void {
    if (tag.locked) {
      // Locked tags are not persisted as last selection.
      this.clearLastSelection();
      return;
    }
    if (!isPlatformBrowser(this.platformId)) return;
    const domainId = this.domain.sessionDomain()?.domainId ?? '';
    try {
      window.sessionStorage.setItem(LS_TAG_KEY, JSON.stringify(tag));
      window.sessionStorage.setItem(LS_DOMAIN_KEY, domainId);
    } catch {
      // sessionStorage unavailable / quota exceeded — skip silently.
    }
    this.storedTag.set(tag);
    this.storedDomain.set(domainId);
  }

  getLastSelection(): Signal<MessageTag | undefined> {
    return this.currentSelection;
  }

  clearLastSelection(): void {
    if (isPlatformBrowser(this.platformId)) {
      try {
        window.sessionStorage.removeItem(LS_TAG_KEY);
        window.sessionStorage.removeItem(LS_DOMAIN_KEY);
      } catch {
        // ignore
      }
    }
    this.storedTag.set(undefined);
    this.storedDomain.set(undefined);
  }

  private read(): MessageTag | undefined {
    if (!isPlatformBrowser(this.platformId)) return undefined;
    try {
      const raw = window.sessionStorage.getItem(LS_TAG_KEY);
      if (!raw) return undefined;
      const parsed = JSON.parse(raw) as Partial<MessageTag>;
      // Minimal shape guard — protects against stale-schema sessionStorage
      // entries from older builds.
      if (typeof parsed?.tagId !== 'string' || typeof parsed?.name !== 'string') {
        return undefined;
      }
      return parsed as MessageTag;
    } catch {
      return undefined;
    }
  }

  private readDomain(): string | undefined {
    if (!isPlatformBrowser(this.platformId)) return undefined;
    try {
      return window.sessionStorage.getItem(LS_DOMAIN_KEY) ?? undefined;
    } catch {
      return undefined;
    }
  }
}
