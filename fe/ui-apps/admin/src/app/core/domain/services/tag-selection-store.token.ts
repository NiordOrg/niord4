import { InjectionToken, Signal, signal } from '@angular/core';

import type { MessageTag } from '../../../shared/models/message-tag.model';

export interface TagSelectionStore {
  /** Persist the user's most recently picked non-locked tag (scoped to current session domain). */
  saveLastSelection(tag: MessageTag): void;
  /** Read the persisted last-selection. Returns undefined when none stored or when the session domain changed. */
  getLastSelection(): Signal<MessageTag | undefined>;
  /** Wipe the persisted last-selection. Called by DomainService on domain switch. */
  clearLastSelection(): void;
}

export const TAG_SELECTION_STORE = new InjectionToken<TagSelectionStore>('TagSelectionStore', {
  providedIn: 'root',
  factory: () => ({
    saveLastSelection: () => {
      // Stub. The real implementation is provided in features/messages.
    },
    getLastSelection: () => signal<MessageTag | undefined>(undefined),
    clearLastSelection: () => {
      // Stub. The real implementation is provided in features/messages.
    },
  }),
});
