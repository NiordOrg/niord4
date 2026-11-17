import { type Provider } from '@angular/core';
import { of } from 'rxjs';
import { vi } from 'vitest';

import { MessageTagActionsService } from '../services/message-tag-actions.service';

/**
 * Returns a `Provider` that stubs `MessageTagActionsService` with no-op vi
 * spies. Used by specs that transitively mount `MessageDetailsMenu` /
 * `MessagesActionMenu` (or `MessageDetailsRow`, `MessageGridCard`, etc.)
 * without exercising the tag-action flow itself.
 *
 * Lives under `features/messages/testing/` rather than `shared/testing/`
 * because the stubbed service is feature-scoped — `shared/` cannot import
 * from `features/` per the project's layering rule.
 */
export function provideFakeMessageTagActions(): Provider {
  return {
    provide: MessageTagActionsService,
    useValue: {
      addMessagesToTag: vi.fn(() => of(undefined)),
      removeMessagesFromTag: vi.fn(() => of(undefined)),
      addMessagesToLastSelectedTag: vi.fn(() => of(undefined)),
    },
  };
}
