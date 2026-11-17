import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../icons/niord-icons';
import { Menu } from '../menu/menu';
import { MenuTrigger } from './menu-trigger';

@Component({
  imports: [MenuTrigger, Menu],
  template: `
    <app-menu-trigger
      icon="lucideSettings"
      [label]="label()"
      [showChevron]="showChevron()"
      ariaLabel="Actions"
      [menu]="actions"
    />
    <app-menu #actions [model]="[]" />
  `,
})
class Host {
  readonly label = signal<string | undefined>(undefined);
  readonly showChevron = signal(true);
}

function setup(configure?: (host: Host) => void) {
  TestBed.configureTestingModule({ providers: [provideNiordIcons()] });
  const fixture = TestBed.createComponent(Host);
  configure?.(fixture.componentInstance);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

const button = (el: HTMLElement) => el.querySelector('button') as HTMLButtonElement;

describe('MenuTrigger', () => {
  it('renders an icon-only trigger with the explicit accessible name', () => {
    const { el } = setup();
    expect(button(el).className).toContain('icon-button');
    expect(button(el).getAttribute('aria-label')).toBe('Actions');
  });

  it('uses the button tier once it has a visible label, which then names it', () => {
    const { el } = setup((host) => host.label.set('Actions'));
    expect(button(el).className).toContain('btn-tertiary');
  });

  it('draws no border by default', () => {
    const { el } = setup();
    expect(button(el).className).not.toContain('border');
  });

  it('drops the trailing chevron when showChevron is false', () => {
    const { el, host, fixture } = setup();
    expect(el.querySelectorAll('app-icon').length).toBe(2); // leading glyph + chevron

    host.showChevron.set(false);
    fixture.detectChanges();
    expect(el.querySelectorAll('app-icon').length).toBe(1);
  });
});
