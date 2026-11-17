import { Component, computed, input, output } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { Menu } from '../../../../shared/components/menu/menu';
import { MenuTriggerFor } from '../../../../shared/components/menu/menu-trigger.directive';
import type { MenuItem } from '../../../../shared/models/menu-item.model';
import type { MessageDateColumnType } from '../../models/message-date-column-type.model';
import type { SortBy, SortOrder } from '../../models/messages-filter-params.model';

@Component({
  selector: 'app-message-date-column-header',
  imports: [Icon, Menu, MenuTriggerFor],
  templateUrl: './message-date-column-header.html',
})
export class MessageDateColumnHeader {
  readonly dateColumnType = input.required<MessageDateColumnType>();
  readonly sortBy = input.required<SortBy>();
  readonly sortOrder = input.required<SortOrder>();

  readonly typeChanged = output<MessageDateColumnType>();
  readonly sortClicked = output<void>();

  // `translateSignal` keeps these reactive — the picker label and the dropdown
  // items now re-render on language change. Using `transloco.translate(...)`
  // would freeze them at the language active at component creation.
  private readonly eventLabel = translateSignal('messages.table.dates.event');
  private readonly publishLabel = translateSignal('messages.table.dates.publish');
  private readonly followUpLabel = translateSignal('messages.table.dates.followUp');
  private readonly sortAscLabel = translateSignal('messages.table.sort.asc');
  private readonly sortDescLabel = translateSignal('messages.table.sort.desc');
  protected readonly selectLabel = translateSignal('messages.table.dates.selectAria');

  readonly currentLabel = computed(() => this.labelFor(this.dateColumnType()));

  readonly typeOptions = computed<MenuItem[]>(() => [
    { label: this.eventLabel(), command: () => this.typeChanged.emit('DATE') },
    { label: this.publishLabel(), command: () => this.typeChanged.emit('PUBLISH_DATE') },
    { label: this.followUpLabel(), command: () => this.typeChanged.emit('FOLLOW_UP_DATE') },
  ]);

  readonly isSorted = computed(() => this.sortBy() === this.dateColumnType());
  readonly directionIconClass = computed(() =>
    this.sortOrder() === 'ASC' ? 'lucideArrowUp' : 'lucideArrowDown',
  );
  readonly directionAriaLabel = computed(() =>
    this.sortOrder() === 'ASC' ? this.sortAscLabel() : this.sortDescLabel(),
  );

  onSort(): void {
    this.sortClicked.emit();
  }

  private labelFor(type: MessageDateColumnType): string {
    switch (type) {
      case 'DATE':
        return this.eventLabel();
      case 'PUBLISH_DATE':
        return this.publishLabel();
      case 'FOLLOW_UP_DATE':
        return this.followUpLabel();
    }
  }
}
