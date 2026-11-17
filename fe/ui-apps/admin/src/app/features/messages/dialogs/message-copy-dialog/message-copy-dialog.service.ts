import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { DialogHostService } from '../../../../shared/dialogs/dialog-host.service';
import type { MainType } from '../../models/message.model';

/**
 * Host-component opener for the copy-message dialog. The messages route and the editor shell each
 * mount one <app-message-copy-dialog>; this service flips visibility and resolves the router command
 * array to navigate for the copy — appending the REFERENCE back-reference when the NM "include
 * reference" checkbox is set — or undefined when the user cancels.
 */
@Injectable({ providedIn: 'root' })
export class MessageCopyDialogService extends DialogHostService<string[]> {
  readonly messageId = signal('');
  readonly mainType = signal<MainType | undefined>(undefined);

  /** Open the copy dialog for a message; the "include reference" checkbox is offered for NM only. */
  open(messageId: string, mainType: MainType | undefined): Observable<string[] | undefined> {
    this.messageId.set(messageId);
    this.mainType.set(mainType);
    return this.armOpen();
  }

  /**
   * Resolve the copy: navigate to the copy route, appending the REFERENCE reference type when the
   * user asked to include a back-reference to the copied message (NM only).
   */
  confirm(includeReference: boolean): void {
    const id = this.messageId();
    this.submit(includeReference ? ['/editor/copy', id, 'REFERENCE'] : ['/editor/copy', id]);
  }
}
