import { Component, input } from '@angular/core';
import {
  Tab as AriaTab,
  TabContent as AriaTabContent,
  TabList as AriaTabList,
  TabPanel as AriaTabPanel,
  Tabs as AriaTabs,
} from '@angular/aria/tabs';

/**
 * Token-driven tabs on `@angular/aria`'s tabs pattern. The four wrappers compose as:
 *
 * ```html
 * <app-tabs>
 *   <app-tab-list [value]="active()" (valueChange)="active.set($event)">
 *     <app-tab value="one">One</app-tab>
 *     <app-tab value="two">Two</app-tab>
 *   </app-tab-list>
 *   <app-tab-panel value="one">…</app-tab-panel>
 *   <app-tab-panel value="two">…</app-tab-panel>
 * </app-tabs>
 * ```
 *
 * The aria directives own `role=tablist/tab/tabpanel`, the roving tabindex and arrow-key
 * navigation, `aria-selected`, and the `aria-controls`/`aria-labelledby` cross-linking by
 * matching `value`; the `.tabs-*` classes in the shared stylesheet own the look. The active
 * tab is aria's own `selectedTab` model, which the tab list exposes as `value` / `valueChange`:
 * one piece of state, with nothing copying it from one place to another.
 *
 * Panels render EAGERLY — all of them mounted, the inactive ones carrying the `inert`
 * attribute aria sets and hidden by the stylesheet. A panel whose content must not exist
 * until it is shown says so itself, with an `@if` on the active value.
 */
@Component({
  selector: 'app-tabs',
  hostDirectives: [AriaTabs],
  template: `<ng-content />`,
  host: { class: 'tabs' },
})
export class AppTabs {}

@Component({
  selector: 'app-tab-list',
  hostDirectives: [
    {
      directive: AriaTabList,
      inputs: ['selectedTab: value'],
      outputs: ['selectedTabChange: valueChange'],
    },
  ],
  template: `<ng-content />`,
  host: { class: 'tabs-list' },
})
export class AppTabList {}

@Component({
  selector: 'app-tab',
  hostDirectives: [{ directive: AriaTab, inputs: ['value'] }],
  template: `<ng-content />`,
  host: { class: 'tabs-tab' },
})
export class AppTab {}

@Component({
  selector: 'app-tab-panel',
  imports: [AriaTabPanel, AriaTabContent],
  // Content is projected eagerly (see the class doc); the empty ngTabContent only satisfies
  // aria's "a panel must carry an ngTabContent" structural requirement.
  template: `
    <div ngTabPanel [value]="value()" class="tabs-panel">
      <ng-content />
      <ng-template ngTabContent></ng-template>
    </div>
  `,
  host: { class: 'contents' },
})
export class AppTabPanel {
  readonly value = input.required<string>();
}
