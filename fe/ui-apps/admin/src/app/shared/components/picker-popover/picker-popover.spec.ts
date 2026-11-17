import { Component, ElementRef, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { PickerPopover } from './picker-popover';

@Component({
  imports: [PickerPopover],
  template: `
    <button #trigger class="pop-trigger">Open</button>
    <app-picker-popover>
      <div class="pop-content">Projected content</div>
    </app-picker-popover>
  `,
})
class PopoverHost {
  readonly popover = viewChild.required(PickerPopover);
  readonly trigger = viewChild.required<ElementRef<HTMLElement>>('trigger');
}

describe('PickerPopover', () => {
  async function setup() {
    TestBed.configureTestingModule({ providers: [] });
    const fixture = TestBed.createComponent(PopoverHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    return fixture;
  }

  const q = (sel: string) => document.querySelector(sel);

  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('does not render the panel before it is opened', async () => {
    await setup();
    expect(q('.picker-panel')).toBeNull();
  });

  it('renders the panel portaled to the body when opened, and removes it on close', async () => {
    const fixture = await setup();
    const host = fixture.componentInstance;

    host.popover().open(host.trigger().nativeElement);
    await fixture.whenStable();

    const panel = q('.picker-panel');
    expect(panel).not.toBeNull();
    // Portaled out of the host, into the body-level overlay container.
    expect(panel!.closest('.cdk-overlay-container')).not.toBeNull();
    expect(host.popover().shown()).toBe(true);

    host.popover().close();
    await fixture.whenStable();

    expect(q('.picker-panel')).toBeNull();
    expect(host.popover().shown()).toBe(false);
  });

  it('projects content into the panel', async () => {
    const fixture = await setup();
    const host = fixture.componentInstance;

    host.popover().open(host.trigger().nativeElement);
    await fixture.whenStable();

    expect(q('.picker-panel .pop-content')?.textContent).toContain('Projected content');
  });

  it('closes an open popover on a document-level Escape', async () => {
    const fixture = await setup();
    const host = fixture.componentInstance;

    host.popover().open(host.trigger().nativeElement);
    await fixture.whenStable();
    expect(q('.picker-panel')).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();

    expect(q('.picker-panel')).toBeNull();
    expect(host.popover().shown()).toBe(false);
  });

  it('still closes on an already-consumed (defaultPrevented) Escape — it has no guard', async () => {
    const fixture = await setup();
    const host = fixture.componentInstance;

    host.popover().open(host.trigger().nativeElement);
    await fixture.whenStable();

    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    event.preventDefault();
    document.dispatchEvent(event);
    await fixture.whenStable();

    // Unlike the drawer, the popover does not stand down on a consumed Escape.
    expect(q('.picker-panel')).toBeNull();
    expect(host.popover().shown()).toBe(false);
  });

  it('does nothing on a document Escape while closed', async () => {
    const fixture = await setup();
    const host = fixture.componentInstance;

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();

    expect(q('.picker-panel')).toBeNull();
    expect(host.popover().shown()).toBe(false);
  });

  it('reopens after close with fresh state', async () => {
    const fixture = await setup();
    const host = fixture.componentInstance;

    host.popover().open(host.trigger().nativeElement);
    await fixture.whenStable();
    expect(q('.picker-panel')).not.toBeNull();

    host.popover().close();
    await fixture.whenStable();
    expect(q('.picker-panel')).toBeNull();

    host.popover().open(host.trigger().nativeElement);
    await fixture.whenStable();
    expect(q('.picker-panel .pop-content')?.textContent).toContain('Projected content');
    expect(host.popover().shown()).toBe(true);
  });
});
