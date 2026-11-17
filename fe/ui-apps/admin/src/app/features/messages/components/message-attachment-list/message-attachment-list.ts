import { Component, input, signal } from '@angular/core';

import { MessageAttachment as MessageAttachmentModel } from '../../models/message.model';
import { MessageAttachment } from '../message-attachment/message-attachment';
import { MessageAttachmentPreviewDialog } from '../message-attachment-preview-dialog/message-attachment-preview-dialog';

@Component({
  selector: 'app-message-attachment-list',
  templateUrl: './message-attachment-list.html',
  imports: [MessageAttachment, MessageAttachmentPreviewDialog],
})
export class MessageAttachmentList {
  readonly attachments = input.required<MessageAttachmentModel[]>();
  /** Explicit-only caption language, passed through to each attachment (positional when unset). */
  readonly language = input<string>();

  readonly previewAttachment = signal<MessageAttachmentModel | null>(null);
  readonly previewVisible = signal(false);

  onAttachmentClicked(attachment: MessageAttachmentModel): void {
    this.previewAttachment.set(attachment);
    this.previewVisible.set(true);
  }

  onPreviewClosed(): void {
    this.previewVisible.set(false);
    this.previewAttachment.set(null);
  }
}
