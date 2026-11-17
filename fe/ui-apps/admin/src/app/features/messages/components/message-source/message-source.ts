import { Component, computed, inject, input } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import { Message, PUBLISHED_MESSAGE_STATUSES } from '../../models/message.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageDateFormatterService } from '../../services/message-date-formatter.service';

@Component({
  selector: 'app-message-source',
  host: { class: 'contents' },
  templateUrl: './message-source.html',
})
export class MessageSource {
  private readonly descriptionService = inject(MessageDescriptionService);
  private readonly dateFormatter = inject(MessageDateFormatterService);
  private readonly transloco = inject(TranslocoService);

  readonly message = input.required<Message>();

  /**
   * Content language for desc selection. Omitted by default → the active app
   * language (behavior-identical for existing callers); a live-preview surface
   * passes an explicit code to render the source line in a chosen language.
   */
  readonly language = input<string>();

  private readonly source = computed(() =>
    this.descriptionService.getSource(this.message().descs, this.language()),
  );

  private readonly publishedLabel = computed(() => {
    const msg = this.message();
    const isPublished = !!msg.status && PUBLISHED_MESSAGE_STATUSES.has(msg.status);
    if (!isPublished || !msg.publishDateFrom) {
      return '';
    }
    const date = this.dateFormatter.formatDateInterval({ fromDate: msg.publishDateFrom });
    if (!date) {
      return '';
    }
    // The compiled bundle carries a `{{date}}` token ("Published {{date}}"), but the
    // backend `message` Dictionary overrides this key with a plain label
    // ("Publiceret"/"Published") and that override wins at runtime — dropping the date.
    // Append the date whenever the active label lacks it, so both forms render
    // "<label> <date>".
    const label = this.transloco.translate('msg.field.published', { date });
    return label.includes(date) ? label : `${label} ${date}`;
  });

  readonly content = computed(() => {
    const src = this.source();
    const pub = this.publishedLabel();
    if (src && pub) return `${src}. ${pub}`;
    if (src) return `${src}.`;
    return pub;
  });
}
