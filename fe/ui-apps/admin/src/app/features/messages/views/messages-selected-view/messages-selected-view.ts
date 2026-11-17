import { Component, inject } from '@angular/core';

import { EmptySelection } from '../../components/empty-selection/empty-selection';
import { MessageDetailsRow } from '../../components/message-details-row/message-details-row';
import type { Message } from '../../models/message.model';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { MessageEditShortcutService } from '../../services/message-edit-shortcut.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { RowLinkDirective } from '../../../../shared/directives/row-link.directive';
import { RowAttentionDirective } from '../../components/row-attention.directive';
import { paintedOnce } from '../../../../shared/utils/next-paint';

@Component({
  selector: 'app-messages-selected-view',
  imports: [EmptySelection, MessageDetailsRow, RowLinkDirective, RowAttentionDirective],
  templateUrl: './messages-selected-view.html',
})
export class MessagesSelectedView {
  private readonly dialog = inject(MessageDetailsDialogService);
  private readonly editShortcut = inject(MessageEditShortcutService);
  protected readonly facade = inject(MessagesFacade);

  /**
   * Whether the view has been painted once. Until then it shows no rows, as while a search is under
   * way, so the frame after a tab click already shows this view; its rows are built in the task after.
   */
  protected readonly painted = paintedOnce();

  /** Row click/activation: hold-E + click → edit directly (auth-gated), else open. */
  protected onActivate(message: Message): void {
    if (this.editShortcut.tryEdit(message)) {
      return;
    }
    this.onOpenDialog(message.id);
  }

  protected onOpenDialog(messageId: string): void {
    this.dialog.open(messageId, this.facade.selection.selectedIds());
  }
}
