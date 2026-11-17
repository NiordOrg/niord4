import { Component, computed, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { translateSignal } from '@jsverse/transloco';

import type { Message } from '@niord/admin/internal/features/messages/models/message.model';
import { Icon } from '@niord/admin/internal/shared/icons/icon';
import { MessageSelectionService } from '@niord/admin/internal/features/messages/services/message-selection.service';

@Component({
  selector: 'app-message-selection-toggle',
  imports: [FormsModule, Icon],
  templateUrl: './message-selection-toggle.html',
  styleUrl: './message-selection-toggle.scss',
  // `inline-flex items-center` on the host makes the star sit on the host's
  // vertical center instead of on the inline text baseline. The `<td>` already
  // vertically centers cell content by default; this aligns the icon WITHIN the
  // host box so the star ends up genuinely centered in the row.
  host: { class: 'inline-flex items-center' },
})
export class MessageSelectionToggle {
  private readonly selection = inject(MessageSelectionService);

  readonly message = input.required<Message>();

  readonly checked = computed(() => this.selection.isSelected(this.message().id));
  readonly inputId = computed(() => `msg-select-${this.message().id}`);
  readonly ariaLabel = translateSignal('messages.list.selection.toggle');

  onToggle(): void {
    this.selection.toggle(this.message());
  }
}
