import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { DialogHostService } from '../../../../shared/dialogs/dialog-host.service';

/**
 * Host-component opener for the two-message comparison. The messages route and the
 * editor shell each mount a single `<app-message-comparison-dialog>`; only one is
 * alive at a time (they are sibling routes), so one root service drives whichever is
 * mounted.
 *
 * The two picked ids are service state (not component state) so each side's reactive
 * load keys on them: reopening with the same ids does not refetch, and so cannot
 * re-toast a stale load error.
 */
@Injectable({ providedIn: 'root' })
export class MessageComparisonDialogService extends DialogHostService<void> {
  readonly messageId1 = signal<string | null>(null);
  readonly messageId2 = signal<string | null>(null);

  /**
   * Reveal the dialog with either side prefilled (the editor seeds only side 2; the
   * message list seeds both, in selection order). A missing id leaves that side blank.
   */
  open(messageId1?: string, messageId2?: string): Observable<void | undefined> {
    this.messageId1.set(messageId1 ?? null);
    this.messageId2.set(messageId2 ?? null);
    return this.armOpen();
  }

  /** Exchange the two ids — both sides and the data snapshots follow. */
  swap(): void {
    const first = this.messageId1();
    this.messageId1.set(this.messageId2());
    this.messageId2.set(first);
  }
}
