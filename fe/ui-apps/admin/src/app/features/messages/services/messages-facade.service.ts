import { computed, inject, Injectable, Signal } from '@angular/core';

import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { MessageListFacade } from './message-list.facade';
import { MessageSelectionService } from './message-selection.service';
import { MessagesFilterFacade } from './messages-filter.facade';
import { MessagesShellFacade } from './messages-shell.facade';

@Injectable({ providedIn: 'root' })
export class MessagesFacade {
  readonly selection = inject(MessageSelectionService);
  readonly shell = inject(MessagesShellFacade);
  readonly filter = inject(MessagesFilterFacade);
  readonly list = inject(MessageListFacade);
  private readonly domain = inject(DomainFacade);

  readonly viewMode = this.shell.viewMode;
  readonly isMap = this.shell.isMap;
  readonly showFilter = this.shell.showFilter;
  readonly maxPageSize = this.shell.maxPageSize;

  readonly selectionSize = computed(() => this.selection.selectedCount());
  readonly searchPublicMessages = this.domain.searchPublicMessages;

  readonly requiresSearch = this.shell.requiresSearch;

  clearSelection(): void {
    this.selection.clear();
  }

  canEditMessageSeries(seriesId: string): Signal<boolean> {
    return this.domain.canEditMessageSeries(seriesId);
  }
}
