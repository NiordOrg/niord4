import { Component, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { partTypeLabelKey } from '../../../../shared/utils/part-type-label-key';
import { Message, MessagePart } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageIdBadge } from '../message-id-badge/message-id-badge';
import { MessagePart as MessagePartComponent } from '../message-part/message-part';
import { MessageAttachment } from '../message-attachment/message-attachment';
import { MessageAttachmentList } from '../message-attachment-list/message-attachment-list';
import { MessageReferenceType } from '../message-reference-type/message-reference-type';
import { MessageCharts } from '../message-charts/message-charts';
import { MessagePublication } from '../message-publication/message-publication';
import { MessageSource } from '../message-source/message-source';
import { PromulgationList } from '../../promulgation/promulgation-list/promulgation-list';

@Component({
  selector: 'app-message-details-renderer',
  templateUrl: './message-details-renderer.html',
  imports: [
    Icon,
    TranslocoPipe,
    MessageIdBadge,
    MessagePartComponent,
    MessageAttachment,
    MessageAttachmentList,
    MessageReferenceType,
    MessageCharts,
    MessagePublication,
    MessageSource,
    PromulgationList,
  ],
})
export class MessageDetailsRenderer {
  private readonly descriptionService = inject(MessageDescriptionService);
  private readonly transloco = inject(TranslocoService);

  readonly message = input.required<Message>();

  /**
   * Content language for desc selection. When omitted (every existing caller),
   * desc resolution falls back to the active app language inside
   * `MessageDescriptionService`, so the default path is behavior-identical and
   * still reactive to app-language changes. A live-preview / comparison surface
   * passes an explicit code to render the message in a chosen content language
   * independent of the app language — threaded to every desc-rendering child.
   */
  readonly language = input<string>();

  /**
   * When true, renders a promulgation block showing only promulgated channels.
   * Default false so the message-list/details surfaces are unchanged;
   * the comparison dialog opts in.
   */
  readonly showPromulgation = input(false);

  /**
   * Emits a referenced message id when the user clicks a reference link inside
   * the body. Consumers (e.g. MessageDetailsDialog) wire this to their own
   * drill / navigation handler.
   */
  readonly referenceClicked = output<string>();

  private readonly desc = computed(() =>
    this.descriptionService.getDescription(this.message().descs, this.language()),
  );

  readonly title = computed(() =>
    this.descriptionService.getTitle(this.message().descs, this.language()),
  );

  readonly references = computed(() => this.message().references ?? []);
  readonly parts = computed(() => this.message().parts ?? []);
  readonly charts = computed(() => this.message().charts ?? []);
  readonly allAttachments = computed(() => this.message().attachments ?? []);

  // Attachments authored with a display placement render INLINE at full size
  // above/below the message body; they also stay in the toggleable list with
  // the rest.
  readonly attachmentsAbove = computed(() =>
    this.allAttachments().filter((a) => a.display === 'ABOVE'),
  );
  readonly attachmentsBelow = computed(() =>
    this.allAttachments().filter((a) => a.display === 'BELOW'),
  );

  // Only entries set to promulgate render in the block (PromulgationList default `showAll=false`),
  // so gate the heading on the same predicate — otherwise entries that exist but none promulgated
  // would show an orphaned heading over an empty list.
  readonly hasPromulgations = computed(() =>
    (this.message().promulgations ?? []).some((p) => p.promulgate),
  );

  readonly hasReferences = computed(() => this.references().length > 0);
  readonly hasParts = computed(() => this.parts().length > 0);
  readonly hasCharts = computed(() => this.charts().length > 0);
  readonly hasAttachments = computed(() => this.allAttachments().length > 0);

  readonly hasPublication = computed(() => {
    const d = this.desc();
    return !!(d?.publication || d?.internalPublication);
  });

  readonly showAttachments = signal(false);

  // translateSignal keeps the toggle label reactive to language changes — the
  // previous one-shot transloco.translate() froze it in the bootstrap language
  // until the next toggle (architecture: all user-visible text updates on
  // language change).
  private readonly showAttachmentsLabel = translateSignal('msg.show_attachments');
  private readonly hideAttachmentsLabel = translateSignal('msg.hide_attachments');
  readonly attachmentsToggleLabel = computed(() =>
    this.showAttachments() ? this.hideAttachmentsLabel() : this.showAttachmentsLabel(),
  );

  partTypeLabel(part: MessagePart): string {
    const type = part.type?.toLowerCase();
    if (!type) {
      return '';
    }
    const key = partTypeLabelKey(type);
    // Unknown (free-form backend) part types show their raw type text rather
    // than a translated made-up key rendering as the key string.
    return key ? this.transloco.translate(key) : (part.type ?? '');
  }

  isFirstOfType(parts: readonly MessagePart[], index: number): boolean {
    return index === 0 || parts[index].type !== parts[index - 1].type;
  }

  toggleAttachments(): void {
    this.showAttachments.update((v) => !v);
  }
}
