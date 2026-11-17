import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Card } from './card';

@Component({
  imports: [Card],
  // Signal-backed host: plain fields don't trigger zoneless change detection.
  template: `<app-card [header]="header()" [padded]="padded()">
    <button cardActions class="act">Join</button>
    <p class="body">Body</p>
  </app-card>`,
})
class Host {
  readonly header = signal('Group membership');
  readonly padded = signal(true);
}

function setup() {
  TestBed.configureTestingModule({ imports: [Host] });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return fixture;
}

describe('Card', () => {
  it('renders the header label verbatim', () => {
    const el = setup().nativeElement as HTMLElement;
    expect(el.querySelector('header')?.textContent).toContain('Group membership');
  });

  /**
   * ONE band style.
   *
   * A card sitting beside an `.admin-panel` has to read as the same kind of
   * surface, so the band matches `.admin-panel-header` — sentence case at the
   * body size, not the uppercase tracked small-caps it once wore.
   */
  it('wears the shared panel-band type, not an uppercase one', () => {
    const el = setup().nativeElement as HTMLElement;
    const label = el.querySelector('header > span') as HTMLElement;

    expect(label.className).toContain('text-sm');
    expect(label.className).toContain('font-semibold');
    expect(label.className).not.toContain('uppercase');
  });

  it('projects the body and the [cardActions] into the header', () => {
    const el = setup().nativeElement as HTMLElement;
    expect(el.querySelector('.body')).not.toBeNull();
    expect(el.querySelector('header .act')).not.toBeNull();
  });

  it('omits the header band when no header is set', () => {
    const fixture = setup();
    fixture.componentInstance.header.set('');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('header')).toBeNull();
    expect(el.querySelector('.body')).not.toBeNull();
  });

  it('drops body padding when padded is false', () => {
    const fixture = setup();
    const body = () =>
      fixture.nativeElement.querySelector('section > div:last-child') as HTMLElement;
    expect(body().classList.contains('p-4')).toBe(true);
    fixture.componentInstance.padded.set(false);
    fixture.detectChanges();
    expect(body().classList.contains('p-4')).toBe(false);
  });
});
