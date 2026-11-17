import { Directive } from '@angular/core';

/**
 * Marks an `<ng-template appMenuItemDef let-item>` projected into `<app-menu>`
 * as the custom row body. The template's `$implicit` context is the
 * {@link MenuItem}; the menu still owns the interactive `cdkMenuItem` wrapper
 * (role / keyboard / auto-close), so the template only supplies the row's inner
 * icon / label / trailing widgets.
 */
@Directive({
  selector: 'ng-template[appMenuItemDef]',
})
export class MenuItemDef {}
