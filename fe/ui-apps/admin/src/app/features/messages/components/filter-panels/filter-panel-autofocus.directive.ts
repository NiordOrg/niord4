import { Directive, ElementRef, Injector, afterNextRender, inject, input } from '@angular/core';

import { MENU_CARET_HANDOFF_ATTR } from '../../../../shared/components/menu/menu-trigger.directive';
import type { FilterName } from '../../models/messages-filter-params.model';
import { MessagesFacade } from '../../services/messages-facade.service';

/**
 * Hands the caret to a filter panel's search box when that filter is switched on from the filter
 * menu, so a panel the user just asked for is ready to type into.
 *
 * Put it on the panel's `<app-filter-panel>`; it focuses the first `<input>` inside. That covers
 * both shapes we have — a panel whose body IS an input (Text) and a panel wrapping an entity
 * multiselect whose input lives a few levels down (Tag / Area / Category / Publication /
 * Promulgation type / Chart / Message series) — with no per-panel wiring.
 *
 * Only a filter enabled FROM THE MENU takes focus. The same panels are rendered when a URL or a
 * named filter restores the filter, and stealing focus there would fight the user on page load;
 * `panelFocusRequest` is the facade's record of which case this is, and consuming it here means a
 * later re-render never re-grabs focus.
 *
 * The host also carries {@link MENU_CARET_HANDOFF_ATTR}, so the menu stays open across the focus
 * move and closes on the first keystroke instead — the user is still picking filters until they
 * commit to typing. `input` bubbles and the menu matches with `closest()`, so tagging the panel
 * covers whatever input is nested inside it.
 */
@Directive({
  selector: '[appFilterAutofocus]',
  host: { [`[attr.${MENU_CARET_HANDOFF_ATTR}]`]: '""' },
})
export class FilterPanelAutofocus {
  /** The filter this panel edits — matched against the facade's pending focus request. */
  readonly filterName = input.required<FilterName>({ alias: 'appFilterAutofocus' });

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly messages = inject(MessagesFacade);

  constructor() {
    // Read once, at construction: the panel is created the moment its filter is enabled, so this
    // is the enable edge. Reading it in an effect would also fire for unrelated later requests.
    const request = this.messages.filter.panelFocusRequest();
    afterNextRender(
      () => {
        if (request !== this.filterName()) {
          return;
        }
        this.messages.filter.panelFocusRequest.set(undefined);
        this.host.nativeElement.querySelector('input')?.focus();
      },
      { injector: this.injector },
    );
  }
}
