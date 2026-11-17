import { Component, computed, inject } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import type { CommentsValue } from '../../../models/messages-filter-params.model';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { MessagesFacade } from '../../../services/messages-facade.service';

interface CommentsOption {
  readonly label: string;
  readonly value: CommentsValue;
}

@Component({
  selector: 'app-comments-filter-panel',
  templateUrl: './comments-filter-panel.html',
  imports: [FilterPanel],
})
export class CommentsFilterPanel {
  private readonly messages = inject(MessagesFacade);
  private readonly auth = inject(AuthFacade);

  private readonly ignoreLabel = translateSignal('messages.filter.comments.option.ignore');
  private readonly anyLabel = translateSignal('messages.filter.comments.option.any');
  private readonly ownLabel = translateSignal('messages.filter.comments.option.own');
  private readonly anyUnackLabel = translateSignal('messages.filter.comments.option.anyUnack');
  private readonly ownUnackLabel = translateSignal('messages.filter.comments.option.ownUnack');

  readonly value = computed(() => this.messages.filter.slice('comments')().value);

  readonly options = computed<CommentsOption[]>(() => {
    const isAuth = this.auth.isAuthenticated();
    const items: CommentsOption[] = [
      { label: this.ignoreLabel(), value: '' },
      { label: this.anyLabel(), value: 'ANY' },
    ];
    if (isAuth) {
      items.push({ label: this.ownLabel(), value: 'OWN' });
    }
    items.push({ label: this.anyUnackLabel(), value: 'ANY_UNACK' });
    if (isAuth) {
      items.push({ label: this.ownUnackLabel(), value: 'OWN_UNACK' });
    }
    return items;
  });

  onChange(value: CommentsValue): void {
    this.messages.filter.setSliceValue('comments', { value });
  }

  clear(): void {
    this.messages.filter.clearFilter('comments');
  }
}
