import { Component, input, inject, computed } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import { MessageLabelService } from '../../../../core/services/message-label.service';
import { LanguageService } from '../../../../core/services/language.service';
import { DateFormatService } from '../../../../core/services/date-format.service';
import { Message } from '../../../../core/models/message.model';

@Component({
  selector: 'app-message-source',
  template: `<span class="italic text-fg-muted">{{ sourceText() }}</span>`,
})
export class MessageSourceComponent {
  private readonly labels = inject(MessageLabelService);
  private readonly languageService = inject(LanguageService);
  private readonly dateFormat = inject(DateFormatService);

  msg = input.required<Message>();

  /** A signal, so the line is re-worded when the bundle lands and when the language changes. */
  private readonly publishedWord = translateSignal('FIELD_PUBLISHED');

  sourceText = computed(() => {
    const m = this.msg();
    const lang = this.languageService.language();
    let source = '';

    const desc = this.labels.desc(m, lang);
    if (desc?.source) {
      source = desc.source;
    }

    if (m.publishDateFrom) {
      if (source.length > 0) {
        if (source.charAt(source.length - 1) !== '.') source += '.';
        source += ' ';
      }
      // The date renders in the deployment's configured zone — the same instant is a different
      // day to a reader whose browser sits a few hours either side of it. An empty string means
      // the language's pattern has not arrived yet, and the date is left off rather than
      // printing a key name under the notice.
      const published = this.dateFormat.format(m.publishDateFrom, 'SOURCE_DATE_FORMAT');
      if (published) {
        source += this.publishedWord() + ' ' + published;
      }
    }

    return source;
  });
}
