import {
  Component,
  Directive,
  Injector,
  TemplateRef,
  computed,
  contentChild,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { RouterLink } from '@angular/router';

import { Icon } from '../../icons/icon';
import type { MenuItem } from '../../models/menu-item.model';
import { MenuItemDef } from './menu-item-def.directive';

/**
 * Captures the ambient injector at its host and re-exposes it so a downstream
 * `ngTemplateOutlet` can forward it into the rendered view.
 *
 * Each menu level (root panel, every nested submenu) is opened by a distinct
 * `CdkMenuTrigger` that attaches its `TemplatePortal` with a per-trigger child
 * injector (CDK's `_getChildMenuInjector`) supplying THAT level's own
 * `MENU_TRIGGER` / `MENU_STACK`. Because the shared `#levelTpl` is reached via
 * an `ngTemplateOutlet` hop, that portal injector would otherwise not reach the
 * `<div cdkMenu>`. Capturing it at the portal-view root (`#panelTpl` / `#subTpl`)
 * and binding `[ngTemplateOutletInjector]` forwards it, so every `<div cdkMenu>`
 * resolves the trigger that actually opened it — the root div → the root trigger,
 * each submenu div → its own nested trigger.
 */
@Directive({ selector: '[appMenuOutletInjector]', exportAs: 'appMenuOutletInjector' })
export class MenuOutletInjector {
  readonly injector = inject(Injector);
}

/**
 * Renders a `MenuItem[]` into a CDK popup menu panel (`@angular/cdk/menu`),
 * exposed as a `TemplateRef` that a trigger opens.
 *
 * It renders NO trigger of its own: place `appMenuTriggerFor` (or the styled
 * `<app-menu-trigger [menu]>`) on the opener and point it at this component.
 * `CdkMenuItem` owns each row's role / roving-tabindex / typeahead / arrow-key
 * navigation / Enter-Space activation / Escape-close. Rows with `items` become
 * nested CDK submenus, created and flip-positioned only when entered.
 *
 * A consumer can override the row body with a projected
 * `<ng-template appMenuItemDef let-item>` (or pass one via `[itemDef]`).
 */
@Component({
  selector: 'app-menu',
  imports: [
    NgTemplateOutlet,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    RouterLink,
    Icon,
    MenuOutletInjector,
  ],
  template: `
    <ng-template #panelTpl>
      <ng-container
        appMenuOutletInjector
        #inj="appMenuOutletInjector"
        [ngTemplateOutlet]="levelTpl"
        [ngTemplateOutletInjector]="inj.injector"
        [ngTemplateOutletContext]="{ $implicit: model() }"
      />
    </ng-template>

    <ng-template #levelTpl let-items>
      <div
        cdkMenu
        class="menu-panel"
        [class]="panelClass()"
        [attr.aria-label]="ariaLabel() ?? null"
      >
        @for (item of items; track $index) {
          @if (item.separator) {
            <hr class="menu-sep" />
          } @else if (item.items?.length) {
            <button
              cdkMenuItem
              [class]="rowClass(item)"
              [cdkMenuItemDisabled]="!!item.disabled"
              [cdkMenuTriggerFor]="subTpl"
              [attr.title]="item.title ?? null"
            >
              <ng-container
                [ngTemplateOutlet]="body()"
                [ngTemplateOutletContext]="{ $implicit: item }"
              />
              <app-icon name="lucideChevronRight" class="ml-auto text-xs opacity-70" />
            </button>
            <ng-template #subTpl>
              <ng-container
                appMenuOutletInjector
                #inj="appMenuOutletInjector"
                [ngTemplateOutlet]="levelTpl"
                [ngTemplateOutletInjector]="inj.injector"
                [ngTemplateOutletContext]="{ $implicit: item.items }"
              />
            </ng-template>
          } @else if (item.routerLink) {
            <a
              cdkMenuItem
              [class]="rowClass(item)"
              [cdkMenuItemDisabled]="!!item.disabled"
              [routerLink]="item.routerLink"
              [attr.title]="item.title ?? null"
            >
              <ng-container
                [ngTemplateOutlet]="body()"
                [ngTemplateOutletContext]="{ $implicit: item }"
              />
            </a>
          } @else if (item.url) {
            <a
              cdkMenuItem
              [class]="rowClass(item)"
              [cdkMenuItemDisabled]="!!item.disabled"
              [href]="item.url"
              [attr.target]="item.target ?? null"
              rel="noopener"
              [attr.title]="item.title ?? null"
            >
              <ng-container
                [ngTemplateOutlet]="body()"
                [ngTemplateOutletContext]="{ $implicit: item }"
              />
            </a>
          } @else {
            <!-- Plain command leaf. Rendered as a <div> (not <button>) so a
                 custom row body may carry a nested interactive control — e.g.
                 the filter dropdown's per-row delete button — without invalid
                 button-in-button nesting; CdkMenuItem still sets role=menuitem +
                 a roving tabindex and owns keyboard activation. -->
            <div
              cdkMenuItem
              [class]="rowClass(item)"
              [cdkMenuItemDisabled]="!!item.disabled"
              [attr.title]="item.title ?? null"
              (cdkMenuItemTriggered)="item.command?.({ item })"
            >
              <ng-container
                [ngTemplateOutlet]="body()"
                [ngTemplateOutletContext]="{ $implicit: item }"
              />
            </div>
          }
        }
      </div>
    </ng-template>

    <!-- Default row body: the item's Lucide icon id + optional spin flag. -->
    <ng-template #defaultBody let-item>
      @if (item.icon; as ic) {
        <app-icon [name]="ic" [spin]="!!item.spin" />
      }
      <span class="flex-1">{{ item.label }}</span>
    </ng-template>
  `,
  host: { class: 'contents' },
})
export class Menu {
  /** The rows to render. */
  readonly model = input.required<readonly MenuItem[]>();
  /** Accessible name for the panel (`role=menu`). */
  readonly ariaLabel = input<string>();
  /** Extra classes merged onto the panel element. */
  readonly panelClass = input<string>('');
  /** Custom row body, as an input (mirrors the projected `appMenuItemDef`). */
  readonly itemDef = input<TemplateRef<{ $implicit: MenuItem }> | undefined>(undefined);

  private readonly projectedItemDef = contentChild(MenuItemDef, { read: TemplateRef });
  private readonly defaultBody =
    viewChild.required<TemplateRef<{ $implicit: MenuItem }>>('defaultBody');

  /**
   * The panel template the trigger attaches to its overlay. Non-required: the
   * trigger's sync effect may read it before this menu's view initialises, in
   * which case it is `undefined` until the `<ng-template>` resolves.
   */
  readonly template = viewChild<TemplateRef<unknown>>('panelTpl');

  /** The custom row body (input wins over the projected template), else the default. */
  protected readonly body = computed(
    () => this.itemDef() ?? this.projectedItemDef() ?? this.defaultBody(),
  );

  protected rowClass(item: MenuItem): string {
    let cls = 'menu-item';
    if (item.disabled) cls += ' is-disabled';
    if (item.styleClass) cls += ' ' + item.styleClass;
    return cls;
  }
}
