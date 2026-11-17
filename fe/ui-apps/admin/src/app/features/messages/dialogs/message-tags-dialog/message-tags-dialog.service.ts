import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { DialogHostService } from '../../../../shared/dialogs/dialog-host.service';
import type { MessageTag } from '../../../../shared/models/message-tag.model';
import type { MessageTagsDialogOpener } from '../../../../shared/forms/message-tags-field/message-tags-dialog.token';

/**
 * Host-component pattern opener. The Messages route mounts a single
 * <app-message-tags-dialog>; this service flips visibility and emits the
 * user's tag pick (or undefined for dismissal).
 */
@Injectable({ providedIn: 'root' })
export class MessageTagsDialogService
  extends DialogHostService<MessageTag>
  implements MessageTagsDialogOpener
{
  readonly includeLocked = signal(false);

  open({ includeLocked = false }: { includeLocked?: boolean } = {}): Observable<
    MessageTag | undefined
  > {
    this.includeLocked.set(includeLocked);
    return this.armOpen();
  }
}
