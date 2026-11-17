import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { FILTER_DEBOUNCE_MS, PublicationListFilter } from './publication-list-filter';

@Component({
  imports: [PublicationListFilter],
  template: `<app-publication-list-filter
    placeholderKey="admin.publications.filter.series"
    [value]="value()"
    (valueChange)="value.set($event)"
  />`,
})
class Host {
  readonly value = signal('');
}

function setup(seed = '') {
  TestBed.configureTestingModule({ providers: [provideTestTransloco(), provideNiordIcons()] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.value.set(seed);
  fixture.detectChanges();
  return {
    fixture,
    host: fixture.componentInstance,
    el: fixture.nativeElement as HTMLElement,
  };
}

function type(el: HTMLElement, value: string): void {
  const input = el.querySelector('input') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

/** Long enough for the debounce to have fired, and short enough not to be felt. */
const settle = () => new Promise((resolve) => setTimeout(resolve, FILTER_DEBOUNCE_MS + 60));

describe('PublicationListFilter', () => {
  it('reports what was typed only once typing has settled', async () => {
    const { el, host, fixture } = setup();

    type(el, 'ugen');
    fixture.detectChanges();
    // The box shows it immediately; the list it filters does not move yet.
    expect((el.querySelector('input') as HTMLInputElement).value).toBe('ugen');
    expect(host.value()).toBe('');

    await settle();
    expect(host.value()).toBe('ugen');
  });

  it('reports the last keystroke only, not every one on the way to it', async () => {
    const { el, host, fixture } = setup();

    for (const text of ['u', 'ug', 'uge']) {
      type(el, text);
      fixture.detectChanges();
    }
    await settle();

    expect(host.value()).toBe('uge');
  });

  it('shows a value the host seeded, so a deep link says what it filtered by', () => {
    const { el } = setup('fyrliste');
    expect((el.querySelector('input') as HTMLInputElement).value).toBe('fyrliste');
  });

  /**
   * The × appears only when there is something to clear, and clears at once.
   *
   * Waiting for the debounce after a clear would leave the reader looking at a
   * filtered list beside an empty box.
   */
  it('offers a clear button only while filtered, and clears without waiting', async () => {
    const { el, host, fixture } = setup();
    expect(el.querySelector('button')).toBeNull();

    type(el, 'uge');
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    const clear = el.querySelector('button') as HTMLButtonElement;
    expect(clear).not.toBeNull();
    clear.click();
    fixture.detectChanges();

    expect(host.value()).toBe('');
    expect((el.querySelector('input') as HTMLInputElement).value).toBe('');
  });
});
