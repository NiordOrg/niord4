import { Component, computed, inject } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { TranslocoPipe } from '@jsverse/transloco';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { Menu } from '../../../shared/components/menu/menu';
import { MenuTriggerFor } from '../../../shared/components/menu/menu-trigger.directive';
import { Icon } from '../../../shared/icons/icon';
import type { MenuItem } from '../../../shared/models/menu-item.model';
import { APP_CONFIG } from '../../config/app-config.token';
import { MenuService } from '../menu.service';
import { AccountMenuService } from './services/account-menu.service';
import { DomainMenuService } from './services/domain-menu.service';
import { HelpMenuService } from './services/help-menu.service';
import { LanguageMenuService } from './services/language-menu.service';

@Component({
  selector: 'app-main-header',
  imports: [
    NgOptimizedImage,
    Menu,
    MenuTriggerFor,
    Icon,
    TranslocoPipe,
    RouterLink,
    RouterLinkActive,
  ],
  templateUrl: './header.html',
  styleUrl: './header.css',
})
export class Header {
  private readonly menuService = inject(MenuService);
  private readonly helpMenuService = inject(HelpMenuService);
  private readonly domainMenuService = inject(DomainMenuService);
  private readonly accountMenuService = inject(AccountMenuService);
  private readonly languageMenuService = inject(LanguageMenuService);
  private readonly appConfig = inject(APP_CONFIG);

  // Badge visible on staging/dev/test deployments so users aren't fooled into thinking
  // they're acting on production data. Driven by the runtime `mode` web setting (the
  // backend DB wins over env): any mode but 'production' shows the badge, labelled
  // with the mode itself (TEST, STAGING, …). A blank mode counts as absent — then the
  // build-time `production` flag decides and the label falls back to the translated
  // default (also the path when the settings endpoint is down and env omits `mode`).
  private readonly deploymentMode = this.appConfig.mode?.trim() || undefined;
  readonly showTestBadge = this.deploymentMode
    ? this.deploymentMode !== 'production'
    : this.appConfig.production !== true;
  readonly testBadgeLabel = this.deploymentMode?.toUpperCase();

  readonly navItems = this.menuService.menuItems;
  readonly helpItem = this.helpMenuService.menuItem;
  readonly domainItem = this.domainMenuService.menuItem;
  readonly accountItem = this.accountMenuService.menuItem;
  readonly languageItem = this.languageMenuService.menuItem;

  /**
   * A header menu row's `icon` is either a Lucide glyph id (`'lucide…'`) or a
   * raw CSS class set (the language rows carry flag-image utilities, no glyph):
   * only the former renders through `<app-icon>`; the latter stays a styled span.
   */
  protected isGlyphIcon(icon: string | undefined): boolean {
    return !!icon && icon.trim().startsWith('lucide');
  }

  // Single flat MenuItem[] used by the mobile burger popup. Combines nav links,
  // the help link, and the account and language items with separators between
  // sections — the mobile menu is intentionally a single flat list rather than
  // nested submenus, and the per-item icon (envelope, flag, etc.) is what
  // identifies which section each row belongs to. The session domain is NOT in
  // this list: below `lg` it keeps its own trigger beside the burger, so adding
  // it here would offer the same switch twice.
  readonly mobileItems = computed<MenuItem[]>(() => {
    const out: MenuItem[] = [...this.navItems()];

    const help = this.helpItem();
    if (help) {
      out.push({ separator: true });
      out.push(help);
    }

    const account = this.accountItem();
    out.push({ separator: true });
    if (account.items?.length) {
      out.push(...account.items);
    } else {
      // Anonymous: AccountMenuService returns the bare login action (no items).
      out.push(account);
    }

    const language = this.languageItem();
    if (language.items?.length) {
      out.push({ separator: true });
      out.push(...language.items);
    }

    return out;
  });

  /**
   * Anonymous account trigger. When authenticated, `accountItem()` has an
   * `items` array (Edit account / Logout) and the account button hosts
   * `appMenuTriggerFor`, so CDK opens the popup directly. When anonymous, the
   * service returns a bare login action with `command` and no items — this
   * handler invokes it instead of opening an empty menu.
   */
  onAccountLogin(event: MouseEvent): void {
    const item = this.accountItem();
    item.command?.({ originalEvent: event, item });
  }
}
