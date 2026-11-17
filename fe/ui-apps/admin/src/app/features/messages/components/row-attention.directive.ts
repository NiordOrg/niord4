import { Directive, computed, inject, input } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import type { Message } from '../models/message.model';
import { MessageDateFormatterService } from '../services/message-date-formatter.service';

/**
 * Flags a message list row that needs looking at — currently, one whose follow-up date has
 * passed. Applies the `.row-attention` left rail and the hover explanation that goes with it.
 *
 * A directive rather than a per-view helper because the table, details and selected views all
 * carry the same row: three copies of "is it overdue, and what does the tooltip say" would drift.
 * The rail is the only colour: the row keeps its ordinary background and everything inside it its
 * ordinary colours; `.row-attention` yields to `.row-selected` so a picked row still reads as picked.
 *
 * The hover title is the non-colour half of the cue — without it the state would be conveyed by
 * colour alone.
 */
@Directive({
  selector: '[appRowAttention]',
  host: {
    '[class.row-attention]': 'needsAttention()',
    '[attr.title]': 'needsAttention() ? hint() : null',
  },
})
export class RowAttentionDirective {
  private readonly dateFormatter = inject(MessageDateFormatterService);

  readonly message = input.required<Message>({ alias: 'appRowAttention' });

  protected readonly hint = translateSignal('messages.list.attention.title');

  protected readonly needsAttention = computed(() =>
    this.dateFormatter.isOverdue(this.message().followUpDate),
  );
}
