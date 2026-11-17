/**
 * Niord's popup-menu item model.
 *
 * Rendered by {@link Menu} (`app-menu`): leaf rows run `command`, rows with
 * `items` become CDK submenus, `separator` rows draw a divider. `routerLink` /
 * `url` render the row as a navigating anchor; `iconClass` / `iconStyle` /
 * `styleClass` are the per-item decoration escape hatches (header domain-colour
 * disc, language flag).
 */
export interface MenuItem {
  /** Visible row text. */
  label?: string;
  /** `@ng-icons/lucide` export identifier, e.g. `'lucideSettings'`. */
  icon?: string;
  /** Spin the icon (loading rows). */
  spin?: boolean;
  /** Extra classes merged onto the icon (e.g. flag sizing, a size override). */
  iconClass?: string;
  /** Inline icon style (header domain-colour disc / language flag bg-image). */
  iconStyle?: Record<string, string> | null;
  /** Invoked when the row is triggered (click / Enter / Space). */
  command?: (event?: MenuItemCommandEvent) => void;
  /** Child rows — rendered as a nested CDK submenu. */
  items?: MenuItem[];
  /** Draw a divider instead of an interactive row. */
  separator?: boolean;
  /** Dim + inert the row. */
  disabled?: boolean;
  /** Native `title` tooltip (used for "coming soon" placeholders). */
  title?: string;
  /** Extra classes applied to the row element. */
  styleClass?: string;
  /** Render the row as a router-link anchor. */
  routerLink?: string | readonly unknown[];
  /** Render the row as an external-link anchor. */
  url?: string;
  /** Anchor `target` (paired with `url`). */
  target?: string;
}

/** The argument passed to {@link MenuItem.command}. */
export interface MenuItemCommandEvent {
  readonly originalEvent?: Event;
  readonly item?: MenuItem;
  readonly [key: string]: unknown;
}
