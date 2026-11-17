import { Component, computed, inject, input } from '@angular/core';
import { MessageLabelService } from '../../../../core/services/message-label.service';
import { Message } from '../../../../core/models/message.model';

@Component({
  selector: 'app-message-id-badge',
  // Markup, not a string of HTML: the pills are elements the template writes, and the gap here
  // is what separates an identifier from the state chip beside it.
  template: `
    @let b = badge();
    <span class="inline-flex items-center gap-1">
      <span class="status-badge mono-id">{{ b.label }}</span>
      @if (b.status; as status) {
        <span
          class="status-badge"
          [class.is-cancelled]="status.kind === 'CANCELLED'"
          [class.is-expired]="status.kind === 'EXPIRED'"
          >{{ status.label }}</span
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

  /** A computed, so a language switch re-words the badge without waiting for a refetch. */
  protected readonly badge = computed(() => this.labels.badge(this.msg(), this.showStatus()));
}
