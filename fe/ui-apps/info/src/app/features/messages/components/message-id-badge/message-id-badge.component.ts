import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { MessageLabelService } from '../../../../core/services/message-label.service';
import { Message } from '../../../../core/models/message.model';

@Component({
  selector: 'app-message-id-badge',
  imports: [TranslocoPipe],
  // Markup, not a string of HTML: the pills are elements the template writes, and the gap here
  // is what separates an identifier from the state chip beside it. The words are translated
  // here, by the pipe, so they change with the language and fill in when its bundle arrives.
  template: `
    @let b = badge();
    @let typeWord = b.typeKey ? (b.typeKey | transloco) + ' ' : '';
    @let mainTypeWord = b.mainTypeKey ? (b.mainTypeKey | transloco) : '';
    <span class="inline-flex items-center gap-1">
      <span class="status-badge mono-id">{{ b.shortId ?? typeWord + mainTypeWord }}</span>
      @if (b.status; as status) {
        <span
          class="status-badge"
          [class.is-cancelled]="status.kind === 'CANCELLED'"
          [class.is-expired]="status.kind === 'EXPIRED'"
          >{{ status.key | transloco }}</span
        >
      }
      @if (b.suffix; as suffix) {
        <span>&nbsp; {{ suffix }}</span>
      }
    </span>
  `,
})
export class MessageIdBadgeComponent {
  private readonly labels = inject(MessageLabelService);

  msg = input.required<Message>();
  showStatus = input(false);

  /** What the badge shows, as keys: the template words them in the language on screen. */
  protected readonly badge = computed(() => this.labels.badge(this.msg(), this.showStatus()));
}
