import { Component, computed, inject, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import { Icon } from '../../../../shared/icons/icon';
import { MessageAttachment } from '../../models/message.model';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import { MessageAttachmentTypeService } from '../../services/message-attachment-type.service';

@Component({
  selector: 'app-message-attachment-preview-dialog',
  templateUrl: './message-attachment-preview-dialog.html',
  imports: [Dialog, Icon, TranslocoPipe],
})
export class MessageAttachmentPreviewDialog {
  private readonly urlBuilder = inject(MessageUrlBuilderService);
  private readonly typeService = inject(MessageAttachmentTypeService);

  readonly attachment = input.required<MessageAttachment>();
  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly fileUrl = computed(() => {
    const path = this.attachment().path;
    return path ? this.urlBuilder.getAttachmentFileUrl(path) : '';
  });

  readonly isImage = computed(() => this.typeService.isImage(this.attachment().type));
  readonly isVideo = computed(() => this.typeService.isVideo(this.attachment().type));

  readonly dialogHeader = computed(() => this.attachment().fileName ?? '');

  onHide(): void {
    this.visibleChange.emit(false);
  }

  openInNewTab(): void {
    const url = this.fileUrl();
    if (url) {
      window.open(url, '_blank', 'noopener,noreferrer');
    }
  }
}
