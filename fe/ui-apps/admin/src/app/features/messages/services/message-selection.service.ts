import { computed, Injectable, signal } from '@angular/core';

import { Message, MessageStatus } from '../models/message.model';
import type { SelectedMessageEntry } from '../models/selected-message.model';

@Injectable({ providedIn: 'root' })
export class MessageSelectionService {
  readonly #entries = signal<SelectedMessageEntry[]>([]);

  readonly selectedEntries = this.#entries.asReadonly();
  readonly selectedMessages = computed(() => this.#entries().map((e) => e.message));
  readonly selectedIds = computed(() => this.#entries().map((e) => e.id));
  readonly selectedCount = computed(() => this.#entries().length);
  readonly isEmpty = computed(() => this.#entries().length === 0);

  private readonly selectedIdSet = computed(() => new Set(this.#entries().map((e) => e.id)));

  isSelected(messageId: string): boolean {
    return this.selectedIdSet().has(messageId);
  }

  select(message: Message): void {
    if (this.isSelected(message.id)) {
      return;
    }
    this.#entries.update((entries) => [...entries, { id: message.id, message }]);
  }

  remove(messageId: string): void {
    this.#entries.update((entries) => entries.filter((e) => e.id !== messageId));
  }

  toggle(message: Message): void {
    if (this.isSelected(message.id)) {
      this.remove(message.id);
    } else {
      this.select(message);
    }
  }

  clear(): void {
    this.#entries.set([]);
  }

  selectLoaded(messages: readonly Message[]): void {
    const currentIds = this.selectedIdSet();
    const newEntries = messages
      .filter((m) => !currentIds.has(m.id))
      .map((m) => ({ id: m.id, message: m }));

    if (newEntries.length > 0) {
      this.#entries.update((entries) => [...entries, ...newEntries]);
    }
  }

  toggleAllLoaded(messages: readonly Message[]): void {
    if (this.#entries().length > 0) {
      this.clear();
    } else {
      this.selectLoaded(messages);
    }
  }

  refreshSnapshotsFromSearchResults(messages: readonly Message[]): void {
    if (messages.length === 0) {
      return;
    }
    const resultMap = new Map(messages.map((m) => [m.id, m]));
    if (!this.#entries().some((e) => resultMap.has(e.id))) {
      return;
    }
    this.#entries.update((entries) =>
      entries.map((entry) => {
        const updated = resultMap.get(entry.id);
        return updated ? { id: entry.id, message: updated } : entry;
      }),
    );
  }

  /**
   * Patch the status on selected snapshots after a bulk status update, so the Selection view's
   * status badges recolour immediately. Needed because {@link refreshSnapshotsFromSearchResults}
   * only touches entries still present in the active search results — a message whose new status
   * drops it out of the current filter would otherwise keep its stale status here.
   */
  applyStatusUpdates(
    updates: readonly { readonly messageId: string; readonly status: MessageStatus }[],
  ): void {
    if (updates.length === 0) {
      return;
    }
    const statusById = new Map(updates.map((u) => [u.messageId, u.status]));
    this.#entries.update((entries) =>
      entries.map((entry) => {
        const status = statusById.get(entry.id);
        return status ? { id: entry.id, message: { ...entry.message, status } } : entry;
      }),
    );
  }
}
