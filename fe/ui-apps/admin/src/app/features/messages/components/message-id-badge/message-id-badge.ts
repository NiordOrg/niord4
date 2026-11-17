import { Component, computed, inject, input } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { SelectAllOnDblclickDirective } from '../../../../shared/directives/select-all-on-dblclick.directive';
import { StatusClassPipe } from '../../../../shared/pipes/status-class.pipe';
import { Message } from '../../models/message.model';
import { MessageRenderingService } from '../../services/message-rendering.service';

@Component({
  selector: 'app-message-id-badge',
  templateUrl: './message-id-badge.html',
  imports: [StatusClassPipe, SelectAllOnDblclickDirective],
})
export class MessageIdBadge {
  private readonly renderingService = inject(MessageRenderingService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);

  readonly message = input.required<Message>();

  readonly displayValue = computed(() => {
    const msg = this.message();
    if (msg.shortId) {
      return msg.shortId;
    }
    // Draft messages don't have a shortId yet. Render a localized
    // "<type> <mainType>" placeholder, e.g. "Permanent NM". The title is
    // deliberately NOT used as a fallback — it's already rendered in the
    // adjacent title cell/section, and duplicating it inside the ID badge
    // visually collapses the ID column into the title column.
    this.language.selectedLanguage(); // depend on the active locale signal
    const typeLabel = msg.type ? this.transloco.translate(`msg.type.${msg.type}`) : '';
    const parts = [typeLabel, msg.mainType ?? ''].filter((s) => s.length > 0);
    return parts.join(' ');
  });

  readonly suffix = computed(() => {
    const msg = this.message();
    if (!msg.shortId) {
      return '';
    }
    return this.renderingService.getIdSuffix(msg).trimStart();
  });
}
