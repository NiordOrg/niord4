import { Component, computed, inject, input } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { MessagesFacade } from '../../services/messages-facade.service';
import { ErrorBanner } from '../../../../shared/components/error-banner/error-banner';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';

@Component({
  selector: 'app-message-list-status',
  imports: [ProgressBar, ErrorBanner],
  templateUrl: './message-list-status.html',
  // `relative block` (0 height when only the overlay progress bar is present) makes this the
  // positioning context for that top-pinned bar, so a reload never pushes the list down. The
  // error banner still renders in-flow below it. This wrapper is used only inside the messages
  // views (block-flow parents), so a 0-height block adds no phantom gap.
  host: { class: 'relative block' },
})
export class MessageListStatus {
  private readonly facade = inject(MessagesFacade);

  /**
   * Whether the view holding this has its rows still to build — the bar shows then as it does while a
   * search is under way, since to the reader it is the same wait.
   */
  readonly pending = input(false);

  readonly loading = computed(() => this.facade.list.loading() || this.pending());
  readonly hasError = computed(() => this.facade.list.error() !== null);

  readonly errorTitle = translateSignal('messages.list.error');
  readonly retryLabel = translateSignal('messages.list.retry');

  onRetry(): void {
    this.facade.list.retry();
  }
}
