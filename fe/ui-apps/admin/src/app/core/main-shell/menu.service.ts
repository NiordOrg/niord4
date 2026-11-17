import { computed, inject, Injectable, Signal } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../shared/models/menu-item.model';

import { ADMIN_AREA_ROLES, EDITOR_AREA_ROLES, SYSADMIN_AREA_ROLES } from '../auth/area-roles';
import { AuthFacade } from '../auth/facades/auth.facade';
import { DomainFacade } from '../domain/facades/domain.facade';
import { COUNTRY_MENU, type MenuAnchor } from './country-menu';

// The base items' fixed order, which is also the order a country entry's anchor is placed by.
const ANCHORS: readonly MenuAnchor[] = ['messages', 'editor', 'schedule', 'admin', 'sysadmin'];

@Injectable({ providedIn: 'root' })
export class MenuService {
  private readonly auth = inject(AuthFacade);
  private readonly domain = inject(DomainFacade);
  private readonly messagesLabel = translateSignal('menu.messages');
  private readonly scheduleLabel = translateSignal('menu.schedule');
  private readonly editorLabel = translateSignal('menu.editor');
  private readonly adminLabel = translateSignal('menu.admin');
  private readonly sysadminLabel = translateSignal('menu.sysadmin');
  private readonly countryEntries = inject(COUNTRY_MENU).map((entry) => ({
    entry,
    label: translateSignal(entry.labelKey),
  }));

  // Same role lists gate the corresponding routes in `app.routes.ts` via
  // `roleGuard`; both go through `AuthFacade.hasAnyRole` to keep menu
  // visibility and route protection aligned through one code path.
  private readonly canSeeEditor = computed(() => this.auth.hasAnyRole(EDITOR_AREA_ROLES));
  private readonly canSeeAdmin = computed(() => this.auth.hasAnyRole(ADMIN_AREA_ROLES));
  private readonly canSeeSysadmin = computed(() => this.auth.hasAnyRole(SYSADMIN_AREA_ROLES));
  // Schedule shares the editor role gate AND is only available in a firing-schedule domain.
  private readonly canSeeSchedule = computed(
    () => this.canSeeEditor() && this.domain.capabilities().firingSchedule,
  );

  readonly menuItems: Signal<readonly MenuItem[]> = computed(() => {
    const base: Record<MenuAnchor, MenuItem | undefined> = {
      messages: { label: this.messagesLabel(), routerLink: 'messages', icon: 'lucideMail' },
      editor: this.canSeeEditor()
        ? { label: this.editorLabel(), routerLink: 'editor', icon: 'lucidePencil' }
        : undefined,
      schedule: this.canSeeSchedule()
        ? { label: this.scheduleLabel(), routerLink: 'schedule', icon: 'lucideCalendar' }
        : undefined,
      admin: this.canSeeAdmin()
        ? { label: this.adminLabel(), routerLink: 'admin', icon: 'lucideShield' }
        : undefined,
      sysadmin: this.canSeeSysadmin()
        ? { label: this.sysadminLabel(), routerLink: 'sysadmin', icon: 'lucideSettings' }
        : undefined,
    };
    // A country entry follows its anchor, or the place its anchor would have when that item is
    // hidden from this user; entries after one anchor keep their file order. Its roles go through
    // the same hasAnyRole as the base items' (none: everyone).
    const items: MenuItem[] = [];
    for (const anchor of ANCHORS) {
      const item = base[anchor];
      if (item) items.push(item);
      for (const { entry, label } of this.countryEntries) {
        if (entry.after !== anchor) continue;
        if (entry.roles.length > 0 && !this.auth.hasAnyRole(entry.roles)) continue;
        items.push({ label: label(), routerLink: entry.routerLink, icon: entry.icon });
      }
    }
    return items;
  });
}
