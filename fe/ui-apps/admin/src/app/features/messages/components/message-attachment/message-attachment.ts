import { Component, computed, inject, input, output } from '@angular/core';

import { MessageAttachment as MessageAttachmentModel } from '../../models/message.model';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import { MessageAttachmentTypeService } from '../../services/message-attachment-type.service';
import { MessageDescriptionService } from '../../services/message-description.service';

@Component({
  selector: 'app-message-attachment',
  templateUrl: './message-attachment.html',
})
export class MessageAttachment {
  private readonly urlBuilder = inject(MessageUrlBuilderService);
  private readonly typeService = inject(MessageAttachmentTypeService);
  private readonly descriptionService = inject(MessageDescriptionService);

  readonly attachment = input.required<MessageAttachmentModel>();
  readonly size = input(40);
  /**
   * `thumbnail` (default): the clickable thumbnail + name/caption label.
   * `source`: the attachment's actual image/video rendered inline at its
   * authored `width`/`height` with an italic caption — the details-view
   * treatment for attachments whose `display` is ABOVE/BELOW the message body.
   */
  readonly mode = input<'thumbnail' | 'source'>('thumbnail');
  // Explicit-only language resolution: when set, the caption resolves at this language;
  // when unset the positional descs[0] read stays exactly as-is so existing callers
  // (whose payloads may not lead with the app language) are behavior-identical.
  readonly language = input<string>();
  readonly clicked = output<MessageAttachmentModel>();

  readonly thumbnailUrl = computed(() => {
    const path = this.attachment().path;
    return path ? this.urlBuilder.getAttachmentThumbnailUrl(path, this.size()) : '';
  });

  readonly fileUrl = computed(() => {
    const path = this.attachment().path;
    return path ? this.urlBuilder.getAttachmentFileUrl(path) : '';
  });

  readonly isImage = computed(() => this.typeService.isImage(this.attachment().type));

  /** Source mode renders a `<video>` for video types, an `<img>` for everything else. */
  readonly isVideo = computed(() => (this.attachment().type ?? '').startsWith('video'));

  /** The authored `width`/`height` (CSS-unit strings), applied verbatim as the element's inline size. */
  readonly sourceWidth = computed(() => this.attachment().width ?? null);
  readonly sourceHeight = computed(() => this.attachment().height ?? null);

  readonly caption = computed(() => {
    const descs = this.attachment().descs;
    if (!descs || descs.length === 0) {
      return '';
    }
    const language = this.language();
    if (language) {
      return this.descriptionService.getDescription(descs, language)?.caption ?? '';
    }
    return descs[0]?.caption ?? '';
  });

  readonly displayLabel = computed(() => this.caption() || this.attachment().fileName || '');

  onClick(): void {
    this.clicked.emit(this.attachment());
  }
}
