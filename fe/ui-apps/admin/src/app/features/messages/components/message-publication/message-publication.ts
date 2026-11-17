import { Component, computed, inject, input } from '@angular/core';

import { Message } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { SafeMessageHtmlPipe } from '../../pipes/safe-message-html.pipe';

@Component({
  selector: 'app-message-publication',
  host: { class: 'contents' },
  templateUrl: './message-publication.html',
  imports: [SafeMessageHtmlPipe],
})
export class MessagePublication {
  private readonly descriptionService = inject(MessageDescriptionService);

  readonly message = input.required<Message>();
  readonly showInternal = input(false);

  /**
   * Content language for desc selection. Omitted by default → the active app
   * language (behavior-identical for existing callers); a live-preview surface
   * passes an explicit code to render the publication in a chosen language.
   */
  readonly language = input<string>();

  readonly publication = computed(() =>
    this.descriptionService.getPublication(this.message().descs, this.language()),
  );
  readonly internalPublication = computed(() =>
    this.descriptionService.getInternalPublication(this.message().descs, this.language()),
  );
}
