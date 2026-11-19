import { Component, computed, inject, input } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { MessagesFacade } from '../../services/messages-facade.service';
import { ErrorBanner } from '../../../../shared/components/error-banner/error-banner';
import { ProgressBar } from '../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../shared/icons/icon';

@Component({
  selector: 'app-message-list-status',
  imports: [ProgressBar, ErrorBanner, Icon],
  templateUrl: './message-list-status.html',
  // `relative block` (0 height when only the overlay progress bar is present) makes this the
  // positioning context for that top-pinned bar, so a reload never pushes the list down. The
  // error banner and the index notice still render in-flow below it. This wrapper is used only
  // inside the messages views (block-flow parents), so a 0-height block adds no phantom gap.
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

  /**
   * Whether the list on screen came from a free-text search the backend answered while its search
   * index was still being filled after a restart: the list is shown as it came, with a notice that
   * messages may be missing from it.
   */
  readonly indexIncomplete = computed(() => this.facade.list.indexIncomplete());

  readonly errorTitle = translateSignal('messages.list.error');
  readonly retryLabel = translateSignal('messages.list.retry');
  readonly indexIncompleteText = translateSignal('messages.list.indexIncomplete');

  onRetry(): void {
    this.facade.list.retry();
  }
}
