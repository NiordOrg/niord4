import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import type { MenuItem } from '../../models/menu-item.model';
import { Menu } from './menu';
import { MenuTriggerFor } from './menu-trigger.directive';

@Component({
  imports: [Menu, MenuTriggerFor],
  template: `
    <button [appMenuTriggerFor]="m">Open</button>
    <app-menu #m [model]="items()" ariaLabel="Actions" />
  `,
})
class MenuHost {
  readonly items = signal<MenuItem[]>([]);
}

describe('Menu (app-menu / CDK menu)', () => {
  const q = (sel: string) => document.querySelector(sel);
  const qa = (sel: string) => Array.from(document.querySelectorAll(sel));

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  async function setup(items: MenuItem[]) {
    TestBed.configureTestingModule({ providers: [provideNiordIcons()] });
    const fixture = TestBed.createComponent(MenuHost);
    fixture.componentInstance.items.set(items);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('sets aria-haspopup=menu on the trigger and opens the panel on click', async () => {
    const fixture = await setup([{ label: 'Print' }]);
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    expect(q('.menu-panel')).toBeNull();

    trigger.click();
    await fixture.whenStable();
    expect(q('.menu-panel')).not.toBeNull();
    expect(q('[cdkMenu]')?.getAttribute('aria-label')).toBe('Actions');
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
  });

  it('renders leaf rows + separators and runs the command on click, auto-closing', async () => {
    const ran = vi.fn();
    const fixture = await setup([
      { label: 'Print', icon: 'lucidePrinter', command: ran },
      { separator: true },
      { label: 'Export', command: () => undefined },
    ]);
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    trigger.click();
    await fixture.whenStable();

    const rows = qa('.menu-item');
    expect(rows.map((r) => r.textContent?.trim())).toEqual(['Print', 'Export']);
    expect(q('.menu-sep')).not.toBeNull();

    (rows[0] as HTMLElement).click();
    await fixture.whenStable();
    expect(ran).toHaveBeenCalledTimes(1);
    // Triggering a leaf closes the whole menu (no manual hide needed).
    expect(q('.menu-panel')).toBeNull();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  it('a disabled row does not run its command', async () => {
    const ran = vi.fn();
    const fixture = await setup([{ label: 'Nope', disabled: true, command: ran }]);
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();

    const row = q('.menu-item') as HTMLElement;
    expect(row.classList.contains('is-disabled')).toBe(true);
    row.click();
    await fixture.whenStable();
    expect(ran).not.toHaveBeenCalled();
  });

  it('renders a submenu parent as a nested menu trigger with a chevron', async () => {
    const fixture = await setup([
      { label: 'Convert', items: [{ label: 'To Line', command: () => undefined }] },
    ]);
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();

    const parent = q('.menu-item') as HTMLElement;
    expect(parent.textContent).toContain('Convert');
    // The parent is itself a CDK submenu trigger (opens its own overlay on entry).
    expect(parent.getAttribute('aria-haspopup')).toBe('menu');
    // …and carries a trailing chevron glyph.
    expect(parent.querySelector('app-icon')).not.toBeNull();
  });
});
