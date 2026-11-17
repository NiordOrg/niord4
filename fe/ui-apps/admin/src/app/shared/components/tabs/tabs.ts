import { Component, effect, inject, input, model } from '@angular/core';
import {
  Tab as AriaTab,
  TabContent as AriaTabContent,
  TabList as AriaTabList,
  TabPanel as AriaTabPanel,
  Tabs as AriaTabs,
} from '@angular/aria/tabs';

/**
 * Token-driven tabs built on `@angular/aria`'s tabs pattern. The five wrappers
 * compose as:
 *
 * ```html
 * <app-tabs [value]="active()" (valueChange)="active.set($event)">
 *   <app-tab-list>
 *     <app-tab value="one">One</app-tab>
 *     <app-tab value="two">Two</app-tab>
 *   </app-tab-list>
 *   <app-tab-panels>
 *     <app-tab-panel value="one">…</app-tab-panel>
 *     <app-tab-panel value="two">…</app-tab-panel>
 *   </app-tab-panels>
 * </app-tabs>
 * ```
 *
 * The aria directives own `role=tablist/tab/tabpanel`, roving-tabindex +
 * arrow-key navigation, `aria-selected`, and the `aria-controls`/`aria-labelledby`
 * cross-linking (by matching `value`). Styling is Layer-3 (`.tabs-*` in
 * components.css), reusing the toolbar underline treatment for the active tab.
 *
 * Deviation from aria's default: panels render EAGERLY (all mounted, inactive
 * ones hidden via the `inert` attribute aria sets) rather than lazily. This is
 * deliberate — every panel stays mounted so a preloaded inactive tab (e.g. the
 * Contacts tab's server search) still runs its on-init data fetch. Content is
 * projected directly into the panel; the empty `ngTabContent` template only
 * satisfies aria's structural requirement.
 */
@Component({
  selector: 'app-tabs',
  hostDirectives: [AriaTabs],
  template: `<ng-content />`,
  host: { class: 'tabs' },
})
export class AppTabs {
  /** Active tab value — two-way (`[value]` + `(valueChange)`). */
  readonly value = model<string>();
}

@Component({
  selector: 'app-tab-list',
  hostDirectives: [AriaTabList],
  template: `<ng-content />`,
  host: { class: 'tabs-list' },
})
export class AppTabList {
  private readonly tabs = inject(AppTabs);
  private readonly list = inject(AriaTabList);

  constructor() {
    // Two-way bridge between the outer `[value]` and aria's `selectedTab` model.
    // Each effect reads exactly ONE side and writes the other; the equality-guarded
    // `set()` (signals don't notify on an equal write) breaks the cycle so a user
    // click or an external value change settles in a single extra pass.
    effect(() => {
      const value = this.tabs.value();
      if (value !== undefined) this.list.selectedTab.set(value);
    });
    effect(() => {
      const selected = this.list.selectedTab();
      if (selected !== undefined) this.tabs.value.set(selected);
    });
  }
}

@Component({
  selector: 'app-tab',
  hostDirectives: [{ directive: AriaTab, inputs: ['value', 'disabled'] }],
  template: `<ng-content />`,
  host: { class: 'tabs-tab' },
})
export class AppTab {}

@Component({
  selector: 'app-tab-panels',
  template: `<ng-content />`,
  host: { class: 'tabs-panels' },
})
export class AppTabPanels {}

@Component({
  selector: 'app-tab-panel',
  imports: [AriaTabPanel, AriaTabContent],
  // Content is projected eagerly (see the class doc); the empty ngTabContent only
  // satisfies aria's "a panel must carry an ngTabContent" structural requirement.
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
