import { Component, computed, inject } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { MessagesFacade } from '../../services/messages-facade.service';

@Component({
  selector: 'app-load-more-messages',
  host: { class: 'contents' },
  imports: [],
  templateUrl: './load-more-messages.html',
})
export class LoadMoreMessages {
  private readonly facade = inject(MessagesFacade);
  private readonly localeFormat = inject(LocaleFormatService);

  // Hidden while a load failed: loadMore() is error-gated in the facade (a failed
  // page must be retried, never skipped), so the retry banner is the recovery path.
  readonly visible = computed(
    () => this.facade.list.hasMore() && this.facade.list.error() === null,
  );

  private readonly labelParams = computed(() => {
    const loaded = this.facade.list.messages().length;
    const total = this.facade.list.total();
    const maxPage = this.facade.shell.maxPageSize();
    return {
      from: this.localeFormat.formatNumber(loaded),
      to: this.localeFormat.formatNumber(Math.min(loaded + maxPage, total)),
      total: this.localeFormat.formatNumber(total),
    };
  });

  readonly label = translateSignal('msg.list.load_more', this.labelParams);

  onClick(): void {
    this.facade.list.loadMore();
  }
}
