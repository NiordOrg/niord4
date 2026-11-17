import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { translateSignal, TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { Confirm } from '../../../shared/components/confirm/confirm';
import { Icon } from '../../../shared/icons/icon';
import { Menu } from '../../../shared/components/menu/menu';
import { MenuItemDef } from '../../../shared/components/menu/menu-item-def.directive';
import { MenuTrigger } from '../../../shared/components/menu-trigger/menu-trigger';
import type { MenuItem } from '../../../shared/models/menu-item.model';
import { SaveNamedFilterDialog } from '../components/save-named-filter-dialog/save-named-filter-dialog';
import { MessagesFacade } from '../services/messages-facade.service';
import type { FilterToggleMenuItem } from '../services/messages-filter.facade';
import { NamedFiltersFacade } from '../services/named-filters.facade';

interface NamedFilterMenuItem extends MenuItem {
  readonly extra?: { readonly onDelete: () => void };
}

@Component({
  selector: 'app-messages-filter-dropdown',
  imports: [Confirm, Icon, Menu, MenuItemDef, MenuTrigger, SaveNamedFilterDialog, TranslocoPipe],
  templateUrl: './messages-filter-dropdown.html',
})
export class MessagesFilterDropdown {
  private readonly auth = inject(AuthFacade);
  private readonly messages = inject(MessagesFacade);
  private readonly namedFiltersFacade = inject(NamedFiltersFacade);

  /** The filter menu's trigger — used to re-open the menu after a filter toggle. */
  private readonly menuTrigger = viewChild.required(MenuTrigger);

  readonly saveDialogVisible = signal(false);

  private readonly clearAllLabel = translateSignal('menu.filter.clear');
  private readonly saveNamedLabel = translateSignal('menu.filter.save');
  private readonly loadingLabel = translateSignal('common.loading');
  private readonly loadErrorLabel = translateSignal('messages.namedFilter.error.load');

  /**
   * Saved-filter rows as menu items: a loading placeholder, the load error when the list could not
   * be loaded, then one per filter with a delete action.
   */
  private readonly namedFilterItems = computed<NamedFilterMenuItem[]>(() => {
    const facade = this.namedFiltersFacade;
    if (!this.auth.isAuthenticated()) {
      return [];
    }
    if (facade.loading() && !facade.hasLoaded()) {
      return [{ label: this.loadingLabel(), disabled: true, styleClass: 'named-filter-loading' }];
    }
    if (facade.loadFailed()) {
      return [
        {
          label: this.loadErrorLabel(),
          icon: 'lucideTriangleAlert',
          disabled: true,
          styleClass: 'named-filter-error',
        },
      ];
    }
    return facade.namedFilters().map((filter) => ({
      label: filter.name,
      command: () => facade.apply(filter),
      styleClass: 'named-filter-item',
      extra: { onDelete: () => facade.confirmRemove(filter) },
    }));
  });

  readonly items = computed<MenuItem[]>(() => {
    const isAuth = this.auth.isAuthenticated();
    const named = this.namedFilterItems();
    // Filter-type toggles keep the menu open (multi-select) — CDK dismisses the
    // menu on every item click, so re-open it right after the toggle runs. Only
    // Clear/Save/named-filter items close the menu (standard leaf behaviour).
    const toggles = this.messages.filter.filterToggleItems().map((toggle) => ({
      ...toggle,
      command: () => {
        toggle.command?.();
        queueMicrotask(() => this.menuTrigger().reopen());
      },
    }));

    const result: MenuItem[] = [];
    result.push(...named);
    if (named.length > 0 && toggles.length > 0) {
      result.push({ separator: true });
    }
    result.push(...toggles);
    if (toggles.length > 0) {
      result.push({ separator: true });
    }
    result.push({
      label: this.clearAllLabel(),
      icon: 'lucideFilterX',
      disabled: !isAuth,
      command: () => this.messages.filter.clearAll(),
    });
    result.push({
      label: this.saveNamedLabel(),
      icon: 'lucideSave',
      disabled: !isAuth,
      command: () => this.openSaveDialog(),
    });
    return result;
  });

  /** Count of enabled filters — shown as a badge on the menu trigger. */
  readonly enabledFilterCount = this.messages.filter.enabledFilterCount;

  /** True for an enabled filter-toggle row — gets the accent "chosen" dot. */
  protected chosenDot(item: MenuItem): boolean {
    return (item as FilterToggleMenuItem).chosen === true;
  }

  /**
   * Loads the saved (named) filters when the dropdown opens. Fired from the
   * trigger's `(opened)` — the CDK menu owns activation + auto-close, so item
   * clicks route straight through `command` with no per-click wiring here.
   */
  onShow(): void {
    this.namedFiltersFacade.load();
  }

  onDeleteClick(event: MouseEvent, item: NamedFilterMenuItem): void {
    event.stopPropagation();
    item.extra?.onDelete();
  }

  isNamedFilterItem(item: MenuItem | NamedFilterMenuItem): item is NamedFilterMenuItem {
    return !!(item as NamedFilterMenuItem).extra?.onDelete;
  }

  openSaveDialog(): void {
    this.saveDialogVisible.set(true);
  }
}
