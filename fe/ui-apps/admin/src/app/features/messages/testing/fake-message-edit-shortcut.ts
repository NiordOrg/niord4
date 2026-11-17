import { type Provider } from '@angular/core';

import { MessageEditShortcutService } from '../services/message-edit-shortcut.service';

/**
 * Stubs {@link MessageEditShortcutService} so component specs don't pull its real
 * deps (Router, document key listeners). `tryEdit` defaults to never claiming the
 * activation; pass `() => true` to simulate the hold-E shortcut firing.
 *
 * `editKeyHeld` is separate because surfaces whose rows are editable by construction
 * read the raw held state instead of asking for a per-message permission verdict.
 */
export function provideFakeMessageEditShortcut(
  tryEdit: () => boolean = () => false,
  editKeyHeld: () => boolean = () => false,
): Provider {
  return {
    provide: MessageEditShortcutService,
    useValue: { tryEdit, canEdit: tryEdit, editKeyHeld },
  };
}
