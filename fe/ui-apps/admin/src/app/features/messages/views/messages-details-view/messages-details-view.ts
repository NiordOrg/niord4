import { Component, inject } from '@angular/core';

import { EmptySearchResult } from '../../components/empty-search-result/empty-search-result';
import { LoadMoreMessages } from '../../components/load-more-messages/load-more-messages';
import { MessageAreaHeading } from '../../components/message-area-heading/message-area-heading';
import { MessageDetailsRow } from '../../components/message-details-row/message-details-row';
import { MessageListStatus } from '../../components/message-list-status/message-list-status';
import type { Message } from '../../models/message.model';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { MessageEditShortcutService } from '../../services/message-edit-shortcut.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { RowLinkDirective } from '../../../../shared/directives/row-link.directive';
import { RowAttentionDirective } from '../../components/row-attention.directive';
import { paintedOnce } from '../../../../shared/utils/next-paint';

@Component({
  selector: 'app-messages-details-view',
  imports: [
    MessageListStatus,
    EmptySearchResult,
    LoadMoreMessages,
    MessageAreaHeading,
    MessageDetailsRow,
    RowLinkDirective,
    RowAttentionDirective,
  ],
  templateUrl: './messages-details-view.html',
})
export class MessagesDetailsView {
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
    this.dialog.open(
      messageId,
      this.facade.list.messages().map((m) => m.id),
    );
  }
}
