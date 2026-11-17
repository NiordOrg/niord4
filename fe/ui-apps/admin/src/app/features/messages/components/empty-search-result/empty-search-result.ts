import { Component, computed, inject } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';

@Component({
  selector: 'app-empty-search-result',
  imports: [EmptyState],
  templateUrl: './empty-search-result.html',
})
export class EmptySearchResult {
  private readonly facade = inject(MessagesFacade);
  private readonly localeFormat = inject(LocaleFormatService);

  private readonly matchesParams = computed(() => ({
    count: this.localeFormat.formatNumber(this.facade.list.total()),
  }));

  readonly title = translateSignal('messages.list.empty.prompt');

  private readonly matchesLabel = translateSignal(
    'messages.list.empty.matches',
    this.matchesParams,
  );
  private readonly noMatchesLabel = translateSignal('messages.list.empty.noMatches');

  readonly body = computed(() =>
    this.facade.list.total() > 0 ? this.matchesLabel() : this.noMatchesLabel(),
  );
}
