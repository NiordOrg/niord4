import { InjectionToken } from '@angular/core';
import { Observable, of } from 'rxjs';

import type { MessageTag } from '../../models/message-tag.model';

export interface MessageTagsDialogOpener {
  open(options: { includeLocked?: boolean }): Observable<MessageTag | undefined>;
}

/**
 * Default factory: a no-op opener that emits `undefined` immediately. The
 * messages feature route provides the real `MessageTagsDialogService` impl;
 * other features and isolated tests get the safe no-op so injection never
 * fails when the token is unbound.
 */
export const MESSAGE_TAGS_DIALOG_OPENER = new InjectionToken<MessageTagsDialogOpener>(
  'MESSAGE_TAGS_DIALOG_OPENER',
  {
    providedIn: 'root',
    factory: () => ({ open: () => of(undefined) }),
  },
);
