import { Component, computed, inject, input } from '@angular/core';

import { MessagePart as MessagePartModel } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { SafeMessageHtmlPipe } from '../../pipes/safe-message-html.pipe';

@Component({
  selector: 'app-message-part',
  host: { class: 'contents' },
  templateUrl: './message-part.html',
  imports: [SafeMessageHtmlPipe],
})
export class MessagePart {
  private readonly descriptionService = inject(MessageDescriptionService);

  readonly part = input.required<MessagePartModel>();
  readonly subject = input(true);

  /**
   * Content language for desc selection. Omitted by default → the active app
   * language (behavior-identical for existing callers); a live-preview surface
   * passes an explicit code to render this part in a chosen language.
   */
  readonly language = input<string>();

  readonly subjectText = computed(() => {
    const desc = this.descriptionService.getDescription(this.part().descs, this.language());
    return desc?.subject ?? '';
  });

  readonly details = computed(() => {
    const desc = this.descriptionService.getDescription(this.part().descs, this.language());
    return desc?.details ?? '';
  });
}
