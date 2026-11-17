import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AppTab, AppTabList, AppTabPanel, AppTabs } from './tabs';

@Component({
  imports: [AppTabs, AppTabList, AppTab, AppTabPanel],
  template: `
    <app-tabs>
      <app-tab-list [value]="active()" (valueChange)="active.set($any($event))">
        <app-tab value="one">One</app-tab>
        <app-tab value="two">Two</app-tab>
      </app-tab-list>
      <app-tab-panel value="one"><p class="p-one">First</p></app-tab-panel>
      <app-tab-panel value="two"><p class="p-two">Second</p></app-tab-panel>
    </app-tabs>
  `,
})
class TabsHost {
  readonly active = signal('one');
}

describe('Tabs (app-tabs / @angular/aria)', () => {
  async function setup() {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(TabsHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    return fixture;
  }

  const q = (root: HTMLElement, sel: string) => root.querySelector(sel);
  const qa = (root: HTMLElement, sel: string) => Array.from(root.querySelectorAll(sel));

  it('renders the aria tab roles', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(qa(el, '[role="tablist"]')).toHaveLength(1);
    expect(qa(el, '[role="tab"]')).toHaveLength(2);
    expect(qa(el, '[role="tabpanel"]')).toHaveLength(2);
  });

  it('mounts every panel eagerly, regardless of active tab', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(q(el, '.p-one')).not.toBeNull();
    expect(q(el, '.p-two')).not.toBeNull();
  });

  it('reflects the active value: selected tab + inert on the inactive panel', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    const [tabOne, tabTwo] = qa(el, '[role="tab"]') as HTMLElement[];
    expect(tabOne.getAttribute('aria-selected')).toBe('true');
    expect(tabTwo.getAttribute('aria-selected')).toBe('false');

    const [panelOne, panelTwo] = qa(el, '[role="tabpanel"]') as HTMLElement[];
    expect(panelOne.hasAttribute('inert')).toBe(false);
    expect(panelTwo.hasAttribute('inert')).toBe(true);
    // aria links the tab to its panel by value.
    expect(tabOne.getAttribute('aria-controls')).toBe(panelOne.getAttribute('id'));
  });

  it('follows an external value change', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    fixture.componentInstance.active.set('two');
    await fixture.whenStable();

    const [tabOne, tabTwo] = qa(el, '[role="tab"]') as HTMLElement[];
    expect(tabOne.getAttribute('aria-selected')).toBe('false');
    expect(tabTwo.getAttribute('aria-selected')).toBe('true');
  });

  it('emits (valueChange) when a tab is activated by click', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    const tabTwo = (qa(el, '[role="tab"]') as HTMLElement[])[1];
    tabTwo.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.active()).toBe('two');
  });

  it('moves the selection with the arrow keys', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    const [tabOne] = qa(el, '[role="tab"]') as HTMLElement[];
    tabOne.focus();
    tabOne.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await fixture.whenStable();
    expect(fixture.componentInstance.active()).toBe('two');
  });
});
