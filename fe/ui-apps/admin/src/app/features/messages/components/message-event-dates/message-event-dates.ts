import { Component, computed, inject, input } from '@angular/core';

import { DateInterval, Message } from '../../models/message.model';
import type { MessageDateColumnType } from '../../models/message-date-column-type.model';
import { MessageDateFormatterService } from '../../services/message-date-formatter.service';

@Component({
  selector: 'app-message-event-dates',
  host: { class: 'contents' },
  template: `@if (formatted()) {
    <span class="whitespace-nowrap">
      {{ formatted() }}
      @if (moreTitle()) {
        <!-- Truncation indicator (first-line DATE cell): a red "…" span whose
             hover title lists every interval, newline-joined. -->
        <span class="cursor-pointer text-danger" [title]="moreTitle()">&hellip;</span>
      }
    </span>
  }`,
})
export class MessageEventDates {
  private readonly dateFormatter = inject(MessageDateFormatterService);

  readonly eventDates = input<DateInterval[]>();
  readonly message = input<Message>();
  readonly type = input<MessageDateColumnType>('DATE');
  readonly firstLine = input(false);

  readonly formatted = computed(() => {
    const message = this.message();
    if (!message) {
      return this.dateFormatter.formatEventDates(this.eventDates());
    }
    return this.dateFormatter.formatForColumnType(message, this.type(), this.firstLine());
  });

  // A passed follow-up date is deliberately NOT flagged on the date text. The row
  // carries that cue (`.row-attention`), so this keeps ordinary body colour.

  /** The `…` hover title when the first-line DATE cell truncates intervals. */
  readonly moreTitle = computed(() => {
    const message = this.message();
    if (!message || !this.firstLine() || this.type() !== 'DATE') {
      return '';
    }
    return this.dateFormatter.hiddenEventDatesTitle(message);
  });
}
