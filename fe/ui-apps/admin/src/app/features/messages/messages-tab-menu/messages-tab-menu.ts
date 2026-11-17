import { Component, computed, ElementRef, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { Icon } from '../../../shared/icons/icon';
import { MessagesActionMenu } from '../messages-action-menu/messages-action-menu';
import { MessagesFilterDropdown } from '../messages-filter-dropdown/messages-filter-dropdown';
import { MessageSelectionService } from '../services/message-selection.service';
import {
  deriveViewMode,
  MessagesShellFacade,
  type MessagesViewMode,
} from '../services/messages-shell.facade';

interface TabDescriptor {
  readonly id: MessagesViewMode;
  readonly labelKey: string;
  readonly icon: string;
}

const TABS: readonly TabDescriptor[] = [
  { id: 'table', labelKey: 'view_mode.table', icon: 'lucideMenu' },
  { id: 'grid', labelKey: 'view_mode.grid', icon: 'lucideLayoutGrid' },
  { id: 'details', labelKey: 'view_mode.details', icon: 'lucideList' },
  { id: 'map', labelKey: 'view_mode.map', icon: 'lucideGlobe' },
  { id: 'selected', labelKey: 'view_mode.selection', icon: 'lucideStar' },
];

@Component({
  selector: 'app-messages-tab-menu',
  imports: [RouterLink, TranslocoPipe, Icon, MessagesFilterDropdown, MessagesActionMenu],
  templateUrl: './messages-tab-menu.html',
  host: {
    class: 'block',
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'onEscape()',
  },
})
export class MessagesTabMenu {
  private readonly selection = inject(MessageSelectionService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly shell = inject(MessagesShellFacade);
  private readonly router = inject(Router);
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly tabs = TABS;
  readonly selectionSize = this.selection.selectedCount;
  readonly hasSelection = computed(() => this.selectionSize() > 0);
  readonly selectionSizeLabel = computed(() =>
    this.localeFormat.formatNumber(this.selectionSize()),
  );

  readonly burgerOpen = signal(false);

  /**
   * The view whose tab shows as chosen: while a navigation to another view is under way, that view,
   * and otherwise the one the page is on. The router builds a view only after the browser has
   * painted once (`paintBeforeView`), so the tab the user clicked shows as chosen at once, and the
   * view follows — where marking the tab when the navigation ends would show both at the same time,
   * after the view had been built.
   */
  protected readonly shownTab = computed<MessagesViewMode | undefined>(() => {
    const navigation = this.router.currentNavigation();
    const target = navigation
      ? deriveViewMode(this.router.serializeUrl(navigation.finalUrl ?? navigation.extractedUrl))
      : undefined;
    return target ?? this.shell.viewMode();
  });

  readonly currentTab = computed<TabDescriptor | undefined>(() => {
    const mode = this.shownTab();
    return mode ? this.tabs.find((tab) => tab.id === mode) : undefined;
  });

  toggleBurger(): void {
    this.burgerOpen.update((open) => !open);
  }

  closeBurger(): void {
    this.burgerOpen.set(false);
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (!this.burgerOpen()) return;
    const target = event.target as Node | null;
    if (target && this.hostRef.nativeElement.contains(target)) return;
    this.closeBurger();
  }

  protected onEscape(): void {
    if (this.burgerOpen()) this.closeBurger();
  }

  onClearSelection(event: Event): void {
    event.preventDefault();
    this.selection.clear();
  }
}
