import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { MessagesFilterPanel } from './components/messages-filter-panel/messages-filter-panel';
import { MessageComparisonDialog } from './dialogs/message-comparison-dialog/message-comparison-dialog';
import { MessageCopyDialog } from './dialogs/message-copy-dialog/message-copy-dialog';
import { MessageDetailsDialog } from './message-details-dialog/message-details-dialog';
import { MessageMailDialog } from './dialogs/message-mail-dialog/message-mail-dialog';
import { MessagePrintDialog } from './dialogs/message-print-dialog/message-print-dialog';
import { MessageTagsDialog } from './dialogs/message-tags-dialog/message-tags-dialog';
import { SortAreaDialog } from './dialogs/sort-area-dialog/sort-area-dialog';
import { MessagesTabMenu } from './messages-tab-menu/messages-tab-menu';
import { MessagesFilterUrlSync } from './services/messages-filter-url-sync.service';
import { MessagesShellFacade } from './services/messages-shell.facade';
import { MyMailsNoticeService } from './services/my-mails-notice.service';

@Component({
  selector: 'app-messages',
  imports: [
    RouterOutlet,
    MessagesTabMenu,
    MessagesFilterPanel,
    MessageComparisonDialog,
    MessageCopyDialog,
    MessageDetailsDialog,
    MessageMailDialog,
    MessagePrintDialog,
    MessageTagsDialog,
    SortAreaDialog,
  ],
  templateUrl: './messages.html',
})
export class Messages {
  protected readonly shell = inject(MessagesShellFacade);

  constructor() {
    // Force instantiation so URL ↔ filter facade subscriptions register at shell mount.
    inject(MessagesFilterUrlSync);
    // Tells the user of a mail they sent that could not be sent (once per page load).
    inject(MyMailsNoticeService).start();
  }
}
