import { Component, inject } from '@angular/core';

import { EmptySearchResult } from '../../components/empty-search-result/empty-search-result';
import { LoadMoreMessages } from '../../components/load-more-messages/load-more-messages';
import { MessageAreaHeading } from '../../components/message-area-heading/message-area-heading';
import { MessageGridCard } from '../../components/message-grid-card/message-grid-card';
import { MessageListStatus } from '../../components/message-list-status/message-list-status';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { paintedOnce } from '../../../../shared/utils/next-paint';

@Component({
  selector: 'app-messages-grid-view',
  imports: [
    MessageListStatus,
    EmptySearchResult,
    LoadMoreMessages,
    MessageAreaHeading,
    MessageGridCard,
  ],
  templateUrl: './messages-grid-view.html',
})
export class MessagesGridView {
  private readonly dialog = inject(MessageDetailsDialogService);
  protected readonly facade = inject(MessagesFacade);

  /**
   * Whether the view has been painted once. Until then it shows no rows, as while a search is under
   * way, so the frame after a tab click already shows this view; its rows are built in the task after.
   */
  protected readonly painted = paintedOnce();

  protected onOpenDialog(messageId: string): void {
    this.dialog.open(
      messageId,
      this.facade.list.messages().map((m) => m.id),
    );
  }
}
